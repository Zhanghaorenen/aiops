package com.aiops.backend.entity;

import java.time.Instant;

import org.apache.ibatis.type.JdbcType;

import com.aiops.backend.persistence.UtcInstantTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName(value = "metric_gpu_process_sample", autoResultMap = true)
public class GpuProcessSampleEntity {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField("snapshot_id")
    private Long snapshotId;
    @TableField("node_id")
    private Long nodeId;
    @TableField("gpu_index")
    private Integer gpuIndex;
    @TableField("gpu_uuid")
    private String gpuUuid;
    @TableField("pid")
    private Long pid;
    @TableField("process_name")
    private String processName;
    @TableField("username")
    private String username;
    @TableField("gpu_memory_usage_bytes")
    private Long gpuMemoryUsageBytes;
    @TableField("task_type")
    private String taskType;
    @TableField(value = "collected_at", jdbcType = JdbcType.TIMESTAMP, typeHandler = UtcInstantTypeHandler.class)
    private Instant collectedAt;
    @TableField(value = "received_at", jdbcType = JdbcType.TIMESTAMP, typeHandler = UtcInstantTypeHandler.class)
    private Instant receivedAt;

    protected GpuProcessSampleEntity() {}

    public GpuProcessSampleEntity(Long snapshotId, Long nodeId, Integer gpuIndex, String gpuUuid,
            Long pid, String processName, String username, Long gpuMemoryUsageBytes,
            String taskType, Instant collectedAt, Instant receivedAt) {
        this.snapshotId = snapshotId;
        this.nodeId = nodeId;
        this.gpuIndex = gpuIndex;
        this.gpuUuid = gpuUuid;
        this.pid = pid;
        this.processName = processName;
        this.username = username;
        this.gpuMemoryUsageBytes = gpuMemoryUsageBytes;
        this.taskType = taskType;
        this.collectedAt = collectedAt;
        this.receivedAt = receivedAt;
    }

    public Long getId() { return id; }
    public Long getSnapshotId() { return snapshotId; }
    public Long getNodeId() { return nodeId; }
    public Integer getGpuIndex() { return gpuIndex; }
    public String getGpuUuid() { return gpuUuid; }
    public Long getPid() { return pid; }
    public String getProcessName() { return processName; }
    public String getUsername() { return username; }
    public Long getGpuMemoryUsageBytes() { return gpuMemoryUsageBytes; }
    public String getTaskType() { return taskType; }
    public Instant getCollectedAt() { return collectedAt; }
    public Instant getReceivedAt() { return receivedAt; }
}
