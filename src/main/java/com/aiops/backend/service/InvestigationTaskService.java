package com.aiops.backend.service;

import com.aiops.backend.security.ClusterPermission;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 数据库行锁与每次领取的新租约保护调查的恢复和提交。 */
@Service
public class InvestigationTaskService {
  private final JdbcTemplate jdbc;
  private final ClusterPermissionService access;
  private final UserMemoryService memories;

  public InvestigationTaskService(
      JdbcTemplate jdbc, ClusterPermissionService access, UserMemoryService memories) {
    this.memories = memories;
    this.jdbc = jdbc;
    this.access = access;
  }

  @org.springframework.beans.factory.annotation.Value("${aiops.checkpoint.enabled:false}")
  private boolean recoveryEnabled;

  private LocalDateTime now() {
    return LocalDateTime.now(ZoneOffset.UTC);
  }

  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  public Map<String, Object> claim() {
    // Lock just one eligible run. Updating the entire expired range can deadlock with
    // another worker moving a pending row into the RUNNING index range in InnoDB.
    var at=now();
    var rows=jdbc.queryForList(
        "SELECT id,status,recovery_attempts FROM ops_investigation_run WHERE status='PENDING' OR (status='RUNNING' AND lease_until<?) ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED",at);
    if(rows.isEmpty()) return null;
    var row=rows.get(0);
    long id=((Number)row.get("id")).longValue();
    boolean expired="RUNNING".equals(row.get("status"));
    int attempts=((Number)row.get("recovery_attempts")).intValue();
    if(expired && (!recoveryEnabled || attempts>=3)) {
      jdbc.update("UPDATE ops_investigation_run SET status='FAILED',error_code='WORKER_LEASE_EXPIRED',finished_at=? WHERE id=?",at,id);
      return null;
    }
    jdbc.update("UPDATE ops_investigation_run SET status='RUNNING',started_at=COALESCE(started_at,?),lease_until=?,lease_token=?,recovery_attempts=? WHERE id=?",
        at,at.plusMinutes(4),UUID.randomUUID().toString(),attempts+(expired?1:0),id);
    return jdbc.queryForMap(
        "SELECT r.*,i.server_id,i.title,i.description,i.source_run_id,u.username,u.status AS user_status FROM"
            + " ops_investigation_run r JOIN ops_incident i ON i.id=r.incident_id JOIN sys_user u"
            + " ON u.id=r.requested_by WHERE r.id=?",
        id);
  }

  public boolean recoverAfterDisconnect(Map<String,Object> run) {
    // Let the current lease expire: the interrupted Python request may still be running.
    return recoveryEnabled && ((Number)run.get("recovery_attempts")).intValue()<3;
  }

  public List<Map<String, Object>> alertContext(Long incidentId) {
    var rows =
        jdbc.queryForList(
            "SELECT"
                + " a.id,a.gpu_index,a.severity,a.status,a.first_triggered_at,a.last_observed_at,a.evidence"
                + " FROM ops_alert_event a JOIN ops_incident_alert l ON l.alert_id=a.id WHERE"
                + " l.incident_id=? ORDER BY a.id LIMIT 20",
            incidentId);
    var result = new ArrayList<Map<String, Object>>();
    for (var row : rows) {
      var item = new LinkedHashMap<String, Object>();
      row.forEach(
          (key, value) -> {
            if (value instanceof java.sql.Timestamp t)
              value = t.toLocalDateTime().toInstant(ZoneOffset.UTC).toString();
            else if (value instanceof LocalDateTime t)
              value = t.toInstant(ZoneOffset.UTC).toString();
            item.put(key.toLowerCase(java.util.Locale.ROOT), value);
          });
      result.add(item);
    }
    return result;
  }

  public void progress(long id, String lease, String payload) {
    if(jdbc.update("UPDATE ops_investigation_run SET progress_json=? WHERE id=? AND requested_by=? AND lease_token=? AND status='RUNNING' AND lease_until>?",
        payload,id,access.currentUser().getId(),lease,now())!=1 && recoveryEnabled) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT);
  }

  public void authorized(Map<String, Object> run, Runnable action) {
    if (!"ACTIVE".equals(run.get("user_status")))
      throw new IllegalStateException("REQUESTER_DISABLED");
    var previous = SecurityContextHolder.getContext();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        new UsernamePasswordAuthenticationToken(
            run.get("username"), null, AuthorityUtils.createAuthorityList("ROLE_USER")));
    SecurityContextHolder.setContext(context);
    try {
      access.requireNode(
          ((Number) run.get("server_id")).longValue(), ClusterPermission.AI_DIAGNOSE);
      action.run();
    } finally {
      SecurityContextHolder.setContext(previous);
    }
  }

  @Transactional
  public void complete(Map<String, Object> run, String report, String error) {
    long id = ((Number) run.get("id")).longValue();
    var rows =
        jdbc.queryForList(
            "SELECT status,lease_token,lease_until FROM ops_investigation_run WHERE id=? FOR"
                + " UPDATE",
            id);
    if (rows.isEmpty()) return;
    var state = rows.get(0);
    LocalDateTime until =
        state.get("lease_until") instanceof java.sql.Timestamp t
            ? t.toLocalDateTime()
            : (LocalDateTime) state.get("lease_until");
    if (!"RUNNING".equals(state.get("status"))
        || !Objects.equals(run.get("lease_token"), state.get("lease_token"))
        || until.isBefore(now())) return;
    if (error == null) {
      authorized(run, () -> {});
      jdbc.update(
          "INSERT INTO ops_diagnosis_report(run_id,incident_id,report_json,created_at)"
              + " VALUES(?,?,?,?)",
          id,
          run.get("incident_id"),
          report,
          now());
    }
    if (error == null) {
      authorized(run, () -> memories.fromReport(run, report));
      var trace = new tools.jackson.databind.ObjectMapper().readTree(report).path("executionTrace").path("traceId").asText("");
      if(trace.matches("[a-f0-9]{32}")) jdbc.update("UPDATE ops_investigation_run SET result_trace_id=? WHERE id=?",trace,id);
    }
    jdbc.update(
        "UPDATE ops_investigation_run SET status=?,finished_at=?,error_code=? WHERE id=?",
        error == null ? "SUCCEEDED" : "FAILED",
        now(),
        error,
        id);
  }
}
