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
  public record PresetRequest(
      @NotNull @Size(min = 1, max = 100) List<@NotNull Long> serverIds,
      @NotBlank String presetKey,
      Double threshold) {}

  private static final Map<String, double[]> DEFAULTS =
      Map.of(
          "SERVER_OFFLINE",
          new double[] {90, 30, 0},
          "CPU_HIGH",
          new double[] {90, 75, 60},
          "MEMORY_HIGH",
          new double[] {90, 75, 60},
          "DISK_HIGH",
          new double[] {90, 80, 60},
          "GPU_TEMP_HIGH",
          new double[] {85, 80, 60},
          "GPU_MEMORY_HIGH",
          new double[] {95, 85, 60});

  public List<Map<String, Object>> presets() {
    access.currentUser();
    var names =
        Map.of(
            "SERVER_OFFLINE",
            "服务器离线",
            "CPU_HIGH",
            "CPU 高占用",
            "MEMORY_HIGH",
            "内存高占用",
            "DISK_HIGH",
            "磁盘空间不足",
            "GPU_TEMP_HIGH",
            "GPU 温度过高",
            "GPU_MEMORY_HIGH",
            "GPU 显存紧张");
    return DEFAULTS.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(
            e ->
                Map.<String, Object>of(
                    "key",
                    e.getKey(),
                    "name",
                    names.get(e.getKey()),
                    "threshold",
                    e.getValue()[0],
                    "recoveryThreshold",
                    e.getValue()[1],
                    "durationSeconds",
                    e.getValue()[2],
                    "unit",
                    e.getKey().equals("SERVER_OFFLINE")
                        ? "秒"
                        : e.getKey().equals("GPU_TEMP_HIGH") ? "°C" : "%",
                    "description",
                    e.getKey().equals("GPU_MEMORY_HIGH")
                        ? "资源占用提示，不直接判定故障；阈值可调整。"
                        : "按新鲜样本与持续时间判定，初始建议值可调整。"))
        .toList();
  }

  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  public Map<String, Integer> applyPreset(PresetRequest req) {
    var defaults = DEFAULTS.get(req.presetKey());
    if (defaults == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知预设策略");
    double threshold = req.threshold() == null ? defaults[0] : req.threshold();
    if (!Double.isFinite(threshold) || threshold <= 0)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "阈值必须为正数");
    var ids = req.serverIds().stream().distinct().sorted().toList();
    for (var id : ids) access.requireNode(id, ClusterPermission.ALERT_RULE_MANAGE);
    int added = 0, skipped = 0;
    for (var id : ids) {
      jdbc.queryForMap("SELECT id FROM ops_node WHERE id=? FOR UPDATE", id);
      if (jdbc.queryForObject(
              "SELECT COUNT(*) FROM ops_alert_rule WHERE server_id=? AND metric=?",
              Long.class,
              id,
              req.presetKey())
          > 0) {
        skipped++;
        continue;
      }
      var node = access.requireNode(id, ClusterPermission.ALERT_RULE_MANAGE);
      jdbc.update(
          "INSERT INTO"
              + " ops_alert_rule(cluster_id,server_id,name,metric,comparison,threshold_value,recovery_threshold,duration_seconds,freshness_seconds,max_gap_seconds,cooldown_seconds,severity,enabled,created_at,updated_at,preset_key)"
              + " VALUES(?,?,?,?,'GE',?,?,?,?,?,300,?,TRUE,?,?,?)",
          node.getClusterId(),
          id,
          "预设 · " + req.presetKey(),
          req.presetKey(),
          threshold,
          Math.min(defaults[1], threshold * 0.9),
          (int) defaults[2],
          90,
          45,
          req.presetKey().equals("GPU_MEMORY_HIGH") ? "INFO" : "WARNING",
          now(),
          now(),
          req.presetKey());
      access.audit(node.getClusterId(), "ALERT_PRESET_ADD", id, req.presetKey());
      added++;
    }
    return Map.of("added", added, "skipped", skipped);
  }

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

  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
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
