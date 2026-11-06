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
    return LocalDateTime.ofInstant(at, ZoneOffset.UTC);
  }

  public List<Map<String, Object>> list(Long serverId) {
    access.requireNode(serverId, ClusterPermission.GPU_VIEW);
    var rows =
        jdbc.queryForList(
            "SELECT t.*,u.username FROM ops_gpu_usage t JOIN sys_user u ON u.id=t.user_id WHERE"
                + " server_id=? ORDER BY id DESC LIMIT 200",
            serverId);
    for (var row : rows) {
      row.put("verification", "UNVERIFIED");
      row.put("reserves_gpu", false);
      row.put("verification_note", "人工登记未与操作系统任务身份绑定，请结合 GPU 进程和采集时间核实");
    }
    return rows;
  }

  @Transactional
  public void create(Long serverId, Usage r) {
    access.requireNode(serverId, ClusterPermission.GPU_VIEW);
    if (!r.expectedEndAt().isAfter(r.startsAt())
        || r.expectedEndAt().isAfter(r.startsAt().plus(Duration.ofDays(90))))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "预计结束时间应晚于开始时间，且不超过 90 天");
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
    jdbc.update(
        "INSERT INTO"
            + " ops_gpu_usage(server_id,gpu_index,user_id,task_name,purpose,starts_at,expected_end_at,notes,status,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
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
            Set.<String>of());
    if (!old.equals(next) && !allowed.get(old).contains(next))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "任务状态不能回退，终态不可重新开始");
    jdbc.update(
        "UPDATE ops_gpu_usage SET status=?,updated_at=? WHERE id=?", next, utc(Instant.now()), id);
  }
}
