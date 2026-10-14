package com.aiops.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.aiops.backend.entity.AgentEntity;
import com.aiops.backend.exception.ResourceNotFoundException;

@SpringBootTest
@Transactional
class AgentServiceImplTests {

    @Autowired
    private AgentService service;

    @Test
    void registerAgentAssignsIdAndDefaults() {
        AgentEntity request = new AgentEntity();
        request.setName("agent-test-01");
        request.setIp("10.0.0.11");

        AgentEntity saved = service.registerAgent(request);

        assertNotNull(saved.getId());
        assertEquals("agent-test-01", saved.getName());
        assertEquals("agent-test-01", saved.getHostname());
        assertEquals("ONLINE", saved.getStatus());
        assertEquals("unknown", saved.getVersion());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getLastHeartbeatAt());
    }

    @Test
    void updateAgentPreservesIdentityAndCreatedAt() {
        AgentEntity request = new AgentEntity();
        request.setName("agent-update-01");
        request.setIp("10.0.0.12");
        AgentEntity saved = service.registerAgent(request);

        AgentEntity update = new AgentEntity();
        update.setName("agent-update-02");
        update.setHostname("server-update-02");
        update.setIp("10.0.0.13");
        update.setStatus("MAINTENANCE");
        update.setVersion("0.2.0");

        AgentEntity updated = service.updateAgent(saved.getId(), update);

        assertEquals(saved.getId(), updated.getId());
        assertEquals(saved.getCreatedAt(), updated.getCreatedAt());
        assertEquals("agent-update-02", updated.getName());
        assertEquals("server-update-02", updated.getHostname());
        assertEquals("MAINTENANCE", updated.getStatus());
        assertEquals("0.2.0", updated.getVersion());
    }

    @Test
    void heartbeatMarksAgentOnline() {
        AgentEntity request = new AgentEntity();
        request.setName("agent-heartbeat-01");
        request.setIp("10.0.0.14");
        request.setStatus("OFFLINE");
        AgentEntity saved = service.registerAgent(request);

        AgentEntity heartbeat = service.heartbeat(saved.getId());

        assertEquals("ONLINE", heartbeat.getStatus());
        assertTrue(!heartbeat.getLastHeartbeatAt().isBefore(saved.getLastHeartbeatAt()));
    }

    @Test
    void deleteAgentRemovesIt() {
        AgentEntity request = new AgentEntity();
        request.setName("agent-delete-01");
        request.setIp("10.0.0.15");
        AgentEntity saved = service.registerAgent(request);

        service.deleteAgent(saved.getId());

        assertThrows(ResourceNotFoundException.class, () -> service.getAgentById(saved.getId()));
    }

    @Test
    void missingAgentThrowsNotFound() {
        assertThrows(ResourceNotFoundException.class, () -> service.getAgentById(999L));
    }
}
