package com.aiops.backend.entity;

import java.time.Instant;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aiops.backend.persistence.UtcInstantTypeHandler;
import org.apache.ibatis.type.JdbcType;


@TableName(value = "sys_login_log", autoResultMap = true)
public class LoginLogEntity {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField(value = "username") private String username;
    @TableField(value = "success") private boolean success;
    @TableField(value = "remote_ip", updateStrategy = FieldStrategy.ALWAYS) private String remoteIp;
    @TableField(value = "created_at", jdbcType = JdbcType.TIMESTAMP, typeHandler = UtcInstantTypeHandler.class, updateStrategy = FieldStrategy.NEVER) private Instant createdAt;

    protected LoginLogEntity() {}
    public LoginLogEntity(String username, boolean success, String remoteIp) {
        this.username = username;
        this.success = success;
        this.remoteIp = remoteIp;
        this.createdAt = Instant.now();
    }
    public Long getId() { return id; }
}
