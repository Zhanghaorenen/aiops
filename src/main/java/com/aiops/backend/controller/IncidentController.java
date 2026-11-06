package com.aiops.backend.controller;

import com.aiops.backend.service.IncidentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class IncidentController {
  private final IncidentService service;

  public IncidentController(IncidentService service) {
    this.service = service;
  }

  @PostMapping("/alerts/{alertId}/investigation")
  public Map<String, Object> fromAlert(
      @PathVariable Long alertId,
      @RequestHeader("Idempotency-Key") String key,
      @RequestParam(defaultValue = "false") boolean retry) {
    return service.fromAlert(alertId, key, retry);
  }

  @GetMapping("/incidents")
  public List<Map<String, Object>> list() {
    return service.list();
  }

  @PostMapping("/incidents")
  public Map<String, Object> create(@Valid @RequestBody IncidentService.Create req) {
    return service.create(req);
  }

  @GetMapping("/incidents/{id}")
  public Map<String, Object> detail(@PathVariable Long id) {
    return service.detail(id);
  }

  @PatchMapping("/incidents/{id}/status")
  public void status(@PathVariable Long id, @Valid @RequestBody State req) {
    service.status(id, req.status());
  }

  @PostMapping("/incidents/{id}/investigations")
  public Map<String, Object> investigate(
      @PathVariable Long id, @RequestHeader("Idempotency-Key") String key) {
    return service.investigate(id, key);
  }

  @PostMapping("/diagnosis-reports/{id}/review")
  public void review(@PathVariable Long id, @Valid @RequestBody Review req) {
    service.review(id, req.helpful(), req.note());
  }

  public record State(@Pattern(regexp = "OPEN|INVESTIGATING|RESOLVED") @NotNull String status) {}

  public record Review(boolean helpful, @Size(max = 2000) String note) {}
}
