package com.aiops.backend.service;

import com.aiops.backend.security.JwtService;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "aiops.investigation.worker-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class InvestigationWorker {
  private final InvestigationTaskService tasks;
  private final JwtService jwt;
  private final String url, key;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final tools.jackson.databind.ObjectMapper json =
      new tools.jackson.databind.ObjectMapper();

  public InvestigationWorker(
      InvestigationTaskService tasks,
      JwtService jwt,
      @Value("${aiops.ai-service.url:http://127.0.0.1:8000}") String url,
      @Value("${aiops.ai-service.service-key:${AIOPS_AI_SERVICE_KEY:}}") String key) {
    this.tasks = tasks;
    this.jwt = jwt;
    this.url = url;
    this.key = key;
  }

  @Scheduled(
      scheduler = "investigationScheduler",
      fixedDelayString = "${aiops.investigation.interval-ms:5000}")
  public void poll() {
    Map<String, Object> run = tasks.claim();
    if (run == null) return;
    try {
      if (key.length() < 32) throw new IllegalStateException("AI_SERVICE_NOT_CONFIGURED");
      tasks.authorized(run, () -> {});
      String token =
          jwt.issue(((Number) run.get("requested_by")).longValue(), (String) run.get("username"));
      var payload =
          Map.of(
              "incident_id",
              run.get("incident_id"),
              "alert_evidence",
              tasks.alertContext(((Number) run.get("incident_id")).longValue()),
              "server_id",
              run.get("server_id"),
              "question",
              run.get("title") + "\n" + Objects.toString(run.get("description"), ""));
      var request =
          HttpRequest.newBuilder(URI.create(url + "/api/internal/investigate"))
              .timeout(Duration.ofSeconds(180))
              .header("Content-Type", "application/json")
              .header("Authorization", "Bearer " + token)
              .header("X-AI-Service-Key", key)
              .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
              .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) throw new IllegalStateException("AI_DEPENDENCY_FAILED");
      if (response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 500000)
        throw new IllegalStateException("INVALID_DIAGNOSIS_REPORT");
      var body = json.readTree(response.body());
      if (body.get("server_id") == null
          || body.get("server_id").asLong() != ((Number) run.get("server_id")).longValue()
          || body.get("incident_id").asLong() != ((Number) run.get("incident_id")).longValue()
          || !body.path("evidence").isArray()
          || !body.path("possible_causes").isArray())
        throw new IllegalStateException("INVALID_DIAGNOSIS_REPORT");
      tasks.complete(run, response.body(), null);
    } catch (Exception e) {
      String code =
          e instanceof IllegalStateException
                  && Set.of(
                          "AI_SERVICE_NOT_CONFIGURED",
                          "AI_DEPENDENCY_FAILED",
                          "INVALID_DIAGNOSIS_REPORT")
                      .contains(e.getMessage())
              ? e.getMessage()
              : "INVESTIGATION_FAILED";
      tasks.complete(run, null, code);
    }
  }
}
