package com.aiops.backend.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ops_node")
public class NodeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "node_key", nullable = false, unique = true, length = 100) private String nodeKey;
    @Column(name = "display_name", nullable = false, length = 160) private String displayName;
    @Column(nullable = false, length = 160) private String hostname;
    @Column(length = 64) private String ip;
    @Column(name = "os_name", length = 120) private String osName;
    @Column(name = "os_version", length = 160) private String osVersion;
    @Column(length = 80) private String architecture;
    @Column(name = "cpu_cores") private Integer cpuCores;
    @Column(name = "memory_bytes") private Long memoryBytes;
    @Column(nullable = false, length = 16) private String status;
    @Column(name = "last_seen_at") private Instant lastSeenAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

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
