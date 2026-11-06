package com.aiops.backend.service;

import com.aiops.backend.security.ClusterPermission;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 使用登记只是协作信息，不预留 GPU，也不把人工 RUNNING 当作真实采样事实。 */
@Service
public class GpuUsageService {
  private final JdbcTemplate jdbc;
  private final ClusterPermissionService access;

  public GpuUsageService(JdbcTemplate jdbc, ClusterPermissionService access) {
    this.jdbc = jdbc;
    this.access = access;
  }

  public record Usage(
      @Min(0) int gpuIndex,
      @NotBlank @Size(max = 160) String taskName,
      @Size(max = 1000) String purpose,
      @NotNull Instant startsAt,
      @NotNull Instant expectedEndAt,
      @Size(max = 2000) String notes,
      @Pattern(regexp = "PLANNED|RUNNING") @NotNull String status) {}

  private LocalDateTime utc(Instant at) {
    // DATETIME(6) 只有微秒精度。写入和冲突查询必须统一截断，否则相邻区间可能误判重叠。
    return LocalDateTime.ofInstant(
        at.truncatedTo(java.time.temporal.ChronoUnit.MICROS), ZoneOffset.UTC);
  }

  public List<Map<String, Object>> list(Long serverId) {
    return list(serverId, null, null);
  }

  public List<Map<String, Object>> list(Long serverId, Instant from, Instant to) {
    access.requireNode(serverId, ClusterPermission.GPU_VIEW);
    expire(serverId);
    boolean window = from != null || to != null;
    if (window
        && (from == null
            || to == null
            || !from.isBefore(to)
            || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "预约查询窗口应在 31 天内");
    var rows =
        jdbc.queryForList(
            "SELECT t.*,u.username FROM ops_gpu_usage t JOIN sys_user u ON u.id=t.user_id WHERE"
                + " server_id=? "
                + (window ? "AND starts_at<? AND expected_end_at>? " : "")
                + "ORDER BY id DESC LIMIT "
                + (window ? "2001" : "200"),
            window ? new Object[] {serverId, utc(to), utc(from)} : new Object[] {serverId});
    if (window && rows.size() > 2000)
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "该时间段预约较多，请缩小查询范围");
    for (var row : rows) {
      row.put("verification", "TRAINING".equals(row.get("reservation_kind")) ? "TRAINING_JOB" : "UNVERIFIED");
      row.put("reserves_gpu", false);
      row.put("verification_note", "TRAINING".equals(row.get("reservation_kind")) ? "训练任务的资源报告与进程身份请在训练任务中心查看" : "人工登记未与操作系统任务身份绑定，请结合 GPU 进程和采集时间核实");
    }
    return rows;
  }

  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  public void create(Long serverId, Usage r) {
    save(serverId, null, r);
  }

  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  public void update(Long serverId, Long id, Usage r) {
    save(serverId, id, r);
  }

  private void save(Long serverId, Long id, Usage r) {
    access.requireNode(serverId, ClusterPermission.GPU_VIEW);
    // 锁定真实节点，即使当前没有任何预约，也能串行化同一服务器的冲突检查。
    jdbc.queryForMap("SELECT id FROM ops_node WHERE id=? FOR UPDATE", serverId);
    expire(serverId);
    if (!"PLANNED".equals(r.status()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新增或编辑预约只能处于计划状态");
    if (id != null) {
      rejectTrainingMutation(id);
      var old =
          jdbc
              .queryForList(
                  "SELECT * FROM ops_gpu_usage WHERE id=? AND server_id=? FOR UPDATE", id, serverId)
              .stream()
              .findFirst()
              .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
      if (((Number) old.get("user_id")).longValue() != access.currentUser().getId())
        access.require(
            access.requireNode(serverId, ClusterPermission.GPU_VIEW).getClusterId(),
            ClusterPermission.AGENT_MANAGE);
      if (!"PLANNED".equals(old.get("status")))
        throw new ResponseStatusException(HttpStatus.CONFLICT, "只能编辑尚未开始的预约");
    }
    if (r.startsAt().isBefore(Instant.now()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能预约过去的时间");
    if (!utc(r.expectedEndAt()).isAfter(utc(r.startsAt()))
        || r.expectedEndAt().isAfter(r.startsAt().plus(Duration.ofDays(7))))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "预约必须大于零且不超过 7 天");
    // MySQL 默认 REPEATABLE READ：权限查询已建立快照。冲突检查使用锁定读，
    // 才能看到等待节点锁期间另一事务刚提交的预约。
    var conflicts =
        jdbc.queryForList(
            "SELECT id FROM ops_gpu_usage WHERE server_id=? AND gpu_index=? AND status IN"
                + " ('PLANNED','RUNNING') AND starts_at<? AND expected_end_at>? AND id<>? FOR"
                + " UPDATE",
            serverId,
            r.gpuIndex(),
            utc(r.expectedEndAt()),
            utc(r.startsAt()),
            id == null ? -1L : id);
    if (!conflicts.isEmpty())
      throw new ResponseStatusException(HttpStatus.CONFLICT, "该 GPU 在所选时间段已被预约，请选择其他时间或 GPU");
    Long exists =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM metric_gpu_sample g WHERE g.node_id=? AND g.gpu_index=? AND"
                + " g.snapshot_id=(SELECT id FROM metric_gpu_snapshot WHERE node_id=? ORDER BY"
                + " collected_at DESC,id DESC LIMIT 1)",
            Long.class,
            serverId,
            r.gpuIndex(),
            serverId);
    if (exists == 0)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "当前快照中没有该 GPU，请确认真实编号后登记");
    var now = utc(Instant.now());
    if (id != null) {
      jdbc.update(
          "UPDATE ops_gpu_usage SET"
              + " gpu_index=?,task_name=?,purpose=?,starts_at=?,expected_end_at=?,notes=?,updated_at=?,reservation_kind='RESERVATION'"
              + " WHERE id=?",
          r.gpuIndex(),
          r.taskName(),
          r.purpose(),
          utc(r.startsAt()),
          utc(r.expectedEndAt()),
          r.notes(),
          now,
          id);
      return;
    }
    jdbc.update(
        "INSERT INTO"
            + " ops_gpu_usage(server_id,gpu_index,user_id,task_name,purpose,starts_at,expected_end_at,notes,status,created_at,updated_at,reservation_kind)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,'RESERVATION')",
        serverId,
        r.gpuIndex(),
        access.currentUser().getId(),
        r.taskName(),
        r.purpose(),
        utc(r.startsAt()),
        utc(r.expectedEndAt()),
        r.notes(),
        r.status(),
        now,
        now);
  }

  @Transactional
  public void status(Long serverId, Long id, String next) {
    var node = access.requireNode(serverId, ClusterPermission.GPU_VIEW);
    jdbc.queryForMap("SELECT id FROM ops_node WHERE id=? FOR UPDATE", serverId);
    expire(serverId);
    rejectTrainingMutation(id);
    var row =
        jdbc
            .queryForList(
                "SELECT * FROM ops_gpu_usage WHERE id=? AND server_id=? FOR UPDATE", id, serverId)
            .stream()
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (((Number) row.get("user_id")).longValue() != access.currentUser().getId())
      access.require(node.getClusterId(), ClusterPermission.AGENT_MANAGE);
    String old = (String) row.get("status");
    var allowed =
        Map.of(
            "PLANNED",
            Set.of("RUNNING", "CANCELLED"),
            "RUNNING",
            Set.of("COMPLETED", "CANCELLED"),
            "COMPLETED",
            Set.<String>of(),
            "CANCELLED",
            Set.<String>of(),
            "EXPIRED",
            Set.<String>of());
    if ("RUNNING".equals(next)) {
      LocalDateTime start =
          jdbc.queryForObject(
              "SELECT starts_at FROM ops_gpu_usage WHERE id=?", LocalDateTime.class, id);
      if (start.isAfter(utc(Instant.now())))
        throw new ResponseStatusException(HttpStatus.CONFLICT, "预约尚未开始，不能标记使用");
    }
    if (!old.equals(next) && !allowed.get(old).contains(next))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "任务状态不能回退，终态不可重新开始");
    jdbc.update(
        "UPDATE ops_gpu_usage SET status=?,updated_at=? WHERE id=?", next, utc(Instant.now()), id);
  }

  private void rejectTrainingMutation(Long id) {
    if (jdbc.queryForObject("SELECT COUNT(*) FROM ops_training_job WHERE id=?", Long.class, id) > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "训练任务请在训练任务中心管理");
  }

  private void expire(Long serverId) {
    jdbc.update(
        "UPDATE ops_gpu_usage SET status='EXPIRED',updated_at=? WHERE server_id=? AND"
            + " reservation_kind='RESERVATION' AND status IN ('PLANNED','RUNNING') AND"
            + " expected_end_at<=?",
        utc(Instant.now()),
        serverId,
        utc(Instant.now()));
  }
}
