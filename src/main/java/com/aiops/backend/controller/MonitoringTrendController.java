package com.aiops.backend.controller;

import com.aiops.backend.service.MonitoringTrendService;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/servers/{serverId}")
public class MonitoringTrendController {
  private final MonitoringTrendService service;

  public MonitoringTrendController(MonitoringTrendService service) {
    this.service = service;
  }

  @GetMapping("/metrics/trends")
  public Map<String, Object> host(
      @PathVariable Long serverId, @RequestParam Instant from, @RequestParam Instant to) {
    return service.trends(serverId, null, from, to);
  }

  @GetMapping("/gpus/{gpuId}/trends")
  public Map<String, Object> gpu(
      @PathVariable Long serverId,
      @PathVariable Integer gpuId,
      @RequestParam Instant from,
      @RequestParam Instant to) {
    return service.trends(serverId, gpuId, from, to);
  }
}
