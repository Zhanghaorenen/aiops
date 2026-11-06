package com.aiops.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.aiops.backend.entity.*;
import com.aiops.backend.repository.*;
import com.aiops.backend.security.*;
import com.aiops.backend.service.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class V02WorkflowTests extends ClusterTestSupport {
  @Autowired MockMvc mvc;
  @Autowired UserRepository users;
  @Autowired ClusterRepository clusters;
  @Autowired NodeRepository nodes;
  @Autowired AgentAccessService agents;
  @Autowired JwtService jwt;
  @Autowired WorkspaceMemberService workspace;
  @Autowired AlertRuleService rules;
  @Autowired AlertEvaluationService evaluation;
  @Autowired IncidentService incidents;
  @Autowired InvestigationTaskService tasks;
  @Autowired GpuUsageService usage;

  private UserEntity member(String name, String role) {
    var user = users.save(new UserEntity(name, "test-hash"));
    grant(name, role);
    return user;
  }

  private void as(String name) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                name, null, AuthorityUtils.createAuthorityList("ROLE_USER")));
  }

  private String bearer(UserEntity user) {
    return "Bearer " + jwt.issue(user.getId(), user.getUsername());
  }

  private AgentAccessService.EnrollmentResult node(String key) {
    return agents.enroll(
        new AgentAccessService.EnrollmentRequest(
            ClusterTestSupport.issue(agents).token(),
            key,
            key,
            key,
            null,
            "Linux",
            "1",
            "amd64",
            2,
            1024L,
            key,
            "test"));
  }

  private void gpu(AgentAccessService.EnrollmentResult n) throws Exception {
    mvc.perform(
            post("/api/agent/metrics")
                .header("Authorization", "Bearer " + n.credential())
                .contentType("application/json")
                .content(
                    "{\"cpuPercent\":95,\"memoryTotalBytes\":100,\"memoryUsedBytes\":50,\"diskTotalBytes\":100,\"diskUsedBytes\":20,\"gpuStatus\":\"AVAILABLE\",\"gpus\":[{\"gpuIndex\":3,\"gpuUuid\":\"GPU-3\",\"gpuName\":\"test"
                        + " GPU\",\"memoryTotalBytes\":1000,\"memoryUsedBytes\":100,\"processes\":[{\"pid\":123,\"gpuIndex\":3,\"processName\":\"python\"}]}]}"))
        .andExpect(status().isAccepted());
  }

  @Test
  void threeRolesReadSharedNodesButOnlyAdminIssuesToken() throws Exception {
    var admin = member("v02admin", "ADMIN");
    var operator = member("v02operator", "OPERATOR");
    var viewer = member("v02viewer", "VIEWER");
    var a = node("v02node1");
    node("v02node2");
    node("v02node3");
    gpu(a);
    for (var user : List.of(admin, operator, viewer)) {
      mvc.perform(get("/api/servers").header("Authorization", bearer(user)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(3));
      mvc.perform(
              get("/api/servers/" + a.nodeId() + "/gpus/3/latest")
                  .header("Authorization", bearer(user)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.gpuUuid").value("GPU-3"));
      mvc.perform(post("/api/agent/enrollment-tokens").header("Authorization", bearer(user)))
          .andExpect(user == admin ? status().isOk() : status().isForbidden());
    }
  }

  @Test
  void roleChangesAreImmediateAndLastAdminIsProtected() throws Exception {
    var user = member("v02role", "VIEWER");
    var c = clusters.requireDefault();
    var current = users.findByUsername("fixtureAdmin").orElseThrow();
    asFixtureAdmin();
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> workspace.update(current.getId(), "VIEWER", null));
    workspace.update(user.getId(), "ADMIN", null);
    workspace.update(user.getId(), "VIEWER", null);
    mvc.perform(post("/api/agent/enrollment-tokens").header("Authorization", bearer(user)))
        .andExpect(status().isForbidden());
    asFixtureAdmin();
    workspace.update(user.getId(), null, "DISABLED");
    mvc.perform(get("/api/workspace").header("Authorization", bearer(user)))
        .andExpect(status().isForbidden());
  }

  @Test
  void crossClusterAndUnjoinedUserCannotReadTarget() throws Exception {
    var outsider = users.save(new UserEntity("v02outsider", "hash"));
    var viewer = member("v02otherreader", "VIEWER");
    var c = clusters.save(new ClusterEntity("other-v02", "other"));
    var n = new NodeEntity("othernode", "other", "host", null, null, null, null, 1, 100L);
    n.assignCluster(c.getId());
    nodes.save(n);
    mvc.perform(get("/api/servers/" + n.getId()).header("Authorization", bearer(viewer)))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/servers/" + n.getId() + "/metrics/latest")
                .header("Authorization", bearer(outsider)))
        .andExpect(status().isForbidden());
  }

  @Test
  void alertLifecycleDeduplicatesAcknowledgesAndRecovers() throws Exception {
    var n = node("alertnode");
    var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    var from = now.minusSeconds(60);
    for (int i = 0; i < 5; i++)
      agents.recordMetric(
          n.agentId(),
          new AgentAccessService.MetricRequest(
              95.0, 100L, 50L, 100L, 20L, from.plusSeconds(i * 15)));
    rules.save(
        null,
        new AlertRuleService.Rule(
            "CPU", n.nodeId(), "CPU_HIGH", "GE", 90, 75, 60, 90, 30, 0, "WARNING", true));
    Long id = testJdbc.queryForObject("SELECT MAX(id) FROM ops_alert_rule", Long.class);
    evaluation.evaluate(id, now);
    evaluation.evaluate(id, now);
    assertEquals(1, testJdbc.queryForObject("SELECT COUNT(*) FROM ops_alert_event", Integer.class));
    for (var changed :
        List.of(
            new AlertRuleService.Rule(
                "CPU", n.nodeId(), "CPU_HIGH", "GT", 90, 75, 60, 90, 30, 0, "WARNING", true),
            new AlertRuleService.Rule(
                "CPU", n.nodeId(), "CPU_HIGH", "GE", 90, 75, 60, 180, 30, 0, "WARNING", true),
            new AlertRuleService.Rule(
                "CPU", n.nodeId(), "CPU_HIGH", "GE", 90, 75, 60, 90, 60, 0, "WARNING", true))) {
      asFixtureAdmin();
      var conflict =
          assertThrows(
              org.springframework.web.server.ResponseStatusException.class,
              () -> rules.save(id, changed));
      assertEquals(409, conflict.getStatusCode().value());
    }
    Long event = testJdbc.queryForObject("SELECT MAX(id) FROM ops_alert_event", Long.class);
    rules.acknowledge(event);
    assertEquals("ACKNOWLEDGED", rules.event(event).get("status"));
    agents.recordMetric(
        n.agentId(),
        new AgentAccessService.MetricRequest(60.0, 100L, 50L, 100L, 20L, now.plusSeconds(1)));
    evaluation.evaluate(id, now.plusSeconds(1));
    assertEquals("RESOLVED", rules.event(event).get("status"));
  }

  @Test
  void investigationIdempotencyLeaseRecoveryAndUsageState() throws Exception {
    var n = node("incidentnode");
    gpu(n);
    asFixtureAdmin();
    var incident =
        incidents.create(new IncidentService.Create(n.nodeId(), "GPU OOM", "test", List.of()));
    long id = ((Number) incident.get("id")).longValue();
    var run = incidents.investigate(id, "request_key_001");
    assertEquals(run.get("id"), incidents.investigate(id, "request_key_001").get("id"));
    var claimed = tasks.claim();
    assertNotNull(claimed);
    testJdbc.update(
        "UPDATE ops_investigation_run SET lease_until=? WHERE id=?",
        LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1),
        claimed.get("id"));
    assertNull(tasks.claim());
    assertEquals(
        "FAILED",
        testJdbc.queryForObject(
            "SELECT status FROM ops_investigation_run WHERE id=?",
            String.class,
            claimed.get("id")));
    usage.create(
        n.nodeId(),
        new GpuUsageService.Usage(
            3,
            "training",
            "experiment",
            Instant.now(),
            Instant.now().plusSeconds(3600),
            "人工登记",
            "PLANNED"));
    var row = usage.list(n.nodeId()).get(0);
    assertEquals("UNVERIFIED", row.get("verification"));
    long task = ((Number) row.get("id")).longValue();
    usage.status(n.nodeId(), task, "RUNNING");
    usage.status(n.nodeId(), task, "COMPLETED");
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> usage.status(n.nodeId(), task, "RUNNING"));
  }

  @Test
  void privateKnowledgeReportIsVisibleOnlyToRequester() {
    var n = node("private-report-node");
    var viewer = member("private-report-viewer", "VIEWER");
    asFixtureAdmin();
    var incident =
        incidents.create(
            new IncidentService.Create(
                n.nodeId(), "GPU investigation", "private KB evidence", List.of()));
    long incidentId = ((Number) incident.get("id")).longValue();
    incidents.investigate(incidentId, "private_report_001");
    var run = tasks.claim();
    tasks.complete(
        run,
        "{\"evidence\":[],\"possible_causes\":[],\"references\":[{\"content\":\"private"
            + " manual\"}]}",
        null);
    assertEquals(1, ((List<?>) incidents.detail(incidentId).get("reports")).size());
    as(viewer.getUsername());
    assertEquals(0, ((List<?>) incidents.detail(incidentId).get("reports")).size());
  }

  @Test
  void disabledAgentAndRevokedTokenCannotWriteOrEnroll() throws Exception {
    var n = node("disable-agent-node");
    asFixtureAdmin();
    var management = new AgentManagementService(permissions, clusters, testJdbc, tokenRepository);
    management.disable(n.agentId());
    mvc.perform(post("/api/agent/heartbeat").header("Authorization", "Bearer " + n.credential()))
        .andExpect(status().isUnauthorized());
    var token = ClusterTestSupport.issue(agents);
    Long tokenId =
        testJdbc.queryForObject("SELECT MAX(id) FROM ops_agent_enroll_token", Long.class);
    asFixtureAdmin();
    management.revoke(tokenId);
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () ->
            agents.enroll(
                new AgentAccessService.EnrollmentRequest(
                    token.token(),
                    "revoked-target",
                    "target",
                    "host",
                    null,
                    null,
                    null,
                    null,
                    1,
                    100L,
                    "revoked-agent",
                    "1")));
  }

  @Autowired ClusterPermissionService permissions;
  @Autowired AgentEnrollTokenRepository tokenRepository;

  @Test
  void workerPersistsSuccessAndDependencyFailureWithoutStoringJwt() throws Exception {
    var n = node("worker-http-node");
    asFixtureAdmin();
    long incidentId =
        ((Number)
                incidents
                    .create(
                        new IncidentService.Create(
                            n.nodeId(), "GPU test", "test HTTP transport", List.of()))
                    .get("id"))
            .longValue();
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    var responseStatus = new java.util.concurrent.atomic.AtomicInteger(200);
    var authSeen = new java.util.concurrent.atomic.AtomicBoolean(false);
    server.createContext(
        "/api/internal/investigate",
        exchange -> {
          authSeen.set(
              "x".repeat(32).equals(exchange.getRequestHeaders().getFirst("X-AI-Service-Key"))
                  && exchange.getRequestHeaders().getFirst("Authorization").startsWith("Bearer "));
          exchange.getRequestBody().readAllBytes();
          String body =
              "{\"incident_id\":"
                  + incidentId
                  + ",\"server_id\":"
                  + n.nodeId()
                  + ",\"evidence\":[],\"possible_causes\":[],\"references\":[],\"execution_performed\":false}";
          byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(responseStatus.get(), bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      var worker =
          new InvestigationWorker(
              tasks, jwt, "http://127.0.0.1:" + server.getAddress().getPort(), "x".repeat(32));
      var first = incidents.investigate(incidentId, "worker_http_001");
      worker.poll();
      assertTrue(authSeen.get());
      assertEquals(
          "SUCCEEDED",
          testJdbc.queryForObject(
              "SELECT status FROM ops_investigation_run WHERE id=?",
              String.class,
              first.get("id")));
      assertEquals(1, ((List<?>) incidents.detail(incidentId).get("reports")).size());
      responseStatus.set(503);
      var second = incidents.investigate(incidentId, "worker_http_002");
      worker.poll();
      assertEquals(
          "FAILED",
          testJdbc.queryForObject(
              "SELECT status FROM ops_investigation_run WHERE id=?",
              String.class,
              second.get("id")));
      assertEquals(
          "AI_DEPENDENCY_FAILED",
          testJdbc.queryForObject(
              "SELECT error_code FROM ops_investigation_run WHERE id=?",
              String.class,
              second.get("id")));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void offlineAndGpuCollectionFailureAreDifferentRules() {
    var n = node("offline-gpu-rules");
    var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    testJdbc.update(
        "UPDATE ops_node SET last_seen_at=? WHERE id=?",
        LocalDateTime.ofInstant(now.minusSeconds(300), ZoneOffset.UTC),
        n.nodeId());
    rules.save(
        null,
        new AlertRuleService.Rule(
            "offline", n.nodeId(), "SERVER_OFFLINE", "GE", 90, 30, 30, 90, 45, 0, "WARNING", true));
    Long offline = testJdbc.queryForObject("SELECT MAX(id) FROM ops_alert_rule", Long.class);
    evaluation.evaluate(offline, now);
    assertEquals(
        1,
        testJdbc.queryForObject(
            "SELECT COUNT(*) FROM ops_alert_event WHERE rule_id=? AND status='FIRING'",
            Integer.class,
            offline));
    rules.save(
        null,
        new AlertRuleService.Rule(
            "collection",
            n.nodeId(),
            "GPU_COLLECTION_FAILED",
            "GE",
            1,
            0,
            60,
            90,
            45,
            0,
            "WARNING",
            true));
    Long failure = testJdbc.queryForObject("SELECT MAX(id) FROM ops_alert_rule", Long.class);
    for (int i = 0; i < 3; i++)
      testJdbc.update(
          "INSERT INTO"
              + " metric_gpu_snapshot(node_id,agent_id,collection_status,collected_at,received_at)"
              + " VALUES(?,?,'FAILED',?,?)",
          n.nodeId(),
          n.agentId(),
          LocalDateTime.ofInstant(now.minusSeconds(i * 30), ZoneOffset.UTC),
          LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    evaluation.evaluate(failure, now);
    assertEquals(
        1,
        testJdbc.queryForObject(
            "SELECT COUNT(*) FROM ops_alert_event WHERE rule_id=? AND status='FIRING'",
            Integer.class,
            failure));
    testJdbc.update(
        "INSERT INTO"
            + " metric_gpu_snapshot(node_id,agent_id,collection_status,collected_at,received_at)"
            + " VALUES(?,?,'UNKNOWN',?,?)",
        n.nodeId(),
        n.agentId(),
        LocalDateTime.ofInstant(now.plusSeconds(1), ZoneOffset.UTC),
        LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    evaluation.evaluate(failure, now.plusSeconds(1));
    assertEquals(
        1,
        testJdbc.queryForObject(
            "SELECT COUNT(*) FROM ops_alert_event WHERE rule_id=? AND status='FIRING'",
            Integer.class,
            failure));
    testJdbc.update(
        "INSERT INTO"
            + " metric_gpu_snapshot(node_id,agent_id,collection_status,collected_at,received_at)"
            + " VALUES(?,?,'NO_GPU',?,?)",
        n.nodeId(),
        n.agentId(),
        LocalDateTime.ofInstant(now.plusSeconds(2), ZoneOffset.UTC),
        LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    evaluation.evaluate(failure, now.plusSeconds(2));
    assertEquals(
        1,
        testJdbc.queryForObject(
            "SELECT COUNT(*) FROM ops_alert_event WHERE rule_id=? AND status='RESOLVED'",
            Integer.class,
            failure));
  }

  @Test
  void cooldownPreventsImmediateNewEventAfterRecovery() {
    var n = node("cooldown-rule");
    var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    agents.recordMetric(
        n.agentId(), new AgentAccessService.MetricRequest(99.0, 100L, 10L, 100L, 10L, now));
    rules.save(
        null,
        new AlertRuleService.Rule(
            "cooldown", n.nodeId(), "CPU_HIGH", "GE", 90, 75, 0, 90, 45, 300, "WARNING", true));
    Long id = testJdbc.queryForObject("SELECT MAX(id) FROM ops_alert_rule", Long.class);
    evaluation.evaluate(id, now);
    agents.recordMetric(
        n.agentId(),
        new AgentAccessService.MetricRequest(60.0, 100L, 10L, 100L, 10L, now.plusSeconds(1)));
    evaluation.evaluate(id, now.plusSeconds(1));
    agents.recordMetric(
        n.agentId(),
        new AgentAccessService.MetricRequest(99.0, 100L, 10L, 100L, 10L, now.plusSeconds(2)));
    evaluation.evaluate(id, now.plusSeconds(2));
    assertEquals(
        1,
        testJdbc.queryForObject(
            "SELECT COUNT(*) FROM ops_alert_event WHERE rule_id=?", Integer.class, id));
    assertEquals(
        0,
        testJdbc.queryForObject(
            "SELECT COUNT(*) FROM ops_alert_event WHERE rule_id=? AND active_key IS NOT NULL",
            Integer.class,
            id));
  }
}
