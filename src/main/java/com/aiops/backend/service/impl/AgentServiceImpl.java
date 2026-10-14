package com.aiops.backend.service.impl;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aiops.backend.entity.AgentEntity;
import com.aiops.backend.exception.ResourceNotFoundException;
import com.aiops.backend.repository.AgentRepository;
import com.aiops.backend.service.AgentService;

@Service
@Transactional
public class AgentServiceImpl implements AgentService {

    private final AgentRepository agentRepository;

    public AgentServiceImpl(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentEntity> listAgents() {
        return agentRepository.findAllByOrderByIdAsc();
    }

    @Override
    public AgentEntity registerAgent(AgentEntity agent) {
        Instant now = Instant.now();

        AgentEntity saved = new AgentEntity(
                null,
                requireText(agent.getName(), "name"),
                blankToDefault(agent.getHostname(), agent.getName()),
                requireText(agent.getIp(), "ip"),
                blankToDefault(agent.getStatus(), "ONLINE"),
                blankToDefault(agent.getVersion(), "unknown"),
                now,
                now,
                now
        );
        return agentRepository.save(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public AgentEntity getAgentById(Long id) {
        return agentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Agent not found: " + id));
    }

    @Override
    public AgentEntity updateAgent(Long id, AgentEntity agent) {
        AgentEntity current = getAgentById(id);
        Instant now = Instant.now();

        AgentEntity updated = new AgentEntity(
                id,
                requireText(agent.getName(), "name"),
                blankToDefault(agent.getHostname(), current.getHostname()),
                requireText(agent.getIp(), "ip"),
                blankToDefault(agent.getStatus(), current.getStatus()),
                blankToDefault(agent.getVersion(), current.getVersion()),
                current.getLastHeartbeatAt(),
                current.getCreatedAt(),
                now
        );
        return agentRepository.save(updated);
    }

    @Override
    public AgentEntity heartbeat(Long id) {
        AgentEntity current = getAgentById(id);
        Instant now = Instant.now();

        AgentEntity updated = new AgentEntity(
                id,
                current.getName(),
                current.getHostname(),
                current.getIp(),
                "ONLINE",
                current.getVersion(),
                now,
                current.getCreatedAt(),
                now
        );
        return agentRepository.save(updated);
    }

    @Override
    public void deleteAgent(Long id) {
        if (!agentRepository.existsById(id)) {
            throw new ResourceNotFoundException("Agent not found: " + id);
        }
        agentRepository.deleteById(id);
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }

    private String blankToDefault(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }
}
