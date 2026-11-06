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

@Service
public class AlertRuleService {
  private final JdbcTemplate jdbc;
  private final ClusterPermissionService access;

  public AlertRuleService(JdbcTemplate jdbc, ClusterPermissionService access) {
    this.jdbc = jdbc;
    this.access = access;
  }

  public record Rule(
      @NotBlank @Size(max = 160) String name,
      @NotNull Long serverId,
      @Pattern(
              regexp =
                  "CPU_HIGH|MEMORY_HIGH|DISK_HIGH|GPU_TEMP_HIGH|GPU_MEMORY_HIGH|SERVER_OFFLINE|GPU_COLLECTION_FAILED")
          @NotNull
          String metric,
      @Pattern(regexp = "GT|GE") @NotNull String comparison,
      @DecimalMin("0") double threshold,
      @DecimalMin("0") double recoveryThreshold,
      @Min(0) @Max(86400) int durationSeconds,
      @Min(15) @Max(3600) int freshnessSeconds,
      @Min(5) @Max(3600) int maxGapSeconds,
      @Min(0) @Max(86400) int cooldownSeconds,
      @Pattern(regexp = "INFO|WARNING|CRITICAL") @NotNull String severity,
      boolean enabled) {}

  private LocalDateTime now() {
    return LocalDateTime.now(ZoneOffset.UTC);
  }

  public List<Map<String, Object>> rules() {
    var ids = access.readableClusterIds();
    if (ids.isEmpty()) return List.of();
    return jdbc.queryForList(
        "SELECT * FROM ops_alert_rule WHERE cluster_id IN ("
            + String.join(",", Collections.nCopies(ids.size(), "?"))
            + ") ORDER BY id DESC",
        ids.toArray());
  }

  @Transactional
  public void save(Long id, Rule r) {
    var node = access.requireNode(r.serverId(), ClusterPermission.ALERT_RULE_MANAGE);
    if (!Double.isFinite(r.threshold())
        || !Double.isFinite(r.recoveryThreshold())
        || r.recoveryThreshold() > r.threshold())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "恢复阈值必须不大于触发阈值");
    if (id == null)
      jdbc.update(
          "INSERT INTO"
              + " ops_alert_rule(cluster_id,server_id,name,metric,comparison,threshold_value,recovery_threshold,duration_seconds,freshness_seconds,max_gap_seconds,cooldown_seconds,severity,enabled,created_at,updated_at)"
              + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          node.getClusterId(),
          r.serverId(),
          r.name(),
          r.metric(),
          r.comparison(),
          r.threshold(),
          r.recoveryThreshold(),
          r.durationSeconds(),
          r.freshnessSeconds(),
          r.maxGapSeconds(),
          r.cooldownSeconds(),
          r.severity(),
          r.enabled(),
          now(),
          now());
    else {
      var old = oneRule(id);
      if (((Number) old.get("server_id")).longValue() != r.serverId())
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "规则不能改绑服务器");
      boolean changed =
          !old.get("metric").equals(r.metric())
              || !old.get("comparison").equals(r.comparison())
              || ((Number) old.get("threshold_value")).doubleValue() != r.threshold()
              || ((Number) old.get("recovery_threshold")).doubleValue() != r.recoveryThreshold()
              || ((Number) old.get("duration_seconds")).intValue() != r.durationSeconds()
              || ((Number) old.get("freshness_seconds")).intValue() != r.freshnessSeconds()
              || ((Number) old.get("max_gap_seconds")).intValue() != r.maxGapSeconds();
      if (changed
          && jdbc.queryForObject(
                  "SELECT COUNT(*) FROM ops_alert_event WHERE rule_id=? AND active_key IS NOT NULL",
                  Long.class,
                  id)
              > 0)
        throw new ResponseStatusException(HttpStatus.CONFLICT, "活动告警未恢复，暂不能修改规则判定条件；可以禁用新告警");
      access.require(
          ((Number) old.get("cluster_id")).longValue(), ClusterPermission.ALERT_RULE_MANAGE);
      jdbc.update(
          "UPDATE ops_alert_rule SET"
              + " name=?,metric=?,comparison=?,threshold_value=?,recovery_threshold=?,duration_seconds=?,freshness_seconds=?,max_gap_seconds=?,cooldown_seconds=?,severity=?,enabled=?,updated_at=?"
              + " WHERE id=?",
          r.name(),
          r.metric(),
          r.comparison(),
          r.threshold(),
          r.recoveryThreshold(),
          r.durationSeconds(),
          r.freshnessSeconds(),
          r.maxGapSeconds(),
          r.cooldownSeconds(),
          r.severity(),
          r.enabled(),
          now(),
          id);
    }
    access.audit(node.getClusterId(), "ALERT_RULE_SAVE", id, r.metric());
  }

  private Map<String, Object> oneRule(Long id) {
    // 与评估器锁同一规则，避免刚检查完活动事件就被并发计算插入新周期。
    return jdbc.queryForList("SELECT * FROM ops_alert_rule WHERE id=? FOR UPDATE", id).stream()
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  public Map<String, Object> event(Long id) {
    var row =
        jdbc.queryForList("SELECT * FROM ops_alert_event WHERE id=?", id).stream()
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    access.requireNode(((Number) row.get("server_id")).longValue(), ClusterPermission.METRIC_VIEW);
    return row;
  }

  public List<Map<String, Object>> events() {
    var ids = access.readableClusterIds();
    if (ids.isEmpty()) return List.of();
    return jdbc.queryForList(
        "SELECT * FROM ops_alert_event WHERE cluster_id IN ("
            + String.join(",", Collections.nCopies(ids.size(), "?"))
            + ") ORDER BY id DESC LIMIT 500",
        ids.toArray());
  }

  @Transactional
  public void acknowledge(Long id) {
    var row = event(id);
    Long cluster = ((Number) row.get("cluster_id")).longValue();
    access.require(cluster, ClusterPermission.ALERT_ACKNOWLEDGE);
    jdbc.update(
        "UPDATE ops_alert_event SET status='ACKNOWLEDGED',acknowledged_at=?,acknowledged_by=? WHERE"
            + " id=? AND status='FIRING'",
        now(),
        access.currentUser().getId(),
        id);
    access.audit(cluster, "ALERT_ACKNOWLEDGE", id, "人工确认不代表指标已经恢复");
  }
}
