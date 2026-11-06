package com.aiops.backend.controller;

import com.aiops.backend.service.AgentManagementService;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AgentManagementController {
  private final AgentManagementService service;

  public AgentManagementController(AgentManagementService service) {
    this.service = service;
  }

  @GetMapping("/managed-agents")
  public List<Map<String, Object>> agents() {
    return service.agents();
  }

  @PostMapping("/managed-agents/{id}/disable")
  public void disable(@PathVariable Long id) {
    service.disable(id);
  }

  @GetMapping("/agent/enrollment-tokens")
  public List<Map<String, Object>> tokens() {
    return service.tokens();
  }

  @DeleteMapping("/agent/enrollment-tokens/{id}")
  public void revoke(@PathVariable Long id) {
    service.revoke(id);
  }
}
