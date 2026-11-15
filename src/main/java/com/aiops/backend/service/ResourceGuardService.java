package com.aiops.backend.service;

import com.aiops.backend.security.ClusterPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * Remote policy never arms a host or supplies a PID/command. Local guard enforces the final
 * boundary.
 */
@Service
public class ResourceGuardService {
  private final JdbcTemplate jdbc;
  private final ClusterPermissionService access;
  private final ObjectMapper json = new ObjectMapper();

  public ResourceGuardService(JdbcTemplate jdbc, ClusterPermissionService access) {
    this.jdbc = jdbc;
    this.access = access;
  }

  public record Policy(
      @Pattern(regexp = "DISABLED|OBSERVE|AUTO") @NotNull String mode,
      @NotNull @Size(max = 32) List<@NotNull @Min(1000) Integer> businessUids,
      @Min(256) @Max(65536) int minAvailableMb,
      @Min(1) @Max(15) int minAvailablePercent,
      @Min(1) @Max(100) int memoryPsiThreshold,
      @Min(4) @Max(300) int sustainSeconds,
      @Min(30) @Max(3600) int cooldownSeconds,
      @Min(1) @Max(10) int maxActionsPerHour,
      @Min(1) @Max(30) int graceSeconds,
      @Min(128) @Max(65536) int minRssMb) {}

  public record Event(
      @NotNull UUID id,
      @Positive double at,
      @Pattern(
              regexp =
                  "PREPARED|NO_ELIGIBLE_PROCESS|WOULD_TERMINATE|UNSUPPORTED_PIDFD|IDENTITY_CHANGED|CANCELLED_NO_SIGNAL|EXITED_AFTER_TERM|TERM_SENT_NO_ESCALATION|EXITED_AFTER_KILL|KILL_SENT_UNCONFIRMED|FAILED_[A-Za-z]+")
          @NotNull
          String status,
      @NotNull @Size(max = 8) Map<String, Object> details) {}

  public record Sync(
      @NotNull @Size(max = 50) List<@Valid Event> events,
      @NotNull @Size(max = 16) Map<String, Object> status) {}

  private LocalDateTime now() {
    return LocalDateTime.now(ZoneOffset.UTC);
  }

  private Policy defaults() {
    return new Policy("DISABLED", List.of(), 1024, 5, 10, 10, 60, 3, 5, 256);
  }

  private String encode(Object value) {
    String result = json.writeValueAsString(value);
    if (result.length() > 16000)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "保护状态或事件过长");
    return result;
  }

  private void ensure(long node) {
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM ops_resource_guard WHERE node_id=?", Integer.class, node)
        == 0)
      try {
        jdbc.update(
            "INSERT INTO ops_resource_guard(node_id,policy_json) VALUES(?,?)",
            node,
            encode(defaults()));
      } catch (org.springframework.dao.DuplicateKeyException concurrentInsert) {
        /* another sync initialized it */
      }
  }

  public Map<String, Object> get(long node) {
    access.requireNode(node, ClusterPermission.METRIC_VIEW);
    var rows = jdbc.queryForList("SELECT * FROM ops_resource_guard WHERE node_id=?", node);
    var result = new LinkedHashMap<String, Object>();
    result.put(
        "policy",
        rows.isEmpty()
            ? defaults()
            : json.readValue((String) rows.get(0).get("policy_json"), Map.class));
    result.put(
        "agentStatus",
        rows.isEmpty() || rows.get(0).get("status_json") == null
            ? Map.of()
            : json.readValue((String) rows.get(0).get("status_json"), Map.class));
    result.put(
        "lastSeenAt",
        rows.isEmpty() || rows.get(0).get("last_seen_at") == null
            ? null
            : TrainingJobService.instant(rows.get(0).get("last_seen_at")).toString());
    result.put(
        "agentOnline",
        !rows.isEmpty()
            && rows.get(0).get("last_seen_at") != null
            && TrainingJobService.instant(rows.get(0).get("last_seen_at"))
                .isAfter(Instant.now().minusSeconds(30)));
    result.put(
        "events",
        jdbc.queryForList(
            "SELECT event_json,received_at FROM ops_resource_guard_event WHERE node_id=? ORDER BY"
                + " received_at DESC LIMIT 50",
            node));
    result.put("executionBoundary", "仅本地已启用的业务UID范围；系统/平台服务排除；LLM不能选择PID或绕过压力检查");
    return result;
  }

  @Transactional
  public void put(long node, Policy policy) {
    var entity = access.requireNode(node, ClusterPermission.AGENT_MANAGE);
    ensure(node);
    if (policy.mode().equals("AUTO") && policy.businessUids().isEmpty())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "自动处置必须声明业务UID范围");
    jdbc.update(
        "UPDATE ops_resource_guard SET policy_json=? WHERE node_id=?", encode(policy), node);
    access.audit(entity.getClusterId(), "RESOURCE_GUARD_POLICY", node, "mode=" + policy.mode());
  }

  @Transactional
  public Map<String, Object> requestCheck(long node) {
    var entity = access.requireNode(node, ClusterPermission.AGENT_MANAGE);
    ensure(node);
    var row = jdbc.queryForMap("SELECT * FROM ops_resource_guard WHERE node_id=? FOR UPDATE", node);
    Object prior = row.get("requested_at");
    if (prior != null && TrainingJobService.instant(prior).isAfter(Instant.now().minusSeconds(30)))
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请等待上次保护检查");
    var id = UUID.randomUUID().toString();
    jdbc.update(
        "UPDATE ops_resource_guard SET request_id=?,requested_at=? WHERE node_id=?",
        id,
        now(),
        node);
    access.audit(entity.getClusterId(), "RESOURCE_GUARD_CHECK", node, id);
    return Map.of(
        "requestId",
        id,
        "requested",
        true,
        "executed",
        false,
        "message",
        "仅排队一次保护检查；不代表已结束进程。采集端仍检查本地开关、UID、持续内存压力和保护规则。");
  }

  @Transactional
  public Map<String, Object> sync(long node, long agent, Sync body) {
    ensure(node);
    for (var event : body.events()) {
      var exists =
          jdbc.queryForList(
              "SELECT node_id,agent_id FROM ops_resource_guard_event WHERE event_id=?",
              event.id().toString());
      if (!exists.isEmpty()) {
        if (((Number) exists.get(0).get("node_id")).longValue() != node
            || ((Number) exists.get(0).get("agent_id")).longValue() != agent)
          throw new ResponseStatusException(HttpStatus.CONFLICT, "事件标识冲突");
        continue;
      }
      jdbc.update(
          "INSERT INTO ops_resource_guard_event(event_id,node_id,agent_id,event_json,received_at)"
              + " VALUES(?,?,?,?,?)",
          event.id().toString(),
          node,
          agent,
          encode(event),
          now());
    }
    jdbc.update(
        "UPDATE ops_resource_guard SET status_json=?,last_seen_at=? WHERE node_id=?",
        encode(body.status()),
        now(),
        node);
    var row = jdbc.queryForMap("SELECT * FROM ops_resource_guard WHERE node_id=?", node);
    var result = new LinkedHashMap<String, Object>();
    result.put("policy", json.readValue((String) row.get("policy_json"), Map.class));
    // Expired requests are never delivered as late actions after a network outage.
    if (row.get("requested_at") != null
        && TrainingJobService.instant(row.get("requested_at"))
            .isAfter(Instant.now().minusSeconds(60)))
      result.put("checkRequest", row.get("request_id"));
    return result;
  }
}
