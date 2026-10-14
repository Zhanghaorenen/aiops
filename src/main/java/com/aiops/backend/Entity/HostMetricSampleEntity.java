package com.aiops.backend.entity;

import java.time.Instant;
import java.math.BigDecimal;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "metric_host_sample")
public class HostMetricSampleEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "node_id", nullable = false) private Long nodeId;
    @Column(name = "agent_id", nullable = false) private Long agentId;
    @Column(name = "cpu_percent", nullable = false, precision = 5, scale = 2) private BigDecimal cpuPercent;
    @Column(name = "memory_total_bytes", nullable = false) private Long memoryTotalBytes;
    @Column(name = "memory_used_bytes", nullable = false) private Long memoryUsedBytes;
    @Column(name = "memory_percent", nullable = false, precision = 5, scale = 2) private BigDecimal memoryPercent;
    @Column(name = "disk_total_bytes", nullable = false) private Long diskTotalBytes;
    @Column(name = "disk_used_bytes", nullable = false) private Long diskUsedBytes;
    @Column(name = "disk_percent", nullable = false, precision = 5, scale = 2) private BigDecimal diskPercent;
    @Column(name = "collected_at", nullable = false) private Instant collectedAt;
    @Column(name = "received_at", nullable = false) private Instant receivedAt;
    protected HostMetricSampleEntity() {}
    public HostMetricSampleEntity(Long nodeId, Long agentId, Double cpuPercent, Long memoryTotalBytes,
            Long memoryUsedBytes, Double memoryPercent, Long diskTotalBytes, Long diskUsedBytes,
            Double diskPercent, Instant collectedAt, Instant receivedAt) {
        this.nodeId = nodeId; this.agentId = agentId; this.cpuPercent = BigDecimal.valueOf(cpuPercent);
        this.memoryTotalBytes = memoryTotalBytes; this.memoryUsedBytes = memoryUsedBytes;
        this.memoryPercent = BigDecimal.valueOf(memoryPercent); this.diskTotalBytes = diskTotalBytes;
        this.diskUsedBytes = diskUsedBytes; this.diskPercent = BigDecimal.valueOf(diskPercent);
        this.collectedAt = collectedAt; this.receivedAt = receivedAt;
    }
    public Long getId() { return id; }
    public Long getNodeId() { return nodeId; }
    public BigDecimal getCpuPercent() { return cpuPercent; }
    public Long getMemoryTotalBytes() { return memoryTotalBytes; }
    public Long getMemoryUsedBytes() { return memoryUsedBytes; }
    public BigDecimal getMemoryPercent() { return memoryPercent; }
    public Long getDiskTotalBytes() { return diskTotalBytes; }
    public Long getDiskUsedBytes() { return diskUsedBytes; }
    public BigDecimal getDiskPercent() { return diskPercent; }
    public Instant getCollectedAt() { return collectedAt; }
    public Instant getReceivedAt() { return receivedAt; }
}
