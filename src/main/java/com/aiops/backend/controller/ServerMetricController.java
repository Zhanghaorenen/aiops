package com.aiops.backend.controller;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.aiops.backend.entity.HostMetricSampleEntity;
import com.aiops.backend.repository.HostMetricSampleRepository;
import com.aiops.backend.repository.NodeRepository;
import org.springframework.data.domain.PageRequest;

@RestController
@RequestMapping("/api/servers/{nodeId}/metrics")
public class ServerMetricController {
    private final NodeRepository nodes;
    private final HostMetricSampleRepository metrics;

    public ServerMetricController(NodeRepository nodes, HostMetricSampleRepository metrics) {
        this.nodes = nodes; this.metrics = metrics;
    }

    @GetMapping("/latest")
    public HostMetricSampleEntity latest(@PathVariable Long nodeId) {
        ensureNode(nodeId);
        return metrics.findTopByNodeIdOrderByCollectedAtDescIdDesc(nodeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "暂无监控数据"));
    }

    @GetMapping
    public List<HostMetricSampleEntity> history(@PathVariable Long nodeId,
            @RequestParam Instant from, @RequestParam Instant to,
            @RequestParam(defaultValue = "500") int limit) {
        ensureNode(nodeId);
        if (from.isAfter(to) || to.isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "时间范围无效");
        }
        return metrics.findAllByNodeIdAndCollectedAtBetweenOrderByCollectedAtAscIdAsc(
                nodeId, from, to, PageRequest.of(0, Math.min(Math.max(limit, 1), 1000)));
    }

    private void ensureNode(Long nodeId) {
        if (!nodes.existsById(nodeId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "服务器不存在");
    }
}
