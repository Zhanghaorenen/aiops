package com.aiops.backend.controller;

import com.aiops.backend.service.InvestigationTaskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/investigation-tasks")
public class InvestigationProgressController {
  private final InvestigationTaskService tasks;
  private final String key;
  public InvestigationProgressController(InvestigationTaskService tasks,
      @Value("${aiops.ai-service.service-key:${AIOPS_AI_SERVICE_KEY:}}") String key) {
    this.tasks=tasks; this.key=key;
  }
  public record Check(@NotBlank @Size(max=80) String tool,@Size(max=30) String status) {}
  public record Progress(@NotBlank @Size(max=500) String stage,@Min(0) @Max(12) int toolCalls,
      @Min(0) @Max(4) int round,@NotNull @Size(max=20) List<@Valid Check> checks) {}
  @PostMapping("/{id}/progress")
  public void progress(@PathVariable long id,@RequestHeader("X-Investigation-Lease") String lease,
      @RequestHeader(value="X-AI-Service-Key",required=false) String supplied,@Valid @RequestBody Progress progress) {
    if(key.length()<32 || supplied==null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    tasks.progress(id,lease,new tools.jackson.databind.ObjectMapper().writeValueAsString(progress));
  }
}
