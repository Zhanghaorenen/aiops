package com.aiops.backend.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

import com.aiops.backend.entity.AgentEntity;
import com.aiops.backend.service.AgentService;

@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping
    public List<AgentEntity> listAgents() {
        return agentService.listAgents();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentEntity registerAgent(@RequestBody AgentEntity agent) {
        return agentService.registerAgent(agent);
    }

    @GetMapping("/{id}")
    public AgentEntity getAgentById(@PathVariable Long id) {
        return agentService.getAgentById(id);
    }

    @PutMapping("/{id}")
    public AgentEntity updateAgent(@PathVariable Long id, @RequestBody AgentEntity agent) {
        return agentService.updateAgent(id, agent);
    }

    @PostMapping("/{id}/heartbeat")
    public AgentEntity heartbeat(@PathVariable Long id) {
        return agentService.heartbeat(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAgent(@PathVariable Long id) {
        agentService.deleteAgent(id);
    }
}
