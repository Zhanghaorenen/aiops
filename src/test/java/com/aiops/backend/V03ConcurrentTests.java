package com.aiops.backend;

import static org.junit.jupiter.api.Assertions.*;

import com.aiops.backend.service.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(
    properties = "spring.datasource.url=jdbc:h2:mem:v03_race;MODE=MySQL;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class V03ConcurrentTests extends ClusterTestSupport {
  @Autowired AgentAccessService agents;
  @Autowired GpuUsageService usage;
  @Autowired AlertRuleService rules;
  @Autowired AlertEvaluationService evaluation;
  @Autowired IncidentService incidents;

  private AgentAccessService.EnrollmentResult node() {
    String key = UUID.randomUUID().toString();
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
                2,
                100L,
                key,
                "test"));
    agents.recordMetric(
        n.agentId(),
        new AgentAccessService.MetricRequest(
            95.0,
            100L,
            10L,
            100L,
            10L,
            Instant.now(),
            "AVAILABLE",
            List.of(
                new AgentAccessService.GpuSampleRequest(
                    0,
                    "GPU-test",
                    "test",
                    1000L,
                    100L,
                    900L,
                    BigDecimal.ONE,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of())),
            null));
    return n;
  }

  private List<Boolean> race(Callable<Boolean> action) throws Exception {
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var results = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 2; i++)
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  asFixtureAdmin();
                  try {
                    return action.call();
                  } finally {
                    SecurityContextHolder.clearContext();
                  }
                }));
      start.countDown();
      return List.of(
          results.get(0).get(20, TimeUnit.SECONDS), results.get(1).get(20, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentBookingsHaveOnlyOneWinner() throws Exception {
    var n = node();
    var start = Instant.now().plusSeconds(600);
    var results =
        race(
            () -> {
              try {
                usage.create(
                    n.nodeId(),
                    new GpuUsageService.Usage(
                        0, "race", null, start, start.plusSeconds(3600), null, "PLANNED"));
                return true;
              } catch (org.springframework.web.server.ResponseStatusException e) {
                assertEquals(409, e.getStatusCode().value());
                return false;
              }
            });
    assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
    assertEquals(1, usage.list(n.nodeId()).size());
  }

  @Test
  void concurrentPresetAddsOneRule() throws Exception {
    var n = node();
    race(
        () -> {
          rules.applyPreset(
              new AlertRuleService.PresetRequest(List.of(n.nodeId()), "GPU_TEMP_HIGH", null));
          return true;
        });
    assertEquals(
        1,
        testJdbc.queryForObject(
            "SELECT COUNT(*) FROM ops_alert_rule WHERE server_id=?", Integer.class, n.nodeId()));
  }

  @Test
  void concurrentAlertAnalysisReusesIncidentAndTask() throws Exception {
    var n = node();
    rules.save(
        null,
        new AlertRuleService.Rule(
            "CPU", n.nodeId(), "CPU_HIGH", "GE", 90, 75, 0, 90, 30, 0, "WARNING", true));
    long rule = testJdbc.queryForObject("SELECT MAX(id) FROM ops_alert_rule", Long.class);
    evaluation.evaluate(rule, Instant.now().plusSeconds(1));
    long alert =
        testJdbc.queryForObject("SELECT id FROM ops_alert_event WHERE rule_id=?", Long.class, rule);
    race(
        () -> {
          incidents.fromAlert(alert, UUID.randomUUID().toString(), false);
          return true;
        });
    assertEquals(
        1,
        testJdbc.queryForObject(
            "SELECT COUNT(*) FROM ops_incident WHERE source_alert_id=?", Integer.class, alert));
    assertEquals(
        1,
        testJdbc.queryForObject(
            "SELECT COUNT(*) FROM ops_investigation_run r JOIN ops_incident i ON i.id=r.incident_id"
                + " WHERE i.source_alert_id=?",
            Integer.class,
            alert));
  }
}
