package com.aiops.backend.controller;
import com.aiops.backend.service.InvestigationPolicyService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/servers/{server}/investigation-policy")
public class InvestigationPolicyController {
  private final InvestigationPolicyService policies;
  public InvestigationPolicyController(InvestigationPolicyService policies) { this.policies=policies; }
  @GetMapping public Object get(@PathVariable long server) { return policies.get(server); }
  @PutMapping public void save(@PathVariable long server,@Valid @RequestBody InvestigationPolicyService.Policy policy) { policies.save(server,policy); }
}
