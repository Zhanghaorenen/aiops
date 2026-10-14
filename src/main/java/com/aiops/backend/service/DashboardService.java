package com.aiops.backend.service;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import com.aiops.backend.repository.OpsAgentRepository;
import com.aiops.backend.vo.DashboardSummaryVO;
import com.aiops.backend.vo.ServerVO;

@Service
public class DashboardService {

    private final ServerService serverService;
    private final OpsAgentRepository agentRepository;

    public DashboardService(ServerService serverService, OpsAgentRepository agentRepository) {
        this.serverService = serverService;
        this.agentRepository = agentRepository;
    }

    public DashboardSummaryVO getSummary() {
        List<ServerVO> servers = serverService.listServers();
        long onlineServers = servers.stream().filter(server -> isStatus(server.getStatus(), "ONLINE")).count();
        long totalAgents = agentRepository.count();
        long onlineAgents = agentRepository.countByStatus("ONLINE");

        return new DashboardSummaryVO(
                servers.size(),
                onlineServers,
                servers.size() - onlineServers,
                totalAgents,
                onlineAgents,
                totalAgents - onlineAgents,
                averageCpuUsage(servers),
                averageMemoryUsage(servers),
                Instant.now()
        );
    }

    private boolean isStatus(String actual, String expected) {
        return actual != null && actual.equalsIgnoreCase(expected);
    }

    private double averageCpuUsage(List<ServerVO> servers) {
        return servers.stream()
                .map(ServerVO::getCpuUsage).filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
    }

    private double averageMemoryUsage(List<ServerVO> servers) {
        return servers.stream()
                .map(ServerVO::getMemoryUsage).filter(java.util.Objects::nonNull).mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
    }
}
