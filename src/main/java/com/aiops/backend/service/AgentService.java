package com.aiops.backend.service;

import java.util.List;

import com.aiops.backend.entity.AgentEntity;

public interface AgentService {
    default AgentEntity collectorHeartbeat(Long id){
        throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.GONE,"旧心跳接口已停用，请使用独立 Agent Credential 调用 /api/agent/heartbeat");
    }

    List<AgentEntity> listAgents();

    AgentEntity registerAgent(AgentEntity agent);

    AgentEntity getAgentById(Long id);

    AgentEntity updateAgent(Long id, AgentEntity agent);

    AgentEntity heartbeat(Long id);

    void deleteAgent(Long id);
}
