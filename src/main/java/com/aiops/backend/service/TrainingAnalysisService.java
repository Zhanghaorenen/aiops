package com.aiops.backend.service;

import com.aiops.backend.persistence.MetricDetails;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 检测同一窗口中的风险信号；输出观测与可能解释，不把相关性当成已确认根因。 */ @Service
public class TrainingAnalysisService {

  private final JdbcTemplate jdbc;
  private final TrainingJobService jobs;

  public TrainingAnalysisService(JdbcTemplate jdbc, TrainingJobService jobs) {
    this.jdbc = jdbc;
    this.jobs = jobs;
  }

  public Map<String, Object> analyze(long server, long id) {
    var job = jobs.get(server, id);
    var policy = jobs.policy(server);
    var samples = jdbc.queryForList(
      "SELECT * FROM metric_host_sample WHERE node_id=? AND collected_at>=? ORDER BY collected_at DESC,id DESC LIMIT 120",
      server,
      TrainingJobService.utc(Instant.now().minusSeconds(1800))
    );
    var risks = new ArrayList<String>();
    var missing = new ArrayList<String>();
    var evidence = new ArrayList<Map<String, Object>>();
    for (var sample : samples) {
      var row = new LinkedHashMap<String, Object>();
      row.put(
        "collectedAt",
        TrainingJobService.instant(sample.get("collected_at")).toString()
      );
      row.put("cpuPercent", sample.get("cpu_percent"));
      row.put(
        "details",
        MetricDetails.decode((String) sample.get("details_json"))
      );
      evidence.add(row);
    }
    for (String key : List.of(
      "memoryPsiAvg10",
      "cpuPsiAvg10",
      "ioPsiAvg10",
      "shmPercent"
    )) {
      var recent = evidence.stream().limit(3).toList();
      double threshold = key.equals("shmPercent")
        ? 90
        : TrainingJobService.number(policy.get("psi_threshold"));
      if (
        recent.size() < 3 ||
        recent
          .stream()
          .anyMatch(s -> !((Map<?, ?>) s.get("details")).containsKey(key))
      ) missing.add(key + " 缺少连续采样");
      else if (
        recent
          .stream()
          .allMatch(
            s ->
              TrainingJobService.number(
                ((Map<?, ?>) s.get("details")).get(key)
              ) >=
              threshold
          )
      ) risks.add(key + " 最近三个采样点持续偏高，需结合任务阶段检查");
    }
    var latest = samples.isEmpty()
      ? Map.<String, Object>of()
      : MetricDetails.decode((String) samples.get(0).get("details_json"));
    if (
      latest.get("memoryAvailableBytes") instanceof Number available &&
      !samples.isEmpty() &&
      available.doubleValue() <
      TrainingJobService.number(samples.get(0).get("memory_total_bytes")) * .1
    ) risks.add("Available RAM 低于总量 10%");
    if (evidence.size() >= 3) {
      var older = (Map<?, ?>) evidence.get(2).get("details");
      if (
        latest.get("swapUsedBytes") instanceof Number a &&
        older.get("swapUsedBytes") instanceof Number b &&
        a.doubleValue() > b.doubleValue()
      ) risks.add("最近窗口 Swap 用量增加");
      if (
        latest.get("oomKillCount") instanceof Number a &&
        older.get("oomKillCount") instanceof Number b &&
        a.doubleValue() > b.doubleValue()
      ) risks.add("系统 OOM kill 计数增加，不能仅据此确定被终止任务");
    }
    var gpuSamples = jdbc.queryForList(
      "SELECT collected_at,utilization_percent,memory_free_bytes,memory_total_bytes FROM metric_gpu_sample WHERE node_id=? AND gpu_index=? AND collected_at>=? ORDER BY collected_at DESC,id DESC LIMIT 120",
      server,
      job.get("gpu_index"),
      TrainingJobService.utc(Instant.now().minusSeconds(1800))
    );
    if (
      gpuSamples.size() >= 3 &&
      gpuSamples
        .stream()
        .limit(3)
        .allMatch(
          g ->
            g.get("memory_free_bytes") instanceof Number &&
            TrainingJobService.number(g.get("memory_total_bytes")) > 0 &&
            TrainingJobService.number(g.get("memory_free_bytes")) /
            TrainingJobService.number(g.get("memory_total_bytes")) <
            .05
        )
    ) risks.add("GPU 剩余显存连续三个采样点不足 5%");
    if (
      samples.isEmpty() ||
      Duration.between(
        TrainingJobService.instant(samples.get(0).get("collected_at")),
        Instant.now()
      ).getSeconds() >
      90
    ) missing.add("主机采样缺失或已过期，历史数据不能证明当前状态");
    if (evidence.size() >= 3) {
      var recent = evidence.stream().limit(3).toList();
      for (String[] capacity : List.of(
        new String[] { "disk_read_mbps", "disks", "readBytesPerSecond" },
        new String[] { "disk_write_mbps", "disks", "writeBytesPerSecond" },
        new String[] { "network_mbps", "network", "receiveBytesPerSecond" },
        new String[] { "network_mbps", "network", "sendBytesPerSecond" }
      )) {
        if (policy.get(capacity[0]) == null) {
          missing.add(capacity[0] + " 未配置设备能力，无法判断是否占满");
          continue;
        }
        boolean saturated = recent
          .stream()
          .allMatch(e -> {
            Object rows = ((Map<?, ?>) e.get("details")).get(capacity[1]);
            return (
              rows instanceof List<?> list &&
              list
                .stream()
                .anyMatch(
                  v ->
                    v instanceof Map<?, ?> map &&
                    map.get(capacity[2]) instanceof Number &&
                    TrainingJobService.number(map.get(capacity[2])) / 1000000 >=
                    TrainingJobService.number(policy.get(capacity[0])) * .9
                )
            );
          });
        if (saturated) risks.add(
          capacity[2] + " 连续接近管理员配置能力，可能存在吞吐竞争"
        );
      }
    }
    var progress = (List<?>) job.get("progress");
    if (progress.size() >= 2) {
      var newest = MetricDetails.decode(
        (String) ((Map<?, ?>) progress.get(0)).get("payload_json")
      );
      var older = MetricDetails.decode(
        (String) ((Map<?, ?>) progress.get(progress.size() - 1)).get(
          "payload_json"
        )
      );
      if (
        newest.get("stepTime") instanceof Number &&
        TrainingJobService.number(older.get("stepTime")) > 0 &&
        TrainingJobService.number(newest.get("stepTime")) >
        TrainingJobService.number(older.get("stepTime")) * 1.3
      ) risks.add(
        "用户上报的 Step Time 比窗口早期增加超过 30%，需核对训练阶段是否一致"
      );
      if (
        newest.get("samplesPerSecond") instanceof Number &&
        TrainingJobService.number(older.get("samplesPerSecond")) > 0 &&
        TrainingJobService.number(newest.get("samplesPerSecond")) <
        TrainingJobService.number(older.get("samplesPerSecond")) * .7
      ) risks.add("用户上报的吞吐下降超过 30%，需结合实际资源证据复查");
    }
    var processes = jdbc.queryForList(
      "SELECT * FROM metric_gpu_process_sample WHERE node_id=? AND gpu_index=? AND pid=? ORDER BY collected_at DESC,id DESC LIMIT 120",
      server,
      job.get("gpu_index"),
      job.get("pid")
    );
    var actual = new ArrayList<Map<String, Object>>();
    for (var p : processes) {
      var d = MetricDetails.decode((String) p.get("details_json"));
      if (
        Objects.equals(d.get("startedAt"), job.get("process_started_at")) &&
        job.get("pid") != null
      ) {
        p.remove("details_json");
        p.put("collected_at",TrainingJobService.instant(p.get("collected_at")).toString());
        p.put("details", d);
        actual.add(p);
      }
    }
    if (actual.isEmpty()) missing.add(
      "未找到所关联进程身份的有效 GPU 采样；不能据此判定退出原因或退出码"
    );
    var peers = jobs
      .list(server)
      .stream()
      .filter(
        p ->
          !p.get("id").equals(job.get("id")) &&
          Set.of("RUNNING", "STARTING", "RESERVED").contains(p.get("state"))
      )
      .toList();
    var report = new LinkedHashMap<String, Object>();
    report.put("observations", risks);
    report.put("hostEvidence", evidence);
    report.put("processHistory", actual);
    report.put("otherJobs", peers);
    report.put("progress", job.get("progress"));
    report.put("progressSource", "USER_REPORTED");
    for(var g:gpuSamples)g.put("collected_at",TrainingJobService.instant(g.get("collected_at")).toString());
    report.put("gpuEvidence", gpuSamples);
    report.put("gpuUtilizationAttribution", "GPU 整体指标，不归因于单个进程");
    report.put("missing", missing);
    var sharedDatasets = new ArrayList<Map<String, Object>>();
    Object dataset = ((Map<?, ?>) job.get("request")).get("dataset");
    if (dataset instanceof String name && !name.isBlank()) {
      for (var peer : jdbc.queryForList(
        "SELECT j.id,j.request_json FROM ops_training_job j JOIN ops_gpu_usage u ON u.id=j.id WHERE u.server_id=? AND j.id<>? AND j.state IN ('RUNNING','STARTING','RESERVED')",
        server,
        id
      )) {
        if (
          name.equals(
            MetricDetails.decode((String) peer.get("request_json")).get(
              "dataset"
            )
          )
        ) sharedDatasets.add(Map.of("jobId", peer.get("id"), "dataset", name));
      }
    }
    report.put("sharedDatasets", sharedDatasets);
    report.put(
      "suggestions",
      List.of(
        "核对训练阶段、批大小及用户上报进度的可比性",
        "检查 DataLoader 并发、数据集所在磁盘与共享内存",
        "根据持续 PSI 和吞吐证据降低并发或错开上传时间",
        "失败原因需结合退出码和授权人员提供的任务日志确认"
      )
    );
    report.put("generatedAt", Instant.now().toString());
    report.put(
      "conclusion",
      "规则提供候选瓶颈；指标同时变化不能证明因果，需结合 Step Time、数据集与任务日志复查"
    );
    return report;
  }
}
