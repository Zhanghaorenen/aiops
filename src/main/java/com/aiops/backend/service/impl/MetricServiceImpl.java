package com.aiops.backend.service.impl;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aiops.backend.entity.AgentEntity;
import com.aiops.backend.entity.MetricEntity;
import com.aiops.backend.exception.ResourceNotFoundException;
import com.aiops.backend.repository.MetricRepository;
import com.aiops.backend.service.AgentService;
import com.aiops.backend.service.MetricService;

@Service
@Transactional
public class MetricServiceImpl implements MetricService {

    private static final int MAX_RECENT_LIMIT = 200;

    private final MetricRepository metricRepository;
    private final AgentService agentService;

    public MetricServiceImpl(MetricRepository metricRepository, AgentService agentService) {
        this.metricRepository = metricRepository;
        this.agentService = agentService;
    }

    @Override
    public MetricEntity recordMetric(MetricEntity metric) {
        AgentEntity agent = agentService.getAgentById(requireAgentId(metric.getAgentId()));
        Instant now = Instant.now();

        MetricEntity saved = new MetricEntity();
        saved.setAgentId(agent.getId());
        saved.setHostname(blankToDefault(metric.getHostname(), agent.getHostname()));
        saved.setCpuUsage(requirePercent(metric.getCpuUsage(), "cpuUsage"));
        saved.setMemoryUsage(requirePercent(metric.getMemoryUsage(), "memoryUsage"));
        saved.setDiskUsage(requirePercent(metric.getDiskUsage(), "diskUsage"));
        saved.setGpuUsage(optionalPercent(metric.getGpuUsage(), "gpuUsage"));
        saved.setNetworkInMbps(requireNonNegative(metric.getNetworkInMbps(), "networkInMbps"));
        saved.setNetworkOutMbps(requireNonNegative(metric.getNetworkOutMbps(), "networkOutMbps"));
        saved.setCollectedAt(metric.getCollectedAt() == null ? now : metric.getCollectedAt());
        saved.setReceivedAt(now);

        agentService.heartbeat(agent.getId());
        return metricRepository.save(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MetricEntity> listRecentMetrics(int limit) {
        int pageSize = Math.min(Math.max(limit, 1), MAX_RECENT_LIMIT);
        return metricRepository.findAllByOrderByCollectedAtDescIdDesc(PageRequest.of(0, pageSize));
    }

    @Override
    @Transactional(readOnly = true)
    public MetricEntity getLatestMetricByAgentId(Long agentId) {
        return metricRepository.findTopByAgentIdOrderByCollectedAtDescIdDesc(requireAgentId(agentId))
                .orElseThrow(() -> new ResourceNotFoundException("Metric not found for agent: " + agentId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<MetricEntity> getMetricHistoryByAgentId(Long agentId) {
        agentService.getAgentById(requireAgentId(agentId));
        return metricRepository.findAllByAgentIdOrderByCollectedAtDescIdDesc(
                agentId, PageRequest.of(0, MAX_RECENT_LIMIT));
    }

    @Override
    @Transactional(readOnly = true)
    public AgentDetails getAgentDetails(Long agentId) {
        AgentEntity agent = agentService.getAgentById(requireAgentId(agentId));
        return new AgentDetails(agent, metricRepository
                .findTopByAgentIdOrderByCollectedAtDescIdDesc(agentId).orElse(null));
    }

    @Override
    @Transactional(readOnly = true)
    public AgentOverview getAgentOverview(Long agentId) {
        AgentDetails details = getAgentDetails(agentId);
        return new AgentOverview(details.agent().getId(), details.agent().getHostname(),
                details.agent().getStatus(), details.latestMetric());
    }

    private Long requireAgentId(Long agentId) {
        if (agentId == null || agentId <= 0) {
            throw new IllegalArgumentException("agentId must be positive");
        }
        return agentId;
    }

    private Double requirePercent(Double value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        if (!Double.isFinite(value) || value < 0.0 || value > 100.0) {
            throw new IllegalArgumentException(fieldName + " must be between 0 and 100");
        }
        return value;
    }

    private Double optionalPercent(Double value, String fieldName) {
        if (value == null) {
            return null;
        }
        return requirePercent(value, fieldName);
    }

    private Double requireNonNegative(Double value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(fieldName + " must be greater than or equal to 0");
        }
        return value;
    }

    private String blankToDefault(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }
}
