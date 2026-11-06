package com.aiops.backend;

import static org.junit.jupiter.api.Assertions.*;

import com.aiops.backend.service.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class TrainingJobTests extends ClusterTestSupport {

  @Autowired
  TrainingJobService jobs;

  @Autowired
  TrainingAnalysisService analysis;

  @Autowired
  AgentAccessService agents;

  @Autowired
  GpuUsageService usage;

  private long node() {
    String key = "train-" + UUID.randomUUID();
    var n = agents.enroll(
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
        64L * 1024 * 1024 * 1024,
        key,
        "test"
      )
    );
    var cards = List.of(0, 1)
      .stream()
      .map(i ->
        new AgentAccessService.GpuSampleRequest(
          i,
          "GPU" + i,
          "test",
          24L * 1048576 * 1024,
          8L * 1048576 * 1024,
          16L * 1048576 * 1024,
          BigDecimal.TEN,
          BigDecimal.TEN,
          BigDecimal.TEN,
          BigDecimal.TEN,
          null,
          null,
          List.of()
        )
      )
      .toList();
    agents.recordMetric(
      n.agentId(),
      new AgentAccessService.MetricRequest(
        10d,
        64L * 1024 * 1024 * 1024,
        16L * 1024 * 1024 * 1024,
        1000L,
        100L,
        Instant.now(),
        "AVAILABLE",
        cards,
        null,
        Map.of("memoryAvailableBytes", 48L * 1024 * 1024 * 1024)
      )
    );
    return n.nodeId();
  }

  private TrainingJobService.Request request(int gpu, String mode, double ram) {
    var start = Instant.now().plusSeconds(3600);
    return new TrainingJobService.Request(
      "train",
      "test",
      gpu,
      mode,
      start,
      start.plusSeconds(3600),
      6000,
      ram,
      2d,
      null,
      null,
      null,
      "dataset-a",
      "python train.py",
      "TRAINING",
      true
    );
  }

  @Test
  void sharedJobsAdmitAndExclusiveBlocks() {
    long n = node();
    assertEquals(
      "RESERVED",
      jobs.save(n, null, request(0, "SHARED", 8000)).get("state")
    );
    assertEquals(
      "RESERVED",
      jobs.save(n, null, request(0, "SHARED", 8000)).get("state")
    );
    var blocked = jobs.save(n, null, request(0, "EXCLUSIVE", 8000));
    assertEquals("QUEUED", blocked.get("state"));
  }

  @Test
  void ramCompetitionIsAcrossDifferentGpus() {
    long n = node();
    assertEquals(
      "RESERVED",
      jobs.save(n, null, request(0, "SHARED", 40000)).get("state")
    );
    assertEquals(
      "QUEUED",
      jobs.save(n, null, request(1, "SHARED", 40000)).get("state")
    );
  }

  @Test
  void cancellationReleasesCommitmentAndLegacyCannotMutateTraining() {
    long n = node();
    var first = jobs.save(n, null, request(0, "EXCLUSIVE", 8000));
    long id = ((Number) first.get("id")).longValue();
    var second = jobs.save(n, null, request(0, "EXCLUSIVE", 8000));
    long other = ((Number) second.get("id")).longValue();
    assertThrows(Exception.class, () -> usage.status(n, id, "RUNNING"));
    jobs.transition(n, id, "CANCELLED");
    new TrainingQueueWorker(testJdbc,jobs).tick();
    assertEquals("RESERVED", jobs.get(n, other).get("state"));
  }

  @Test
  void missingAndStaleTelemetryCannotAdmit() {
    long n = node();
    testJdbc.update(
      "UPDATE metric_host_sample SET collected_at=? WHERE node_id=?",
      java.time.LocalDateTime.now(ZoneOffset.UTC).minusHours(1),
      n
    );
    assertEquals(
      "QUEUED",
      jobs.save(n, null, request(0, "SHARED", 8000)).get("state")
    );
  }

  @Test
  void agentExitNeedsExactIdentityAndResultRemainsUnconfirmed() {
    long n = node();
    var job = jobs.save(n, null, request(0, "SHARED", 8000));
    long id = ((Number) job.get("id")).longValue();
    testJdbc.update(
      "UPDATE ops_training_job SET state='RUNNING',pid=123,process_started_at='original' WHERE id=?",
      id
    );
    String details =
      "{\"processExits\":[{\"pid\":123,\"startedAt\":\"reused\",\"status\":\"EXITED_UNKNOWN\"}]}";
    testJdbc.update(
      "UPDATE metric_host_sample SET details_json=? WHERE node_id=?",
      details,
      n
    );
    jobs.observeExit(n, id);
    assertEquals("RUNNING", jobs.get(n, id).get("state"));
    testJdbc.update(
      "UPDATE metric_host_sample SET details_json=? WHERE node_id=?",
      details.replace("reused", "original"),
      n
    );
    jobs.observeExit(n, id);
    assertEquals("EXITED_UNKNOWN", jobs.get(n, id).get("state"));
    jobs.transition(n, id, "SUCCEEDED");
    assertEquals("SUCCEEDED", jobs.get(n, id).get("state"));
  }

  @Test
  void missingPerformanceEvidenceDoesNotInventBottlenecks() {
    long n = node();
    var job = jobs.save(n, null, request(0, "SHARED", 8000));
    var report = analysis.analyze(n, ((Number) job.get("id")).longValue());
    assertTrue(((List<?>) report.get("observations")).isEmpty());
    assertFalse(((List<?>) report.get("missing")).isEmpty());
    assertEquals("USER_REPORTED", report.get("progressSource"));
  }

  @Test
  void runningCommitmentIsNotDeductedAgainFromLiveAvailableRam() {
    long n = node();
    var old = request(0, "SHARED", 40000);
    var start = Instant.now().plusSeconds(30);
    var near = new TrainingJobService.Request(
      old.taskName(),
      old.description(),
      0,
      "SHARED",
      start,
      start.plusSeconds(3600),
      6000,
      40000,
      2d,
      null,
      null,
      null,
      null,
      null,
      "TRAINING",
      true
    );
    var first = jobs.save(n, null, near);
    long id = ((Number) first.get("id")).longValue();
    assertEquals("RESERVED", first.get("state"));
    testJdbc.update(
      "UPDATE ops_training_job SET state='RUNNING' WHERE id=?",
      id
    );
    testJdbc.update("UPDATE ops_gpu_usage SET status='RUNNING' WHERE id=?", id);
    var second = new TrainingJobService.Request(
      old.taskName(),
      old.description(),
      0,
      "SHARED",
      start,
      start.plusSeconds(3600),
      6000,
      8000,
      2d,
      null,
      null,
      null,
      null,
      null,
      "TRAINING",
      true
    );
    assertEquals("RESERVED", jobs.save(n, null, second).get("state"));
  }

  @Test
  void viewerCannotCreateTrainingJobs() {
    long n = node();
    testJdbc.update(
      "UPDATE ops_cluster_member SET role='VIEWER' WHERE user_id=(SELECT id FROM sys_user WHERE username='fixtureAdmin')"
    );
    var error = assertThrows(
      org.springframework.web.server.ResponseStatusException.class,
      () -> jobs.save(n, null, request(0, "SHARED", 8000))
    );
    assertEquals(403, error.getStatusCode().value());
  }
}
