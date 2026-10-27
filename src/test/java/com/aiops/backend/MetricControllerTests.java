package com.aiops.backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.aiops.backend.entity.AgentEntity;
import com.aiops.backend.entity.UserEntity;
import com.aiops.backend.repository.UserRepository;
import com.aiops.backend.security.JwtService;
import com.aiops.backend.service.AgentService;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MetricControllerTests {
    @Autowired private MockMvc mvc;
    @Autowired private AgentService agents;
    @Autowired private UserRepository users;
    @Autowired private JwtService jwtService;
    private String authorization;
    private Long agentId;

    @BeforeEach
    void prepare() {
        UserEntity account = users.save(new UserEntity("metric-reader", "unused-test-password-hash"));
        authorization = "Bearer " + jwtService.issue(account.getId(), account.getUsername());
        AgentEntity agent = new AgentEntity();
        agent.setName("legacy-agent");
        agent.setHostname("legacy-host");
        agent.setIp("127.0.0.1");
        agentId = agents.registerAgent(agent).getId();
    }

    @Test
    void metricEndpointsReturnStoredCpuMemoryDiskAndGpu() throws Exception {
        mvc.perform(post("/api/metrics").header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"agentId":%d,"cpuUsage":42.5,"memoryUsage":68.0,"diskUsage":51.2,
                                 "gpuUsage":12.0,"networkInMbps":125.0,"networkOutMbps":80.0}
                                """.formatted(agentId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gpuUsage").value(12.0));
        mvc.perform(get(path("/history")).header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].agentId").value(agentId))
                .andExpect(jsonPath("$[0].gpuUsage").value(12.0));
        mvc.perform(get(path("/latest")).header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cpuUsage").value(42.5))
                .andExpect(jsonPath("$.memoryUsage").value(68.0))
                .andExpect(jsonPath("$.diskUsage").value(51.2));
        mvc.perform(get(path("/details")).header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agent.id").value(agentId))
                .andExpect(jsonPath("$.latestMetric.gpuUsage").value(12.0));
        mvc.perform(get(path("/overview")).header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value(agentId))
                .andExpect(jsonPath("$.hostname").value("legacy-host"))
                .andExpect(jsonPath("$.latestMetric.cpuUsage").value(42.5));
    }

    @Test
    void absentSamplesAreExplicitAndUnknownAgentIsNotFound() throws Exception {
        mvc.perform(get(path("/history")).header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(path("/latest")).header("Authorization", authorization))
                .andExpect(status().isNotFound());
        mvc.perform(get(path("/details")).header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.latestMetric").isEmpty());
        for (String suffix : List.of("/latest", "/history", "/details", "/overview")) {
            mvc.perform(get("/api/metrics/agents/" + Long.MAX_VALUE + suffix)
                            .header("Authorization", authorization))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void unfinishedClusterEndpointsReturnNotImplemented() throws Exception {
        for (String suffix : List.of("/overview", "/details")) {
            mvc.perform(get("/api/metrics/clusters/1" + suffix).header("Authorization", authorization))
                    .andExpect(status().isNotImplemented());
        }
    }

    private String path(String suffix) { return "/api/metrics/agents/" + agentId + suffix; }
}
