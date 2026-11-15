package com.aiops.backend.controller;

import com.aiops.backend.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
public class ChatMemoryController {
  private final LongTermMemoryService memory;
  private final String key;
  public ChatMemoryController(LongTermMemoryService memory,@Value("${aiops.ai-service.service-key:${AIOPS_AI_SERVICE_KEY:}}") String key) { this.memory=memory;this.key=key; }
  public record Setting(@NotNull Boolean automatic) {}
  public record Edit(@NotBlank @Size(max=4000) String content,@NotNull @Pattern(regexp="USER_PREFERENCE|WORKFLOW|ENVIRONMENT|CONFIRMED_EXPERIENCE") String category) {}
  public record Ref(@NotNull UUID id,@Positive long version) {}
  public record Verify(@NotNull @Size(max=5) List<@Valid Ref> references) {}
  @GetMapping("/chat-memory/settings") public Object settings() { return memory.settings(); }
  @PutMapping("/chat-memory/settings") public void settings(@Valid @RequestBody Setting setting) { memory.automatic(setting.automatic()); }
  @PostMapping("/chat-memory/retry") public void retry() { memory.retry(); }
  @PatchMapping("/memories/{id}") public void edit(@PathVariable UUID id,@Valid @RequestBody Edit edit) { memory.manual(id.toString(),edit.category(),edit.content()); }
  @DeleteMapping("/memories") public void clear() { memory.clear(); }
  @GetMapping("/memories/{id}") public Object get(@PathVariable UUID id) { return memory.get(id.toString()); }
  @PostMapping("/memories/verify") public Object verify(@Valid @RequestBody Verify request) {
    return memory.verify(request.references().stream().map(r->Map.<String,Object>of("id",r.id().toString(),"version",r.version())).toList());
  }
  @GetMapping("/sessions/{id}/summary") public Object summary(@PathVariable UUID id) { return memory.summary(id.toString()); }
  @GetMapping("/chat-memory/tasks/{id}") public Object task(@PathVariable UUID id,@RequestHeader("X-Memory-Lease") String lease,@RequestHeader(value="X-AI-Service-Key",required=false) String supplied) {
    if(key.length()<32 || supplied==null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    var task=memory.task(id.toString(),lease);
    return Map.of("task_id",task.get("id"),"payload",com.aiops.backend.persistence.MetricDetails.decode((String)task.get("payload_json")));
  }
}
