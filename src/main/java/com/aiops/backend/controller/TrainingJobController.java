package com.aiops.backend.controller;

import com.aiops.backend.service.TrainingAnalysisService;
import com.aiops.backend.service.TrainingJobService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/servers/{serverId}/training")
public class TrainingJobController {

  private final TrainingJobService jobs;
  private final TrainingAnalysisService analysis;

  public TrainingJobController(
    TrainingJobService jobs,
    TrainingAnalysisService analysis
  ) {
    this.jobs = jobs;
    this.analysis = analysis;
  }

  @GetMapping("/jobs")
  public Object list(@PathVariable long serverId) {
    return jobs.list(serverId);
  }

  @PostMapping("/jobs")
  public Object create(
    @PathVariable long serverId,
    @Valid @RequestBody TrainingJobService.Request r
  ) {
    return jobs.save(serverId, null, r);
  }

  @GetMapping("/jobs/{id}")
  public Object get(@PathVariable long serverId, @PathVariable long id) {
    return jobs.get(serverId, id);
  }

  @PutMapping("/jobs/{id}")
  public Object update(
    @PathVariable long serverId,
    @PathVariable long id,
    @Valid @RequestBody TrainingJobService.Request r
  ) {
    return jobs.save(serverId, id, r);
  }

  @PostMapping("/jobs/{id}/recheck")
  public Object recheck(@PathVariable long serverId, @PathVariable long id) {
    return jobs.recheck(serverId, id);
  }

  public record Transition(
    @NotNull @Pattern(
      regexp = "STARTING|SUCCEEDED|FAILED|CANCELLED"
    ) String state
  ) {}

  @PostMapping("/jobs/{id}/state")
  public Object state(
    @PathVariable long serverId,
    @PathVariable long id,
    @Valid @RequestBody Transition r
  ) {
    return jobs.transition(serverId, id, r.state());
  }

  public record Link(@Positive long pid, @NotBlank String startedAt) {}

  @PostMapping("/jobs/{id}/process")
  public Object link(
    @PathVariable long serverId,
    @PathVariable long id,
    @Valid @RequestBody Link r
  ) {
    return jobs.link(serverId, id, r.pid(), r.startedAt());
  }

  @PostMapping("/jobs/{id}/progress")
  public void progress(
    @PathVariable long serverId,
    @PathVariable long id,
    @Valid @RequestBody TrainingJobService.Progress p
  ) {
    jobs.progress(serverId, id, p);
  }

  @GetMapping("/jobs/{id}/analysis")
  public Object analysis(@PathVariable long serverId, @PathVariable long id) {
    return analysis.analyze(serverId, id);
  }

  @GetMapping("/policy")
  public Object policy(@PathVariable long serverId) {
    return jobs.policy(serverId);
  }

  @PutMapping("/policy")
  public void policy(
    @PathVariable long serverId,
    @Valid @RequestBody TrainingJobService.Policy p
  ) {
    jobs.policy(serverId, p);
  }

  public record Identity(
    @Positive long userId,
    @NotBlank String linuxUsername
  ) {}

  @PutMapping("/identity")
  public void identity(
    @PathVariable long serverId,
    @Valid @RequestBody Identity r
  ) {
    jobs.identity(serverId, r.userId(), r.linuxUsername());
  }
}
