package com.aiops.backend.entity;

import java.time.Instant;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.aiops.backend.persistence.UtcInstantTypeHandler;
import org.apache.ibatis.type.JdbcType;


@TableName(value = "ops_node", autoResultMap = true)
public class NodeEntity {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField(value = "node_key") private String nodeKey;
    @TableField(value = "display_name") private String displayName;
    @TableField(value = "hostname") private String hostname;
    @TableField(value = "ip", updateStrategy = FieldStrategy.ALWAYS) private String ip;
    @TableField(value = "os_name", updateStrategy = FieldStrategy.ALWAYS) private String osName;
    @TableField(value = "os_version", updateStrategy = FieldStrategy.ALWAYS) private String osVersion;
    @TableField(value = "architecture", updateStrategy = FieldStrategy.ALWAYS) private String architecture;
    @TableField(value = "cpu_cores", updateStrategy = FieldStrategy.ALWAYS) private Integer cpuCores;
    @TableField(value = "memory_bytes", updateStrategy = FieldStrategy.ALWAYS) private Long memoryBytes;
    @TableField(value = "status") private String status;
    @TableField(value = "last_seen_at", jdbcType = JdbcType.TIMESTAMP, typeHandler = UtcInstantTypeHandler.class, updateStrategy = FieldStrategy.ALWAYS) private Instant lastSeenAt;
    @TableField(value = "created_at", jdbcType = JdbcType.TIMESTAMP, typeHandler = UtcInstantTypeHandler.class, updateStrategy = FieldStrategy.NEVER) private Instant createdAt;
    @TableField(value = "updated_at", jdbcType = JdbcType.TIMESTAMP, typeHandler = UtcInstantTypeHandler.class) private Instant updatedAt;

    protected NodeEntity() {}

    public NodeEntity(String nodeKey, String displayName, String hostname, String ip, String osName,
            String osVersion, String architecture, Integer cpuCores, Long memoryBytes) {
        this.nodeKey = nodeKey;
        this.displayName = displayName;
        this.hostname = hostname;
        this.ip = ip;
        this.osName = osName;
        this.osVersion = osVersion;
        this.architecture = architecture;
        this.cpuCores = cpuCores;
        this.memoryBytes = memoryBytes;
        this.status = "ONLINE";
        this.lastSeenAt = Instant.now();
        this.createdAt = this.lastSeenAt;
        this.updatedAt = this.lastSeenAt;
    }

    public void markOnline(Instant now) {
        this.status = "ONLINE";
        this.lastSeenAt = now;
        this.updatedAt = now;
    }
    public void markOffline(Instant now) { this.status = "OFFLINE"; this.updatedAt = now; }

    public Long getId() { return id; }
    public String getNodeKey() { return nodeKey; }
    public String getDisplayName() { return displayName; }
    public String getHostname() { return hostname; }
    public String getIp() { return ip; }
    public String getOsName() { return osName; }
    public String getOsVersion() { return osVersion; }
    public String getArchitecture() { return architecture; }
    public Integer getCpuCores() { return cpuCores; }
    public Long getMemoryBytes() { return memoryBytes; }
    public String getStatus() { return status; }
    public Instant getLastSeenAt() { return lastSeenAt; }
}
