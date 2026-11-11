package com.aiops.backend.controller;

import com.aiops.backend.service.UserMemoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class UserMemoryController {
  private final UserMemoryService service;

  public UserMemoryController(UserMemoryService service) {
    this.service = service;
  }

  public record Setting(boolean automatic) {}

  public record Memory(@NotBlank @Size(max = 4000) String content) {}

  public record Candidate(
      @Positive Long incidentId,
      @NotBlank @Size(max = 160) String title,
      @NotBlank @Size(max = 2000) String symptoms,
      @NotBlank @Size(max = 4000) String evidenceJson,
      @Size(max = 2000) String candidateCause) {}

  public record Review(
      @NotNull @Pattern(regexp = "VERIFIED|REJECTED|SUPERSEDED") String status,
      @Size(max = 4000) String resolution,
      boolean share) {}

  @GetMapping("/ai/memory/settings")
  public Object settings() {
    return Map.of("automatic", service.automatic());
  }

  @PutMapping("/ai/memory/settings")
  public void settings(@RequestBody Setting s) {
    service.automatic(s.automatic());
  }

  @GetMapping("/ai/memories")
  public Object list() {
    return service.memories();
  }

  @PostMapping("/ai/memories")
  public void remember(@Valid @RequestBody Memory m) {
    service.remember(m.content());
  }

  @DeleteMapping("/ai/memories/{id}")
  public void forget(@PathVariable UUID id) {
    service.forget(id.toString());
  }

  @GetMapping("/servers/{server}/training/incident-cases")
  public Object cases(
      @PathVariable long server, @RequestParam(defaultValue = "false") boolean verified) {
    return service.cases(server, verified);
  }

  @GetMapping("/servers/{server}/training/incident-cases/{id}")
  public Object getCase(@PathVariable long server, @PathVariable UUID id) {
    return service.getCase(server, id.toString());
  }

  @PostMapping("/servers/{server}/training/incident-cases")
  public Object candidate(@PathVariable long server, @Valid @RequestBody Candidate r) {
    return Map.of(
        "id",
        service.candidate(
            server, r.incidentId(), r.title(), r.symptoms(), r.evidenceJson(), r.candidateCause()));
  }

  @PostMapping("/servers/{server}/training/incident-cases/{id}/review")
  public void review(
      @PathVariable long server, @PathVariable UUID id, @Valid @RequestBody Review r) {
    service.review(server, id.toString(), r.status(), r.resolution(), r.share());
  }

  @DeleteMapping("/servers/{server}/training/incident-cases/{id}")
  public void delete(@PathVariable long server, @PathVariable UUID id) {
    service.deleteCase(server, id.toString());
  }
}
