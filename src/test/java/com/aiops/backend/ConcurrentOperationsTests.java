package com.aiops.backend;

import static org.junit.jupiter.api.Assertions.*;

import com.aiops.backend.service.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(
    properties = "spring.datasource.url=jdbc:h2:mem:v02_concurrency;MODE=MySQL;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ConcurrentOperationsTests extends ClusterTestSupport {
  @Autowired AgentAccessService agents;
  @Autowired AlertRuleService rules;
  @Autowired AlertEvaluationService evaluation;
  @Autowired WorkspaceMemberService workspace;
  @Autowired AgentManagementService management;

  private List<Boolean> race(Callable<Boolean> one, Callable<Boolean> two) throws Exception {
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var a =
          executor.submit(
              () -> {
                start.await();
                return one.call();
              });
      var b =
          executor.submit(
              () -> {
                start.await();
                return two.call();
              });
      start.countDown();
      return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void tokenConsumedOnlyOnceUnderConcurrentEnrollment() throws Exception {
    String key = "race-" + UUID.randomUUID();
    String token = ClusterTestSupport.issue(agents).token();
    Callable<Boolean> enroll =
        () -> {
          try {
            agents.enroll(
                new AgentAccessService.EnrollmentRequest(
                    token, key, key, key, null, null, null, null, 1, 100L, key, "1"));
            return true;
          } catch (org.springframework.web.server.ResponseStatusException e) {
            return false;
          }
        };
    var result = race(enroll, enroll);
    assertEquals(1, result.stream().filter(Boolean::booleanValue).count());
    assertEquals(
        1,
        testJdbc.queryForObject(
            "SELECT COUNT(*) FROM ops_node WHERE node_key=?", Integer.class, key));
  }

  @Test
  void enrollmentAndRevocationHaveOnlyOneWinner() throws Exception {
    String key = "revoke-race-" + UUID.randomUUID();
    String token = ClusterTestSupport.issue(agents).token();
    Long id = testJdbc.queryForObject("SELECT MAX(id) FROM ops_agent_enroll_token", Long.class);
    var result =
        race(
            () -> {
              try {
                agents.enroll(
                    new AgentAccessService.EnrollmentRequest(
                        token, key, key, key, null, null, null, null, 1, 100L, key, "1"));
                return true;
              } catch (org.springframework.web.server.ResponseStatusException e) {
                return false;
              }
            },
            () -> {
              asFixtureAdmin();
              try {
                management.revoke(id);
                return true;
              } catch (org.springframework.web.server.ResponseStatusException e) {
                return false;
              } finally {
                SecurityContextHolder.clearContext();
              }
            });
    assertEquals(1, result.stream().filter(Boolean::booleanValue).count());
  }

  @Test
  void concurrentHeartbeatCannotReactivateDisabledAgent() throws Exception {
    String key = "disable-race-" + UUID.randomUUID();
    var enrolled =
        agents.enroll(
            new AgentAccessService.EnrollmentRequest(
                ClusterTestSupport.issue(agents).token(),
                key,
                key,
                key,
                null,
                null,
                null,
                null,
                1,
                100L,
                key,
                "1"));
    race(
        () -> {
          try {
            agents.heartbeat(enrolled.agentId(), "1");
            return true;
          } catch (org.springframework.web.server.ResponseStatusException e) {
            return false;
          }
        },
        () -> {
          asFixtureAdmin();
          try {
            management.disable(enrolled.agentId());
            return true;
          } finally {
            SecurityContextHolder.clearContext();
          }
        });
    assertEquals(
        "DISABLED",
        testJdbc.queryForObject(
            "SELECT status FROM ops_agent WHERE id=?", String.class, enrolled.agentId()));
    assertNull(agents.authenticate(enrolled.credential()));
  }

  @Test
  void concurrentAlertEvaluationsCreateSingleActiveEvent() throws Exception {
    String key = "alert-race-" + UUID.randomUUID();
    var n =
        agents.enroll(
            new AgentAccessService.EnrollmentRequest(
                ClusterTestSupport.issue(agents).token(),
                key,
                key,
                key,
                null,
                null,
                null,
                null,
                1,
                100L,
                key,
                "1"));
    var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    for (int i = 0; i < 5; i++)
      agents.recordMetric(
          n.agentId(),
          new AgentAccessService.MetricRequest(
              95.0, 100L, 10L, 100L, 10L, now.minusSeconds(i * 15)));
    rules.save(
        null,
        new AlertRuleService.Rule(
            "race", n.nodeId(), "CPU_HIGH", "GE", 90, 75, 60, 90, 30, 0, "WARNING", true));
    Long id = testJdbc.queryForObject("SELECT MAX(id) FROM ops_alert_rule", Long.class);
    var result =
        race(
            () -> {
              evaluation.evaluate(id, now);
              return true;
            },
            () -> {
              evaluation.evaluate(id, now);
              return true;
            });
    assertEquals(List.of(true, true), result);
    assertEquals(
        1,
        testJdbc.queryForObject(
            "SELECT COUNT(*) FROM ops_alert_event WHERE rule_id=? AND active_key IS NOT NULL",
            Integer.class,
            id));
  }

  @Test
  void concurrentAdminDemotionsLeaveAnEffectiveAdmin() throws Exception {
    for (String user : List.of("raceAdminA", "raceAdminB")) {
      testJdbc.update(
          "INSERT INTO sys_user(username,password_hash,status,created_at,updated_at)"
              + " VALUES(?,'hash','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
          user);
      grant(user, "ADMIN");
    }
    testJdbc.update(
        "DELETE FROM ops_cluster_member WHERE user_id IN (SELECT id FROM sys_user WHERE"
            + " username='fixtureAdmin')");
    Callable<Boolean> a = () -> demote("raceAdminA"), b = () -> demote("raceAdminB");
    var result = race(a, b);
    assertEquals(1, result.stream().filter(Boolean::booleanValue).count());
    assertEquals(
        1,
        testJdbc.queryForObject(
            "SELECT COUNT(*) FROM ops_cluster_member WHERE role='ADMIN' AND status='ACTIVE'",
            Integer.class));
  }

  private boolean demote(String user) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                user, null, AuthorityUtils.createAuthorityList("ROLE_USER")));
    try {
      workspace.update(
          testJdbc.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, user),
          "VIEWER",
          null);
      return true;
    } catch (org.springframework.web.server.ResponseStatusException e) {
      return false;
    } finally {
      SecurityContextHolder.clearContext();
    }
  }
}
