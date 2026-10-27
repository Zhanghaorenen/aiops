package com.aiops.backend.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.aiops.backend.entity.AgentEntity;
import com.aiops.backend.entity.HostMetricSampleEntity;
import com.aiops.backend.entity.MetricEntity;
import com.aiops.backend.repository.HostMetricSampleRepository;
import com.aiops.backend.repository.MetricRepository;
import com.aiops.backend.service.AgentAccessService;
import com.aiops.backend.service.AgentAccessService.EnrollmentRequest;
import com.aiops.backend.service.AgentAccessService.EnrollmentResult;
import com.aiops.backend.service.AgentService;

@SpringBootTest
@Transactional
class MetricPersistenceTests {
    // Exact DATETIME(6) values: nanosecond-only Hibernate/H2 precision is not assumed.
    private static final Instant BASE = Instant.parse("2026-01-12T03:04:05.123456Z");

    @Autowired private HostMetricSampleRepository hostMetrics;
    @Autowired private MetricRepository legacyMetrics;
    @Autowired private AgentAccessService access;
    @Autowired private AgentService legacyAgents;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void hostMetricInstantWritesUtcWallClockAndReadsJdbcUtcValueAtMicrosecondPrecision() {
        EnrollmentResult enrolled = enroll("utc-host");
        Instant received = BASE.plusSeconds(2);
        HostMetricSampleEntity saved = hostMetrics.save(sample(enrolled, BASE, received));

        // getObject(LocalDateTime.class) checks the DATETIME wall clock directly;
        // it cannot accidentally cancel a wrong JVM-zone conversion in the handler.
        assertEquals(utc(BASE), jdbc.queryForObject(
                "select collected_at from metric_host_sample where id = ?", LocalDateTime.class, saved.getId()));
        assertEquals(utc(received), jdbc.queryForObject(
                "select received_at from metric_host_sample where id = ?", LocalDateTime.class, saved.getId()));
        HostMetricSampleEntity loaded = hostMetrics.findById(saved.getId()).orElseThrow();
        assertEquals(BASE, loaded.getCollectedAt());
        assertEquals(received, loaded.getReceivedAt());

        Instant jdbcTime = Instant.parse("2026-01-13T21:22:23.654321Z");
        jdbc.update("update metric_host_sample set collected_at = ?, received_at = ? where id = ?",
                utc(jdbcTime), utc(jdbcTime.plusSeconds(1)), saved.getId());

        HostMetricSampleEntity reloaded = hostMetrics.findById(saved.getId()).orElseThrow();
        assertEquals(jdbcTime, reloaded.getCollectedAt());
        assertEquals(jdbcTime.plusSeconds(1), reloaded.getReceivedAt());
    }

    @Test
    void legacyMetricInstantAlsoUsesUtcAndPreservesMicrosecondsInBothDirections() {
        AgentEntity agent = legacyAgent();
        MetricEntity saved = legacyMetrics.save(legacyMetric(agent.getId(), BASE));

        assertEquals(utc(BASE), jdbc.queryForObject(
                "select collected_at from metrics where id = ?", LocalDateTime.class, saved.getId()));
        assertEquals(utc(BASE.plusSeconds(1)), jdbc.queryForObject(
                "select received_at from metrics where id = ?", LocalDateTime.class, saved.getId()));
        MetricEntity loaded = legacyMetrics.findById(saved.getId()).orElseThrow();
        assertEquals(BASE, loaded.getCollectedAt());
        assertEquals(BASE.plusSeconds(1), loaded.getReceivedAt());

        Instant rewritten = BASE.plusSeconds(3600);
        jdbc.update("update metrics set collected_at = ? where id = ?", utc(rewritten), saved.getId());
        assertEquals(rewritten, legacyMetrics.findById(saved.getId()).orElseThrow().getCollectedAt());
    }

    @Test
    void savingNullGpuUsageActuallyClearsExistingDatabaseColumn() {
        AgentEntity agent = legacyAgent();
        MetricEntity saved = legacyMetrics.save(legacyMetric(agent.getId(), BASE));
        assertEquals(12.5, jdbc.queryForObject(
                "select gpu_usage from metrics where id = ?", Double.class, saved.getId()));

        MetricEntity loaded = legacyMetrics.findById(saved.getId()).orElseThrow();
        loaded.setGpuUsage(null);
        legacyMetrics.save(loaded);

        assertNull(jdbc.queryForObject("select gpu_usage from metrics where id = ?", Double.class, saved.getId()));
        assertNull(legacyMetrics.findById(saved.getId()).orElseThrow().getGpuUsage());
        assertEquals(1, jdbc.queryForObject("select count(*) from metrics where id = ?", Integer.class, saved.getId()));
        assertEquals(42.5, jdbc.queryForObject(
                "select cpu_usage from metrics where id = ?", Double.class, saved.getId()));
        assertEquals(BASE, legacyMetrics.findById(saved.getId()).orElseThrow().getCollectedAt());
    }

    @Test
    void hostHistoryUsesInclusiveWindowDeterministicIdTiesLimitsAndNodeIsolation() {
        EnrollmentResult first = enroll("history-first");
        EnrollmentResult second = enroll("history-second");
        hostMetrics.save(sample(first, BASE.minusSeconds(1), BASE));
        HostMetricSampleEntity start = hostMetrics.save(sample(first, BASE, BASE));
        HostMetricSampleEntity tieLow = hostMetrics.save(sample(first, BASE.plusSeconds(1), BASE));
        HostMetricSampleEntity tieHigh = hostMetrics.save(sample(first, BASE.plusSeconds(1), BASE));
        HostMetricSampleEntity end = hostMetrics.save(sample(first, BASE.plusSeconds(2), BASE));
        hostMetrics.save(sample(first, BASE.plusSeconds(3), BASE));
        HostMetricSampleEntity other = hostMetrics.save(sample(second, BASE.plusSeconds(2), BASE));
        assertEquals(6, jdbc.queryForObject(
                "select count(*) from metric_host_sample where node_id = ?", Integer.class, first.nodeId()));

        List<HostMetricSampleEntity> ascending = hostMetrics
                .findAllByNodeIdAndCollectedAtBetweenOrderByCollectedAtAscIdAsc(
                        first.nodeId(), BASE, BASE.plusSeconds(2), 3);
        List<HostMetricSampleEntity> descending = hostMetrics
                .findAllByNodeIdAndCollectedAtBetweenOrderByCollectedAtDescIdDesc(
                        first.nodeId(), BASE, BASE.plusSeconds(2), 2);

        assertEquals(List.of(start.getId(), tieLow.getId(), tieHigh.getId()), ids(ascending));
        assertEquals(List.of(end.getId(), tieHigh.getId()), ids(descending));
        assertTrue(ascending.stream().allMatch(value -> value.getNodeId().equals(first.nodeId())));
        assertTrue(descending.stream().allMatch(value -> value.getNodeId().equals(first.nodeId())));
        assertEquals(List.of(start.getId(), tieLow.getId(), tieHigh.getId(), end.getId()),
                ids(access.metricHistory(first.nodeId(), BASE, BASE.plusSeconds(2), 10)));
        assertEquals(other.getId(), access.latestMetric(second.nodeId()).getId());
        assertEquals(List.of(other.getId()), ids(access.metricHistory(
                second.nodeId(), BASE, BASE.plusSeconds(2), 10)));
        assertTrue(hostMetrics.findAllByNodeIdAndCollectedAtBetweenOrderByCollectedAtAscIdAsc(
                Long.MAX_VALUE, BASE, BASE.plusSeconds(2), 10).isEmpty());
    }

    @Test
    void hostLatestUsesHigherIdWhenCollectedTimesTieWithoutCrossingNodes() {
        EnrollmentResult first = enroll("latest-first");
        EnrollmentResult second = enroll("latest-second");
        hostMetrics.save(sample(first, BASE, BASE));
        HostMetricSampleEntity expected = hostMetrics.save(sample(first, BASE, BASE));
        HostMetricSampleEntity other = hostMetrics.save(sample(second, BASE.plusSeconds(100), BASE));

        assertEquals(expected.getId(), access.latestMetric(first.nodeId()).getId());
        assertEquals(other.getId(), access.latestMetric(second.nodeId()).getId());
        assertEquals(expected.getId(), jdbc.queryForObject(
                "select id from metric_host_sample where node_id = ? order by collected_at desc, id desc limit 1",
                Long.class, first.nodeId()));
    }

    @Test
    void historyClampsExtremeLimitsToOneAndOneThousandRealDatabaseRows() {
        EnrollmentResult enrolled = enroll("bounded");
        // A JDBC batch creates real rows cheaply, independently of the mapper under test.
        List<Integer> indexes = IntStream.rangeClosed(0, 1000).boxed().toList();
        jdbc.batchUpdate("""
                insert into metric_host_sample
                (node_id, agent_id, cpu_percent, memory_total_bytes, memory_used_bytes, memory_percent,
                 disk_total_bytes, disk_used_bytes, disk_percent, collected_at, received_at)
                values (?, ?, 25.0, 1000, 250, 25.0, 2000, 1000, 50.0, ?, ?)
                """, indexes, 250, (statement, index) -> {
                    statement.setLong(1, enrolled.nodeId());
                    statement.setLong(2, enrolled.agentId());
                    statement.setObject(3, utc(BASE.plusSeconds(index)));
                    statement.setObject(4, utc(BASE.plusSeconds(index + 1)));
                });
        assertEquals(1001, jdbc.queryForObject(
                "select count(*) from metric_host_sample where node_id = ?", Integer.class, enrolled.nodeId()));

        List<HostMetricSampleEntity> maximum = access.metricHistory(
                enrolled.nodeId(), BASE, BASE.plusSeconds(1000), Integer.MAX_VALUE);
        List<HostMetricSampleEntity> minimum = access.metricHistory(
                enrolled.nodeId(), BASE, BASE.plusSeconds(1000), Integer.MIN_VALUE);

        assertEquals(1000, maximum.size());
        assertEquals(BASE, maximum.get(0).getCollectedAt());
        assertEquals(BASE.plusSeconds(999), maximum.get(999).getCollectedAt());
        assertEquals(1, minimum.size());
        assertEquals(maximum.get(0).getId(), minimum.get(0).getId());
        assertEquals(jdbc.queryForList(
                "select id from metric_host_sample where node_id = ? order by collected_at asc, id asc limit 1000",
                Long.class, enrolled.nodeId()), ids(maximum));
    }

    @Test
    void legacyRepositoryLimitAndLatestHaveStableTiesAndAgentIsolation() {
        AgentEntity first = legacyAgent();
        AgentEntity second = legacyAgent();
        MetricEntity low = legacyMetrics.save(legacyMetric(first.getId(), BASE));
        MetricEntity high = legacyMetrics.save(legacyMetric(first.getId(), BASE));
        legacyMetrics.save(legacyMetric(first.getId(), BASE.minusSeconds(1)));
        MetricEntity other = legacyMetrics.save(legacyMetric(second.getId(), BASE.plusSeconds(1)));

        assertEquals(List.of(high.getId(), low.getId()), legacyMetrics
                .findAllByAgentIdOrderByCollectedAtDescIdDesc(first.getId(), 2)
                .stream().map(MetricEntity::getId).toList());
        assertEquals(high.getId(), legacyMetrics.findTopByAgentIdOrderByCollectedAtDescIdDesc(first.getId())
                .orElseThrow().getId());
        assertEquals(other.getId(), legacyMetrics.findTopByAgentIdOrderByCollectedAtDescIdDesc(second.getId())
                .orElseThrow().getId());
        assertEquals(3, jdbc.queryForObject(
                "select count(*) from metrics where agent_id = ?", Integer.class, first.getId()));
        assertTrue(legacyMetrics.findAllByAgentIdOrderByCollectedAtDescIdDesc(Long.MAX_VALUE, 2).isEmpty());
    }

    @Test
    void hostHistoryKeepsNanosecondQueryBoundsForBothSortDirections() {
        EnrollmentResult enrolled = enroll("history-nanos");
        HostMetricSampleEntity atBase = hostMetrics.save(sample(enrolled, BASE, BASE));
        HostMetricSampleEntity nextMicro = hostMetrics.save(sample(enrolled, BASE.plusNanos(1000), BASE));
        HostMetricSampleEntity followingMicro = hostMetrics.save(sample(enrolled, BASE.plusNanos(2000), BASE));
        assertEquals(List.of(utc(BASE), utc(BASE.plusNanos(1000)), utc(BASE.plusNanos(2000))),
                jdbc.queryForList(
                        "select collected_at from metric_host_sample where node_id = ? order by collected_at asc, id asc",
                        LocalDateTime.class, enrolled.nodeId()));

        // >= BASE+1ns must not round down to include the preceding stored microsecond.
        assertEquals(List.of(nextMicro.getId(), followingMicro.getId()), ids(hostMetrics
                .findAllByNodeIdAndCollectedAtBetweenOrderByCollectedAtAscIdAsc(
                        enrolled.nodeId(), BASE.plusNanos(1), BASE.plusNanos(2000), 10)));
        assertEquals(List.of(followingMicro.getId(), nextMicro.getId()), ids(hostMetrics
                .findAllByNodeIdAndCollectedAtBetweenOrderByCollectedAtDescIdDesc(
                        enrolled.nodeId(), BASE.plusNanos(1), BASE.plusNanos(2000), 10)));

        // <= BASE+999ns includes BASE, but must not include the next microsecond.
        assertEquals(List.of(atBase.getId()), ids(hostMetrics
                .findAllByNodeIdAndCollectedAtBetweenOrderByCollectedAtAscIdAsc(
                        enrolled.nodeId(), BASE, BASE.plusNanos(999), 10)));
        assertEquals(List.of(atBase.getId()), ids(hostMetrics
                .findAllByNodeIdAndCollectedAtBetweenOrderByCollectedAtDescIdDesc(
                        enrolled.nodeId(), BASE, BASE.plusNanos(999), 10)));
    }

    private EnrollmentResult enroll(String label) {
        String key = label + "-" + UUID.randomUUID();
        return access.enroll(new EnrollmentRequest(access.issueEnrollmentToken().token(), key,
                "Persistence metric node", "metrics-host", "127.0.0.1", "Linux", "test",
                "amd64", 4, 4096L, key, "1.0.0"));
    }

    private HostMetricSampleEntity sample(EnrollmentResult enrolled, Instant collected, Instant received) {
        return new HostMetricSampleEntity(enrolled.nodeId(), enrolled.agentId(), 25.0,
                1000L, 250L, 25.0, 2000L, 1000L, 50.0, collected, received);
    }

    private AgentEntity legacyAgent() {
        AgentEntity request = new AgentEntity();
        request.setName("metric-persistence-" + UUID.randomUUID());
        request.setIp("127.0.0.1");
        return legacyAgents.registerAgent(request);
    }

    private MetricEntity legacyMetric(Long agentId, Instant collected) {
        MetricEntity value = new MetricEntity();
        value.setAgentId(agentId);
        value.setHostname("persistence-host");
        value.setCpuUsage(42.5);
        value.setMemoryUsage(50.0);
        value.setDiskUsage(25.0);
        value.setGpuUsage(12.5);
        value.setNetworkInMbps(1.0);
        value.setNetworkOutMbps(2.0);
        value.setCollectedAt(collected);
        value.setReceivedAt(collected.plusSeconds(1));
        return value;
    }

    private List<Long> ids(List<HostMetricSampleEntity> samples) {
        return samples.stream().map(HostMetricSampleEntity::getId).toList();
    }

    private static LocalDateTime utc(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
