package com.aiops.backend.controller;

import com.aiops.backend.service.WorkloadAdvisorService;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/servers/{serverId}/training")
public class WorkloadAdvisorController {
  private final WorkloadAdvisorService advisor;

  public WorkloadAdvisorController(WorkloadAdvisorService advisor) {
    this.advisor = advisor;
  }

  @GetMapping("/resource-presets")
  public Object presets(@PathVariable long serverId) {
    return advisor.presets(serverId);
  }

  @GetMapping("/workloads/{id}/resource-suggestion")
  public Object suggestion(@PathVariable long serverId, @PathVariable UUID id) {
    return advisor.suggestion(serverId, id.toString());
  }

  @GetMapping("/workload-analysis")
  public Object analyze(
      @PathVariable long serverId,
      @RequestParam(required = false) UUID runId,
      @RequestParam(required = false) @Positive Long snapshotId,
      @RequestParam(required = false) @Positive Long pid) {
    if (runId == null && (snapshotId == null || pid == null)
        || runId != null && (snapshotId != null || pid != null))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提供运行 ID，或快照 ID 与 PID");
    return advisor.analyze(serverId, runId == null ? null : runId.toString(), snapshotId, pid);
  }
}
