package com.aiops.backend.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.aiops.backend.entity.MetricEntity;
import com.aiops.backend.service.MetricService;

@RestController
@RequestMapping("/api/metrics")
public class MetricController {

    private final MetricService metricService;

    public MetricController(MetricService metricService) {
        this.metricService = metricService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MetricEntity recordMetric(@RequestBody MetricEntity metric) {
        return metricService.recordMetric(metric);
    }

    @GetMapping("/recent")
    public List<MetricEntity> listRecentMetrics(@RequestParam(defaultValue = "20") int limit) {
        return metricService.listRecentMetrics(limit);
    }

    @GetMapping("/agents/{agentId}/latest")
    public MetricEntity getLatestMetricByAgentId(@PathVariable Long agentId) {
        return metricService.getLatestMetricByAgentId(agentId);
    }
    @GetMapping("/agents/{agentId}/history")
    public List<MetricEntity> getMetricHistoryByAgentId(@PathVariable Long agentId) {
        return metricService.getMetricHistoryByAgentId(agentId);
    }
    /**
     * 查询“服务器列表、服务器详细信息、GPU进程信息、指标历史数据，服务器概览、集群概览
     */
    @GetMapping("/agents/{agentId}/details")
    public MetricEntity getAgentDetails(@PathVariable Long agentId) {
        // 调用 MetricService 的 getAgentDetails 方法获取服务器详细信息
        AgentDetails agentDetails = metricService.getAgentDetails(agentId);
        // 将 AgentDetails 转换为 MetricEntity
        MetricEntity metricEntity = new MetricEntity();
       
        return metricEntity;
    }
    @GetMapping("/agents/{agentId}/overview")
    public MetricEntity getAgentOverview(@PathVariable Long agentId) {
        AgentOverview agentOverview = metricService.getAgentOverview(agentId);
        
        return agentOverview
    }
    @GetMapping("/clusters/{clusterId}/overview")
    public ClusterOverview getClusterOverview(@PathVariable Long clusterId) {
        ClusterOverview clusterOverview = metricService.getClusterOverview(clusterId);
        return clusterOverview;
    }
    @GetMapping("/clusters/{clusterId}/details")
    public ClusterDetails getClusterDetails(@PathVariable Long clusterId) {
        ClusterDetails clusterDetails = metricService.getClusterDetails(clusterId);
        return clusterDetails;
    }
    
}

