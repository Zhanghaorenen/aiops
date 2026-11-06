package com.aiops.backend.controller;

import com.aiops.backend.service.AlertRuleService;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AlertController {
  private final AlertRuleService service;

  public AlertController(AlertRuleService service) {
    this.service = service;
  }

  @GetMapping("/alert-presets")
  public List<Map<String, Object>> presets() {
    return service.presets();
  }

  @PostMapping("/alert-presets/apply")
  public Map<String, Integer> apply(@Valid @RequestBody AlertRuleService.PresetRequest req) {
    return service.applyPreset(req);
  }

  @GetMapping("/alert-rules")
  public List<Map<String, Object>> rules() {
    return service.rules();
  }

  @PostMapping("/alert-rules")
  public void create(@Valid @RequestBody AlertRuleService.Rule rule) {
    service.save(null, rule);
  }

  @PutMapping("/alert-rules/{id}")
  public void update(@PathVariable Long id, @Valid @RequestBody AlertRuleService.Rule rule) {
    service.save(id, rule);
  }

  @GetMapping("/alerts")
  public List<Map<String, Object>> events() {
    return service.events();
  }

  @GetMapping("/alerts/{id}")
  public Map<String, Object> event(@PathVariable Long id) {
    return service.event(id);
  }

  @PostMapping("/alerts/{id}/acknowledge")
  public void ack(@PathVariable Long id) {
    service.acknowledge(id);
  }
}
