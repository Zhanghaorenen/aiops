package com.aiops.backend.service;

import com.aiops.backend.persistence.MetricDetails;
import com.aiops.backend.security.ClusterPermission;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 多维资源承诺复用 GPU 预约表；所有准入决策都在同一服务器行锁下执行。 */ @Service
public class TrainingJobService {

  private final JdbcTemplate jdbc;
  private final ClusterPermissionService access;
  private final ObjectMapper json = new ObjectMapper();

  public TrainingJobService(
    JdbcTemplate jdbc,
    ClusterPermissionService access
  ) {
    this.jdbc = jdbc;
    this.access = access;
  }

  public record Request(
    @NotBlank @Size(max = 160) String taskName,
    @Size(max = 1000) String description,
    @Min(0) int gpuIndex,
    @Pattern(regexp = "SHARED|EXCLUSIVE") @NotNull String mode,
    @NotNull Instant startsAt,
    @NotNull Instant expectedEndAt,
    @Positive double expectedVramMb,
    @Positive double expectedRamMb,
    @Positive Double expectedCpuCores,
    @Positive Double expectedDiskReadMbps,
    @Positive Double expectedDiskWriteMbps,
    @Positive Double expectedNetworkMbps,
    @Size(max = 1000) String dataset,
    @Size(max = 2000) String runConfiguration,
    @Size(max = 80) String taskType,
    boolean queueIfUnavailable
  ) {}

  public record Policy(
    @PositiveOrZero double ramReserveMb,
    @PositiveOrZero double vramReserveMb,
    @Positive @Max(100) double psiThreshold,
    @Positive Double diskReadMbps,
    @Positive Double diskWriteMbps,
    @Positive Double networkMbps
  ) {}

  public record Progress(
    @Min(0) Long epoch,
    @Min(0) Long step,
    @Positive Double stepTime,
    @Positive Double samplesPerSecond,
    Double loss,
    @Size(max = 160) String phase
  ) {}

  static LocalDateTime utc(Instant at) {
    return LocalDateTime.ofInstant(
      at.truncatedTo(java.time.temporal.ChronoUnit.MICROS),
      ZoneOffset.UTC
    );
  }

  static double number(Object value) {
    return value instanceof Number n ? n.doubleValue() : 0;
  }

  static Instant instant(Object v) {
    return v instanceof java.sql.Timestamp t
      ? t.toLocalDateTime().toInstant(ZoneOffset.UTC)
      : ((LocalDateTime) v).toInstant(ZoneOffset.UTC);
  }

  private ResponseStatusException error(int code, String text) {
    return new ResponseStatusException(HttpStatus.valueOf(code), text);
  }

  private void permission(long server, boolean write) {
    var n = access.requireNode(server, ClusterPermission.GPU_VIEW);
    if (
      write &&
      "VIEWER".equals(
        access.require(n.getClusterId(), ClusterPermission.GPU_VIEW).getRole()
      )
    ) throw error(403, "只读成员不能管理训练任务");
  }

  private void lock(long server) {
    jdbc.queryForMap("SELECT id FROM ops_node WHERE id=? FOR UPDATE", server);
  }

  public List<Map<String, Object>> list(long server) {
    permission(server, false);
    return jdbc.queryForList(
      "SELECT u.*,j.state,j.mode,j.pid,j.process_started_at,j.linux_username FROM ops_gpu_usage u JOIN ops_training_job j ON j.id=u.id WHERE server_id=? ORDER BY u.id DESC LIMIT 500",
      server
    );
  }

  public Map<String, Object> get(long server, long id) {
    permission(server, false);
    var rows = jdbc.queryForList(
      "SELECT u.*,owner.username,j.mode,j.state,j.request_json,j.admission_json,j.pid,j.process_started_at,j.linux_username FROM ops_gpu_usage u JOIN sys_user owner ON owner.id=u.user_id JOIN ops_training_job j ON j.id=u.id WHERE u.server_id=? AND u.id=?",
      server,
      id
    );
    if (rows.isEmpty()) throw error(404, "训练任务不存在");
    var row = rows.get(0);
    row.put(
      "request",
      json.readValue((String) row.remove("request_json"), Map.class)
    );
    Object report = row.remove("admission_json");
    row.put(
      "admission",
      report == null ? Map.of() : json.readValue(report.toString(), Map.class)
    );
    row.put(
      "history",
      jdbc.queryForList(
        "SELECT * FROM ops_training_event WHERE job_id=? ORDER BY id DESC LIMIT 200",
        id
      )
    );
    row.put(
      "progress",
      jdbc.queryForList(
        "SELECT * FROM ops_training_progress WHERE job_id=? ORDER BY id DESC LIMIT 500",
        id
      )
    );
    row.put("executionMode", "MANUAL_PID");
    return row;
  }

  private Map<String, Object> own(long server, long id) {
    permission(server, true);
    var job = get(server, id);
    if (
      number(job.get("user_id")) != access.currentUser().getId()
    ) access.require(
      access.requireNode(server, ClusterPermission.GPU_VIEW).getClusterId(),
      ClusterPermission.AGENT_MANAGE
    );
    return job;
  }

  public Map<String, Object> policy(long server) {
    permission(server, false);
    return jdbc
      .queryForList(
        "SELECT * FROM ops_training_policy WHERE server_id=?",
        server
      )
      .stream()
      .findFirst()
      .orElse(
        new LinkedHashMap<>(
          Map.of(
            "ram_reserve_mb",
            2048,
            "vram_reserve_mb",
            512,
            "psi_threshold",
            10
          )
        )
      );
  }

  @Transactional
  public void policy(long server, Policy p) {
    access.require(
      access.requireNode(server, ClusterPermission.GPU_VIEW).getClusterId(),
      ClusterPermission.AGENT_MANAGE
    );
    lock(server);
    jdbc.update("DELETE FROM ops_training_policy WHERE server_id=?", server);
    jdbc.update(
      "INSERT INTO ops_training_policy(server_id,ram_reserve_mb,vram_reserve_mb,psi_threshold,disk_read_mbps,disk_write_mbps,network_mbps) VALUES(?,?,?,?,?,?,?)",
      server,
      p.ramReserveMb(),
      p.vramReserveMb(),
      p.psiThreshold(),
      p.diskReadMbps(),
      p.diskWriteMbps(),
      p.networkMbps()
    );
  }

  @Transactional
  public void identity(long server, long user, String username) {
    var node = access.requireNode(server, ClusterPermission.GPU_VIEW);
    access.require(node.getClusterId(), ClusterPermission.MEMBER_MANAGE);
    if (
      username == null || !username.matches("[a-zA-Z_][a-zA-Z0-9_.-]{0,63}")
    ) throw error(400, "Linux 用户名格式无效");
    if (
      jdbc.queryForObject(
        "SELECT COUNT(*) FROM ops_cluster_member WHERE cluster_id=? AND user_id=? AND status='ACTIVE'",
        Long.class,
        node.getClusterId(),
        user
      ) ==
      0
    ) throw error(400, "该用户不是活动成员");
    lock(server);
    jdbc.update(
      "DELETE FROM ops_training_linux_identity WHERE server_id=? AND user_id=?",
      server,
      user
    );
    jdbc.update(
      "INSERT INTO ops_training_linux_identity VALUES(?,?,?)",
      server,
      user,
      username
    );
  }

  private void event(long id, String action, String detail) {
    jdbc.update(
      "INSERT INTO ops_training_event(job_id,actor_id,event_type,detail,created_at) VALUES(?,?,?,?,?)",
      id,
      access.currentUser().getId(),
      action,
      detail,
      utc(Instant.now())
    );
  }

  private void state(long id, String state, String detail) {
    String previous = jdbc.queryForObject(
      "SELECT state FROM ops_training_job WHERE id=?",
      String.class,
      id
    );
    jdbc.update("UPDATE ops_training_job SET state=? WHERE id=?", state, id);
    String legacy = switch (state) {
      case "RESERVED", "STARTING" -> "PLANNED";
      case "RUNNING" -> "RUNNING";
      case "SUCCEEDED", "FAILED", "EXITED_UNKNOWN" -> "COMPLETED";
      default -> state;
    };
    jdbc.update(
      "UPDATE ops_gpu_usage SET status=?,updated_at=? WHERE id=?",
      legacy,
      utc(Instant.now()),
      id
    );
    if (!state.equals(previous)) event(id, state, detail);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Map<String, Object> save(long server, Long id, Request r) {
    permission(server, true);
    lock(server);
    if (
      r.startsAt().isBefore(Instant.now()) ||
      !r.expectedEndAt().isAfter(r.startsAt()) ||
      Duration.between(r.startsAt(), r.expectedEndAt()).compareTo(
        Duration.ofDays(7)
      ) >
      0
    ) throw error(400, "任务应从未来开始，时长大于零且不超过七天");
    if (id != null) {
      var old = own(server, id);
      if (
        !Set.of("PENDING", "QUEUED", "RESERVED", "REJECTED").contains(
          old.get("state")
        )
      ) throw error(409, "只能编辑未启动任务");
    }
    if (id == null) {
      jdbc.update(
        "INSERT INTO ops_gpu_usage(server_id,gpu_index,user_id,task_name,purpose,starts_at,expected_end_at,status,created_at,updated_at,reservation_kind) VALUES(?,?,?,?,?,?,?,'PENDING',?,?,'TRAINING')",
        server,
        r.gpuIndex(),
        access.currentUser().getId(),
        r.taskName(),
        r.description(),
        utc(r.startsAt()),
        utc(r.expectedEndAt()),
        utc(Instant.now()),
        utc(Instant.now())
      );
      id = jdbc.queryForObject(
        "SELECT MAX(id) FROM ops_gpu_usage WHERE server_id=?",
        Long.class,
        server
      );
      jdbc.update(
        "INSERT INTO ops_training_job(id,mode,state,request_json) VALUES(?,?,'PENDING',?)",
        id,
        r.mode(),
        json.writeValueAsString(r)
      );
      event(id, "CREATED", "手动 PID 关联模式");
    } else {
      jdbc.update(
        "UPDATE ops_gpu_usage SET gpu_index=?,task_name=?,purpose=?,starts_at=?,expected_end_at=? WHERE id=?",
        r.gpuIndex(),
        r.taskName(),
        r.description(),
        utc(r.startsAt()),
        utc(r.expectedEndAt()),
        id
      );
      jdbc.update(
        "UPDATE ops_training_job SET mode=?,request_json=? WHERE id=?",
        r.mode(),
        json.writeValueAsString(r),
        id
      );
    }
    evaluate(server, id, r, false);
    return get(server, id);
  }

  private Map<String, Object> evaluate(
    long server,
    long id,
    Request r,
    boolean starting
  ) {
    var reasons = new ArrayList<String>();
    var warnings = new ArrayList<String>();
    var evidence = new LinkedHashMap<String, Object>();
    var p = policy(server);
    var hosts = jdbc.queryForList(
      "SELECT * FROM metric_host_sample WHERE node_id=? ORDER BY collected_at DESC,id DESC LIMIT 1",
      server
    );
    var gpu = jdbc.queryForList(
      "SELECT * FROM metric_gpu_sample WHERE node_id=? AND gpu_index=? AND snapshot_id=(SELECT id FROM metric_gpu_snapshot WHERE node_id=? ORDER BY collected_at DESC,id DESC LIMIT 1)",
      server,
      r.gpuIndex(),
      server
    );
    double ram = 0,
      vram = 0,
      cores = 0;
    var node = jdbc.queryForMap("SELECT * FROM ops_node WHERE id=?", server);
    cores = number(node.get("cpu_cores"));
    if (hosts.isEmpty() || gpu.isEmpty()) reasons.add(
      "缺少目标主机或 GPU 实际采样"
    );
    else {
      var h = hosts.get(0);
      var g = gpu.get(0);
      var d = MetricDetails.decode((String) h.get("details_json"));
      if (
        !fresh(h.get("collected_at")) || !fresh(g.get("collected_at"))
      ) reasons.add("采样已过期，不能据此承诺资源");
      // 近期准入基于 Available；远期只作容量承诺，启动时再次按实际可用量检查。
      boolean live =
        starting || !r.startsAt().isAfter(Instant.now().plusSeconds(90));
      ram = live
        ? number(d.get("memoryAvailableBytes")) / 1048576
        : number(h.get("memory_total_bytes")) / 1048576;
      vram =
        number(g.get(live ? "memory_free_bytes" : "memory_total_bytes")) /
        1048576;
      if (live && !d.containsKey("memoryAvailableBytes")) reasons.add(
        "Agent 未提供 Available RAM"
      );
      if (
        live &&
        "EXCLUSIVE".equals(r.mode()) &&
        jdbc.queryForObject(
          "SELECT COUNT(*) FROM metric_gpu_process_sample WHERE snapshot_id=? AND gpu_index=?",
          Long.class,
          g.get("snapshot_id"),
          r.gpuIndex()
        ) >
        0
      ) reasons.add("GPU 上已有实际计算进程，不能确认独占可用");
      evidence.put("collectedAt", instant(h.get("collected_at")).toString());
      evidence.put("hostDetails", d);
      evidence.put(
        "currentAvailableRamMb",
        d.get("memoryAvailableBytes") instanceof Number
          ? number(d.get("memoryAvailableBytes")) / 1048576
          : null
      );
      evidence.put(
        "currentFreeVramMb",
        g.get("memory_free_bytes") instanceof Number
          ? number(g.get("memory_free_bytes")) / 1048576
          : null
      );
      for (String pressure : List.of(
        "memoryPsiAvg10",
        "cpuPsiAvg10",
        "ioPsiAvg10"
      )) {
        if (!d.containsKey(pressure)) warnings.add(
          pressure + " 未上报，无法判断压力"
        );
        else if (number(d.get(pressure)) >= number(p.get("psi_threshold"))) {
          if (
            live &&
            (pressure.equals("memoryPsiAvg10") ||
              (pressure.equals("ioPsiAvg10") &&
                (r.expectedDiskReadMbps() != null ||
                  r.expectedDiskWriteMbps() != null)))
          ) reasons.add(pressure + " 已超过管理员压力阈值");
          else warnings.add(pressure + " 当前偏高，启动时应复查");
        }
      }
      evidence.put("gpu", g);
      evidence.put(
        "basis",
        live ? "LIVE_AVAILABLE" : "FUTURE_CAPACITY_RECHECK_AT_START"
      );
      if (number(d.get("load1")) > cores && cores > 0) warnings.add(
        "当前 CPU Load 超过逻辑核心数"
      );
    }
    var overlaps = jdbc.queryForList(
      "SELECT u.*,j.state AS job_state,j.mode,j.request_json FROM ops_gpu_usage u LEFT JOIN ops_training_job j ON j.id=u.id WHERE server_id=? AND u.id<>? AND u.status IN ('PLANNED','RUNNING') AND starts_at<? AND expected_end_at>? FOR UPDATE",
      server,
      id,
      utc(r.expectedEndAt()),
      utc(r.startsAt())
    );
    double pendingRam = 0,
      pendingVram = 0,
      pendingCpu = 0;
    boolean live =
      starting || !r.startsAt().isAfter(Instant.now().plusSeconds(90));
    for (var other : overlaps) {
      boolean same = number(other.get("gpu_index")) == r.gpuIndex();
      if (
        same &&
        (other.get("request_json") == null ||
          "EXCLUSIVE".equals(r.mode()) ||
          "EXCLUSIVE".equals(other.get("mode")))
      ) reasons.add("GPU 与任务/旧预约 " + other.get("id") + " 的独占区间冲突");
      if (
        other.get("request_json") != null &&
        !(live && "RUNNING".equals(other.get("job_state")))
      ) {
        var q = json.readValue(
          other.get("request_json").toString(),
          Request.class
        );
        pendingRam += q.expectedRamMb();
        pendingCpu += q.expectedCpuCores() == null ? 0 : q.expectedCpuCores();
        if (same) pendingVram += q.expectedVramMb();
      }
    }
    if (
      r.expectedRamMb() + pendingRam + number(p.get("ram_reserve_mb")) > ram
    ) reasons.add("整机 RAM 不足：申请、未启动承诺和安全预留超过可用容量");
    if (
      r.expectedVramMb() + pendingVram + number(p.get("vram_reserve_mb")) > vram
    ) reasons.add("目标 GPU 显存不足");
    if (
      r.expectedCpuCores() != null &&
      r.expectedCpuCores() + pendingCpu >
      (live && !hosts.isEmpty()
        ? cores * (1 - number(hosts.get(0).get("cpu_percent")) / 100)
        : cores)
    ) reasons.add("CPU 核心承诺超过整机容量");
    for (String[] demand : new String[][] {
      { "磁盘读", "disk_read_mbps" },
      { "磁盘写", "disk_write_mbps" },
      { "网络", "network_mbps" },
    }) {
      Double requested = switch (demand[1]) {
        case "disk_read_mbps" -> r.expectedDiskReadMbps();
        case "disk_write_mbps" -> r.expectedDiskWriteMbps();
        default -> r.expectedNetworkMbps();
      };
      if (requested != null) {
        if (p.get(demand[1]) == null) warnings.add(
          demand[0] + "容量未配置，无法保证吞吐"
        );
        else {
          double committed = 0;
          for (var other : overlaps)
            if (
              other.get("request_json") != null &&
              !(live && "RUNNING".equals(other.get("job_state")))
            ) {
              var q = json.readValue(
                other.get("request_json").toString(),
                Request.class
              );
              Double v = switch (demand[1]) {
                case "disk_read_mbps" -> q.expectedDiskReadMbps();
                case "disk_write_mbps" -> q.expectedDiskWriteMbps();
                default -> q.expectedNetworkMbps();
              };
              committed += v == null ? 0 : v;
            }
          double actual = 0;
          if (live && evidence.get("hostDetails") instanceof Map<?, ?> host) {
            String listKey = demand[1].equals("network_mbps")
              ? "network"
              : "disks";
            Object rows = host.get(listKey);
            boolean measured = false;
            var devices = new HashMap<String, Double>();
            if (rows instanceof List<?> list) for (Object row : list)
              if (row instanceof Map<?, ?> values) {
                String field = demand[1].equals("disk_read_mbps")
                  ? "readBytesPerSecond"
                  : demand[1].equals("disk_write_mbps")
                    ? "writeBytesPerSecond"
                    : "receiveBytesPerSecond";
                if (values.get(field) instanceof Number) {
                  measured = true;
                  double rate = number(values.get(field)) / 1000000;
                  if (listKey.equals("network")) actual = Math.max(
                    actual,
                    Math.max(
                      rate,
                      number(values.get("sendBytesPerSecond")) / 1000000
                    )
                  );
                  else devices.merge(
                    String.valueOf(values.get("device")),
                    rate,
                    Math::max
                  );
                }
              }
            if (!listKey.equals("network")) actual = devices
              .values()
              .stream()
              .mapToDouble(Double::doubleValue)
              .sum();
            if (!measured) warnings.add(
              demand[0] + "实际吞吐缺测，仅检查逻辑承诺容量"
            );
            evidence.put(demand[1] + "_observed", measured ? actual : null);
          }
          if (
            requested + committed + actual > number(p.get(demand[1]))
          ) reasons.add(demand[0] + "带宽承诺超过管理员配置容量");
        }
      }
    }
    var report = new LinkedHashMap<String, Object>();
    report.put("allowed", reasons.isEmpty());
    report.put("reasons", reasons);
    report.put("warnings", warnings);
    report.put("requested", r);
    report.put("gpuUsers",jdbc.queryForList("SELECT pid,username,gpu_memory_usage_bytes,collected_at FROM metric_gpu_process_sample WHERE snapshot_id=(SELECT id FROM metric_gpu_snapshot WHERE node_id=? ORDER BY collected_at DESC,id DESC LIMIT 1) AND gpu_index=? ORDER BY gpu_memory_usage_bytes DESC LIMIT 64",server,r.gpuIndex()));
    report.put("availableRamMb", ram);
    report.put("currentAvailableRamMb", evidence.get("currentAvailableRamMb"));
    report.put("currentFreeVramMb", evidence.get("currentFreeVramMb"));
    report.put("availableVramMb", vram);
    report.put("pendingRamMb", pendingRam);
    report.put("pendingVramMb", pendingVram);
    report.put("safety", p);
    report.put("evidence", evidence);
    report.put(
      "competingReservations",
      overlaps
        .stream()
        .map(o ->
          Map.of(
            "id",
            o.get("id"),
            "userId",
            o.get("user_id"),
            "taskName",
            o.get("task_name")
          )
        )
        .toList()
    );
    report.put(
      "suggestion",
      "减少资源峰值、错开时间，或排队后重新检查；共享预约不提供硬件隔离"
    );
    jdbc.update(
      "UPDATE ops_training_job SET admission_json=? WHERE id=?",
      json.writeValueAsString(report),
      id
    );
    if (!starting) state(
      id,
      reasons.isEmpty()
        ? "RESERVED"
        : r.queueIfUnavailable()
          ? "QUEUED"
          : "REJECTED",
      String.join("；", reasons)
    );
    return report;
  }

  private boolean fresh(Object at) {
    var age = Duration.between(instant(at), Instant.now()).getSeconds();
    return age >= -60 && age <= 90;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Map<String, Object> recheck(long server, long id) {
    lockAfterPermission(server);
    var job = own(server, id);
    if (
      !Set.of("QUEUED", "REJECTED", "RESERVED", "PENDING").contains(
        job.get("state")
      )
    ) throw error(409, "当前状态不能重新准入");
    if (!Instant.now().isBefore(instant(job.get("expected_end_at")))) {
      state(id, "REJECTED", "预约窗口已结束");
      return get(server, id);
    }
    evaluate(
      server,
      id,
      json.convertValue(job.get("request"), Request.class),
      false
    );
    return get(server, id);
  }

  private void lockAfterPermission(long server) {
    permission(server, true);
    lock(server);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Map<String, Object> transition(long server, long id, String target) {
    lockAfterPermission(server);
    var job = own(server, id);
    String old = job.get("state").toString();
    boolean valid = switch (target) {
      case "CANCELLED" -> Set.of(
        "PENDING",
        "QUEUED",
        "RESERVED",
        "STARTING",
        "RUNNING",
        "REJECTED"
      ).contains(old);
      case "STARTING" -> "RESERVED".equals(old);
      case "SUCCEEDED", "FAILED" -> Set.of(
        "RUNNING",
        "EXITED_UNKNOWN"
      ).contains(old);
      default -> false;
    };
    if (!valid) throw error(409, "不允许的任务状态转换");
    if ("STARTING".equals(target)) {
      if (
        Instant.now().isBefore(instant(job.get("starts_at"))) ||
        !Instant.now().isBefore(instant(job.get("expected_end_at")))
      ) throw error(409, "当前时间不在预约区间内");
      var report = evaluate(
        server,
        id,
        json.convertValue(job.get("request"), Request.class),
        true
      );
      if (!Boolean.TRUE.equals(report.get("allowed"))) return get(server, id);
    }
    state(id, target, "用户操作；手动模式不执行进程启动或终止");
    return get(server, id);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Map<String, Object> link(
    long server,
    long id,
    long pid,
    String startedAt
  ) {
    lockAfterPermission(server);
    var job = own(server, id);
    if (!"STARTING".equals(job.get("state"))) throw error(
      409,
      "先发起启动检查，再关联进程"
    );
    var processes = jdbc.queryForList(
      "SELECT * FROM metric_gpu_process_sample WHERE node_id=? AND gpu_index=? AND pid=? AND snapshot_id=(SELECT id FROM metric_gpu_snapshot WHERE node_id=? ORDER BY collected_at DESC,id DESC LIMIT 1)",
      server,
      job.get("gpu_index"),
      pid,
      server
    );
    if (processes.isEmpty()) throw error(409, "最新 GPU 快照未找到该进程");
    var process = processes.get(0);
    var d = MetricDetails.decode((String) process.get("details_json"));
    if (
      !fresh(process.get("collected_at")) ||
      !Objects.equals(d.get("startedAt"), startedAt)
    ) throw error(409, "进程采样过期或 PID 身份已变化");
    var identities = jdbc.queryForList(
      "SELECT linux_username FROM ops_training_linux_identity WHERE server_id=? AND user_id=?",
      String.class,
      server,
      job.get("user_id")
    );
    if (
      identities.isEmpty() || !identities.get(0).equals(process.get("username"))
    ) throw error(403, "请管理员先绑定任务所有者的 Linux 用户身份");
    if (
      jdbc.queryForObject(
        "SELECT COUNT(*) FROM ops_training_job j JOIN ops_gpu_usage u ON u.id=j.id WHERE u.server_id=? AND j.pid=? AND j.process_started_at=? AND j.state='RUNNING'",
        Long.class,
        server,
        pid,
        startedAt
      ) >
      0
    ) throw error(409, "该进程已关联其他任务");
    jdbc.update(
      "UPDATE ops_training_job SET pid=?,process_started_at=?,linux_username=? WHERE id=?",
      pid,
      startedAt,
      process.get("username"),
      id
    );
    state(id, "RUNNING", "已匹配 PID、启动时间、Linux 用户和 GPU");
    return get(server, id);
  }

  @Transactional
  public void progress(long server, long id, Progress p) {
    var job = own(server, id);
    if (!"RUNNING".equals(job.get("state"))) throw error(
      409,
      "任务尚未关联运行进程"
    );
    if (p.loss() != null && !Double.isFinite(p.loss())) throw error(
      400,
      "loss 必须是有限数值"
    );
    jdbc.update(
      "INSERT INTO ops_training_progress(job_id,payload_json,collected_at) VALUES(?,?,?)",
      id,
      json.writeValueAsString(p),
      utc(Instant.now())
    );
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void observeExit(long server, long id) {
    lockAfterPermission(server);
    var job = own(server, id);
    if (!"RUNNING".equals(job.get("state"))) return;
    jdbc.update(
      "UPDATE ops_gpu_usage SET updated_at=? WHERE id=?",
      utc(Instant.now()),
      id
    );
    var samples = jdbc.queryForList(
      "SELECT collected_at,details_json FROM metric_host_sample WHERE node_id=? ORDER BY collected_at DESC,id DESC LIMIT 1",
      server
    );
    if (samples.isEmpty() || !fresh(samples.get(0).get("collected_at"))) return;
    var details = MetricDetails.decode(
      (String) samples.get(0).get("details_json")
    );
    if (
      details.get("processExits") instanceof List<?> exits
    ) for (var value : exits)
      if (
        value instanceof Map<?, ?> exit &&
        number(exit.get("pid")) == number(job.get("pid")) &&
        Objects.equals(exit.get("startedAt"), job.get("process_started_at")) &&
        "EXITED_UNKNOWN".equals(exit.get("status"))
      ) {
        state(
          id,
          "EXITED_UNKNOWN",
          "Agent 确认进程身份已退出；退出码未知，等待用户或 SDK 确认结果"
        );
        return;
      }
  }
}
