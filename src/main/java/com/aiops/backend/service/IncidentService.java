package com.aiops.backend.service;

import com.aiops.backend.security.ClusterPermission;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IncidentService {
  private final JdbcTemplate jdbc;
  private final ClusterPermissionService access;
  private final AlertRuleService alerts;

  public IncidentService(JdbcTemplate j, ClusterPermissionService a, AlertRuleService e) {
    jdbc = j;
    access = a;
    alerts = e;
  }

  public record Create(
      @NotNull Long serverId,
      @NotBlank @Size(max = 200) String title,
      @Size(max = 2000) String description,
      @NotNull @Size(max = 20) List<Long> alertIds) {}

  private LocalDateTime now() {
    return LocalDateTime.now(ZoneOffset.UTC);
  }

  public Map<String, Object> get(Long id) {
    var row =
        jdbc.queryForList("SELECT * FROM ops_incident WHERE id=?", id).stream()
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    access.requireNode(((Number) row.get("server_id")).longValue(), ClusterPermission.METRIC_VIEW);
    return row;
  }

  public List<Map<String, Object>> list() {
    var ids = access.readableClusterIds();
    if (ids.isEmpty()) return List.of();
    return jdbc.queryForList(
        "SELECT * FROM ops_incident WHERE cluster_id IN ("
            + String.join(",", Collections.nCopies(ids.size(), "?"))
            + ") ORDER BY id DESC LIMIT 200",
        ids.toArray());
  }

  @Transactional
  public Map<String, Object> create(Create r) {
    var node = access.requireNode(r.serverId(), ClusterPermission.INCIDENT_RESOLVE);
    long user = access.currentUser().getId();
    // 用生成键定位本次插入，不以“最新一条”推断身份，避免并发串单。
    var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
    jdbc.update(
        c -> {
          var ps =
              c.prepareStatement(
                  "INSERT INTO"
                      + " ops_incident(cluster_id,server_id,title,description,status,created_by,created_at,updated_at)"
                      + " VALUES(?,?,?,?,'OPEN',?,?,?)",
                  java.sql.Statement.RETURN_GENERATED_KEYS);
          ps.setLong(1, node.getClusterId());
          ps.setLong(2, r.serverId());
          ps.setString(3, r.title());
          ps.setString(4, r.description());
          ps.setLong(5, user);
          ps.setObject(6, now());
          ps.setObject(7, now());
          return ps;
        },
        key);
    long id = key.getKey().longValue();
    for (Long alertId : new HashSet<>(r.alertIds())) {
      var alert = alerts.event(alertId);
      if (((Number) alert.get("server_id")).longValue() != r.serverId())
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只能关联同一服务器的告警");
      jdbc.update("INSERT INTO ops_incident_alert VALUES(?,?)", id, alertId);
    }
    history(id, "CREATED", r.title());
    return get(id);
  }

  @Transactional
  public void status(Long id, String state) {
    var row = get(id);
    access.require(
        ((Number) row.get("cluster_id")).longValue(), ClusterPermission.INCIDENT_RESOLVE);
    if (!Set.of("OPEN", "INVESTIGATING", "RESOLVED").contains(state))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    jdbc.update("UPDATE ops_incident SET status=?,updated_at=? WHERE id=?", state, now(), id);
    history(id, "STATUS", state);
  }

  @Transactional
  public Map<String, Object> investigate(Long id, String requestKey) {
    var row =
        jdbc.queryForList("SELECT * FROM ops_incident WHERE id=? FOR UPDATE", id).stream()
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    access.requireNode(((Number) row.get("server_id")).longValue(), ClusterPermission.AI_DIAGNOSE);
    long user = access.currentUser().getId();
    if (requestKey == null || !requestKey.matches("[a-zA-Z0-9_-]{8,80}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要有效的幂等请求标识");
    var same =
        jdbc.queryForList(
            "SELECT * FROM ops_investigation_run WHERE incident_id=? AND requested_by=? AND"
                + " request_key=?",
            id,
            user,
            requestKey);
    if (!same.isEmpty()) return same.get(0);
    Long active =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM ops_investigation_run WHERE incident_id=? AND status IN"
                + " ('PENDING','RUNNING')",
            Long.class,
            id);
    if (active > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "该事件已有调查任务，请等待完成");
    jdbc.update(
        "INSERT INTO ops_investigation_run(incident_id,requested_by,request_key,status,created_at)"
            + " VALUES(?,?,?,'PENDING',?)",
        id,
        user,
        requestKey,
        now());
    history(id, "INVESTIGATION_REQUESTED", requestKey);
    return jdbc.queryForMap(
        "SELECT * FROM ops_investigation_run WHERE incident_id=? AND requested_by=? AND"
            + " request_key=?",
        id,
        user,
        requestKey);
  }

  public Map<String, Object> detail(Long id) {
    var result = new LinkedHashMap<>(get(id));
    result.put(
        "alerts",
        jdbc.queryForList(
            "SELECT a.* FROM ops_alert_event a JOIN ops_incident_alert l ON l.alert_id=a.id WHERE"
                + " l.incident_id=?",
            id));
    result.put(
        "history",
        jdbc.queryForList(
            "SELECT * FROM ops_incident_history WHERE incident_id=? ORDER BY id", id));
    result.put(
        "runs",
        jdbc.queryForList(
            "SELECT id,incident_id,requested_by,status,created_at,started_at,finished_at,error_code"
                + " FROM ops_investigation_run WHERE incident_id=? ORDER BY id DESC",
            id));
    result.put(
        "reports",
        jdbc.queryForList(
            "SELECT d.* FROM ops_diagnosis_report d JOIN ops_investigation_run r ON r.id=d.run_id"
                + " WHERE d.incident_id=? AND r.requested_by=? ORDER BY d.id DESC",
            id,
            access.currentUser().getId()));
    return result;
  }

  @Transactional
  public void review(Long reportId, boolean helpful, String note) {
    var found =
        jdbc.queryForList(
            "SELECT d.* FROM ops_diagnosis_report d JOIN ops_investigation_run r ON r.id=d.run_id"
                + " WHERE d.id=? AND r.requested_by=?",
            reportId,
            access.currentUser().getId());
    if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    var report = found.get(0);
    var incident = get(((Number) report.get("incident_id")).longValue());
    access.require(
        ((Number) incident.get("cluster_id")).longValue(), ClusterPermission.INCIDENT_RESOLVE);
    jdbc.update(
        "UPDATE ops_diagnosis_report SET helpful=?,review_note=?,reviewed_by=? WHERE id=?",
        helpful,
        note,
        access.currentUser().getId(),
        reportId);
    history(((Number) incident.get("id")).longValue(), "REPORT_REVIEW", Boolean.toString(helpful));
  }

  private void history(Long id, String action, String detail) {
    jdbc.update(
        "INSERT INTO ops_incident_history(incident_id,actor_user_id,action,detail,created_at)"
            + " VALUES(?,?,?,?,?)",
        id,
        access.currentUser().getId(),
        action,
        detail,
        now());
  }
}
