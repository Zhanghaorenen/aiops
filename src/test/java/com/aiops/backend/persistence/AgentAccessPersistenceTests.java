package com.aiops.backend.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.aiops.backend.entity.NodeEntity;
import com.aiops.backend.entity.OpsAgentEntity;
import com.aiops.backend.repository.NodeRepository;
import com.aiops.backend.repository.OpsAgentRepository;
import com.aiops.backend.service.AgentAccessService;
import com.aiops.backend.service.AgentAccessService.EnrollmentRequest;
import com.aiops.backend.service.AgentAccessService.EnrollmentResult;
import com.aiops.backend.service.AgentAccessService.MetricRequest;

@SpringBootTest
@Transactional
class AgentAccessPersistenceTests {
    @Autowired private AgentAccessService service;
    @Autowired private NodeRepository nodes;
    @Autowired private OpsAgentRepository agents;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void changingLoadedNodeDoesNotWriteUntilExplicitSave() {
        EnrollmentResult enrolled = enroll("explicit-save");
        NodeEntity node = nodes.findById(enrolled.nodeId()).orElseThrow();
        Instant changedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        node.markOffline(changedAt);

        assertEquals("ONLINE", nodeStatus(enrolled.nodeId()));
        assertEquals("ONLINE", nodes.findById(enrolled.nodeId()).orElseThrow().getStatus());

        nodes.save(node);

        assertEquals("OFFLINE", nodeStatus(enrolled.nodeId()));
        assertEquals(utc(changedAt), timestamp("ops_node", "updated_at", enrolled.nodeId()));
    }

    @Test
    void enrollmentPersistsOneTimeTokenConsumptionAndRejectsReuseWithoutCreatingRows() {
        String key = key("one-time");
        String token = service.issueEnrollmentToken().token();
        EnrollmentResult enrolled = service.enroll(request(token, key, key));
        LocalDateTime usedAt = jdbc.queryForObject(
                "select used_at from ops_agent_enroll_token where token_hash = ?",
                LocalDateTime.class, hash(token));
        assertNotNull(usedAt);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.enroll(request(token, key + "-reuse", key + "-reuse")));

        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
        assertEquals(0, count("select count(*) from ops_node where node_key = ?", key + "-reuse"));
        assertEquals(0, count("select count(*) from ops_agent where agent_key = ?", key + "-reuse"));
        assertEquals(usedAt, jdbc.queryForObject(
                "select used_at from ops_agent_enroll_token where token_hash = ?",
                LocalDateTime.class, hash(token)));
        assertNotNull(service.authenticate(enrolled.credential()));
    }

    @Test
    void reenrollmentPersistsRotatedCredentialAndInvalidatesOldCredential() {
        String key = key("rotate");
        EnrollmentResult first = service.enroll(request(service.issueEnrollmentToken().token(), key, key));
        LocalDateTime createdAt = timestamp("ops_agent", "created_at", first.agentId());
        String nextToken = service.issueEnrollmentToken().token();
        EnrollmentRequest next = request(nextToken, key, key);
        next = new EnrollmentRequest(next.enrollmentToken(), next.nodeKey(), next.displayName(),
                next.hostname(), next.ip(), next.osName(), next.osVersion(), next.architecture(),
                next.cpuCores(), next.memoryBytes(), next.agentKey(), "2.0.0");

        EnrollmentResult rotated = service.enroll(next);

        assertEquals(first.nodeId(), rotated.nodeId());
        assertEquals(first.agentId(), rotated.agentId());
        assertNotEquals(first.credential(), rotated.credential());
        assertEquals(hash(rotated.credential()), jdbc.queryForObject(
                "select credential_hash from ops_agent where id = ?", String.class, first.agentId()));
        assertEquals("2.0.0", jdbc.queryForObject(
                "select version from ops_agent where id = ?", String.class, first.agentId()));
        assertEquals(createdAt, timestamp("ops_agent", "created_at", first.agentId()));
        assertEquals(1, count("select count(*) from ops_agent where agent_key = ?", key));
        assertNull(service.authenticate(first.credential()));
        assertEquals(first.agentId(), service.authenticate(rotated.credential()).getId());
        assertNotNull(jdbc.queryForObject(
                "select used_at from ops_agent_enroll_token where token_hash = ?",
                LocalDateTime.class, hash(nextToken)));
    }

    @Test
    void heartbeatPersistsBothStatusesTimestampsAndTrimmedVersion() {
        EnrollmentResult enrolled = enroll("heartbeat");
        Instant stale = Instant.now().minusSeconds(300).truncatedTo(ChronoUnit.MICROS);
        setLastSeen(enrolled, stale, true);

        service.heartbeat(enrolled.agentId(), " 2.1.0 ");

        assertEquals("ONLINE", agentStatus(enrolled.agentId()));
        assertEquals("ONLINE", nodeStatus(enrolled.nodeId()));
        assertEquals("2.1.0", jdbc.queryForObject(
                "select version from ops_agent where id = ?", String.class, enrolled.agentId()));
        LocalDateTime heartbeat = timestamp("ops_agent", "last_heartbeat_at", enrolled.agentId());
        assertTrue(heartbeat.isAfter(utc(stale)));
        assertEquals(heartbeat, timestamp("ops_node", "last_seen_at", enrolled.nodeId()));
        assertEquals(heartbeat, timestamp("ops_agent", "updated_at", enrolled.agentId()));
        assertEquals(heartbeat, timestamp("ops_node", "updated_at", enrolled.nodeId()));
    }

    @Test
    void offlineSweepPersistsOnlyStaleRowsAndPreservesLastSeenEvidence() {
        EnrollmentResult staleAgent = enroll("stale");
        EnrollmentResult freshAgent = enroll("fresh");
        Instant stale = Instant.now().minusSeconds(300).truncatedTo(ChronoUnit.MICROS);
        Instant fresh = Instant.now().truncatedTo(ChronoUnit.MICROS);
        setLastSeen(staleAgent, stale, false);
        setLastSeen(freshAgent, fresh, false);

        service.markDisconnectedAgentsOffline();

        assertEquals("OFFLINE", agentStatus(staleAgent.agentId()));
        assertEquals("OFFLINE", nodeStatus(staleAgent.nodeId()));
        assertEquals("ONLINE", agentStatus(freshAgent.agentId()));
        assertEquals("ONLINE", nodeStatus(freshAgent.nodeId()));
        assertEquals(utc(stale), timestamp("ops_agent", "last_heartbeat_at", staleAgent.agentId()));
        assertEquals(utc(stale), timestamp("ops_node", "last_seen_at", staleAgent.nodeId()));
        assertTrue(timestamp("ops_agent", "updated_at", staleAgent.agentId()).isAfter(utc(stale)));
        assertTrue(timestamp("ops_node", "updated_at", staleAgent.nodeId()).isAfter(utc(stale)));
    }

    @Test
    void metricIngestionPersistsHeartbeatAlongWithActualSample() {
        EnrollmentResult enrolled = enroll("metric-heartbeat");
        setLastSeen(enrolled, Instant.now().minusSeconds(300).truncatedTo(ChronoUnit.MICROS), true);
        Instant collected = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.MICROS);

        var sample = service.recordMetric(enrolled.agentId(),
                new MetricRequest(37.5, 1000L, 250L, 2000L, 1000L, collected));

        assertEquals("ONLINE", agentStatus(enrolled.agentId()));
        assertEquals("ONLINE", nodeStatus(enrolled.nodeId()));
        assertEquals(1, count("select count(*) from metric_host_sample where id = ? and node_id = ? and agent_id = ?",
                sample.getId(), enrolled.nodeId(), enrolled.agentId()));
        assertEquals(utc(collected), timestamp("metric_host_sample", "collected_at", sample.getId()));
        assertEquals(timestamp("metric_host_sample", "received_at", sample.getId()),
                timestamp("ops_agent", "last_heartbeat_at", enrolled.agentId()));
        assertEquals(timestamp("ops_agent", "last_heartbeat_at", enrolled.agentId()),
                timestamp("ops_node", "last_seen_at", enrolled.nodeId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void crossNodeConflictRollsBackTokenConsumptionAndNewNodeThenAllowsRetry() {
        // Committed fixtures let the failed service transaction genuinely roll back,
        // rather than observing mutations inside a rollback-only test transaction.
        String key = key("conflict");
        String otherKey = key + "-other";
        List<String> rawTokens = new ArrayList<>();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        try {
            String firstToken = service.issueEnrollmentToken().token();
            rawTokens.add(firstToken);
            EnrollmentResult initial = service.enroll(request(firstToken, key, key));
            String retryToken = service.issueEnrollmentToken().token();
            rawTokens.add(retryToken);

            ResponseStatusException error = assertThrows(ResponseStatusException.class,
                    () -> tx.execute(status -> service.enroll(request(retryToken, otherKey, key))));

            assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
            assertNull(jdbc.queryForObject(
                    "select used_at from ops_agent_enroll_token where token_hash = ?",
                    LocalDateTime.class, hash(retryToken)));
            assertEquals(0, count("select count(*) from ops_node where node_key = ?", otherKey));
            assertEquals(hash(initial.credential()), jdbc.queryForObject(
                    "select credential_hash from ops_agent where id = ?", String.class, initial.agentId()));

            EnrollmentResult retry = service.enroll(request(retryToken, key, key));
            assertEquals(initial.agentId(), retry.agentId());
            assertNotNull(jdbc.queryForObject(
                    "select used_at from ops_agent_enroll_token where token_hash = ?",
                    LocalDateTime.class, hash(retryToken)));
            assertEquals(hash(retry.credential()), jdbc.queryForObject(
                    "select credential_hash from ops_agent where id = ?", String.class, retry.agentId()));
        } finally {
            tx.executeWithoutResult(status -> {
                jdbc.update("delete from metric_host_sample where node_id in (select id from ops_node where node_key in (?, ?))",
                        key, otherKey);
                jdbc.update("delete from ops_agent where node_id in (select id from ops_node where node_key in (?, ?))",
                        key, otherKey);
                jdbc.update("delete from ops_node where node_key in (?, ?)", key, otherKey);
                rawTokens.forEach(token -> jdbc.update(
                        "delete from ops_agent_enroll_token where token_hash = ?", hash(token)));
            });
        }
    }

    @Test
    void offlineFindersKeepStrictNanosecondCutoffForNodesAndAgents() {
        Instant base = Instant.parse("2026-01-12T03:04:05.123456Z");
        EnrollmentResult before = enroll("cutoff-before");
        EnrollmentResult atBase = enroll("cutoff-base");
        EnrollmentResult after = enroll("cutoff-after");
        setLastSeen(before, base.minusNanos(1000), false);
        setLastSeen(atBase, base, false);
        setLastSeen(after, base.plusNanos(1000), false);
        List<Long> nodeIds = List.of(before.nodeId(), atBase.nodeId(), after.nodeId());
        List<Long> agentIds = List.of(before.agentId(), atBase.agentId(), after.agentId());
        assertEquals(utc(base), timestamp("ops_node", "last_seen_at", atBase.nodeId()));
        assertEquals(utc(base), timestamp("ops_agent", "last_heartbeat_at", atBase.agentId()));
        assertEquals(utc(base.minusNanos(1000)), timestamp("ops_node", "last_seen_at", before.nodeId()));
        assertEquals(utc(base.minusNanos(1000)), timestamp("ops_agent", "last_heartbeat_at", before.agentId()));
        assertEquals(utc(base.plusNanos(1000)), timestamp("ops_node", "last_seen_at", after.nodeId()));
        assertEquals(utc(base.plusNanos(1000)), timestamp("ops_agent", "last_heartbeat_at", after.agentId()));

        // Filtering to this test's IDs tolerates unrelated committed database rows.
        assertEquals(List.of(before.nodeId(), atBase.nodeId()), nodes
                .findAllByStatusAndLastSeenAtBefore("ONLINE", base.plusNanos(1))
                .stream().map(NodeEntity::getId).filter(nodeIds::contains).toList());
        assertEquals(List.of(before.agentId(), atBase.agentId()), agents
                .findAllByStatusAndLastHeartbeatAtBefore("ONLINE", base.plusNanos(1))
                .stream().map(OpsAgentEntity::getId).filter(agentIds::contains).toList());

        // An exact microsecond cutoff remains strict: equality must be excluded.
        assertEquals(List.of(before.nodeId()), nodes
                .findAllByStatusAndLastSeenAtBefore("ONLINE", base)
                .stream().map(NodeEntity::getId).filter(nodeIds::contains).toList());
        assertEquals(List.of(before.agentId()), agents
                .findAllByStatusAndLastHeartbeatAtBefore("ONLINE", base)
                .stream().map(OpsAgentEntity::getId).filter(agentIds::contains).toList());
    }

    private EnrollmentResult enroll(String label) {
        String key = key(label);
        return service.enroll(request(service.issueEnrollmentToken().token(), key, key));
    }

    private EnrollmentRequest request(String token, String nodeKey, String agentKey) {
        return new EnrollmentRequest(token, nodeKey, "Persistence node", "persistence-host", "127.0.0.1",
                "Linux", "test", "amd64", 4, 4096L, agentKey, "1.0.0");
    }

    private void setLastSeen(EnrollmentResult enrolled, Instant time, boolean offline) {
        NodeEntity node = nodes.findById(enrolled.nodeId()).orElseThrow();
        OpsAgentEntity agent = agents.findById(enrolled.agentId()).orElseThrow();
        node.markOnline(time);
        agent.heartbeat(time, null);
        if (offline) {
            node.markOffline(time);
            agent.markOffline(time);
        }
        nodes.save(node);
        agents.save(agent);
    }

    private String nodeStatus(Long id) {
        return jdbc.queryForObject("select status from ops_node where id = ?", String.class, id);
    }

    private String agentStatus(Long id) {
        return jdbc.queryForObject("select status from ops_agent where id = ?", String.class, id);
    }

    private LocalDateTime timestamp(String table, String column, Long id) {
        // Identifiers are private test constants; all data remains bound parameters.
        return jdbc.queryForObject("select " + column + " from " + table + " where id = ?",
                LocalDateTime.class, id);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static String key(String label) { return label + "-" + UUID.randomUUID(); }
    private static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
