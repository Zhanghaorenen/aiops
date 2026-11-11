package com.aiops.backend.service;

import com.aiops.backend.persistence.MetricDetails;
import com.aiops.backend.security.ClusterPermission;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 从已有 GPU 快照派生运行记录，不建立第二套主机监控存储。 */
@Service
public class WorkloadService {
  private final JdbcTemplate jdbc;
  private final ClusterPermissionService access;
  private final ObjectMapper json = new ObjectMapper();

  public WorkloadService(JdbcTemplate jdbc, ClusterPermissionService access) {
    this.jdbc = jdbc;
    this.access = access;
  }

  static double n(Object v) {
    return TrainingJobService.number(v);
  }

  static LocalDateTime utc(Instant t) {
    return TrainingJobService.utc(t);
  }

  static String hash(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private ResponseStatusException error(int code, String text) {
    return new ResponseStatusException(HttpStatus.valueOf(code), text);
  }

  private boolean admin(long server) {
    return "ADMIN"
        .equals(
            access
                .require(
                    access.requireNode(server, ClusterPermission.GPU_VIEW).getClusterId(),
                    ClusterPermission.GPU_VIEW)
                .getRole());
  }

  private Map<String, Object> run(long server, String id, boolean privateDetails) {
    access.requireNode(server, ClusterPermission.GPU_VIEW);
    var rows =
        jdbc.queryForList("SELECT * FROM ops_workload_run WHERE server_id=? AND id=?", server, id);
    if (rows.isEmpty()) throw error(404, "运行记录不存在");
    var r = rows.get(0);
    if (privateDetails
        && !Objects.equals(r.get("user_id"), access.currentUser().getId())
        && !admin(server)) throw error(403, "无权读取私人任务记录");
    return r;
  }

  /** 由已认证 Agent 的入库事务调用，不使用前端提交的用户 ID。 */
  @Transactional
  public void observe(long server) {
    jdbc.queryForMap("SELECT id FROM ops_node WHERE id=? FOR UPDATE", server);
    var snapshots =
        jdbc.queryForList(
            "SELECT * FROM metric_gpu_snapshot WHERE node_id=? ORDER BY collected_at DESC,id DESC"
                + " LIMIT 1",
            server);
    if (snapshots.isEmpty()) return;
    var snapshot = snapshots.get(0);
    Object snapshotId = snapshot.get("id");
    Instant at = TrainingJobService.instant(snapshot.get("collected_at"));
    var all =
        jdbc.queryForList(
            "SELECT p.*,g.gpu_uuid,g.gpu_name,g.utilization_percent FROM metric_gpu_process_sample"
                + " p JOIN metric_gpu_sample g ON g.snapshot_id=p.snapshot_id AND"
                + " g.gpu_index=p.gpu_index WHERE p.snapshot_id=?",
            snapshotId);
    var identities = new HashMap<String, Map<String, Object>>();
    for (var p : all) {
      var d = MetricDetails.decode((String) p.get("details_json"));
      boolean validTime = false;
      try {
        validTime =
            d.get("startedAt") instanceof String t && !Instant.parse(t).isAfter(at.plusSeconds(60));
      } catch (java.time.format.DateTimeParseException ignored) {
        /* 不完整的进程身份不建档。 */
      }
      if (validTime && d.get("linuxUid") instanceof Number) {
        p.put("details", d);
        identities.put(p.get("pid").toString(), p);
      }
    }
    var groups = new LinkedHashMap<String, List<Map<String, Object>>>();
    for (var p : all) {
      if (!(p.get("details") instanceof Map<?, ?> d)) continue;
      var root = p;
      var seen = new HashSet<Object>();
      // 只合并同一 UID、同一快照中确实出现的 GPU 父子进程；缺少证据保持独立候选。
      while (seen.add(root.get("pid"))) {
        var rd = (Map<?, ?>) root.get("details");
        var parent = identities.get(String.valueOf(rd.get("parentPid")));
        if (parent == null
            || !Objects.equals(
                ((Map<?, ?>) parent.get("details")).get("linuxUid"), d.get("linuxUid"))) break;
        if (Instant.parse(((Map<?, ?>) parent.get("details")).get("startedAt").toString())
            .isAfter(Instant.parse(d.get("startedAt").toString()))) break;
        root = parent;
      }
      var rd = (Map<?, ?>) root.get("details");
      String key = root.get("pid") + "|" + rd.get("startedAt");
      groups.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
    }
    var active = new HashSet<String>();
    for (var entry : groups.entrySet()) {
      var processes = entry.getValue();
      var first = processes.get(0);
      var d = (Map<?, ?>) first.get("details");
      String[] parts = entry.getKey().split("\\|", 2);
      long pid = Long.parseLong(parts[0]);
      String started = parts[1];
      var mapping =
          jdbc.queryForList(
              "SELECT i.user_id FROM ops_training_linux_identity i JOIN sys_user u ON"
                  + " u.id=i.user_id JOIN ops_node n ON n.id=i.server_id JOIN ops_cluster_member m"
                  + " ON m.cluster_id=n.cluster_id AND m.user_id=i.user_id WHERE i.server_id=? AND"
                  + " i.linux_username=? AND (i.linux_uid IS NULL OR i.linux_uid=?) AND"
                  + " u.status='ACTIVE' AND m.status='ACTIVE'",
              server,
              first.get("username"),
              d.get("linuxUid"));
      Object owner = mapping.size() == 1 ? mapping.get(0).get("user_id") : null;
      var registered =
          jdbc.queryForList(
              "SELECT j.*,u.user_id FROM ops_training_job j JOIN ops_gpu_usage u ON u.id=j.id WHERE"
                  + " u.server_id=? AND j.pid=? AND j.process_started_at=? AND u.user_id=?",
              server,
              pid,
              started,
              owner);
      Object job = registered.isEmpty() ? null : registered.get(0).get("id");
      var rows =
          jdbc.queryForList(
              "SELECT * FROM ops_workload_run WHERE server_id=? AND root_pid=? AND"
                  + " process_started_at=?",
              server,
              pid,
              started);
      String id = rows.isEmpty() ? UUID.randomUUID().toString() : rows.get(0).get("id").toString();
      active.add(id);
      // 运行归属一旦确定就不随管理员后续改映射而转给另一用户。
      if (!rows.isEmpty()
          && rows.get(0).get("user_id") != null
          && !Objects.equals(rows.get(0).get("user_id"), owner)) {
        registered = List.of();
        job = null;
      }

      if (rows.isEmpty())
        jdbc.update(
            "INSERT INTO"
                + " ops_workload_run(id,server_id,user_id,job_id,root_pid,process_started_at,linux_uid,linux_username,state,confirmed,grouping_json,first_seen,last_seen)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id,
            server,
            owner,
            job,
            pid,
            started,
            d.get("linuxUid"),
            first.get("username"),
            "RUNNING",
            job != null,
            json.writeValueAsString(
                Map.of(
                    "basis",
                    "GPU_PARENT_UID_START_TIME",
                    "confidence",
                    "CONSERVATIVE",
                    "trainingConfirmed",
                    job != null)),
            utc(at),
            utc(at));
      else if (at.isAfter(TrainingJobService.instant(rows.get(0).get("last_seen")))
          && !Set.of("EXITED_UNKNOWN", "SUCCEEDED", "FAILED").contains(rows.get(0).get("state")))
        jdbc.update(
            "UPDATE ops_workload_run SET state='RUNNING',last_seen=? WHERE id=?", utc(at), id);
      if (!rows.isEmpty() && rows.get(0).get("user_id") == null && owner != null)
        jdbc.update("UPDATE ops_workload_run SET user_id=? WHERE id=?", owner, id);
      if (!registered.isEmpty() && (rows.isEmpty() || rows.get(0).get("job_id") == null)) {
        jdbc.update("UPDATE ops_workload_run SET job_id=? WHERE id=?", job, id);
        var req = MetricDetails.decode((String) registered.get(0).get("request_json"));
        configure(
            id,
            owner,
            String.valueOf(req.getOrDefault("taskType", "UNCLASSIFIED")),
            hash(String.valueOf(req.get("runConfiguration")) + "|" + req.get("dataset")),
            hash(
                jdbc.queryForMap(
                        "SELECT os_name,os_version,architecture FROM ops_node WHERE id=?", server)
                    .toString()),
            processes);
      }
      var metrics = measure(processes, server, at);
      if (jdbc.queryForObject(
              "SELECT COUNT(*) FROM ops_workload_sample WHERE run_id=? AND snapshot_id=?",
              Long.class,
              id,
              snapshotId)
          == 0)
        jdbc.update(
            "INSERT INTO ops_workload_sample(run_id,snapshot_id,sampled_at,metrics_json)"
                + " VALUES(?,?,?,?)",
            id,
            snapshotId,
            utc(at),
            json.writeValueAsString(metrics));
      long samples =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM ops_workload_sample WHERE run_id=?", Long.class, id);
      if (samples % 10 == 0) refreshProfile(id);
    }
    var host =
        jdbc.queryForList(
            "SELECT details_json,collected_at FROM metric_host_sample WHERE node_id=? ORDER BY"
                + " collected_at DESC,id DESC LIMIT 1",
            server);
    Object exits =
        host.isEmpty()
            ? null
            : MetricDetails.decode((String) host.get(0).get("details_json")).get("processExits");
    for (var old :
        jdbc.queryForList(
            "SELECT * FROM ops_workload_run WHERE server_id=? AND state IN ('RUNNING','MISSING')",
            server)) {
      String id = old.get("id").toString();
      if (at.isBefore(TrainingJobService.instant(old.get("last_seen")))) continue;
      String state = null, source = null;
      if (old.get("job_id") != null) {
        String reported =
            jdbc.queryForObject(
                "SELECT state FROM ops_training_job WHERE id=?", String.class, old.get("job_id"));
        if (Set.of("SUCCEEDED", "FAILED").contains(reported)) {
          state = reported;
          source = "USER_OR_SDK";
        }
      }
      if (state == null && !active.contains(id) && exits instanceof List<?> list) {
        var last =
            jdbc.queryForList(
                "SELECT metrics_json FROM ops_workload_sample WHERE run_id=? ORDER BY sampled_at"
                    + " DESC LIMIT 1",
                id);
        Object members =
            last.isEmpty()
                ? null
                : MetricDetails.decode((String) last.get(0).get("metrics_json")).get("members");
        boolean allExited =
            members instanceof List<?> tracked
                && !tracked.isEmpty()
                && tracked.stream()
                    .allMatch(
                        member ->
                            member instanceof Map<?, ?> m
                                && list.stream()
                                    .anyMatch(
                                        value ->
                                            value instanceof Map<?, ?> e
                                                && n(e.get("pid")) == n(m.get("pid"))
                                                && Objects.equals(
                                                    e.get("startedAt"), m.get("startedAt"))
                                                && "EXITED_UNKNOWN".equals(e.get("status"))));
        if (allExited) {
          state = "EXITED_UNKNOWN";
          source = "AGENT_GROUP_IDENTITIES_EXIT";
        }
      }
      if (state != null) {
        jdbc.update(
            "UPDATE ops_workload_run SET state=?,ended_at=?,outcome_source=? WHERE id=?",
            state,
            utc(at),
            source,
            id);
        reconcilePredictions(id, at);
        refreshProfile(id);
      } else if (!active.contains(id))
        jdbc.update("UPDATE ops_workload_run SET state='MISSING' WHERE id=?", id);
    }
  }

  private Map<String, Object> measure(List<Map<String, Object>> ps, long server, Instant at) {
    var m = new LinkedHashMap<String, Object>();
    var perGpu = new TreeMap<String, Double>();
    var cards = new TreeMap<String, Object>();
    double cpu = 0, rss = 0, uss = 0, read = 0, write = 0;
    boolean cpuKnown = true, rssKnown = true, ussKnown = true, ioKnown = true, gpuKnown = true;
    var memberByPid = new LinkedHashMap<Object, Map<?, ?>>();
    for (var p : ps) {
      var d = (Map<?, ?>) p.get("details");
      String uuid = p.get("gpu_uuid").toString();
      gpuKnown &= p.get("gpu_memory_usage_bytes") instanceof Number;
      if (p.get("gpu_memory_usage_bytes") instanceof Number)
        perGpu.merge(uuid, n(p.get("gpu_memory_usage_bytes")), Double::sum);
      cards.put(uuid, p.get("utilization_percent"));
      // 先保留 GPU 进程的完整采样，再补充同 UID 子进程，避免多卡和重叠进程树重复计数。
      memberByPid.put(p.get("pid"), d);
    }
    for (var p : ps) {
      var d = (Map<?, ?>) p.get("details");
      if (d.get("processTree") instanceof List<?> list)
        for (Object value : list)
          if (value instanceof Map<?, ?> child
              && child.get("pid") != null
              && child.get("startedAt") != null
              && Objects.equals(child.get("linuxUid"), d.get("linuxUid")))
            memberByPid.putIfAbsent(child.get("pid"), child);
    }
    var members = new ArrayList<Map<String, Object>>();
    for (var entry : memberByPid.entrySet()) {
      var d = entry.getValue();
      members.add(Map.of("pid", entry.getKey(), "startedAt", d.get("startedAt")));
      cpuKnown &= d.get("cpuPercent") instanceof Number;
      cpu += n(d.get("cpuPercent"));
      rssKnown &= d.get("memoryRssBytes") instanceof Number;
      rss += n(d.get("memoryRssBytes"));
      ussKnown &= d.get("memoryUssBytes") instanceof Number;
      uss += n(d.get("memoryUssBytes"));
      ioKnown &=
          d.get("readBytesPerSecond") instanceof Number
              && d.get("writeBytesPerSecond") instanceof Number;
      read += n(d.get("readBytesPerSecond"));
      write += n(d.get("writeBytesPerSecond"));
    }
    m.put("cpuPercent", cpuKnown ? cpu : null);
    m.put("rssEstimateBytes", rssKnown ? rss : null);
    m.put("ussBytes", ussKnown ? uss : null);
    m.put("readBytesPerSecond", ioKnown ? read : null);
    m.put("writeBytesPerSecond", ioKnown ? write : null);
    m.put("gpuMemoryByUuid", perGpu);
    m.put(
        "gpuMemoryBytes",
        gpuKnown
            ? perGpu.values().stream().mapToDouble(Double::doubleValue).max().orElse(0)
            : null);
    m.put("wholeGpuUtilization", cards);
    m.put("members", members);
    m.put(
        "metricScope",
        "Observed GPU processes and same-UID descendants, capped by Agent; RSS sum is an upper"
            + " estimate with shared pages, USS excludes shared pages; GPU utilization belongs to"
            + " whole card; CPU may exceed 100%");
    var h =
        jdbc.queryForList(
            "SELECT details_json FROM metric_host_sample WHERE node_id=? AND collected_at<=? ORDER"
                + " BY collected_at DESC,id DESC LIMIT 1",
            server,
            utc(at));
    var pressure = new LinkedHashMap<String, Object>();
    if (!h.isEmpty()) {
      var d = MetricDetails.decode((String) h.get(0).get("details_json"));
      for (String key : List.of("cpuPsiAvg10", "memoryPsiAvg10", "ioPsiAvg10"))
        pressure.put(key, d.get(key));
    }
    m.put("hostPressure", pressure);
    return m;
  }

  private void configure(
      String id,
      Object owner,
      String type,
      String config,
      String environment,
      List<Map<String, Object>> ps) {
    var models = new TreeSet<String>();
    for (var p : ps) models.add(String.valueOf(p.get("gpu_name")));
    String key = hash(owner + "|" + type + "|" + config + "|" + environment + "|" + models);
    jdbc.update(
        "UPDATE ops_workload_run SET"
            + " confirmed=TRUE,task_type=?,configuration_key=?,environment_key=?,profile_key=?"
            + " WHERE id=?",
        type,
        config,
        environment,
        key,
        id);
  }

  @Transactional
  public void confirm(
      long server, String id, String type, String config, String environment, Long jobId) {
    var r = run(server, id, true);
    jdbc.queryForMap("SELECT id FROM ops_node WHERE id=? FOR UPDATE", server);
    if (r.get("user_id") == null) throw error(409, "请管理员先维护唯一的 Linux 用户映射");
    if (jobId != null) {
      var linked =
          jdbc.queryForList(
              "SELECT j.id FROM ops_training_job j JOIN ops_gpu_usage u ON u.id=j.id WHERE"
                  + " u.server_id=? AND j.id=? AND u.user_id=? AND j.pid=? AND"
                  + " j.process_started_at=?",
              server,
              jobId,
              r.get("user_id"),
              r.get("root_pid"),
              r.get("process_started_at"));
      if (linked.isEmpty()) throw error(409, "训练登记与实际进程身份不一致，请先关联进程");
      jdbc.update("UPDATE ops_workload_run SET job_id=? WHERE id=?", jobId, id);
    }
    var first =
        jdbc.queryForList(
            "SELECT metrics_json,snapshot_id FROM ops_workload_sample WHERE run_id=? ORDER BY"
                + " sampled_at LIMIT 1",
            id);
    var cards = new ArrayList<Map<String, Object>>();
    if (!first.isEmpty()) {
      var gpuUuids =
          MetricDetails.decode((String) first.get(0).get("metrics_json")).get("gpuMemoryByUuid");
      if (gpuUuids instanceof Map<?, ?> gpu)
        for (Object uuid : gpu.keySet())
          cards.addAll(
              jdbc.queryForList(
                  "SELECT gpu_name FROM metric_gpu_sample WHERE snapshot_id=? AND gpu_uuid=?",
                  first.get(0).get("snapshot_id"),
                  uuid));
    }
    configure(id, r.get("user_id"), type, hash(config), environment, cards);
    refreshProfile(id);
  }

  public List<Map<String, Object>> list(long server, boolean active) {
    access.requireNode(server, ClusterPermission.GPU_VIEW);
    long user = access.currentUser().getId();
    boolean manager = admin(server);
    var rows =
        jdbc.queryForList(
            "SELECT * FROM ops_workload_run WHERE server_id=?"
                + (active ? " AND state IN ('RUNNING','MISSING')" : "")
                + " ORDER BY last_seen DESC LIMIT 200",
            server);
    var result = new ArrayList<Map<String, Object>>();
    for (var r : rows) {
      var summary = new LinkedHashMap<String, Object>();
      for (String k : List.of("id", "state", "first_seen", "last_seen", "ended_at"))
        summary.put(k, r.get(k));
      var latest =
          jdbc.queryForList(
              "SELECT metrics_json,sampled_at FROM ops_workload_sample WHERE run_id=? ORDER BY"
                  + " sampled_at DESC LIMIT 1",
              r.get("id"));
      if (!latest.isEmpty()) {
        var metrics =
            new LinkedHashMap<>(MetricDetails.decode((String) latest.get(0).get("metrics_json")));
        metrics.remove("members");
        summary.put("resources", metrics);
        summary.put("sampledAt", latest.get(0).get("sampled_at"));
      }
      summary.put("privateDetails", manager || Objects.equals(r.get("user_id"), user));
      if (Boolean.TRUE.equals(summary.get("privateDetails"))) {
        summary.put("run", r);
      } else summary.put("owner", "SHARED_RESOURCE_SUMMARY");
      summary.put(
          "fresh",
          Duration.between(TrainingJobService.instant(r.get("last_seen")), Instant.now())
                  .getSeconds()
              <= 90);
      summary.put("completion", completionSummary(server, r.get("id").toString()));
      result.add(summary);
    }
    return result;
  }

  public Map<String, Object> completionSummary(long server, String id) {
    var r = run(server, id, false);
    if (!"RUNNING".equals(r.get("state"))
        || Duration.between(TrainingJobService.instant(r.get("last_seen")), Instant.now())
                .getSeconds()
            > 90) return Map.of("confidence", "UNAVAILABLE");
    var predictions =
        jdbc.queryForList(
            "SELECT earliest_at,latest_at,predicted_at FROM ops_workload_prediction WHERE run_id=?"
                + " AND actual_at IS NULL ORDER BY predicted_at DESC LIMIT 1",
            id);
    if (predictions.isEmpty()
        || Duration.between(
                    TrainingJobService.instant(predictions.get(0).get("predicted_at")),
                    Instant.now())
                .getSeconds()
            > 90) return Map.of("confidence", "UNAVAILABLE");
    var prediction = predictions.get(0);
    return Map.of(
        "earliestAt",
        TrainingJobService.instant(prediction.get("earliest_at")).toString(),
        "latestAt",
        TrainingJobService.instant(prediction.get("latest_at")).toString(),
        "updatedAt",
        TrainingJobService.instant(prediction.get("predicted_at")).toString(),
        "confidence",
        "ESTIMATE",
        "limitations",
        "共享摘要，仅为预计结束范围，不代表资源已释放");
  }

  public Map<String, Object> detail(long server, String id) {
    var r = run(server, id, true);
    r.put(
        "samples",
        jdbc.queryForList(
            "SELECT sampled_at,metrics_json FROM ops_workload_sample WHERE run_id=? ORDER BY"
                + " sampled_at DESC LIMIT 200",
            id));
    return r;
  }

  public Map<String, Object> profile(long server, String id) {
    var r = run(server, id, true);
    var rows =
        jdbc.queryForList(
            "SELECT * FROM ops_workload_profile WHERE profile_key=? AND user_id=?",
            r.get("profile_key"),
            r.get("user_id"));
    return rows.isEmpty()
        ? Map.of("confidence", "LOW_CONFIDENCE", "missingEvidence", List.of("尚无已确认的同配置任务画像"))
        : MetricDetails.decode((String) rows.get(0).get("statistics_json"));
  }

  public static double quantile(List<Double> values, double q) {
    if (values.isEmpty()) return 0;
    var sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    double pos = q * (sorted.size() - 1);
    int i = (int) pos;
    return sorted.get(i)
        + (sorted.get(Math.min(i + 1, sorted.size() - 1)) - sorted.get(i)) * (pos - i);
  }

  private void refreshProfile(String id) {
    var r = jdbc.queryForMap("SELECT * FROM ops_workload_run WHERE id=?", id);
    if (!Boolean.TRUE.equals(r.get("confirmed"))
        || r.get("profile_key") == null
        || r.get("user_id") == null) return;
    jdbc.queryForMap("SELECT id FROM sys_user WHERE id=? FOR UPDATE", r.get("user_id"));
    var runs =
        jdbc.queryForList(
            "SELECT * FROM ops_workload_run WHERE profile_key=? AND user_id=? ORDER BY first_seen"
                + " DESC LIMIT 100",
            r.get("profile_key"),
            r.get("user_id"));
    var metrics = new HashMap<String, List<Double>>();
    var durations = new ArrayList<Double>();
    int sampleCount = 0;
    for (var one : runs) {
      if (one.get("ended_at") != null && "SUCCEEDED".equals(one.get("state")))
        durations.add(
            (double)
                Duration.between(
                        Instant.parse(one.get("process_started_at").toString()),
                        TrainingJobService.instant(one.get("ended_at")))
                    .getSeconds());
      for (var sample :
          jdbc.queryForList(
              "SELECT metrics_json FROM ops_workload_sample WHERE run_id=? ORDER BY sampled_at DESC"
                  + " LIMIT 5000",
              one.get("id"))) {
        sampleCount++;
        var m = MetricDetails.decode((String) sample.get("metrics_json"));
        for (String key :
            List.of(
                "gpuMemoryBytes",
                "cpuPercent",
                "rssEstimateBytes",
                "ussBytes",
                "readBytesPerSecond",
                "writeBytesPerSecond"))
          if (m.get(key) instanceof Number)
            metrics.computeIfAbsent(key, k -> new ArrayList<>()).add(n(m.get(key)));
      }
    }
    var stats = new LinkedHashMap<String, Object>();
    for (var e : metrics.entrySet())
      stats.put(
          e.getKey(),
          Map.of(
              "p50",
              quantile(e.getValue(), .5),
              "p95",
              quantile(e.getValue(), .95),
              "peak",
              quantile(e.getValue(), 1),
              "mean",
              e.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0),
              "samples",
              e.getValue().size()));
    stats.put(
        "predictionQuality",
        jdbc.queryForList(
            "SELECT p.method,COUNT(*) AS evaluated,AVG(p.absolute_error_seconds) AS mae_seconds"
                + " FROM ops_workload_prediction p JOIN ops_workload_run r ON r.id=p.run_id WHERE"
                + " r.profile_key=? AND r.user_id=? AND p.actual_at IS NOT NULL GROUP BY p.method",
            r.get("profile_key"),
            r.get("user_id")));
    stats.put(
        "outcomes",
        runs.stream()
            .collect(
                java.util.stream.Collectors.groupingBy(
                    one -> String.valueOf(one.get("state")),
                    java.util.stream.Collectors.counting())));
    stats.put(
        "hasProgress",
        runs.stream()
            .anyMatch(
                one ->
                    one.get("job_id") != null
                        && jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ops_training_progress WHERE job_id=?",
                                Long.class,
                                one.get("job_id"))
                            > 0));
    stats.put("successfulDurationsSeconds", durations);
    stats.put("runCount", runs.size());
    stats.put("sampleCount", sampleCount);
    stats.put(
        "confidence", runs.size() >= 3 && sampleCount >= 10 ? "STATISTICAL" : "LOW_CONFIDENCE");
    stats.put("requiredAdditionalRuns", Math.max(0, 3 - runs.size()));
    stats.put(
        "scope",
        "Latest 100 matching private runs, up to 5000 samples each; GPU-observed processes; RSS"
            + " upper estimate");
    stats.put("updatedAt", Instant.now().toString());
    jdbc.update("DELETE FROM ops_workload_profile WHERE profile_key=?", r.get("profile_key"));
    jdbc.update(
        "INSERT INTO ops_workload_profile(profile_key,user_id,statistics_json,updated_at)"
            + " VALUES(?,?,?,?)",
        r.get("profile_key"),
        r.get("user_id"),
        json.writeValueAsString(stats),
        utc(Instant.now()));
  }

  private void reconcilePredictions(String id, Instant actual) {
    for (var p :
        jdbc.queryForList(
            "SELECT * FROM ops_workload_prediction WHERE run_id=? AND actual_at IS NULL", id)) {
      Instant a = TrainingJobService.instant(p.get("earliest_at")),
          b = TrainingJobService.instant(p.get("latest_at"));
      double midpoint = (a.getEpochSecond() + b.getEpochSecond()) / 2d;
      double error = Math.abs(actual.getEpochSecond() - midpoint),
          horizon =
              actual.getEpochSecond()
                  - TrainingJobService.instant(p.get("predicted_at")).getEpochSecond();
      jdbc.update(
          "UPDATE ops_workload_prediction SET actual_at=?,absolute_error_seconds=?,relative_error=?"
              + " WHERE id=?",
          utc(actual),
          error,
          horizon > 0 ? error / horizon : null,
          p.get("id"));
    }
  }

  @Transactional
  public Map<String, Object> predict(long server, String id) {
    var r = run(server, id, true);
    // SDK 已确认结果时不再等待下一个 GPU 快照，停机前的最后一次报告也能归档。
    if (r.get("job_id") != null && Set.of("RUNNING", "MISSING").contains(r.get("state"))) {
      var reported =
          jdbc.queryForMap(
              "SELECT j.state,u.updated_at FROM ops_training_job j JOIN ops_gpu_usage u ON"
                  + " u.id=j.id WHERE j.id=?",
              r.get("job_id"));
      if (Set.of("SUCCEEDED", "FAILED").contains(reported.get("state"))) {
        Instant ended = TrainingJobService.instant(reported.get("updated_at"));
        jdbc.update(
            "UPDATE ops_workload_run SET state=?,ended_at=?,outcome_source='USER_OR_SDK' WHERE"
                + " id=?",
            reported.get("state"),
            utc(ended),
            id);
        reconcilePredictions(id, ended);
        refreshProfile(id);
        return Map.of("confidence", "UNAVAILABLE", "reason", "任务已由用户或 SDK 确认结束");
      }
    }
    Instant now = Instant.now();
    if (!"RUNNING".equals(r.get("state"))
        || Duration.between(TrainingJobService.instant(r.get("last_seen")), now).getSeconds() > 90)
      return Map.of("confidence", "UNAVAILABLE", "reason", "任务未运行或采样已过期");
    double low = 0, high = 0;
    int count = 0;
    String method = "HISTORY_V1";
    if (r.get("job_id") != null) {
      var progress =
          jdbc.queryForList(
              "SELECT * FROM ops_training_progress WHERE job_id=? ORDER BY collected_at DESC,id"
                  + " DESC LIMIT 20",
              r.get("job_id"));
      if (progress.size() >= 3
          && Duration.between(TrainingJobService.instant(progress.get(0).get("collected_at")), now)
                  .getSeconds()
              <= 90) {
        var newest = MetricDetails.decode((String) progress.get(0).get("payload_json"));
        var times = new ArrayList<Double>();
        for (var p : progress) {
          var m = MetricDetails.decode((String) p.get("payload_json"));
          if (Objects.equals(m.get("phase"), newest.get("phase"))
              && n(m.get("stepTime")) > 0
              && n(m.get("totalSteps")) == n(newest.get("totalSteps"))
              && Duration.between(TrainingJobService.instant(p.get("collected_at")), now)
                      .getSeconds()
                  <= 300) times.add(n(m.get("stepTime")));
          else break;
        }
        double remain = n(newest.get("totalSteps")) - n(newest.get("step"));
        var oldest =
            MetricDetails.decode(
                (String) progress.get(Math.max(0, times.size() - 1)).get("payload_json"));
        if (times.size() >= 3
            && n(newest.get("step")) > n(oldest.get("step"))
            && quantile(times, .9) <= quantile(times, .1) * 1.5
            && remain > 0
            && Set.of("train", "training")
                .contains(String.valueOf(newest.get("phase")).toLowerCase(Locale.ROOT))
            && n(newest.get("totalSteps")) > 0) {
          low = remain * quantile(times, .1) * .9;
          high = remain * quantile(times, .9) * 1.2;
          count = times.size();
          method = "PROGRESS_V1";
        }
        if (count == 0
            && n(newest.get("totalEpochs")) > n(newest.get("epoch"))
            && Set.of("train", "training")
                .contains(String.valueOf(newest.get("phase")).toLowerCase(Locale.ROOT))) {
          var secondsPerEpoch = new ArrayList<Double>();
          for (int i = 0; i < progress.size() - 1; i++) {
            var recent = progress.get(i);
            var previous = progress.get(i + 1);
            var a = MetricDetails.decode((String) recent.get("payload_json"));
            var b = MetricDetails.decode((String) previous.get("payload_json"));
            if (!Objects.equals(a.get("phase"), newest.get("phase"))
                || !Objects.equals(b.get("phase"), newest.get("phase"))
                || n(a.get("totalEpochs")) != n(newest.get("totalEpochs"))
                || n(b.get("totalEpochs")) != n(newest.get("totalEpochs"))) break;
            if (Duration.between(TrainingJobService.instant(previous.get("collected_at")), now)
                    .getSeconds()
                > 1800) break;
            double delta = n(a.get("epoch")) - n(b.get("epoch"));
            double seconds =
                Duration.between(
                            TrainingJobService.instant(previous.get("collected_at")),
                            TrainingJobService.instant(recent.get("collected_at")))
                        .toMillis()
                    / 1000d;
            if (delta < 0) break;
            if (delta > 0 && seconds > 0) secondsPerEpoch.add(seconds / delta);
          }
          if (secondsPerEpoch.size() >= 2
              && quantile(secondsPerEpoch, .9) <= quantile(secondsPerEpoch, .1) * 1.5) {
            double epochs = n(newest.get("totalEpochs")) - n(newest.get("epoch"));
            low = epochs * quantile(secondsPerEpoch, .1) * .9;
            high = epochs * quantile(secondsPerEpoch, .9) * 1.2;
            count = secondsPerEpoch.size() + 1;
            method = "EPOCH_PROGRESS_V1";
          }
        }
      }
    }
    if (count == 0) {
      var stats = profile(server, id);
      Object raw = stats.get("successfulDurationsSeconds");
      var durations = new ArrayList<Double>();
      double elapsed =
          Duration.between(Instant.parse(r.get("process_started_at").toString()), now).getSeconds();
      if (raw instanceof List<?> list)
        for (Object d : list) if (n(d) > elapsed) durations.add(n(d) - elapsed);
      if (durations.size() < 3)
        return Map.of(
            "confidence",
            "LOW_CONFIDENCE",
            "reason",
            "缺少近期稳定进度或至少三次相同配置且时长超过当前运行时长的成功记录",
            "sampleCount",
            durations.size());
      count = durations.size();
      low = quantile(durations, .1);
      high = quantile(durations, .9);
    }
    if (!Double.isFinite(low) || !Double.isFinite(high) || low < 0 || high > 31536000)
      return Map.of("confidence", "UNAVAILABLE", "reason", "预测跨度或速度异常，不能可靠估计");
    Instant earliest = now.plusSeconds((long) low),
        latest = now.plusSeconds((long) Math.ceil(high));
    var previous =
        jdbc.queryForList(
            "SELECT predicted_at FROM ops_workload_prediction WHERE run_id=? ORDER BY predicted_at"
                + " DESC LIMIT 1",
            id);
    if (previous.isEmpty()
        || Duration.between(TrainingJobService.instant(previous.get(0).get("predicted_at")), now)
                .getSeconds()
            >= 60)
      jdbc.update(
          "INSERT INTO ops_workload_prediction(id,run_id,method,predicted_at,earliest_at,latest_at)"
              + " VALUES(?,?,?,?,?,?)",
          UUID.randomUUID().toString(),
          id,
          method,
          utc(now),
          utc(earliest),
          utc(latest));
    return Map.of(
        "method",
        method,
        "remainingSeconds",
        List.of(low, high),
        "earliestAt",
        earliest.toString(),
        "latestAt",
        latest.toString(),
        "sampleCount",
        count,
        "confidence",
        "ESTIMATE",
        "updatedAt",
        now.toString(),
        "limitations",
        "验证、保存检查点和训练阶段变化可能延长耗时；预计结束不代表资源已释放");
  }
}
