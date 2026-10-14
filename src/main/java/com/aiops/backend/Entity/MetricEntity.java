package com.aiops.backend.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "metrics")
public class MetricEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long agentId;

    @Column(nullable = false, length = 100)
    private String hostname;

    @Column(nullable = false)
    private Double cpuUsage;

    @Column(nullable = false)
    private Double memoryUsage;

    @Column(nullable = false)
    private Double diskUsage;

    private Double gpuUsage;

    @Column(nullable = false)
    private Double networkInMbps;

    @Column(nullable = false)
    private Double networkOutMbps;

    @Column(nullable = false)
    private Instant collectedAt;

    @Column(nullable = false)
    private Instant receivedAt;

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
