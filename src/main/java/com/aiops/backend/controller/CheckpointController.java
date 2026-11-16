package com.aiops.backend.controller;

import com.aiops.backend.service.InvestigationCheckpointService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/investigation-tasks/{id}/checkpoint")
@ConditionalOnProperty(name = "aiops.checkpoint.enabled", havingValue = "true")
public class CheckpointController {
  private final InvestigationCheckpointService service;
  private final String key;

  public CheckpointController(
      InvestigationCheckpointService service,
      @Value("${aiops.ai-service.service-key:${AIOPS_AI_SERVICE_KEY:}}") String key) {
    this.service = service;
    this.key = key;
  }

  private void verify(String supplied) {
    if (key.length() < 32
        || !MessageDigest.isEqual(
            key.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }

  @GetMapping
  public Object read(
      @PathVariable long id,
      @RequestHeader("X-AI-Service-Key") String supplied,
      @RequestHeader("X-Investigation-Lease") String lease,
      @RequestParam(required = false) String checkpoint) {
    verify(supplied);
    return service.read(id, lease, checkpoint);
  }

  @PostMapping
  public void save(
      @PathVariable long id,
      @RequestHeader("X-AI-Service-Key") String supplied,
      @RequestHeader("X-Investigation-Lease") String lease,
      @RequestBody InvestigationCheckpointService.Entry entry) {
    verify(supplied);
    service.save(id, lease, entry);
  }
}
