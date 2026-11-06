package com.aiops.backend.service;

import com.aiops.backend.persistence.MetricDetails;
import com.aiops.backend.security.ClusterPermission;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 七天窗口先统计原始样本，再按时间桶降采样，避免早期 LIMIT 截掉近期曲线。 */
@Service
public class MonitoringTrendService {
  private final JdbcTemplate jdbc;
  private final ClusterPermissionService access;

  public MonitoringTrendService(JdbcTemplate jdbc, ClusterPermissionService access) {
    this.jdbc = jdbc;
    this.access = access;
  }

  public Map<String, Object> trends(Long serverId, Integer gpu, Instant from, Instant to) {
    access.requireNode(serverId, ClusterPermission.METRIC_VIEW);
    if (gpu != null && gpu < 0)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GPU 编号必须为非负数");
    if (from == null
        || to == null
        || !from.isBefore(to)
        || to.isAfter(Instant.now())
        || Duration.between(from, to).compareTo(Duration.ofDays(7)) > 0)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择不超过七天的历史窗口");
    var fields =
        gpu == null
            ? Map.of(
                "cpuPercent",
                "cpu_percent",
                "memoryPercent",
                "memory_percent",
                "diskPercent",
                "disk_percent")
            : Map.of(
                "utilization",
                "utilization_percent",
                "gpuMemoryUsed",
                "memory_used_bytes",
                "temperature",
                "temperature_c",
                "powerUsage",
                "power_usage_w");
    String columns = String.join(",", fields.values());
    var params = new ArrayList<Object>();
    params.add(serverId);
    if (gpu != null) params.add(gpu);
    params.add(LocalDateTime.ofInstant(from, ZoneOffset.UTC));
    params.add(LocalDateTime.ofInstant(to, ZoneOffset.UTC));
    var raw =
        jdbc.queryForList(
            "SELECT collected_at,details_json,"
                + columns
                + " FROM "
                + (gpu == null ? "metric_host_sample" : "metric_gpu_sample")
                + " WHERE node_id=? "
                + (gpu == null ? "" : "AND gpu_index=? ")
                + "AND collected_at BETWEEN ? AND ? ORDER BY collected_at,id LIMIT 130001",
            params.toArray());
    if (raw.size() > 130000)
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "采样过密，请缩小时间范围");
    var rows = new ArrayList<Map<String, Object>>();
    for (var r : raw) {
      var row = new LinkedHashMap<String, Object>();
      Object at = r.get("collected_at");
      Instant time =
          at instanceof java.sql.Timestamp t
              ? t.toLocalDateTime().toInstant(ZoneOffset.UTC)
              : ((LocalDateTime) at).toInstant(ZoneOffset.UTC);
      row.put("collectedAt", time.toString());
      row.put("details", MetricDetails.decode((String) r.get("details_json")));
      fields.forEach((key, column) -> row.put(key, r.get(column)));
      rows.add(row);
    }
    var stats = new LinkedHashMap<String, Object>();
    for (String key : fields.keySet()) {
      var valid = rows.stream().filter(r -> r.get(key) instanceof Number).toList();
      if (valid.isEmpty()) {
        stats.put(key, Map.of());
        continue;
      }
      var peak =
          valid.stream()
              .max(Comparator.comparingDouble(r -> ((Number) r.get(key)).doubleValue()))
              .orElseThrow();
      stats.put(
          key,
          Map.of(
              "average",
              valid.stream()
                  .mapToDouble(r -> ((Number) r.get(key)).doubleValue())
                  .average()
                  .orElseThrow(),
              "maximum",
              peak.get(key),
              "peakAt",
              peak.get("collectedAt"),
              "count",
              valid.size()));
    }
    var sampled = new ArrayList<Map<String, Object>>();
    // 每桶返回字段均值；原始峰值和时间在 stats 中独立保留。
    long bucketSize = Math.max(1, Duration.between(from, to).toSeconds() / 720);
    var buckets = new LinkedHashMap<Long, List<Map<String, Object>>>();
    for (var row : rows) {
      long bucket =
          Duration.between(from, Instant.parse((String) row.get("collectedAt"))).toSeconds()
              / bucketSize;
      buckets.computeIfAbsent(bucket, k -> new ArrayList<>()).add(row);
    }
    for (var group : buckets.values()) {
      var row = new LinkedHashMap<String, Object>(group.get(group.size() - 1));
      for (String key : fields.keySet()) {
        var values =
            group.stream()
                .filter(r -> r.get(key) instanceof Number)
                .mapToDouble(r -> ((Number) r.get(key)).doubleValue());
        row.put(
            key,
            values.average().isPresent()
                ? group.stream()
                    .filter(r -> r.get(key) instanceof Number)
                    .mapToDouble(r -> ((Number) r.get(key)).doubleValue())
                    .average()
                    .orElseThrow()
                : null);
      }
      sampled.add(row);
    }
    return Map.of(
        "samples",
        sampled,
        "stats",
        stats,
        "rawCount",
        rows.size(),
        "bucketSeconds",
        bucketSize,
        "from",
        from.toString(),
        "to",
        to.toString());
  }
}
