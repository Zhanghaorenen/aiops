package com.aiops.backend.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.aiops.backend.security.AgentAuthenticationFilter.AgentPrincipal;
import com.aiops.backend.service.AgentAccessService;
import com.aiops.backend.service.AgentAccessService.EnrollmentRequest;
import com.aiops.backend.service.AgentAccessService.MetricRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/agent")
public class AgentAccessController {
    private final AgentAccessService agentAccessService;
    public AgentAccessController(AgentAccessService agentAccessService) { this.agentAccessService = agentAccessService; }
    //声明一个新的EnrollmentToken的记录类型，包含一个token字段
    @PostMapping("/enrollment-tokens")
    public AgentAccessService.EnrollmentToken issueToken() { return agentAccessService.issueEnrollmentToken(); }

    @PostMapping("/enroll")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentAccessService.EnrollmentResult enroll(@Valid @RequestBody EnrollmentRequest request) {
        return agentAccessService.enroll(request);
    }

    @PostMapping("/heartbeat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void heartbeat(Authentication authentication, @RequestBody(required = false) HeartbeatRequest request) {
        AgentPrincipal principal = (AgentPrincipal) authentication.getPrincipal();
        agentAccessService.heartbeat(principal.agentId(), request == null ? null : request.agentVersion());
    }

    @PostMapping("/metrics")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void metric(Authentication authentication, @Valid @RequestBody MetricRequest request) {
        AgentPrincipal principal = (AgentPrincipal) authentication.getPrincipal();
        agentAccessService.recordMetric(principal.agentId(), request);
    }

    public record HeartbeatRequest(String agentVersion) {}
}
