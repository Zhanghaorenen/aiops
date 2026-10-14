package com.aiops.backend.vo;

import java.time.Instant;

public class DashboardSummaryVO {

    private final long totalServers;
    private final long onlineServers;
    private final long offlineServers;
    private final long totalAgents;
    private final long onlineAgents;
    private final long offlineAgents;
    private final double averageCpuUsage;
    private final double averageMemoryUsage;
    private final Instant generatedAt;

    public DashboardSummaryVO(
            long totalServers,
            long onlineServers,
            long offlineServers,
            long totalAgents,
            long onlineAgents,
            long offlineAgents,
            double averageCpuUsage,
            double averageMemoryUsage,
            Instant generatedAt
    ) {
        this.totalServers = totalServers;
        this.onlineServers = onlineServers;
        this.offlineServers = offlineServers;
        this.totalAgents = totalAgents;
        this.onlineAgents = onlineAgents;
        this.offlineAgents = offlineAgents;
        this.averageCpuUsage = averageCpuUsage;
        this.averageMemoryUsage = averageMemoryUsage;
        this.generatedAt = generatedAt;
    }

    public long getTotalServers() {
        return totalServers;
    }

    public long getOnlineServers() {
        return onlineServers;
    }

    public long getOfflineServers() {
        return offlineServers;
    }

    public long getTotalAgents() {
        return totalAgents;
    }

    public long getOnlineAgents() {
        return onlineAgents;
    }

    public long getOfflineAgents() {
        return offlineAgents;
    }

    public double getAverageCpuUsage() {
        return averageCpuUsage;
    }

    public double getAverageMemoryUsage() {
        return averageMemoryUsage;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }
}
