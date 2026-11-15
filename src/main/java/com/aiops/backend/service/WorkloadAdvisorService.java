package com.aiops.backend.service;

import com.aiops.backend.persistence.MetricDetails;
import com.aiops.backend.security.ClusterPermission;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 直接分析已发现进程，并把可追溯的历史统计转换为资源表单建议。 */
@Service
public class WorkloadAdvisorService {
  private final JdbcTemplate jdbc;
  private final ClusterPermissionService access;
  private final WorkloadService workloads;
  private final TrainingJobService jobs;

  public WorkloadAdvisorService(
      JdbcTemplate jdbc,
      ClusterPermissionService access,
      WorkloadService workloads,
      TrainingJobService jobs) {
    this.jdbc = jdbc;
    this.access = access;
    this.workloads = workloads;
    this.jobs = jobs;
  }

  private double number(Object v) {
    return TrainingJobService.number(v);
  }

  private Map<String, Object> find(long server, String runId, Long snapshotId, Long pid) {
    return workloads.discoverAndList(server, runId == null).stream()
        .filter(
            row ->
                runId != null
                    ? runId.equals(row.get("runId"))
                    : Objects.equals(row.get("snapshotId"), snapshotId)
                        && row.get("pid") instanceof Number p
                        && pid != null
                        && p.longValue() == pid)
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "进程采样已更新，请刷新任务列表"));
  }

  public Map<String, Object> analyze(long server, String runId, Long snapshotId, Long pid) {
    var row = find(server, runId, snapshotId, pid);
    var observations = new ArrayList<String>();
    var missing = new ArrayList<String>();
    var metrics = row.get("resources") instanceof Map<?, ?> values ? values : Map.of();
    boolean fresh =
        Boolean.TRUE.equals(row.get("fresh"))
            && Set.of("RUNNING", "OBSERVED").contains(String.valueOf(row.get("state")));
    if (!fresh) missing.add("记录已经归档、采样暂缺或过期，以下仅是最近观测，不能据此判断当前任务状态");
    if (runId == null)
      missing.add(
          "START_TIME_MISSING".equals(row.get("identityStatus"))
              ? "尚未取得进程启动时间，当前只能分析该快照，不能关联历史任务或预测结束时间"
              : "该进程仅展示当前 GPU 快照，历史运行已归档或未关联，不推断训练状态");
    if ("UID_UNAVAILABLE".equals(row.get("identityStatus")))
      missing.add("采集端未上报 UID；未确认的用户身份不会自动归并");
    var pressure = metrics.get("hostPressure") instanceof Map<?, ?> p ? p : Map.of();
    var policy = jobs.policy(server);
    for (String key : List.of("cpuPsiAvg10", "memoryPsiAvg10", "ioPsiAvg10")) {
      if (!(pressure.get(key) instanceof Number)) missing.add(key + " 暂无采样");
      else if (fresh && number(pressure.get(key)) >= number(policy.get("psi_threshold")))
        observations.add(key + " 超过资源压力阈值；这是主机级信号，不能单独认定由本任务引起");
    }
    var gpu =
        jdbc.queryForList(
            "SELECT"
                + " gpu_index,gpu_uuid,memory_free_bytes,memory_total_bytes,utilization_percent,collected_at"
                + " FROM metric_gpu_sample WHERE node_id=? AND snapshot_id=(SELECT id FROM"
                + " metric_gpu_snapshot WHERE node_id=? ORDER BY collected_at DESC,id DESC LIMIT"
                + " 1)",
            server,
            server);
    var gpuEvidence = new ArrayList<Map<String, Object>>();
    for (var g : gpu) {
      boolean involved =
          row.get("gpuIndices") instanceof List<?> indices
              && indices.stream().anyMatch(i -> number(i) == number(g.get("gpu_index")));
      if (metrics.get("gpuMemoryByUuid") instanceof Map<?, ?> cards
          && cards.containsKey(g.get("gpu_uuid"))) involved = true;
      if (!involved) continue;
      gpuEvidence.add(g);
      if (fresh
          && number(g.get("memory_total_bytes")) > 0
          && g.get("memory_free_bytes") instanceof Number
          && number(g.get("memory_free_bytes")) / number(g.get("memory_total_bytes")) < .05)
        observations.add("GPU " + g.get("gpu_index") + " 剩余显存低于 5%，需留意峰值波动");
    }
    for (String field :
        List.of(
            "cpuPercent",
            "rssEstimateBytes",
            "gpuMemoryBytes",
            "readBytesPerSecond",
            "writeBytesPerSecond"))
      if (!(metrics.get(field) instanceof Number)) missing.add(field + " 缺少有效采样");
    boolean privateDetails = runId != null && Boolean.TRUE.equals(row.get("privateDetails"));
    var result = new LinkedHashMap<String, Object>();
    result.put("serverId", server);
    result.put("runId", runId);
    result.put("displayName", row.get("displayName"));
    result.put("sampledAt", row.get("sampledAt"));
    result.put("fresh", fresh);
    result.put("resources", metrics);
    result.put("observations", observations);
    result.put("missingEvidence", missing);
    result.put("gpuEvidence", gpuEvidence);
    result.put(
        "profile",
        privateDetails
            ? workloads.profile(server, runId)
            : Map.of("confidence", "UNAVAILABLE", "reason", "仅提供共享资源摘要"));
    result.put(
        "prediction",
        privateDetails
            ? workloads.predict(server, runId)
            : runId != null
                ? workloads.completionSummary(server, runId)
                : Map.of("confidence", "UNAVAILABLE", "reason", "缺少稳定进程身份"));
    if (privateDetails) result.put("samples", workloads.detail(server, runId).get("samples"));
    result.put(
        "conclusion",
        !fresh
            ? "采样过期，请等待采集端上报"
            : observations.isEmpty()
                ? "已取得资源观测；现有证据未发现超过已检查阈值的压力信号，不能据此保证任务健康"
                : "发现资源压力信号，建议结合任务阶段检查");
    result.put("metricScope", "CPU 为进程组单核百分比；RAM RSS 为共享页可能重复的估计；GPU 利用率属于整卡，PSI 属于主机");
    return result;
  }

  /** 只列当前用户在目标服务器的记录，进程名相同不代表任务配置相同。 */
  public List<Map<String, Object>> presets(long server) {
    access.requireNode(server, ClusterPermission.GPU_VIEW);
    long user = access.currentUser().getId();
    var runs =
        jdbc.queryForList(
            "SELECT r.*,u.task_name FROM ops_workload_run r LEFT JOIN ops_gpu_usage u ON"
                + " u.id=r.job_id WHERE r.server_id=? AND r.user_id=? ORDER BY r.last_seen DESC"
                + " LIMIT 60",
            server,
            user);
    var seen = new HashSet<String>();
    var result = new ArrayList<Map<String, Object>>();
    for (var run : runs) {
      String key =
          Boolean.TRUE.equals(run.get("confirmed")) && run.get("profile_key") != null
              ? run.get("profile_key").toString()
              : run.get("id").toString();
      if (!seen.add(key)) continue;
      var draft = draft(server, run);
      if (draft.get("suggested") instanceof Map<?, ?> fields
          && (fields.containsKey("expectedVramMb") || fields.containsKey("expectedRamMb")))
        result.add(draft);
    }
    result.sort(
        Comparator.<Map<String, Object>>comparingInt(
                p -> "CONFIRMED_PROFILE".equals(p.get("source")) ? 0 : 1)
            .thenComparing(
                Comparator.comparingDouble((Map<String, Object> p) -> number(p.get("runCount")))
                    .reversed()));
    return result.stream().limit(12).toList();
  }

  public Map<String, Object> suggestion(long server, String id) {
    workloads.detail(server, id); // 复用现有的私人任务授权。
    var rows =
        jdbc.queryForList(
            "SELECT r.*,u.task_name FROM ops_workload_run r LEFT JOIN ops_gpu_usage u ON"
                + " u.id=r.job_id WHERE r.server_id=? AND r.id=?",
            server,
            id);
    return draft(server, rows.get(0));
  }

  private Map<String, Object> draft(long server, Map<String, Object> run) {
    var samples =
        jdbc.queryForList(
            "SELECT metrics_json,sampled_at,snapshot_id FROM ops_workload_sample WHERE run_id=?"
                + " ORDER BY sampled_at DESC LIMIT 200",
            run.get("id"));
    var profileRows =
        jdbc.queryForList(
            "SELECT statistics_json FROM ops_workload_profile WHERE profile_key=? AND user_id=?",
            run.get("profile_key"),
            run.get("user_id"));
    boolean confirmed = Boolean.TRUE.equals(run.get("confirmed")) && !profileRows.isEmpty();
    Map<String, Object> stats =
        confirmed
            ? MetricDetails.decode((String) profileRows.get(0).get("statistics_json"))
            : summarize(samples);
    var suggested = new LinkedHashMap<String, Object>();
    var evidence = new LinkedHashMap<String, Object>();
    var missing = new ArrayList<String>();
    for (String[] field :
        new String[][] {
          {"gpuMemoryBytes", "expectedVramMb"},
          {"rssEstimateBytes", "expectedRamMb"},
          {"cpuPercent", "expectedCpuCores"},
          {"readBytesPerSecond", "expectedDiskReadMbps"},
          {"writeBytesPerSecond", "expectedDiskWriteMbps"}
        }) {
      if (!(stats.get(field[0]) instanceof Map<?, ?> values)
          || !(values.get("p95") instanceof Number)
          || !(values.get("peak") instanceof Number)) {
        missing.add(field[1] + " 暂无历史依据");
        continue;
      }
      double value = Math.max(number(values.get("p95")) * 1.15, number(values.get("peak")));
      if (!Double.isFinite(value) || value <= 0) {
        missing.add(field[1] + " 未观测到有效用量");
        continue;
      }
      double estimate =
          field[0].equals("cpuPercent")
              ? Math.ceil(value / 100 * 2) / 2
              : field[0].endsWith("PerSecond")
                  ? Math.ceil(value / 1000000 * 10) / 10
                  : Math.ceil(value / 1048576 / 256) * 256;
      suggested.put(field[1], estimate);
      evidence.put(
          field[1],
          Map.of(
              "p95",
              values.get("p95"),
              "peak",
              values.get("peak"),
              "samples",
              values.containsKey("samples") ? values.get("samples") : 0));
    }
    var gpuIndices = new ArrayList<Object>();
    String label =
        run.get("task_name") instanceof String title && !title.isBlank()
            ? title
            : "GPU 进程 · PID " + run.get("root_pid");
    if (!samples.isEmpty()) {
      var cards =
          jdbc.queryForList(
              "SELECT gpu_index,process_name FROM metric_gpu_process_sample WHERE snapshot_id=? AND"
                  + " pid=? ORDER BY gpu_index",
              samples.get(0).get("snapshot_id"),
              run.get("root_pid"));
      for (var card : cards) gpuIndices.add(card.get("gpu_index"));
      if (run.get("task_name") == null && !cards.isEmpty())
        label =
            cards.get(0).getOrDefault("process_name", "GPU 进程") + " · PID " + run.get("root_pid");
      if (!gpuIndices.isEmpty()) suggested.put("gpuIndex", gpuIndices.get(0));
    }
    // GPU 编号只是观测来源。多卡任务不能被默默转换为单卡训练计划。
    boolean multiple =
        samples.stream()
            .anyMatch(
                s ->
                    MetricDetails.decode((String) s.get("metrics_json")).get("gpuMemoryByUuid")
                            instanceof Map<?, ?> cards
                        && cards.size() > 1);
    var result = new LinkedHashMap<String, Object>();
    result.put(
        "id", run.get("profile_key") != null && confirmed ? run.get("profile_key") : run.get("id"));
    result.put("sourceRunId", run.get("id"));
    result.put("label", label);
    result.put("source", confirmed ? "CONFIRMED_PROFILE" : "OBSERVED_RUN");
    result.put("confidence", confirmed ? stats.get("confidence") : "LOW_CONFIDENCE");
    result.put("runCount", stats.getOrDefault("runCount", 1));
    result.put("sampleCount", stats.getOrDefault("sampleCount", samples.size()));
    result.put(
        "sampledAt",
        samples.isEmpty()
            ? null
            : TrainingJobService.instant(samples.get(0).get("sampled_at")).toString());
    result.put("suggested", suggested);
    result.put("evidence", evidence);
    result.put("missingEvidence", missing);
    result.put("multipleGpus", multiple);
    result.put("taskType", run.get("task_type"));
    result.put(
        "basis", confirmed ? "相同配置的历史 P95 加 15% 余量与观测峰值取较大值" : "该次运行已观测用量的参考值，尚不能代表相同模型或未来峰值");
    result.put("ramBasis", "RSS 上界估计，可能重复计算共享页");
    return result;
  }

  private Map<String, Object> summarize(List<Map<String, Object>> samples) {
    var fields = new HashMap<String, List<Double>>();
    for (var sample : samples) {
      var metrics = MetricDetails.decode((String) sample.get("metrics_json"));
      for (String key :
          List.of(
              "gpuMemoryBytes",
              "rssEstimateBytes",
              "cpuPercent",
              "readBytesPerSecond",
              "writeBytesPerSecond"))
        if (metrics.get(key) instanceof Number n
            && Double.isFinite(n.doubleValue())
            && n.doubleValue() >= 0)
          fields.computeIfAbsent(key, k -> new ArrayList<>()).add(n.doubleValue());
    }
    var stats = new LinkedHashMap<String, Object>();
    fields.forEach(
        (key, values) ->
            stats.put(
                key,
                Map.of(
                    "p95",
                    WorkloadService.quantile(values, .95),
                    "peak",
                    WorkloadService.quantile(values, 1),
                    "samples",
                    values.size())));
    stats.put("sampleCount", samples.size());
    return stats;
  }
}
