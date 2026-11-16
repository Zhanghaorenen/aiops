package com.aiops.backend.controller;

import com.aiops.backend.service.ClusterPermissionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
    "${OTEL_ENABLED:false} or ${LANGFUSE_ENABLED:false}")
@RestController
@RequestMapping("/api/ai/traces")
public class RequestTraceController {
  private final JdbcTemplate jdbc;
  private final ClusterPermissionService access;
  private final String serviceKey;
  private final ObjectMapper json = new ObjectMapper();
  private static final String LABEL = "[a-zA-Z0-9_.:/-]{1,160}";

  public RequestTraceController(
      JdbcTemplate jdbc,
      ClusterPermissionService access,
      @Value("${aiops.ai-service.service-key:${AIOPS_AI_SERVICE_KEY:}}") String key) {
    this.jdbc = jdbc;
    this.access = access;
    this.serviceKey = key;
  }

  public record Step(
      @NotNull @Pattern(regexp = LABEL) String name,
      @NotNull @Pattern(regexp = LABEL) String type,
      @NotNull @Pattern(regexp = "OK|ERROR") String status,
      @Min(0) long startMs,
      @Min(0) long durationMs,
      @Pattern(regexp = LABEL) String error,
      @Size(max = 160) String model,
      @Min(0) Long inputTokens,
      @Min(0) Long outputTokens) {}

  public record Timeline(
      @NotNull UUID executionId,
      @NotNull @Pattern(regexp = "[0-9a-f]{32}") String traceId,
      @Positive double startedAt,
      @Min(0) long durationMs,
      Long incidentId,
      Long taskId,
      @Size(max = 64) String threadId,
      @Size(max = 512) @NotNull List<@Valid Step> spans) {}

  @PostMapping
  public void save(
      @RequestHeader("X-AI-Service-Key") String key, @RequestBody @Valid Timeline timeline) {
    if (serviceKey.length() < 32
        || !MessageDigest.isEqual(
            key.getBytes(StandardCharsets.UTF_8), serviceKey.getBytes(StandardCharsets.UTF_8)))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    try {
      jdbc.update(
          "INSERT INTO ops_request_trace(user_id,trace_id,execution_id,spans_json,created_at)"
              + " VALUES(?,?,?,?,?)",
          access.currentUser().getId(),
          timeline.traceId(),
          timeline.executionId().toString(),
          json.writeValueAsString(timeline),
          LocalDateTime.now(ZoneOffset.UTC));
    } catch (org.springframework.dao.DuplicateKeyException alreadySaved) {
      /* idempotent exporter delivery */
    }
  }

  @GetMapping("/{traceId}")
  public Object get(@PathVariable String traceId) {
    var rows =
        jdbc.queryForList(
            "SELECT spans_json FROM ops_request_trace WHERE user_id=? AND trace_id=? ORDER BY id"
                + " LIMIT 50",
            String.class,
            access.currentUser().getId(),
            traceId);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "尚无可见的执行记录");
    return rows.stream().map(json::readTree).toList();
  }

  @Scheduled(fixedDelayString = "${aiops.trace.cleanup-ms:3600000}")
  public void clean() {
    jdbc.update(
        "DELETE FROM ops_request_trace WHERE created_at<?",
        LocalDateTime.now(ZoneOffset.UTC).minusDays(7));
  }
}
