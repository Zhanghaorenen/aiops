package com.aiops.backend.controller;

import com.aiops.backend.service.ResourceNotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/resources")
public class ResourceNotificationController {
  private final ResourceNotificationService service;

  public ResourceNotificationController(ResourceNotificationService service) {
    this.service = service;
  }

  public record Subscription(
      @Positive long serverId,
      @Min(0) int gpuIndex,
      @PositiveOrZero double requiredVramMb,
      @PositiveOrZero double requiredRamMb,
      UUID runId,
      @Min(60) @Max(86400) int cooldownSeconds) {}

  @GetMapping("/subscriptions")
  public Object subscriptions() {
    return service.subscriptions();
  }

  @PostMapping("/subscriptions")
  public Object subscribe(@Valid @RequestBody Subscription s) {
    return Map.of(
        "id",
        service.subscribe(
            s.serverId(),
            s.gpuIndex(),
            s.requiredVramMb(),
            s.requiredRamMb(),
            s.runId() == null ? null : s.runId().toString(),
            s.cooldownSeconds()));
  }

  @DeleteMapping("/subscriptions/{id}")
  public void cancel(@PathVariable UUID id) {
    service.cancel(id.toString());
  }

  @GetMapping("/notifications")
  public Object notifications() {
    return service.notifications();
  }

  @PostMapping("/notifications/{id}/read")
  public void read(@PathVariable UUID id) {
    service.read(id.toString());
  }
}
