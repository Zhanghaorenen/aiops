package com.aiops.backend.entity;

import java.time.Instant;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aiops.backend.persistence.UtcInstantTypeHandler;
import org.apache.ibatis.type.JdbcType;
import java.math.BigDecimal;

@TableName(value = "metric_host_sample", autoResultMap = true)
public class HostMetricSampleEntity {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    @TableField(value = "node_id") private Long nodeId;
    @TableField(value = "agent_id") private Long agentId;
    @TableField(value = "cpu_percent") private BigDecimal cpuPercent;
    @TableField(value = "memory_total_bytes") private Long memoryTotalBytes;
    @TableField(value = "memory_used_bytes") private Long memoryUsedBytes;
    @TableField(value = "memory_percent") private BigDecimal memoryPercent;
    @TableField(value = "disk_total_bytes") private Long diskTotalBytes;
    @TableField(value = "disk_used_bytes") private Long diskUsedBytes;
    @TableField(value = "disk_percent") private BigDecimal diskPercent;
    @TableField(value = "collected_at", jdbcType = JdbcType.TIMESTAMP, typeHandler = UtcInstantTypeHandler.class) private Instant collectedAt;
    @TableField(value = "received_at", jdbcType = JdbcType.TIMESTAMP, typeHandler = UtcInstantTypeHandler.class) private Instant receivedAt;
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
