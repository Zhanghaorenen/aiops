package com.aiops.backend.entity;

import java.time.Instant;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aiops.backend.persistence.UtcInstantTypeHandler;
import org.apache.ibatis.type.JdbcType;

@TableName(value = "ops_agent", autoResultMap = true)
public class OpsAgentEntity {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    @TableField(value = "node_id") private Long nodeId;
    @TableField(value = "agent_key") private String agentKey;
    @TableField(value = "credential_hash") private String credentialHash;
    @TableField(value = "version") private String version;
    @TableField(value = "status") private String status;
    @TableField(value = "last_heartbeat_at", jdbcType = JdbcType.TIMESTAMP, typeHandler = UtcInstantTypeHandler.class, updateStrategy = FieldStrategy.ALWAYS) private Instant lastHeartbeatAt;
    @TableField(value = "created_at", jdbcType = JdbcType.TIMESTAMP, typeHandler = UtcInstantTypeHandler.class, updateStrategy = FieldStrategy.NEVER) private Instant createdAt;
    @TableField(value = "updated_at", jdbcType = JdbcType.TIMESTAMP, typeHandler = UtcInstantTypeHandler.class) private Instant updatedAt;

    protected OpsAgentEntity() {}
    public OpsAgentEntity(Long nodeId, String agentKey, String credentialHash, String version, Instant now) {
        this.nodeId = nodeId; this.agentKey = agentKey; this.credentialHash = credentialHash;
        this.version = version; this.status = "ONLINE"; this.lastHeartbeatAt = now;
        this.createdAt = now; this.updatedAt = now;
    }
    public void heartbeat(Instant now, String version) {
        this.status = "ONLINE"; this.lastHeartbeatAt = now; this.updatedAt = now;
        if (version != null && !version.isBlank()) this.version = version.trim();
    }
    public void markOffline(Instant now) { this.status = "OFFLINE"; this.updatedAt = now; }
    public void rotateCredential(String newHash, String newVersion, Instant now) {
        this.credentialHash = newHash;
        this.version = newVersion;
        this.status = "ONLINE";
        this.lastHeartbeatAt = now;
        this.updatedAt = now;
    }
    public Long getId() { return id; }
    public Long getNodeId() { return nodeId; }
    public String getAgentKey() { return agentKey; }
    public String getCredentialHash() { return credentialHash; }
    public String getStatus() { return status; }
}
