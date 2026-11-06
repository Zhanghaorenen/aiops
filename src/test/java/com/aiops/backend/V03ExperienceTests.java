package com.aiops.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.aiops.backend.persistence.MetricDetails;
import com.aiops.backend.repository.*;
import com.aiops.backend.security.JwtService;
import com.aiops.backend.service.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class V03ExperienceTests extends ClusterTestSupport {
  @Autowired AgentAccessService agents;
  @Autowired GpuUsageService usage;
  @Autowired AlertRuleService rules;
  @Autowired AlertEvaluationService evaluation;
  @Autowired IncidentService incidents;
  @Autowired MonitoringTrendService trends;
  @Autowired HostMetricSampleRepository metrics;
  @Autowired KnowledgeBaseService knowledge;
  @Autowired MockMvc mvc;
  @Autowired UserRepository users;
  @Autowired JwtService jwt;

  private AgentAccessService.EnrollmentResult node() {
    String key = "v03-" + UUID.randomUUID();
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
    var cards =
        List.of(0, 1).stream()
            .map(
                i ->
                    new AgentAccessService.GpuSampleRequest(
                        i,
                        "GPU-" + i,
                        "test",
                        1000L,
                        100L,
                        900L,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.TEN,
                        BigDecimal.TEN,
                        null,
                        null,
                        List.of()))
            .toList();
    agents.recordMetric(
        n.agentId(),
        new AgentAccessService.MetricRequest(
            95.0, 100L, 50L, 100L, 10L, Instant.now(), "AVAILABLE", cards, null));
    return n;
  }

  private GpuUsageService.Usage reservation(int gpu, Instant from, Instant to) {
    return new GpuUsageService.Usage(gpu, "test booking", "test", from, to, null, "PLANNED");
  }

  @Test
  void halfOpenReservationConflictsAndSeparateGpus() {
    var n = node();
    var start = Instant.now().plusSeconds(600);
    var end = start.plusSeconds(3600);
    usage.create(n.nodeId(), reservation(0, start, end));
    var conflict =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () ->
                usage.create(
                    n.nodeId(), reservation(0, start.plusSeconds(30), end.plusSeconds(30))));
    assertEquals(409, conflict.getStatusCode().value());
    usage.create(n.nodeId(), reservation(1, start, end));
    usage.create(n.nodeId(), reservation(0, end, end.plusSeconds(3600)));
    assertEquals(3, usage.list(n.nodeId()).size());
    assertEquals(2, usage.list(n.nodeId(), start, end).size());
    var adjacent = usage.list(n.nodeId(), end, end.plusSeconds(3600)).get(0);
    long adjacentId = ((Number) adjacent.get("id")).longValue();
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () ->
            usage.update(
                n.nodeId(),
                adjacentId,
                reservation(0, start.plusSeconds(30), end.plusSeconds(30))));
  }

  @Test
  void invalidDatesOwnershipAndExpiry() {
    var n = node();
    var start = Instant.now().plusSeconds(600);
    assertThrows(
        Exception.class,
        () -> usage.create(n.nodeId(), reservation(0, Instant.now().minusSeconds(1), start)));
    assertThrows(
        Exception.class,
        () -> usage.create(n.nodeId(), reservation(0, start, start.plus(Duration.ofDays(8)))));
    usage.create(n.nodeId(), reservation(0, start, start.plusSeconds(60)));
    var booking = usage.list(n.nodeId()).get(0);
    long id = ((Number) booking.get("id")).longValue();
    assertThrows(Exception.class, () -> usage.status(n.nodeId(), id, "RUNNING"));
    var viewer = users.save(new com.aiops.backend.entity.UserEntity("v03viewer", "test"));
    grant("v03viewer", "VIEWER");
    org.springframework.security.core.context.SecurityContextHolder.getContext()
        .setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "v03viewer",
                null,
                org.springframework.security.core.authority.AuthorityUtils.createAuthorityList(
                    "ROLE_USER")));
    assertThrows(Exception.class, () -> usage.status(n.nodeId(), id, "CANCELLED"));
    asFixtureAdmin();
    testJdbc.update(
        "UPDATE ops_gpu_usage SET expected_end_at=? WHERE id=?",
        LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1),
        id);
    assertEquals("EXPIRED", usage.list(n.nodeId()).get(0).get("status"));
    assertThrows(Exception.class, () -> usage.status(n.nodeId(), id, "RUNNING"));
  }

  @Test
  void presetBatchIsIdempotentAndViewerCannotApply() throws Exception {
    var a = node();
    var b = node();
    var req =
        new AlertRuleService.PresetRequest(List.of(a.nodeId(), b.nodeId()), "GPU_TEMP_HIGH", null);
    assertEquals(2, rules.applyPreset(req).get("added"));
    assertEquals(2, rules.applyPreset(req).get("skipped"));
    var viewer = users.save(new com.aiops.backend.entity.UserEntity("v03presets", "hash"));
    grant("v03presets", "VIEWER");
    mvc.perform(
            post("/api/alert-presets/apply")
                .header(
                    "Authorization", "Bearer " + jwt.issue(viewer.getId(), viewer.getUsername()))
                .contentType("application/json")
                .content("{\"serverIds\":[" + a.nodeId() + "],\"presetKey\":\"CPU_HIGH\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void alertCreatesOneIncidentAndOneActiveRun() {
    var n = node();
    rules.save(
        null,
        new AlertRuleService.Rule(
            "CPU", n.nodeId(), "CPU_HIGH", "GE", 90, 75, 0, 90, 30, 0, "WARNING", true));
    long rule = testJdbc.queryForObject("SELECT MAX(id) FROM ops_alert_rule", Long.class);
    evaluation.evaluate(rule, Instant.now().plusSeconds(1));
    long alert =
        testJdbc.queryForObject("SELECT id FROM ops_alert_event WHERE rule_id=?", Long.class, rule);
    var first = incidents.fromAlert(alert, "request_v03_first", false);
    long id = ((Number) first.get("incidentId")).longValue();
    assertEquals(
        id,
        ((Number) incidents.fromAlert(alert, "request_v03_second", false).get("incidentId"))
            .longValue());
    assertEquals(
        1,
        testJdbc.queryForObject(
            "SELECT COUNT(*) FROM ops_investigation_run WHERE incident_id=?", Integer.class, id));
    testJdbc.update("UPDATE ops_investigation_run SET status='FAILED' WHERE incident_id=?", id);
    incidents.fromAlert(alert, "request_v03_third", false);
    assertEquals(
        1,
        testJdbc.queryForObject(
            "SELECT COUNT(*) FROM ops_investigation_run WHERE incident_id=?", Integer.class, id));
    incidents.fromAlert(alert, "request_v03_retry", true);
    assertEquals(
        2,
        testJdbc.queryForObject(
            "SELECT COUNT(*) FROM ops_investigation_run WHERE incident_id=?", Integer.class, id));
  }

  @Test
  void extendedTelemetryRoundTripAndUnknownFieldsDropped() throws Exception {
    var n = node();
    var body =
        "{\"cpuPercent\":20,\"memoryTotalBytes\":1000,\"memoryUsedBytes\":200,\"diskTotalBytes\":1000,\"diskUsedBytes\":100,\"details\":{\"load1\":1.2,\"perCorePercent\":[10,30],\"environment\":\"SECRET\",\"network\":[{\"name\":\"eth0\",\"bytesSent\":40}]},\"gpuStatus\":\"AVAILABLE\",\"gpus\":[{\"gpuIndex\":0,\"gpuUuid\":\"GPU-0\",\"details\":{\"powerLimitW\":300},\"processes\":[{\"pid\":123,\"details\":{\"workingDirectory\":\"/work/train\",\"cpuPercent\":125,\"cmdline\":\"SECRET\"}}]}]}";
    mvc.perform(
            post("/api/agent/metrics")
                .header("Authorization", "Bearer " + n.credential())
                .contentType("application/json")
                .content(body))
        .andExpect(status().isAccepted());
    var details =
        metrics.findTopByNodeIdOrderByCollectedAtDescIdDesc(n.nodeId()).orElseThrow().getDetails();
    assertEquals(1.2, ((Number) details.get("load1")).doubleValue());
    assertFalse(details.containsKey("environment"));
    var user = users.findByUsername("fixtureAdmin").orElseThrow();
    String token = "Bearer " + jwt.issue(user.getId(), user.getUsername());
    mvc.perform(
            get("/api/servers/" + n.nodeId() + "/gpus/processes").header("Authorization", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].workingDirectory").value("/work/train"))
        .andExpect(jsonPath("$[0].cmdline").doesNotExist());
    assertThrows(
        Exception.class,
        () -> MetricDetails.encode(Map.of("perCorePercent", List.of(101)), "HOST"));
  }

  @Test
  void trendsUseWholeWindowAndReportTruePeak() {
    var n = node();
    var now = Instant.now();
    for (int i = 0; i < 20; i++)
      agents.recordMetric(
          n.agentId(),
          new AgentAccessService.MetricRequest(
              (double) i, 100L, 10L, 100L, 10L, now.minusSeconds(3600 - i * 120)));
    var result = trends.trends(n.nodeId(), null, now.minusSeconds(7200), Instant.now());
    assertEquals(21, result.get("rawCount"));
    @SuppressWarnings("unchecked")
    var stats = (Map<String, Object>) result.get("stats");
    @SuppressWarnings("unchecked")
    var cpu = (Map<String, Object>) stats.get("cpuPercent");
    assertEquals(95.0, ((Number) cpu.get("maximum")).doubleValue());
  }

  @Test
  void reindexOnlyOwnerAndReadyOrFailedDocuments() {
    var kb =
        knowledge.create(
            "fixtureAdmin",
            new com.aiops.backend.dto.knowledge.KnowledgeRequests.Base("v03", "test"));
    var doc =
        knowledge.registerDocument(
            "fixtureAdmin",
            kb.id,
            new com.aiops.backend.dto.knowledge.KnowledgeRequests.Document(
                "x.md", "text/markdown", "a".repeat(64), 5, "b".repeat(32)));
    assertThrows(Exception.class, () -> knowledge.prepareReindex("fixtureAdmin", kb.id, doc.id));
    knowledge.updateIndexStatus(
        doc.id,
        new com.aiops.backend.dto.knowledge.KnowledgeRequests.IndexStatus(
            "PROCESSING", null, "test", null));
    knowledge.updateIndexStatus(
        doc.id,
        new com.aiops.backend.dto.knowledge.KnowledgeRequests.IndexStatus(
            "FAILED", null, "test", "INDEX_FAILED"));
    assertEquals(2, knowledge.prepareReindex("fixtureAdmin", kb.id, doc.id).indexVersion);
  }
}
