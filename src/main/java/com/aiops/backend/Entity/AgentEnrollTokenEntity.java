package com.aiops.backend.entity;

import java.time.Instant;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aiops.backend.persistence.UtcInstantTypeHandler;
import org.apache.ibatis.type.JdbcType;

@TableName(value = "ops_agent_enroll_token", autoResultMap = true)
public class AgentEnrollTokenEntity {
    @TableId(value = "id", type = IdType.AUTO) private Long id;
    @TableField(value = "token_hash") private String tokenHash;
    @TableField(value = "expires_at", jdbcType = JdbcType.TIMESTAMP, typeHandler = UtcInstantTypeHandler.class) private Instant expiresAt;
    @TableField(value = "used_at", jdbcType = JdbcType.TIMESTAMP, typeHandler = UtcInstantTypeHandler.class, updateStrategy = FieldStrategy.ALWAYS) private Instant usedAt;
    @TableField(value = "created_at", jdbcType = JdbcType.TIMESTAMP, typeHandler = UtcInstantTypeHandler.class, updateStrategy = FieldStrategy.NEVER) private Instant createdAt;
    protected AgentEnrollTokenEntity() {}
    public AgentEnrollTokenEntity(String tokenHash, Instant expiresAt, Instant createdAt) {
        this.tokenHash = tokenHash; this.expiresAt = expiresAt; this.createdAt = createdAt;
    }
    public boolean isUsableAt(Instant now) { return usedAt == null && expiresAt.isAfter(now); }
    public void markUsed(Instant now) { this.usedAt = now; }
    public Long getId() { return id; }
}
