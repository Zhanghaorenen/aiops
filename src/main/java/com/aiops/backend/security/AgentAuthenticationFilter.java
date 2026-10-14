package com.aiops.backend.security;

import java.io.IOException;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.aiops.backend.entity.OpsAgentEntity;
import com.aiops.backend.service.AgentAccessService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class AgentAuthenticationFilter extends OncePerRequestFilter {
    private final AgentAccessService agentAccessService;

    public AgentAuthenticationFilter(AgentAccessService agentAccessService) {
        this.agentAccessService = agentAccessService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("/api/agent/heartbeat".equals(request.getRequestURI())
                || "/api/agent/metrics".equals(request.getRequestURI())) {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                OpsAgentEntity agent = agentAccessService.authenticate(header.substring(7));
                if (agent != null) {
                    var principal = new AgentPrincipal(agent.getId(), agent.getNodeId());
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(principal, null,
                                    AuthorityUtils.createAuthorityList("ROLE_AGENT")));
                }
            }
        }
        chain.doFilter(request, response);
    }

    public record AgentPrincipal(Long agentId, Long nodeId) {}
}
