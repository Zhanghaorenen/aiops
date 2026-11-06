package com.aiops.backend.service;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlertEvaluationService {
  private final JdbcTemplate jdbc;
  private final AlertRuleEngine engine;

  public AlertEvaluationService(JdbcTemplate j, AlertRuleEngine e) {
    jdbc = j;
    engine = e;
  }

  public List<Long> enabledIds() {
    return jdbc.queryForList(
        "SELECT id FROM ops_alert_rule WHERE enabled=TRUE OR EXISTS(SELECT 1 FROM ops_alert_event e"
            + " WHERE e.rule_id=ops_alert_rule.id AND e.active_key IS NOT NULL)",
        Long.class);
  }

  private Instant instant(Object value) {
    if (value instanceof java.sql.Timestamp t) return t.toLocalDateTime().toInstant(ZoneOffset.UTC);
    if (value instanceof LocalDateTime t) return t.toInstant(ZoneOffset.UTC);
    return null;
  }

  private Number number(Map<String, Object> r, String name) {
    return (Number) r.get(name);
  }

  private int integer(Map<String, Object> r, String name) {
    return number(r, name).intValue();
  }

  @Transactional
  public void evaluate(Long ruleId, Instant now) {
    // 锁规则而不是依赖单 JVM synchronized，多实例也不能为同一规则重复创建活动事件。
    var rows =
        jdbc.queryForList(
            "SELECT r.* FROM ops_alert_rule r JOIN ops_cluster c ON c.id=r.cluster_id WHERE r.id=?"
                + " AND c.status='ACTIVE' FOR UPDATE",
            ruleId);
    if (rows.isEmpty()) return;
    var r = rows.get(0);
    long server = number(r, "server_id").longValue();
    String metric = (String) r.get("metric");
    int duration = integer(r, "duration_seconds"),
        freshness = integer(r, "freshness_seconds"),
        gap = integer(r, "max_gap_seconds");
    Instant from = now.minusSeconds((long) duration + freshness + gap + 60);
    Map<Integer, List<AlertRuleEngine.Sample>> series = new LinkedHashMap<>();
    if (Set.of("CPU_HIGH", "MEMORY_HIGH", "DISK_HIGH").contains(metric)) {
      String column =
          Map.of(
                  "CPU_HIGH",
                  "cpu_percent",
                  "MEMORY_HIGH",
                  "memory_percent",
                  "DISK_HIGH",
                  "disk_percent")
              .get(metric);
      series.put(
          -1,
          jdbc.query(
              "SELECT collected_at,"
                  + column
                  + " AS sample_value FROM metric_host_sample WHERE node_id=? AND collected_at>=?"
                  + " ORDER BY collected_at,id LIMIT 40000",
              (rs, n) ->
                  new AlertRuleEngine.Sample(
                      rs.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC),
                      rs.getDouble(2)),
              server,
              LocalDateTime.ofInstant(from, ZoneOffset.UTC)));
    } else if (metric.equals("SERVER_OFFLINE")) {
      var node = jdbc.queryForMap("SELECT last_seen_at FROM ops_node WHERE id=?", server);
      Instant last = instant(node.get("last_seen_at"));
      if (last == null) return;
      double seconds = Math.max(0, Duration.between(last, now).getSeconds());
      // 离线是“距最后心跳的时长”，不是把缺测 CPU 当作 0。
      series.put(
          -1,
          List.of(
              new AlertRuleEngine.Sample(
                  now.minusSeconds(
                      Math.max(0, (long) (seconds - number(r, "threshold_value").doubleValue()))),
                  seconds),
              new AlertRuleEngine.Sample(now, seconds)));
    } else if (metric.equals("GPU_COLLECTION_FAILED")) {
      series.put(
          -1,
          jdbc.query(
              "SELECT collected_at,collection_status FROM metric_gpu_snapshot WHERE node_id=? AND"
                  + " collected_at>=? ORDER BY collected_at,id LIMIT 40000",
              (rs, n) ->
                  new AlertRuleEngine.Sample(
                      rs.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC),
                      collectionFailure(rs.getString(2))),
              server,
              LocalDateTime.ofInstant(from, ZoneOffset.UTC)));
    } else {
      String value =
          metric.equals("GPU_TEMP_HIGH")
              ? "g.temperature_c"
              : "CASE WHEN g.memory_total_bytes>0 THEN"
                  + " g.memory_used_bytes*100.0/g.memory_total_bytes ELSE NULL END";
      var samples =
          jdbc.queryForList(
              "SELECT g.gpu_index,g.collected_at,"
                  + value
                  + " AS sample_value FROM metric_gpu_sample g JOIN metric_gpu_snapshot s ON"
                  + " s.id=g.snapshot_id WHERE g.node_id=? AND s.collection_status='AVAILABLE' AND"
                  + " g.collected_at>=? ORDER BY g.collected_at,g.id LIMIT 40000",
              server,
              LocalDateTime.ofInstant(from, ZoneOffset.UTC));
      for (var row : samples) {
        int gpu = ((Number) row.get("gpu_index")).intValue();
        series
            .computeIfAbsent(gpu, k -> new ArrayList<>())
            .add(
                new AlertRuleEngine.Sample(
                    instant(row.get("collected_at")),
                    row.get("sample_value") instanceof Number n ? n.doubleValue() : null));
      }
    }
    for (var entry : series.entrySet()) {
      var d =
          engine.evaluate(
              entry.getValue(),
              now,
              duration,
              freshness,
              gap,
              number(r, "threshold_value").doubleValue(),
              number(r, "recovery_threshold").doubleValue(),
              (String) r.get("comparison"));
      if (metric.equals("SERVER_OFFLINE")) {
        double age = entry.getValue().get(entry.getValue().size() - 1).value();
        d =
            new AlertRuleEngine.Decision(
                age >= number(r, "threshold_value").doubleValue() + duration,
                age <= number(r, "recovery_threshold").doubleValue(),
                now,
                age);
      }
      if (d.observedAt() == null) continue;
      String key = ruleId + ":" + server + ":" + entry.getKey();
      var active = jdbc.queryForList("SELECT * FROM ops_alert_event WHERE active_key=?", key);
      if (!active.isEmpty()) {
        var old = active.get(0);
        Instant observed = instant(old.get("last_observed_at"));
        if (observed != null && !d.observedAt().isAfter(observed)) continue;
        if (d.recovered())
          jdbc.update(
              "UPDATE ops_alert_event SET"
                  + " status='RESOLVED',active_key=NULL,resolved_at=?,last_observed_at=? WHERE"
                  + " id=?",
              utc(d.observedAt()),
              utc(d.observedAt()),
              old.get("id"));
        else
          jdbc.update(
              "UPDATE ops_alert_event SET last_observed_at=?,evidence=? WHERE id=?",
              utc(d.observedAt()),
              evidence(metric, d),
              old.get("id"));
      } else if (d.firing() && Boolean.TRUE.equals(r.get("enabled"))) {
        var resolved =
            jdbc.queryForList(
                "SELECT resolved_at FROM ops_alert_event WHERE fingerprint=? AND resolved_at IS NOT"
                    + " NULL ORDER BY resolved_at DESC LIMIT 1",
                key);
        if (!resolved.isEmpty()
            && instant(resolved.get(0).get("resolved_at"))
                .plusSeconds(integer(r, "cooldown_seconds"))
                .isAfter(now)) continue;
        jdbc.update(
            "INSERT INTO"
                + " ops_alert_event(rule_id,cluster_id,server_id,gpu_index,fingerprint,active_key,severity,status,first_triggered_at,last_observed_at,evidence)"
                + " VALUES(?,?,?,?,?,?,?,'FIRING',?,?,?)",
            ruleId,
            r.get("cluster_id"),
            server,
            entry.getKey() < 0 ? null : entry.getKey(),
            key,
            key,
            r.get("severity"),
            utc(d.observedAt()),
            utc(d.observedAt()),
            evidence(metric, d));
      }
    }
  }

  private Double collectionFailure(String status) {
    // 未知/未上报保留缺测，不能通过三元表达式的数值拆箱变成异常或误报恢复。
    return switch (status) {
      case "FAILED", "UNAVAILABLE" -> Double.valueOf(1);
      case "AVAILABLE", "NO_GPU" -> Double.valueOf(0);
      default -> null;
    };
  }

  private LocalDateTime utc(Instant i) {
    return LocalDateTime.ofInstant(i, ZoneOffset.UTC);
  }

  private String evidence(String metric, AlertRuleEngine.Decision d) {
    return "{\"metric\":\""
        + metric
        + "\",\"value\":"
        + d.value()
        + ",\"collected_at\":\""
        + d.observedAt()
        + "\"}";
  }
}
