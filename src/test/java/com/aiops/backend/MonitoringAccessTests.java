package com.aiops.backend;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.aiops.backend.entity.UserEntity;
import com.aiops.backend.repository.HostMetricSampleRepository;
import com.aiops.backend.repository.MetricRepository;
import com.aiops.backend.repository.UserRepository;
import com.aiops.backend.security.JwtService;
import com.aiops.backend.service.AgentAccessService;
import com.aiops.backend.service.AgentAccessService.EnrollmentRequest;
import com.aiops.backend.service.AgentAccessService.EnrollmentResult;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MonitoringAccessTests {
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private JwtService jwtService;
    @Autowired private AgentAccessService agents;
    @Autowired private HostMetricSampleRepository hostMetrics;
    @Autowired private MetricRepository legacyMetrics;
    @Autowired private JdbcTemplate jdbc;
    @Value("${aiops.jwt.secret}") private String jwtSecret;

    private UserEntity account;
    private String jwt;
    private EnrollmentResult target;

    @BeforeEach
    void prepareUserAndNode() {
        account = users.save(new UserEntity("monitor-reader", "unused-test-password-hash"));
        jwt = jwtService.issue(account.getId(), account.getUsername());
        target = enroll("target");
    }

    @Test
    void userJwtReadsUploadedSamplesForRequestedNode() throws Exception {
        Instant latest = Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        upload(target, 73.25, latest);
        upload(target, 10.0, latest.minusSeconds(60));
        upload(enroll("other"), 99.0, latest.plusSeconds(30));

        mvc.perform(get("/api/auth/me").header("Authorization", bearer(jwt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(account.getId()))
                .andExpect(jsonPath("$.username").value("monitor-reader"));
        mvc.perform(get("/api/servers").header("Authorization", bearer(jwt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + target.nodeId() + ")].cpuUsage", hasItem(73.25)));
        mvc.perform(get(serverPath()).header("Authorization", bearer(jwt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(target.nodeId()))
                .andExpect(jsonPath("$.hostname").value("host-target"))
                .andExpect(jsonPath("$.cpuUsage").value(73.25))
                .andExpect(jsonPath("$.memoryUsage").value(50.0))
                .andExpect(jsonPath("$.diskUsage").value(75.0));
        mvc.perform(get(serverPath() + "/metrics/latest").header("Authorization", bearer(jwt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeId").value(target.nodeId()))
                .andExpect(jsonPath("$.cpuPercent").value(73.25))
                .andExpect(jsonPath("$.collectedAt").value(latest.toString()));
        assertEquals(3, hostMetrics.count());
        assertEquals(0, legacyMetrics.count());
    }

    @Test
    void historyFiltersNodeAndTimeAndReturnsChronologicalOrder() throws Exception {
        Instant start = Instant.now().minusSeconds(300).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        upload(target, 30.0, start.plusSeconds(120));
        upload(target, 20.0, start.plusSeconds(60));
        upload(target, 10.0, start.minusSeconds(60));
        upload(enroll("other"), 99.0, start.plusSeconds(60));

        mvc.perform(get(serverPath() + "/metrics").header("Authorization", bearer(jwt))
                        .param("from", start.toString()).param("to", start.plusSeconds(180).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].cpuPercent").value(20.0))
                .andExpect(jsonPath("$[1].cpuPercent").value(30.0));
        mvc.perform(get(serverPath() + "/metrics").header("Authorization", bearer(jwt))
                        .param("from", start.toString()).param("to", start.plusSeconds(180).toString())
                        .param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void missingBearerCannotReadUserData() throws Exception {
        for (String path : protectedPaths()) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "a.b.c", ""})
    void malformedBearerCannotReadUserData(String token) throws Exception {
        assertUnauthorized(token);
    }

    @Test
    void expiredJwtCannotReadUserData() throws Exception {
        String expired = Jwts.builder().subject(account.getUsername()).claim("uid", account.getId())
                .expiration(Date.from(Instant.now().minusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret))).compact();
        assertUnauthorized(expired);
    }

    @Test
    void jwtWithWrongSignatureCannotReadUserData() throws Exception {
        String forged = Jwts.builder().subject(account.getUsername()).claim("uid", account.getId())
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Jwts.SIG.HS256.key().build()).compact();
        assertUnauthorized(forged);
    }

    @Test
    void disabledUserJwtCannotReadUserData() throws Exception {
        jdbc.update("update sys_user set status = ? where id = ?", "DISABLED", account.getId());
        assertUnauthorized(jwt);
    }

    @Test
    void deletedUserJwtCannotReadUserData() throws Exception {
        users.delete(account);
        assertUnauthorized(jwt);
    }

    @Test
    void validAgentCredentialCannotReadUserDataOrMintEnrollmentTokens() throws Exception {
        mvc.perform(post("/api/agent/heartbeat").header("Authorization", bearer(target.credential())))
                .andExpect(status().isNoContent());
        assertUnauthorized(target.credential());
        mvc.perform(post("/api/agent/enrollment-tokens").header("Authorization", bearer(target.credential())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedAgentRoleStillCannotReadUserEndpoints() throws Exception {
        for (String path : protectedPaths()) {
            mvc.perform(get(path).with(user("agent").roles("AGENT"))).andExpect(status().isForbidden());
        }
    }

    @Test
    void userJwtCannotImpersonateAgentForWrites() throws Exception {
        mvc.perform(post("/api/agent/heartbeat").header("Authorization", bearer(jwt)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/agent/metrics").header("Authorization", bearer(jwt))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        assertEquals(0, hostMetrics.count());
    }

    @Test
    void missingServerAndMissingSamplesReturnNotFound() throws Exception {
        mvc.perform(get(serverPath()).header("Authorization", bearer(jwt)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cpuUsage").isEmpty());
        mvc.perform(get(serverPath() + "/metrics/latest").header("Authorization", bearer(jwt)))
                .andExpect(status().isNotFound());
        for (String suffix : List.of("", "/metrics/latest")) {
            mvc.perform(get("/api/servers/" + Long.MAX_VALUE + suffix).header("Authorization", bearer(jwt)))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void invalidHistoryRangeAndInvalidMetricDoNotSucceed() throws Exception {
        Instant now = Instant.now();
        for (Instant end : List.of(now.minusSeconds(120), now.plusSeconds(120))) {
            mvc.perform(get(serverPath() + "/metrics").header("Authorization", bearer(jwt))
                            .param("from", now.minusSeconds(60).toString()).param("to", end.toString()))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/agent/metrics").header("Authorization", bearer(target.credential()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cpuPercent":101,"memoryTotalBytes":1000,"memoryUsedBytes":500,
                                 "diskTotalBytes":2000,"diskUsedBytes":1500}
                                """))
                .andExpect(status().isBadRequest());
        assertEquals(0, hostMetrics.count());
    }

    private EnrollmentResult enroll(String key) {
        return agents.enroll(new EnrollmentRequest(agents.issueEnrollmentToken().token(),
                "node-" + key, "Node " + key, "host-" + key, "127.0.0.1",
                "Linux", "test", "amd64", 4, 1000L, "agent-" + key, "0.1.0"));
    }

    private void upload(EnrollmentResult agent, double cpu, Instant time) throws Exception {
        mvc.perform(post("/api/agent/metrics").header("Authorization", bearer(agent.credential()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cpuPercent":%s,"memoryTotalBytes":1000,"memoryUsedBytes":500,
                                 "diskTotalBytes":2000,"diskUsedBytes":1500,"collectedAt":"%s"}
                                """.formatted(cpu, time)))
                .andExpect(status().isAccepted());
    }

    private List<String> protectedPaths() {
        return List.of("/api/auth/me", "/api/servers", serverPath(), serverPath() + "/metrics/latest",
                serverPath() + "/metrics?from=2026-01-01T00:00:00Z&to=2026-01-02T00:00:00Z&limit=10",
                "/api/metrics/recent", "/api/metrics/agents/1/history");
    }

    private void assertUnauthorized(String token) throws Exception {
        for (String path : protectedPaths()) {
            mvc.perform(get(path).header("Authorization", bearer(token))).andExpect(status().isUnauthorized());
        }
    }

    private String serverPath() { return "/api/servers/" + target.nodeId(); }
    private String bearer(String token) { return "Bearer " + token; }
}
