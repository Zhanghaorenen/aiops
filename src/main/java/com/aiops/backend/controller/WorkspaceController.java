package com.aiops.backend.controller;

import com.aiops.backend.service.WorkspaceMemberService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController {
  private final WorkspaceMemberService service;

  public WorkspaceController(WorkspaceMemberService service) {
    this.service = service;
  }

  @GetMapping("/audit")
  public List<Map<String, Object>> audit() {
    return service.audit();
  }

  @GetMapping
  public Map<String, Object> mine() {
    return service.mine();
  }

  @GetMapping("/members")
  public List<Map<String, Object>> list() {
    return service.list();
  }

  @PostMapping("/members")
  public void add(@Valid @RequestBody Add req) {
    service.add(req.username(), req.role());
  }

  @PatchMapping("/members/{userId}/role")
  public void role(@PathVariable Long userId, @Valid @RequestBody Role req) {
    service.update(userId, req.role(), null);
  }

  @PatchMapping("/members/{userId}/status")
  public void status(@PathVariable Long userId, @Valid @RequestBody Status req) {
    service.update(userId, null, req.status());
  }

  @DeleteMapping("/members/{userId}")
  public void remove(@PathVariable Long userId) {
    service.update(userId, null, "DISABLED");
  }

  public record Add(
      @NotBlank String username, @Pattern(regexp = "ADMIN|OPERATOR|VIEWER") @NotNull String role) {}

  public record Role(@Pattern(regexp = "ADMIN|OPERATOR|VIEWER") @NotNull String role) {}

  public record Status(@Pattern(regexp = "ACTIVE|DISABLED") @NotNull String status) {}
}
