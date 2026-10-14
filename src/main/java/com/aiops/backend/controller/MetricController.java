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
}
