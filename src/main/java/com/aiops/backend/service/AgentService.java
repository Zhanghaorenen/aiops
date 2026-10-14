package com.aiops.backend.service;

import java.util.List;

import com.aiops.backend.entity.AgentEntity;

public interface AgentService {

    List<AgentEntity> listAgents();

    AgentEntity registerAgent(AgentEntity agent);

    AgentEntity getAgentById(Long id);

    AgentEntity updateAgent(Long id, AgentEntity agent);

    AgentEntity heartbeat(Long id);

    void deleteAgent(Long id);
}
