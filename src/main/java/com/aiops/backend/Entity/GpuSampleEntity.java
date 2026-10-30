package com.aiops.backend.entity;

import java.math.BigDecimal;
import java.time.Instant;

import org.apache.ibatis.type.JdbcType;

import com.aiops.backend.persistence.UtcInstantTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName(value = "metric_gpu_sample", autoResultMap = true)
public class GpuSampleEntity {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField("snapshot_id")
    private Long snapshotId;
    @TableField("node_id")
    private Long nodeId;
    @TableField("agent_id")
    private Long agentId;
    @TableField("gpu_index")
    private Integer gpuIndex;
    @TableField("gpu_uuid")
    private String gpuUuid;
    @TableField("gpu_name")
    private String gpuName;
    @TableField("memory_total_bytes")
    private Long memoryTotalBytes;
    @TableField("memory_used_bytes")
    private Long memoryUsedBytes;
    @TableField("memory_free_bytes")
    private Long memoryFreeBytes;
    @TableField("utilization_percent")
    private BigDecimal utilizationPercent;
    @TableField("memory_utilization_percent")
    private BigDecimal memoryUtilizationPercent;
    @TableField("temperature_c")
    private BigDecimal temperatureC;
    @TableField("power_usage_w")
    private BigDecimal powerUsageW;
    @TableField("fan_speed_percent")
    private BigDecimal fanSpeedPercent;
    @TableField("clock_speed_mhz")
    private BigDecimal clockSpeedMhz;
    @TableField(value = "collected_at", jdbcType = JdbcType.TIMESTAMP, typeHandler = UtcInstantTypeHandler.class)
    private Instant collectedAt;
    @TableField(value = "received_at", jdbcType = JdbcType.TIMESTAMP, typeHandler = UtcInstantTypeHandler.class)
    private Instant receivedAt;

    protected GpuSampleEntity() {}

    public GpuSampleEntity(Long snapshotId, Long nodeId, Long agentId, Integer gpuIndex,
            String gpuUuid, String gpuName, Long memoryTotalBytes, Long memoryUsedBytes,
            Long memoryFreeBytes, BigDecimal utilizationPercent, BigDecimal memoryUtilizationPercent,
            BigDecimal temperatureC, BigDecimal powerUsageW, BigDecimal fanSpeedPercent,
            BigDecimal clockSpeedMhz, Instant collectedAt, Instant receivedAt) {
        this.snapshotId = snapshotId;
        this.nodeId = nodeId;
        this.agentId = agentId;
        this.gpuIndex = gpuIndex;
        this.gpuUuid = gpuUuid;
        this.gpuName = gpuName;
        this.memoryTotalBytes = memoryTotalBytes;
        this.memoryUsedBytes = memoryUsedBytes;
        this.memoryFreeBytes = memoryFreeBytes;
        this.utilizationPercent = utilizationPercent;
        this.memoryUtilizationPercent = memoryUtilizationPercent;
        this.temperatureC = temperatureC;
        this.powerUsageW = powerUsageW;
        this.fanSpeedPercent = fanSpeedPercent;
        this.clockSpeedMhz = clockSpeedMhz;
        this.collectedAt = collectedAt;
        this.receivedAt = receivedAt;
    }

    public Long getId() { return id; }
    public Long getSnapshotId() { return snapshotId; }
    public Long getNodeId() { return nodeId; }
    public Long getAgentId() { return agentId; }
    public Integer getGpuIndex() { return gpuIndex; }
    public String getGpuUuid() { return gpuUuid; }
    public String getGpuName() { return gpuName; }
    public Long getMemoryTotalBytes() { return memoryTotalBytes; }
    public Long getMemoryUsedBytes() { return memoryUsedBytes; }
    public Long getMemoryFreeBytes() { return memoryFreeBytes; }
    public BigDecimal getUtilizationPercent() { return utilizationPercent; }
    public BigDecimal getMemoryUtilizationPercent() { return memoryUtilizationPercent; }
    public BigDecimal getTemperatureC() { return temperatureC; }
    public BigDecimal getPowerUsageW() { return powerUsageW; }
    public BigDecimal getFanSpeedPercent() { return fanSpeedPercent; }
    public BigDecimal getClockSpeedMhz() { return clockSpeedMhz; }
    public Instant getCollectedAt() { return collectedAt; }
    public Instant getReceivedAt() { return receivedAt; }
}
