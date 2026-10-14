package com.aiops.backend.entity;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ops_agent")
public class OpsAgentEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "node_id", nullable = false) private Long nodeId;
    @Column(name = "agent_key", nullable = false, unique = true, length = 100) private String agentKey;
    @Column(name = "credential_hash", nullable = false, unique = true, length = 64) private String credentialHash;
    @Column(nullable = false, length = 32) private String version;
    @Column(nullable = false, length = 16) private String status;
    @Column(name = "last_heartbeat_at") private Instant lastHeartbeatAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

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
