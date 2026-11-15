package com.aiops.backend.controller;

import com.aiops.backend.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/servers/{serverId}/training")
public class WorkloadController {
  @org.springframework.beans.factory.annotation.Autowired private RuntimeContentionService contention;

  @GetMapping("/runtime-contention")
  public Object runtimeContention(@PathVariable long serverId) { return contention.analyze(serverId); }
  private final WorkloadService workloads;
  private final TrainingJobService jobs;

  public WorkloadController(WorkloadService workloads, TrainingJobService jobs) {
    this.workloads = workloads;
    this.jobs = jobs;
  }

  @GetMapping("/workloads")
  public Object list(
      @PathVariable long serverId, @RequestParam(defaultValue = "true") boolean active) {
    return workloads.discoverAndList(serverId, active);
  }

  @GetMapping("/workloads/{id}")
  public Object detail(@PathVariable long serverId, @PathVariable UUID id) {
    return workloads.detail(serverId, id.toString());
  }

  public record Confirm(
      @NotBlank @Size(max = 80) String taskType,
      @NotBlank @Size(max = 160) String configurationKey,
      @NotBlank @Size(max = 160) String environmentKey,
      @Positive Long jobId) {}

  @PostMapping("/workloads/{id}/confirm")
  public void confirm(
      @PathVariable long serverId, @PathVariable UUID id, @Valid @RequestBody Confirm r) {
    workloads.confirm(
        serverId, id.toString(), r.taskType(), r.configurationKey(), r.environmentKey(), r.jobId());
  }

  @GetMapping("/workloads/{id}/profile")
  public Object profile(@PathVariable long serverId, @PathVariable UUID id) {
    return workloads.profile(serverId, id.toString());
  }

  @GetMapping("/workloads/{id}/prediction")
  public Object prediction(@PathVariable long serverId, @PathVariable UUID id) {
    return workloads.predict(serverId, id.toString());
  }

  private static double numeric(Object v) {
    return v instanceof Number n ? n.doubleValue() : 0;
  }

  @GetMapping("/capacity")
  public Object capacity(
      @PathVariable long serverId,
      @RequestParam(defaultValue = "0") @Min(0) int gpuIndex,
      @RequestParam(defaultValue = "0") @PositiveOrZero double vramMb,
      @RequestParam(defaultValue = "0") @PositiveOrZero double ramMb,
      @RequestParam(required = false) UUID runId) {
    var result = jobs.capacity(serverId, gpuIndex, vramMb, ramMb);
    if (runId != null) {
      var profile = workloads.profile(serverId, runId.toString());
      result.put("historicalProfile", profile);
      if ("STATISTICAL".equals(profile.get("confidence"))
          && profile.get("gpuMemoryBytes") instanceof Map<?, ?> gpu
          && profile.get("rssEstimateBytes") instanceof Map<?, ?> ram) {
        double suggestedVram =
            Math.max(
                vramMb,
                Math.max(numeric(gpu.get("p95")) * 1.15, numeric(gpu.get("peak"))) / 1048576);
        double suggestedRam =
            Math.max(
                ramMb,
                Math.max(numeric(ram.get("p95")) * 1.15, numeric(ram.get("peak"))) / 1048576);
        result.put(
            "historicalRecommendation",
            Map.of(
                "vramMb",
                suggestedVram,
                "ramMb",
                suggestedRam,
                "basis",
                "P95 * 1.15 or observed peak, RAM uses RSS upper estimate"));
        result.put(
            "historicalAdmission", jobs.capacity(serverId, gpuIndex, suggestedVram, suggestedRam));
      }
    }
    return result;
  }
}
