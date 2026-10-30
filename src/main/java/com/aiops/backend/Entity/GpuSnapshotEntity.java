package com.aiops.backend.entity;

import java.time.Instant;

import org.apache.ibatis.type.JdbcType;

import com.aiops.backend.persistence.UtcInstantTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName(value = "metric_gpu_snapshot", autoResultMap = true)
public class GpuSnapshotEntity {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField("node_id")
    private Long nodeId;
    @TableField("agent_id")
    private Long agentId;
    @TableField("collection_status")
    private String collectionStatus;
    @TableField("error_message")
    private String errorMessage;
    @TableField(value = "collected_at", jdbcType = JdbcType.TIMESTAMP, typeHandler = UtcInstantTypeHandler.class)
    private Instant collectedAt;
    @TableField(value = "received_at", jdbcType = JdbcType.TIMESTAMP, typeHandler = UtcInstantTypeHandler.class)
    private Instant receivedAt;

    protected GpuSnapshotEntity() {}

    public GpuSnapshotEntity(Long nodeId, Long agentId, String collectionStatus,
            String errorMessage, Instant collectedAt, Instant receivedAt) {
        this.nodeId = nodeId;
        this.agentId = agentId;
        this.collectionStatus = collectionStatus;
        this.errorMessage = errorMessage;
        this.collectedAt = collectedAt;
        this.receivedAt = receivedAt;
    }

    public Long getId() { return id; }
    public Long getNodeId() { return nodeId; }
    public Long getAgentId() { return agentId; }
    public String getCollectionStatus() { return collectionStatus; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCollectedAt() { return collectedAt; }
    public Instant getReceivedAt() { return receivedAt; }
}
