package com.aiops.backend.service;

import java.util.List;

import com.aiops.backend.entity.MetricEntity;
import com.aiops.backend.entity.AgentEntity;

public interface MetricService {

    MetricEntity recordMetric(MetricEntity metric);

    List<MetricEntity> listRecentMetrics(int limit);

    MetricEntity getLatestMetricByAgentId(Long agentId);
    List<MetricEntity> getMetricHistoryByAgentId(Long agentId);
    AgentDetails getAgentDetails(Long agentId);
    AgentOverview getAgentOverview(Long agentId);

    record AgentDetails(AgentEntity agent, MetricEntity latestMetric) {}
    record AgentOverview(Long agentId, String hostname, String status, MetricEntity latestMetric) {}
}
