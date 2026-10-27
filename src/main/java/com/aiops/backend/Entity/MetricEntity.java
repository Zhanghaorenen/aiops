package com.aiops.backend.entity;

import java.time.Instant;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aiops.backend.persistence.UtcInstantTypeHandler;
import org.apache.ibatis.type.JdbcType;


@TableName(value = "metrics", autoResultMap = true)
public class MetricEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "agent_id") private Long agentId;

    @TableField(value = "hostname") private String hostname;

    @TableField(value = "cpu_usage") private Double cpuUsage;

    @TableField(value = "memory_usage") private Double memoryUsage;

    @TableField(value = "disk_usage") private Double diskUsage;

    @TableField(value = "gpu_usage", updateStrategy = FieldStrategy.ALWAYS)
    private Double gpuUsage;

    @TableField(value = "network_in_mbps") private Double networkInMbps;

    @TableField(value = "network_out_mbps") private Double networkOutMbps;

    @TableField(value = "collected_at", jdbcType = JdbcType.TIMESTAMP, typeHandler = UtcInstantTypeHandler.class) private Instant collectedAt;

    @TableField(value = "received_at", jdbcType = JdbcType.TIMESTAMP, typeHandler = UtcInstantTypeHandler.class) private Instant receivedAt;

    public Long getId() {
        return id;
    }

    public Long getAgentId() {
        return agentId;
    }

    public String getHostname() {
        return hostname;
    }

    public Double getCpuUsage() {
        return cpuUsage;
    }

    public Double getMemoryUsage() {
        return memoryUsage;
    }

    public Double getDiskUsage() {
        return diskUsage;
    }

    public Double getGpuUsage() {
        return gpuUsage;
    }

    public Double getNetworkInMbps() {
        return networkInMbps;
    }

    public Double getNetworkOutMbps() {
        return networkOutMbps;
    }

    public Instant getCollectedAt() {
        return collectedAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public void setAgentId(Long agentId) {
        this.agentId = agentId;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public void setCpuUsage(Double cpuUsage) {
        this.cpuUsage = cpuUsage;
    }

    public void setMemoryUsage(Double memoryUsage) {
        this.memoryUsage = memoryUsage;
    }

    public void setDiskUsage(Double diskUsage) {
        this.diskUsage = diskUsage;
    }

    public void setGpuUsage(Double gpuUsage) {
        this.gpuUsage = gpuUsage;
    }

    public void setNetworkInMbps(Double networkInMbps) {
        this.networkInMbps = networkInMbps;
    }

    public void setNetworkOutMbps(Double networkOutMbps) {
        this.networkOutMbps = networkOutMbps;
    }

    public void setCollectedAt(Instant collectedAt) {
        this.collectedAt = collectedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }
}
