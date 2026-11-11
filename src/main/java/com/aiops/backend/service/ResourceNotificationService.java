package com.aiops.backend.service;

import com.aiops.backend.security.ClusterPermission;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ResourceNotificationService {
  private final JdbcTemplate jdbc;
  private final ClusterPermissionService access;
  private final TrainingJobService jobs;
  private final WorkloadService workloads;
  private final ObjectMapper json = new ObjectMapper();

  public ResourceNotificationService(
      JdbcTemplate j,
      ClusterPermissionService a,
      TrainingJobService jobs,
      WorkloadService workloads) {
    jdbc = j;
    access = a;
    this.jobs = jobs;
    this.workloads = workloads;
  }

  public List<Map<String, Object>> subscriptions() {
    return jdbc.queryForList(
        "SELECT * FROM ops_resource_subscription WHERE user_id=? ORDER BY created_at DESC LIMIT"
            + " 200",
        access.currentUser().getId());
  }

  public String subscribe(long server, int gpu, double vram, double ram, String run, int cooldown) {
    access.requireNode(server, ClusterPermission.GPU_VIEW);
    if (!Double.isFinite(vram) || !Double.isFinite(ram) || vram < 0 || ram < 0 || vram + ram <= 0)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请设置有效资源需求");
    if (run != null) workloads.completionSummary(server, run);
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM ops_resource_subscription WHERE user_id=? AND status='ACTIVE'",
            Long.class,
            access.currentUser().getId())
        >= 100) throw new ResponseStatusException(HttpStatus.CONFLICT, "最多保留 100 条活动订阅");
    String id = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO"
            + " ops_resource_subscription(id,user_id,server_id,gpu_index,required_vram_mb,required_ram_mb,run_id,cooldown_seconds,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?)",
        id,
        access.currentUser().getId(),
        server,
        gpu,
        vram,
        ram,
        run,
        cooldown,
        TrainingJobService.utc(Instant.now()));
    return id;
  }

  public void cancel(String id) {
    if (jdbc.update(
            "UPDATE ops_resource_subscription SET status='CANCELLED' WHERE id=? AND user_id=?",
            id,
            access.currentUser().getId())
        != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  }

  public Object notifications() {
    return jdbc.queryForList(
        "SELECT notice.id,notice.kind,notice.content_json,notice.is_read,notice.created_at FROM"
            + " ops_user_notification notice JOIN ops_resource_subscription s ON"
            + " s.id=notice.subscription_id JOIN ops_node n ON n.id=s.server_id JOIN ops_cluster c"
            + " ON c.id=n.cluster_id JOIN ops_cluster_member m ON m.cluster_id=c.id AND"
            + " m.user_id=notice.user_id WHERE notice.user_id=? AND c.status='ACTIVE' AND"
            + " m.status='ACTIVE' AND m.role IN ('ADMIN','OPERATOR','VIEWER') ORDER BY"
            + " notice.created_at DESC LIMIT 200",
        access.currentUser().getId());
  }

  public void read(String id) {
    if (jdbc.update(
            "UPDATE ops_user_notification SET is_read=TRUE WHERE id=? AND user_id=?",
            id,
            access.currentUser().getId())
        != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  }

  @Transactional
  public void check(String id) {
    var rows =
        jdbc.queryForList(
            "SELECT * FROM ops_resource_subscription WHERE id=? AND user_id=? AND status='ACTIVE'"
                + " FOR UPDATE",
            id,
            access.currentUser().getId());
    if (rows.isEmpty()) return;
    var s = rows.get(0);
    long server = ((Number) s.get("server_id")).longValue();
    access.requireNode(server, ClusterPermission.GPU_VIEW);
    var capacity =
        jobs.capacity(
            server,
            ((Number) s.get("gpu_index")).intValue(),
            TrainingJobService.number(s.get("required_vram_mb")),
            TrainingJobService.number(s.get("required_ram_mb")));
    if (Boolean.TRUE.equals(capacity.get("allowed")))
      notify(
          s,
          "ACTUALLY_AVAILABLE",
          Map.of(
              "message", "最新采样和准入检查满足资源需求，请在实际启动前再次检查", "serverId", server, "capacity", capacity));
    else if (s.get("run_id") instanceof String run) {
      var prediction = workloads.completionSummary(server, run);
      if (prediction.get("earliestAt") instanceof String earliest
          && Duration.between(Instant.now(), Instant.parse(earliest)).getSeconds() <= 1800)
        notify(
            s,
            "EXPECTED_AVAILABLE",
            Map.of("message", "相关任务可能即将结束；尚未确认资源释放", "serverId", server, "prediction", prediction));
    }
  }

  private void notify(Map<String, Object> s, String kind, Map<String, Object> content) {
    Instant now = Instant.now();
    long cooldown = ((Number) s.get("cooldown_seconds")).longValue();
    var previous =
        jdbc.queryForList(
            "SELECT created_at FROM ops_user_notification WHERE subscription_id=? AND kind=? ORDER"
                + " BY created_at DESC LIMIT 1",
            s.get("id"),
            kind);
    if (!previous.isEmpty()
        && Duration.between(TrainingJobService.instant(previous.get(0).get("created_at")), now)
                .getSeconds()
            < cooldown) return;
    String dedup = s.get("id") + ":" + kind + ":" + (now.getEpochSecond() / cooldown);
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM ops_user_notification WHERE dedup_key=?", Long.class, dedup)
        > 0) return;
    jdbc.update(
        "INSERT INTO"
            + " ops_user_notification(id,user_id,subscription_id,kind,content_json,dedup_key,created_at)"
            + " VALUES(?,?,?,?,?,?,?)",
        UUID.randomUUID().toString(),
        access.currentUser().getId(),
        s.get("id"),
        kind,
        json.writeValueAsString(content),
        dedup,
        TrainingJobService.utc(now));
  }
}
