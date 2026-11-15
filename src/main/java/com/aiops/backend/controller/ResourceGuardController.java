package com.aiops.backend.controller;

import com.aiops.backend.security.AgentAuthenticationFilter.AgentPrincipal;
import com.aiops.backend.service.ResourceGuardService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class ResourceGuardController {
  private final ResourceGuardService service;

  public ResourceGuardController(ResourceGuardService service) {
    this.service = service;
  }

  @GetMapping("/api/servers/{node}/resource-guard")
  public Object get(@PathVariable long node) {
    return service.get(node);
  }

  @PutMapping("/api/servers/{node}/resource-guard")
  public void put(@PathVariable long node, @Valid @RequestBody ResourceGuardService.Policy policy) {
    service.put(node, policy);
  }

  @PostMapping("/api/servers/{node}/resource-guard/check")
  public Object check(@PathVariable long node) {
    return service.requestCheck(node);
  }

  @PostMapping("/api/agent/resource-guard/sync")
  public Object sync(
      Authentication authentication, @Valid @RequestBody ResourceGuardService.Sync body) {
    var principal = (AgentPrincipal) authentication.getPrincipal();
    return service.sync(principal.nodeId(), principal.agentId(), body);
  }
}
