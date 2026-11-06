package com.aiops.backend.controller;

import com.aiops.backend.service.GpuUsageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/servers/{serverId}/gpu-usage")
public class GpuUsageController {
  private final GpuUsageService service;

  public GpuUsageController(GpuUsageService service) {
    this.service = service;
  }

  @GetMapping
  public List<Map<String, Object>> list(@PathVariable Long serverId) {
    return service.list(serverId);
  }

  @PostMapping
  public void create(@PathVariable Long serverId, @Valid @RequestBody GpuUsageService.Usage req) {
    service.create(serverId, req);
  }

  @PatchMapping("/{id}/status")
  public void status(
      @PathVariable Long serverId, @PathVariable Long id, @Valid @RequestBody Status req) {
    service.status(serverId, id, req.status());
  }

  public record Status(
      @Pattern(regexp = "PLANNED|RUNNING|COMPLETED|CANCELLED") @NotNull String status) {}
}
