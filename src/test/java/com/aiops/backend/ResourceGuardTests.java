package com.aiops.backend;

import static org.junit.jupiter.api.Assertions.*;

import com.aiops.backend.service.ResourceGuardService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@Transactional
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class ResourceGuardTests extends ClusterTestSupport {
  @Autowired ResourceGuardService guard;
  @Autowired org.springframework.test.web.servlet.MockMvc mvc;
  @Autowired com.aiops.backend.service.AgentAccessService agents;
  @Autowired com.aiops.backend.security.JwtService jwt;

  private long node() {
    long cluster =
        testJdbc.queryForObject("SELECT id FROM ops_cluster ORDER BY id LIMIT 1", Long.class);
    testJdbc.update(
        "INSERT INTO"
            + " ops_node(node_key,display_name,hostname,status,cluster_id,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        "guard-" + UUID.randomUUID(),
        "guard fixture",
        "fixture",
        "ONLINE",
        cluster);
    return testJdbc.queryForObject("SELECT MAX(id) FROM ops_node", Long.class);
  }

  private ResourceGuardService.Policy policy() {
    return new ResourceGuardService.Policy("AUTO", List.of(1001), 1024, 5, 10, 10, 60, 3, 5, 256);
  }

  @Test
  void disabledByDefaultAndNoClaimOfExecution() {
    long n = node();
    var initial = guard.get(n);
    assertEquals("DISABLED", ((ResourceGuardService.Policy) initial.get("policy")).mode());
    guard.put(n, policy());
    var result = guard.requestCheck(n);
    assertEquals(true, result.get("requested"));
    assertEquals(false, result.get("executed"));
    assertThrows(ResponseStatusException.class, () -> guard.requestCheck(n));
  }

  @Test
  void viewerCannotEnableOrRequestAndCannotCrossWorkspace() {
    long n = node();
    long user =
        testJdbc.queryForObject(
            "SELECT id FROM sys_user WHERE username='fixtureAdmin'", Long.class);
    testJdbc.update("UPDATE ops_cluster_member SET role='VIEWER' WHERE user_id=?", user);
    assertNotNull(guard.get(n));
    assertEquals(
        403,
        assertThrows(ResponseStatusException.class, () -> guard.put(n, policy()))
            .getStatusCode()
            .value());
    assertEquals(
        403,
        assertThrows(ResponseStatusException.class, () -> guard.requestCheck(n))
            .getStatusCode()
            .value());
    testJdbc.update("UPDATE ops_cluster_member SET status='DISABLED' WHERE user_id=?", user);
    assertThrows(ResponseStatusException.class, () -> guard.get(n));
  }

  @Test
  void agentEventsAreIdempotentAndBoundToNode() {
    long n = node(), other = node();
    var event =
        new ResourceGuardService.Event(
            UUID.randomUUID(), 1.0, "WOULD_TERMINATE", Map.of("pid", 42));
    var sync = new ResourceGuardService.Sync(List.of(event), Map.of("armed", false));
    guard.sync(n, 100L, sync);
    guard.sync(n, 100L, sync);
    assertEquals(
        1,
        testJdbc.queryForObject(
            "SELECT COUNT(*) FROM ops_resource_guard_event WHERE node_id=?", Integer.class, n));
    assertEquals(
        409,
        assertThrows(ResponseStatusException.class, () -> guard.sync(other, 101L, sync))
            .getStatusCode()
            .value());
  }

  @Test
  void automaticPolicyRequiresBusinessScope() {
    long n = node();
    var p = policy();
    assertThrows(
        ResponseStatusException.class,
        () ->
            guard.put(
                n,
                new ResourceGuardService.Policy(
                    p.mode(),
                    List.of(),
                    p.minAvailableMb(),
                    p.minAvailablePercent(),
                    p.memoryPsiThreshold(),
                    p.sustainSeconds(),
                    p.cooldownSeconds(),
                    p.maxActionsPerHour(),
                    p.graceSeconds(),
                    p.minRssMb())));
  }

  @Test
  void httpAgentCredentialIsScopedAndCannotConfigurePolicies() throws Exception {
    String key = "guard-http-" + UUID.randomUUID();
    var enrolled =
        agents.enroll(
            new com.aiops.backend.service.AgentAccessService.EnrollmentRequest(
                issue(agents).token(),
                key,
                key,
                key,
                null,
                "Linux",
                null,
                "x86_64",
                2,
                8L * 1024 * 1024 * 1024,
                key,
                "test"));
    long user =
        testJdbc.queryForObject(
            "SELECT id FROM sys_user WHERE username='fixtureAdmin'", Long.class);
    String userToken = "Bearer " + jwt.issue(user, "fixtureAdmin");
    String agentToken = "Bearer " + enrolled.credential();
    String path = "/api/agent/resource-guard/sync",
        body = "{\"events\":[],\"status\":{\"armed\":false}}";
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .contentType("application/json")
                .content(body))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isUnauthorized());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .header("Authorization", userToken)
                .contentType("application/json")
                .content(body))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isForbidden());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .header("Authorization", agentToken)
                .contentType("application/json")
                .content(body))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/servers/" + enrolled.nodeId() + "/resource-guard")
                .header("Authorization", userToken))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.agentOnline")
                .value(true))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.agentStatus.armed")
                .value(false))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.lastSeenAt")
                .value(org.hamcrest.Matchers.endsWith("Z")));
    assertEquals(
        1,
        testJdbc.queryForObject(
            "SELECT COUNT(*) FROM ops_resource_guard WHERE node_id=?",
            Integer.class,
            enrolled.nodeId()));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/servers/" + enrolled.nodeId() + "/resource-guard/check")
                .header("Authorization", agentToken))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isUnauthorized());
  }

  @Test
  void httpRejectsRootUidPolicy() throws Exception {
    long n = node();
    long user =
        testJdbc.queryForObject(
            "SELECT id FROM sys_user WHERE username='fixtureAdmin'", Long.class);
    String body =
        "{\"mode\":\"AUTO\",\"businessUids\":[0],\"minAvailableMb\":1024,\"minAvailablePercent\":5,\"memoryPsiThreshold\":10,\"sustainSeconds\":10,\"cooldownSeconds\":60,\"maxActionsPerHour\":3,\"graceSeconds\":5,\"minRssMb\":256}";
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/api/servers/" + n + "/resource-guard")
                .header("Authorization", "Bearer " + jwt.issue(user, "fixtureAdmin"))
                .contentType("application/json")
                .content(body))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isBadRequest());
  }
}
