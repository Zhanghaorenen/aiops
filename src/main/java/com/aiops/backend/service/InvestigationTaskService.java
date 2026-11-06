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

/** 数据库租约使重启后的任务可被标记失败；不把用户 JWT 持久化到任务表。 */
@Service
public class InvestigationTaskService {
  private final JdbcTemplate jdbc;
  private final ClusterPermissionService access;

  public InvestigationTaskService(JdbcTemplate jdbc, ClusterPermissionService access) {
    this.jdbc = jdbc;
    this.access = access;
  }

  private LocalDateTime now() {
    return LocalDateTime.now(ZoneOffset.UTC);
  }

  @Transactional
  public Map<String, Object> claim() {
    jdbc.update(
        "UPDATE ops_investigation_run SET"
            + " status='FAILED',error_code='WORKER_LEASE_EXPIRED',finished_at=? WHERE"
            + " status='RUNNING' AND lease_until<?",
        now(),
        now());
    var rows =
        jdbc.queryForList(
            "SELECT id FROM ops_investigation_run WHERE status='PENDING' ORDER BY id LIMIT 1");
    if (rows.isEmpty()) return null;
    long id = ((Number) rows.get(0).get("id")).longValue();
    String lease = UUID.randomUUID().toString();
    if (jdbc.update(
            "UPDATE ops_investigation_run SET"
                + " status='RUNNING',started_at=?,lease_until=?,lease_token=? WHERE id=? AND"
                + " status='PENDING'",
            now(),
            now().plusMinutes(4),
            lease,
            id)
        != 1) return null;
    return jdbc.queryForMap(
        "SELECT r.*,i.server_id,i.title,i.description,u.username,u.status AS user_status FROM"
            + " ops_investigation_run r JOIN ops_incident i ON i.id=r.incident_id JOIN sys_user u"
            + " ON u.id=r.requested_by WHERE r.id=?",
        id);
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
    jdbc.update(
        "UPDATE ops_investigation_run SET status=?,finished_at=?,error_code=? WHERE id=?",
        error == null ? "SUCCEEDED" : "FAILED",
        now(),
        error,
        id);
  }
}
