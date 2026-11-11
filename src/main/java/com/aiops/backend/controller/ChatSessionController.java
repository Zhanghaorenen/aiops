package com.aiops.backend.controller;

import com.aiops.backend.service.ChatSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/sessions")
public class ChatSessionController {
  private final ChatSessionService service;

  public ChatSessionController(ChatSessionService service) {
    this.service = service;
  }

  public record Create(@NotBlank @Size(max = 160) String title) {}

  public record Update(
      @Size(min = 1, max = 160) String title,
      @NotNull @Pattern(regexp = "ACTIVE|ARCHIVED|DELETED") String status) {}

  public record Begin(@NotNull UUID requestId, @NotBlank @Size(max = 4000) String question) {}

  public record Finish(
      @NotNull UUID lease,
      @NotNull @Pattern(regexp = "COMPLETED|FAILED|CANCELLED|INTERRUPTED") String status,
      @Size(max = 8000) String answer,
      @Size(max = 60000) String metadata) {}

  @GetMapping
  public List<Map<String, Object>> list() {
    return service.list();
  }

  @PostMapping
  public Map<String, Object> create(@Valid @RequestBody Create body) {
    return service.create(body.title());
  }

  @PatchMapping("/{id}")
  public void update(@PathVariable UUID id, @Valid @RequestBody Update body) {
    service.update(id.toString(), body.title(), body.status());
  }

  @DeleteMapping("/{id}")
  public void delete(@PathVariable UUID id) {
    service.update(id.toString(), null, "DELETED");
  }

  @GetMapping("/{id}/messages")
  public List<Map<String, Object>> messages(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "9223372036854775807") long before,
      @RequestParam(defaultValue = "50") int limit) {
    return service.messages(id.toString(), before, limit);
  }

  @PostMapping("/{id}/turns")
  public Map<String, Object> begin(@PathVariable UUID id, @Valid @RequestBody Begin body) {
    return service.begin(id.toString(), body.requestId().toString(), body.question());
  }

  @PostMapping("/{id}/turns/{request}/finish")
  public void finish(
      @PathVariable UUID id, @PathVariable UUID request, @Valid @RequestBody Finish body) {
    service.finish(
        id.toString(),
        request.toString(),
        body.status(),
        body.answer(),
        body.metadata(),
        body.lease().toString());
  }
}
