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
  public List<Map<String, Object>> list(
      @PathVariable Long serverId,
      @RequestParam(required = false) java.time.Instant from,
      @RequestParam(required = false) java.time.Instant to) {
    return service.list(serverId, from, to);
  }

  @PostMapping
  public void create(@PathVariable Long serverId, @Valid @RequestBody GpuUsageService.Usage req) {
    service.create(serverId, req);
  }

  @PutMapping("/{id}")
  public void update(
      @PathVariable Long serverId,
      @PathVariable Long id,
      @Valid @RequestBody GpuUsageService.Usage req) {
    service.update(serverId, id, req);
  }

  @PatchMapping("/{id}/status")
  public void status(
      @PathVariable Long serverId, @PathVariable Long id, @Valid @RequestBody Status req) {
    service.status(serverId, id, req.status());
  }

  public record Status(
      @Pattern(regexp = "PLANNED|RUNNING|COMPLETED|CANCELLED") @NotNull String status) {}
}
