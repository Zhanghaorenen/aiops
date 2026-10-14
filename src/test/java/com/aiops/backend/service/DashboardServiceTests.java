package com.aiops.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.aiops.backend.vo.DashboardSummaryVO;

@SpringBootTest
class DashboardServiceTests {

    @Autowired
    private DashboardService dashboardService;

    @Test
    void summaryAggregatesServerAndAgentStatus() {
        DashboardSummaryVO summary = dashboardService.getSummary();

        assertEquals(0, summary.getTotalServers());
        assertEquals(0, summary.getOnlineServers());
        assertEquals(0, summary.getOfflineServers());
        assertEquals(0, summary.getTotalAgents());
        assertEquals(0, summary.getOnlineAgents());
        assertEquals(0, summary.getOfflineAgents());
        assertEquals(0.0, summary.getAverageCpuUsage(), 0.001);
        assertEquals(0.0, summary.getAverageMemoryUsage(), 0.001);
        assertNotNull(summary.getGeneratedAt());
    }
}
