package com.aiops.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.aiops.backend.entity.AgentEntity;
import com.aiops.backend.entity.MetricEntity;
import com.aiops.backend.exception.ResourceNotFoundException;

@SpringBootTest
@Transactional
class MetricServiceTests {

    @Autowired
    private AgentService agentService;

    @Autowired
    private MetricService metricService;

    @Test
    void recordMetricPersistsMetricAndMarksAgentOnline() {
        AgentEntity agent = createAgent("agent-metric-01", "OFFLINE");
        MetricEntity saved = metricService.recordMetric(metricFor(agent.getId(), Instant.parse("2026-09-14T08:00:00Z")));

        assertNotNull(saved.getId());
        assertEquals(agent.getId(), saved.getAgentId());
        assertEquals("server-agent-metric-01", saved.getHostname());
        assertEquals(42.5, saved.getCpuUsage());
        assertEquals("ONLINE", agentService.getAgentById(agent.getId()).getStatus());
    }

    @Test
    void listRecentMetricsHonorsLimitAndSortsNewestFirst() {
        AgentEntity agent = createAgent("agent-metric-02", "ONLINE");
        metricService.recordMetric(metricFor(agent.getId(), Instant.parse("2026-09-14T08:00:00Z")));
        MetricEntity latest = metricService.recordMetric(metricFor(agent.getId(), Instant.parse("2026-09-14T08:01:00Z")));

        List<MetricEntity> recent = metricService.listRecentMetrics(1);

        assertEquals(1, recent.size());
        assertEquals(latest.getId(), recent.get(0).getId());
    }

    @Test
    void latestMetricByAgentReturnsNewestMetric() {
        AgentEntity agent = createAgent("agent-metric-03", "ONLINE");
        metricService.recordMetric(metricFor(agent.getId(), Instant.parse("2026-09-14T08:00:00Z")));
        MetricEntity latest = metricService.recordMetric(metricFor(agent.getId(), Instant.parse("2026-09-14T08:02:00Z")));

        MetricEntity result = metricService.getLatestMetricByAgentId(agent.getId());

        assertEquals(latest.getId(), result.getId());
    }

    @Test
    void recordMetricRejectsInvalidPercent() {
        AgentEntity agent = createAgent("agent-metric-04", "ONLINE");
        MetricEntity metric = metricFor(agent.getId(), Instant.now());
        metric.setCpuUsage(120.0);

        assertThrows(IllegalArgumentException.class, () -> metricService.recordMetric(metric));
    }

    @Test
    void latestMetricByAgentThrowsWhenMissing() {
        assertThrows(ResourceNotFoundException.class, () -> metricService.getLatestMetricByAgentId(999L));
    }

    @Test
    void historyFiltersAgentAndPreservesGpuInNewestFirstOrder() {
        AgentEntity first = createAgent("history-01", "ONLINE");
        AgentEntity second = createAgent("history-02", "ONLINE");
        Instant time = Instant.parse("2026-09-14T08:00:00Z");
        MetricEntity latest = metricService.recordMetric(metricFor(first.getId(), time.plusSeconds(60)));
        MetricEntity older = metricService.recordMetric(metricFor(first.getId(), time));
        metricService.recordMetric(metricFor(second.getId(), time.plusSeconds(120)));

        List<MetricEntity> history = metricService.getMetricHistoryByAgentId(first.getId());

        assertEquals(List.of(latest.getId(), older.getId()), history.stream().map(MetricEntity::getId).toList());
        assertEquals(12.0, history.get(0).getGpuUsage());
        assertEquals(latest.getId(), metricService.getAgentDetails(first.getId()).latestMetric().getId());
        assertEquals(latest.getId(), metricService.getAgentOverview(first.getId()).latestMetric().getId());
    }

    @Test
    void historyIsBoundedToTwoHundredSamples() {
        AgentEntity agent = createAgent("bounded-01", "ONLINE");
        Instant time = Instant.parse("2026-09-14T08:00:00Z");
        for (int index = 0; index < 201; index++) {
            metricService.recordMetric(metricFor(agent.getId(), time.plusSeconds(index)));
        }

        List<MetricEntity> history = metricService.getMetricHistoryByAgentId(agent.getId());
        assertEquals(200, history.size());
        assertEquals(time.plusSeconds(200), history.get(0).getCollectedAt());
        assertEquals(time.plusSeconds(1), history.get(199).getCollectedAt());
    }

    @Test
    void detailsAndOverviewAllowAgentWithoutSamples() {
        AgentEntity agent = createAgent("empty-01", "OFFLINE");

        assertEquals(agent.getId(), metricService.getAgentDetails(agent.getId()).agent().getId());
        assertNull(metricService.getAgentDetails(agent.getId()).latestMetric());
        assertEquals("OFFLINE", metricService.getAgentOverview(agent.getId()).status());
        assertNull(metricService.getAgentOverview(agent.getId()).latestMetric());
        assertEquals(List.of(), metricService.getMetricHistoryByAgentId(agent.getId()));
    }

    @Test
    void historyDetailsAndOverviewRejectUnknownAgent() {
        assertThrows(ResourceNotFoundException.class, () -> metricService.getMetricHistoryByAgentId(Long.MAX_VALUE));
        assertThrows(ResourceNotFoundException.class, () -> metricService.getAgentDetails(Long.MAX_VALUE));
        assertThrows(ResourceNotFoundException.class, () -> metricService.getAgentOverview(Long.MAX_VALUE));
    }

    @Test
    void nonFinitePercentagesAndNetworkValuesAreRejected() {
        AgentEntity agent = createAgent("finite-01", "ONLINE");
        for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            MetricEntity percent = metricFor(agent.getId(), Instant.now());
            percent.setGpuUsage(value);
            assertThrows(IllegalArgumentException.class, () -> metricService.recordMetric(percent));
            MetricEntity network = metricFor(agent.getId(), Instant.now());
            network.setNetworkInMbps(value);
            assertThrows(IllegalArgumentException.class, () -> metricService.recordMetric(network));
        }
    }

    private AgentEntity createAgent(String name, String status) {
        AgentEntity agent = new AgentEntity();
        agent.setName(name);
        agent.setHostname("server-" + name);
        agent.setIp("10.0.1." + name.substring(name.length() - 2));
        agent.setStatus(status);
        agent.setVersion("0.2.0");
        return agentService.registerAgent(agent);
    }

    private MetricEntity metricFor(Long agentId, Instant collectedAt) {
        MetricEntity metric = new MetricEntity();
        metric.setAgentId(agentId);
        metric.setCpuUsage(42.5);
        metric.setMemoryUsage(68.0);
        metric.setDiskUsage(51.2);
        metric.setGpuUsage(12.0);
        metric.setNetworkInMbps(125.0);
        metric.setNetworkOutMbps(80.0);
        metric.setCollectedAt(collectedAt);
        return metric;
    }
}
