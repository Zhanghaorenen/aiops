package com.aiops.backend.service;

import java.util.List;

import com.aiops.backend.entity.MetricEntity;

public interface MetricService {

    MetricEntity recordMetric(MetricEntity metric);

    List<MetricEntity> listRecentMetrics(int limit);

    MetricEntity getLatestMetricByAgentId(Long agentId);
}
