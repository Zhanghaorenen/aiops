package com.aiops.backend.entity;

import java.time.Instant;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aiops.backend.persistence.UtcInstantTypeHandler;
import org.apache.ibatis.type.JdbcType;


@TableName(value = "sys_user", autoResultMap = true)
public class UserEntity {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "username") private String username;

    @TableField(value = "password_hash") private String passwordHash;

    @TableField(value = "status") private String status;

    @TableField(value = "created_at", jdbcType = JdbcType.TIMESTAMP, typeHandler = UtcInstantTypeHandler.class, updateStrategy = FieldStrategy.NEVER) private Instant createdAt;

    @TableField(value = "updated_at", jdbcType = JdbcType.TIMESTAMP, typeHandler = UtcInstantTypeHandler.class) private Instant updatedAt;
    // Email persistence requires a matching Flyway migration before it can be enabled.
    @TableField(exist = false)
    private String email;
    protected UserEntity() {}

    public UserEntity(String username, String passwordHash) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.email = null;
        this.status = "ACTIVE";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; this.updatedAt = Instant.now(); }
}
