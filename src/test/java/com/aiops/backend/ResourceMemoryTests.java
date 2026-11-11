package com.aiops.backend;

import static org.junit.jupiter.api.Assertions.*;

import com.aiops.backend.service.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@Transactional
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class ResourceMemoryTests extends ClusterTestSupport {
  @Autowired org.springframework.test.web.servlet.MockMvc mvc;
  @Autowired com.aiops.backend.security.JwtService jwt;
  @Autowired ChatSessionService chats;
  @Autowired GpuManagerService gpuQueries;
  @Autowired IncidentService incidents;
  @Autowired AgentAccessService agents;
  @Autowired TrainingJobService jobs;
  @Autowired WorkloadService workloads;
  @Autowired UserMemoryService memories;
  @Autowired ResourceNotificationService reminders;

  private String id() {
    return UUID.randomUUID().toString();
  }

  @Test
  void httpSessionApiUsesVerifiedJwtAndRejectsAnotherOwner() throws Exception {
    var session = chats.create("JWT 会话").get("id").toString();
    String tokenA = "Bearer " + jwt.issue(user(), "fixtureAdmin");
    other();
    long otherId =
        testJdbc.queryForObject("SELECT id FROM sys_user WHERE username='memoryOther'", Long.class);
    String tokenB = "Bearer " + jwt.issue(otherId, "memoryOther");
    SecurityContextHolder.clearContext();
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/ai/sessions/" + session + "/messages")
                .header("Authorization", tokenA))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/ai/sessions/" + session + "/messages")
                .header("Authorization", tokenB))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isNotFound());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                    "/api/ai/sessions/" + session)
                .header("Authorization", tokenB))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isNotFound());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/ai/sessions")
                .header("Authorization", "Bearer invalid"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isUnauthorized());
    testJdbc.update("UPDATE sys_user SET status='DISABLED' WHERE id=?", otherId);
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/ai/sessions")
                .header("Authorization", tokenB))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isUnauthorized());
  }

  private void other() {
    testJdbc.update(
        "INSERT INTO sys_user(username,password_hash,status,created_at,updated_at)"
            + " VALUES('memoryOther','test','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    grant("memoryOther", "OPERATOR");
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "memoryOther", null, AuthorityUtils.createAuthorityList("ROLE_USER")));
  }

  private long user() {
    return testJdbc.queryForObject(
        "SELECT id FROM sys_user WHERE username='fixtureAdmin'", Long.class);
  }

  private AgentAccessService.EnrollmentResult node() {
    String key = "memory-" + id();
    var n =
        agents.enroll(
            new AgentAccessService.EnrollmentRequest(
                issue(agents).token(),
                key,
                key,
                key,
                null,
                null,
                null,
                null,
                8,
                64L * 1024 * 1048576,
                key,
                "test"));
    jobs.identity(n.nodeId(), user(), "linux-a", 1000L);
    return n;
  }

  private void sample(
      AgentAccessService.EnrollmentResult node,
      Instant at,
      String started,
      boolean process,
      boolean exited) {
    Map<String, Object> details =
        new HashMap<>(
            Map.of(
                "memoryAvailableBytes",
                48L * 1024 * 1048576,
                "cpuPsiAvg10",
                0,
                "memoryPsiAvg10",
                0,
                "ioPsiAvg10",
                0));
    if (exited)
      details.put(
          "processExits",
          List.of(
              Map.of(
                  "pid",
                  123,
                  "startedAt",
                  started,
                  "observedAt",
                  at.toString(),
                  "status",
                  "EXITED_UNKNOWN"),
              Map.of(
                  "pid",
                  124,
                  "startedAt",
                  started,
                  "observedAt",
                  at.toString(),
                  "status",
                  "EXITED_UNKNOWN")));
    var p =
        new AgentAccessService.GpuProcessRequest(
            123L,
            "python",
            "linux-a",
            3L * 1024 * 1048576,
            "Python Task",
            0,
            Map.of(
                "workingDirectory",
                "/private/training-data",
                "executablePath",
                "/private/python",
                "linuxUid",
                1000,
                "startedAt",
                started,
                "cpuPercent",
                20,
                "memoryRssBytes",
                1000,
                "memoryUssBytes",
                600,
                "readBytesPerSecond",
                10,
                "writeBytesPerSecond",
                20,
                "processTree",
                List.of(
                    Map.of(
                        "pid",
                        124,
                        "startedAt",
                        started,
                        "linuxUid",
                        1000,
                        "cpuPercent",
                        10,
                        "memoryRssBytes",
                        500,
                        "memoryUssBytes",
                        300,
                        "readBytesPerSecond",
                        5,
                        "writeBytesPerSecond",
                        5))));
    var gpu =
        new AgentAccessService.GpuSampleRequest(
            0,
            "GPU-test",
            "Model-A",
            24L * 1024 * 1048576,
            8L * 1024 * 1048576,
            16L * 1024 * 1048576,
            BigDecimal.TEN,
            BigDecimal.TEN,
            BigDecimal.TEN,
            BigDecimal.TEN,
            null,
            null,
            process ? List.of(p) : List.of());
    agents.recordMetric(
        node.agentId(),
        new AgentAccessService.MetricRequest(
            20d,
            64L * 1024 * 1048576,
            16L * 1024 * 1048576,
            1000L,
            100L,
            at,
            "AVAILABLE",
            List.of(gpu),
            null,
            details));
  }

  @Test
  void sessionsArePrivateIncludingMutationAndDisabledUsers() {
    String session = chats.create("私有").get("id").toString();
    other();
    assertTrue(chats.list().isEmpty());
    assertEquals(
        404,
        assertThrows(
                ResponseStatusException.class, () -> chats.messages(session, Long.MAX_VALUE, 50))
            .getStatusCode()
            .value());
    assertThrows(ResponseStatusException.class, () -> chats.update(session, "越权", "DELETED"));
    assertThrows(ResponseStatusException.class, () -> chats.begin(session, id(), "读取"));
    asFixtureAdmin();
    assertEquals(1, chats.list().size());
    testJdbc.update("UPDATE sys_user SET status='DISABLED' WHERE username='fixtureAdmin'");
    assertEquals(
        401,
        assertThrows(ResponseStatusException.class, () -> chats.list()).getStatusCode().value());
  }

  @Test
  void turnsSerializeReplayAndRecoverExpiredLeaseWithoutStaleCompletion() {
    String session = chats.create("会话").get("id").toString(), request = id();
    String lease = chats.begin(session, request, "问题").get("lease").toString();
    assertEquals(
        409,
        assertThrows(ResponseStatusException.class, () -> chats.begin(session, id(), "并发"))
            .getStatusCode()
            .value());
    testJdbc.update(
        "UPDATE ai_chat_session SET lease_until=? WHERE id=?",
        LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1),
        session);
    String next = chats.begin(session, request, "问题").get("lease").toString();
    assertNotEquals(lease, next);
    assertThrows(
        ResponseStatusException.class,
        () -> chats.finish(session, request, "COMPLETED", "过期", null, lease));
    chats.finish(session, request, "COMPLETED", "回答", "{}", next);
    assertEquals("回答", chats.begin(session, request, "问题").get("replay"));
    assertThrows(ResponseStatusException.class, () -> chats.begin(session, request, "另一个问题"));
    assertEquals(2, chats.messages(session, Long.MAX_VALUE, 50).size());
    String failure = id();
    var pending = chats.begin(session, failure, "取消");
    chats.finish(session, failure, "CANCELLED", null, null, pending.get("lease").toString());
    var context = (List<?>) chats.begin(session, id(), "继续").get("history");
    assertEquals(2, context.size());
  }

  @Test
  void processIdentityIncludesStartTimeAndMissingIsNotExit() {
    var node = node();
    Instant now = Instant.now();
    String started = now.minusSeconds(100).toString();
    sample(node, now, started, true, false);
    var first = workloads.list(node.nodeId(), true).get(0);
    String run = first.get("id").toString();
    var resources = (Map<?, ?>) first.get("resources");
    assertEquals(30d, ((Number) resources.get("cpuPercent")).doubleValue());
    assertEquals(1500d, ((Number) resources.get("rssEstimateBytes")).doubleValue());
    sample(node, now.plusSeconds(1), started, false, false);
    assertEquals("MISSING", workloads.detail(node.nodeId(), run).get("state"));
    var rootOnly =
        new tools.jackson.databind.ObjectMapper()
            .writeValueAsString(
                Map.of(
                    "processExits",
                    List.of(Map.of("pid", 123, "startedAt", started, "status", "EXITED_UNKNOWN"))));
    testJdbc.update(
        "UPDATE metric_host_sample SET details_json=? WHERE node_id=?", rootOnly, node.nodeId());
    workloads.observe(node.nodeId());
    assertEquals(
        "MISSING", workloads.detail(node.nodeId(), run).get("state"), "主进程退出而子进程没有退出证据时不能归档整个任务");

    sample(node, now.plusSeconds(2), started, false, true);
    assertEquals("EXITED_UNKNOWN", workloads.detail(node.nodeId(), run).get("state"));
    sample(node, now.plusSeconds(3), now.toString(), true, false);
    assertEquals(2, workloads.list(node.nodeId(), false).size());
    workloads.confirm(node.nodeId(), run, "TRAINING", "config-a", "env-a", null);
    assertEquals("LOW_CONFIDENCE", workloads.profile(node.nodeId(), run).get("confidence"));
    assertEquals(
        "/private/training-data",
        gpuQueries.getGpuProcesses(node.nodeId()).get(0).get("workingDirectory"));
    other();
    assertNull(gpuQueries.getGpuProcesses(node.nodeId()).get(0).get("workingDirectory"));
    assertNull(gpuQueries.getGpuProcesses(node.nodeId()).get(0).get("executablePath"));
    assertThrows(ResponseStatusException.class, () -> workloads.detail(node.nodeId(), run));
    assertFalse((Boolean) workloads.list(node.nodeId(), false).get(0).get("privateDetails"));
  }

  @Test
  void budgetRejectsStaleSamplesAndNotificationsDeduplicate() {
    var node = node();
    Instant now = Instant.now();
    sample(node, now, now.minusSeconds(1).toString(), false, false);
    var budget = jobs.capacity(node.nodeId(), 0, 10240, 8192);
    assertEquals(true, budget.get("allowed"));
    assertEquals(
        15872d,
        ((Number) ((Map<?, ?>) budget.get("estimatedSafeBudget")).get("vramMb")).doubleValue());
    String sub = reminders.subscribe(node.nodeId(), 0, 10240, 8192, null, 3600);
    reminders.check(sub);
    reminders.check(sub);
    assertEquals(
        1,
        testJdbc.queryForObject(
            "SELECT COUNT(*) FROM ops_user_notification WHERE subscription_id=?",
            Integer.class,
            sub));
    other();
    assertThrows(ResponseStatusException.class, () -> reminders.cancel(sub));
    assertEquals(0, ((List<?>) reminders.notifications()).size());
    asFixtureAdmin();
    testJdbc.update(
        "UPDATE metric_host_sample SET collected_at=? WHERE node_id=?",
        LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10),
        node.nodeId());
    var stale = jobs.capacity(node.nodeId(), 0, 1, 1);
    assertEquals(false, stale.get("allowed"));
    assertNull(((Map<?, ?>) stale.get("estimatedSafeBudget")).get("ramMb"));
  }

  @Test
  void privateMemoriesAndUnverifiedCasesNeverLeak() {
    var node = node();
    assertFalse(memories.automatic());
    memories.remember("我确认的配置");
    String candidate = memories.candidate(node.nodeId(), null, "OOM", "显存不足", "[\"E1\"]", "候选原因");
    assertTrue(memories.cases(node.nodeId(), true).isEmpty());
    other();
    assertEquals(0, ((List<?>) memories.memories()).size());
    assertThrows(ResponseStatusException.class, () -> memories.getCase(node.nodeId(), candidate));
    asFixtureAdmin();
    memories.review(node.nodeId(), candidate, "VERIFIED", "降低 batch 后观察三次训练成功", true);
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "memoryOther", null, AuthorityUtils.createAuthorityList("ROLE_USER")));
    assertEquals(1, memories.cases(node.nodeId(), true).size());
    assertThrows(
        ResponseStatusException.class,
        () -> memories.review(node.nodeId(), candidate, "REJECTED", "", false));
    asFixtureAdmin();
    memories.review(node.nodeId(), candidate, "SUPERSEDED", "结果不适用", false);
    assertTrue(memories.cases(node.nodeId(), true).isEmpty());
  }

  @Test
  void predictionRefusesInsufficientHistoryAndQuantilesAreInterpolated() {
    var node = node();
    Instant now = Instant.now();
    sample(node, now, now.minusSeconds(10).toString(), true, false);
    String run = workloads.list(node.nodeId(), true).get(0).get("id").toString();
    assertEquals("LOW_CONFIDENCE", workloads.predict(node.nodeId(), run).get("confidence"));
    assertEquals(95.5, WorkloadService.quantile(List.of(10d, 100d), .95), .0001);
  }

  @Test
  void progressPredictionKeepsFeedbackAndCompletedRunProfile() {
    var node = node();
    Instant now = Instant.now();
    String started = now.minusSeconds(60).toString();
    sample(node, now, started, true, false);
    var request =
        new TrainingJobService.Request(
            "progress-test",
            null,
            0,
            "SHARED",
            now.plusSeconds(30),
            now.plusSeconds(3600),
            4096,
            8192,
            1d,
            null,
            null,
            null,
            "dataset",
            "batch=16",
            "TRAINING",
            false);
    long job = ((Number) jobs.save(node.nodeId(), null, request).get("id")).longValue();
    testJdbc.update(
        "UPDATE ops_gpu_usage SET starts_at=? WHERE id=?",
        LocalDateTime.now(ZoneOffset.UTC).minusSeconds(30),
        job);
    jobs.transition(node.nodeId(), job, "STARTING");
    jobs.link(node.nodeId(), job, 123, started);
    sample(node, now.plusSeconds(1), started, true, false);
    for (long step : List.of(10L, 20L, 30L))
      jobs.progress(
          node.nodeId(),
          job,
          new TrainingJobService.Progress(1L, step, .5, 64d, null, "training", 100L, 5L));
    String run = workloads.list(node.nodeId(), true).get(0).get("id").toString();
    var eta = workloads.predict(node.nodeId(), run);
    assertEquals("PROGRESS_V1", eta.get("method"));
    assertEquals(3, eta.get("sampleCount"));
    assertThrows(
        ResponseStatusException.class,
        () ->
            jobs.progress(
                node.nodeId(),
                job,
                new TrainingJobService.Progress(
                    1L, 35L, Double.POSITIVE_INFINITY, 64d, null, "training", 100L, 5L)));
    other();
    assertThrows(ResponseStatusException.class, () -> workloads.predict(node.nodeId(), run));
    assertEquals("ESTIMATE", workloads.completionSummary(node.nodeId(), run).get("confidence"));
    String waiting = reminders.subscribe(node.nodeId(), 0, 20000, 8192, run, 60);
    reminders.check(waiting);
    assertEquals(
        "EXPECTED_AVAILABLE",
        testJdbc.queryForObject(
            "SELECT kind FROM ops_user_notification WHERE subscription_id=?",
            String.class,
            waiting));
    asFixtureAdmin();
    testJdbc.update("DELETE FROM ops_training_progress WHERE job_id=?", job);
    for (long epoch : List.of(1L, 2L, 3L)) {
      jobs.progress(
          node.nodeId(),
          job,
          new TrainingJobService.Progress(epoch, null, null, null, null, "training", null, 10L));
      long progressId =
          testJdbc.queryForObject(
              "SELECT MAX(id) FROM ops_training_progress WHERE job_id=?", Long.class, job);
      testJdbc.update(
          "UPDATE ops_training_progress SET collected_at=? WHERE id=?",
          LocalDateTime.ofInstant(Instant.now().minusSeconds((3 - epoch) * 20), ZoneOffset.UTC),
          progressId);
    }
    assertEquals("EPOCH_PROGRESS_V1", workloads.predict(node.nodeId(), run).get("method"));

    jobs.transition(node.nodeId(), job, "SUCCEEDED");
    assertEquals("UNAVAILABLE", workloads.predict(node.nodeId(), run).get("confidence"));
    sample(node, now.plusSeconds(2), started, false, true);
    assertEquals("SUCCEEDED", workloads.detail(node.nodeId(), run).get("state"));
    assertNotNull(
        testJdbc
            .queryForMap("SELECT * FROM ops_workload_prediction WHERE run_id=?", run)
            .get("absolute_error_seconds"));
    assertEquals(
        1, ((List<?>) workloads.profile(node.nodeId(), run).get("predictionQuality")).size());
  }

  @Test
  void matchingHistoryBuildsPercentilesAndSupportsRangePrediction() {
    var node = node();
    Instant now = Instant.now();
    for (int i = 0; i < 3; i++) {
      String started = now.minusSeconds((i + 1) * 1000L).toString();
      for (int j = 0; j < 4; j++) sample(node, now.plusSeconds(i * 4L + j), started, true, false);
      String run = workloads.list(node.nodeId(), true).get(0).get("id").toString();
      testJdbc.update(
          "UPDATE ops_workload_run SET state='SUCCEEDED',ended_at=?,outcome_source='USER_OR_SDK'"
              + " WHERE id=?",
          LocalDateTime.ofInstant(now.plusSeconds(i * 4L + 3), ZoneOffset.UTC),
          run);
      workloads.confirm(node.nodeId(), run, "TRAINING", "same-config", "same-env", null);
    }
    sample(node, now.plusSeconds(13), now.minusSeconds(100).toString(), true, false);
    String current = workloads.list(node.nodeId(), true).get(0).get("id").toString();
    workloads.confirm(node.nodeId(), current, "TRAINING", "same-config", "same-env", null);
    var profile = workloads.profile(node.nodeId(), current);
    assertEquals("STATISTICAL", profile.get("confidence"));
    assertEquals(4, ((Number) profile.get("runCount")).intValue());
    assertEquals(13, ((Number) profile.get("sampleCount")).intValue());
    assertEquals(
        3d * 1024 * 1048576,
        ((Number) ((Map<?, ?>) profile.get("gpuMemoryBytes")).get("p95")).doubleValue());
    var prediction = workloads.predict(node.nodeId(), current);
    assertEquals("HISTORY_V1", prediction.get("method"));
    assertEquals(3, prediction.get("sampleCount"));
    var range = (List<?>) prediction.get("remainingSeconds");
    assertTrue(((Number) range.get(1)).doubleValue() > ((Number) range.get(0)).doubleValue());
  }

  @Test
  void automaticExperienceSummarizesEvidenceButRemainsPrivateAndUnverified() {
    var node = node();
    var incident =
        incidents.create(new IncidentService.Create(node.nodeId(), "CPU 竞争", null, List.of()));
    var context =
        Map.<String, Object>of(
            "server_id", node.nodeId(), "incident_id", incident.get("id"), "title", "CPU 竞争");
    String report =
        new tools.jackson.databind.ObjectMapper()
            .writeValueAsString(
                Map.of(
                    "anomaly_time",
                    "2026-11-11T00:00:00Z",
                    "evidence",
                    List.of(Map.of("id", "E1", "kind", "host_metrics")),
                    "possible_causes",
                    List.of(Map.of("hypothesis", "数据预处理线程竞争")),
                    "suggested_steps",
                    List.of("核对 DataLoader 并发")));
    memories.fromReport(context, report);
    assertTrue(memories.cases(node.nodeId(), false).isEmpty());
    memories.automatic(true);
    memories.fromReport(context, report);
    memories.fromReport(context, report);
    var cases = memories.cases(node.nodeId(), false);
    assertEquals(1, cases.size());
    assertEquals("CANDIDATE", cases.get(0).get("status"));
    assertEquals("PRIVATE", cases.get(0).get("scope"));
    assertTrue(cases.get(0).get("candidate_cause").toString().contains("数据预处理线程竞争"));
    assertTrue(cases.get(0).get("candidate_cause").toString().contains("DataLoader"));
    assertTrue(cases.get(0).get("symptoms").toString().contains("host_metrics"));
    assertTrue(memories.cases(node.nodeId(), true).isEmpty());
  }
}
