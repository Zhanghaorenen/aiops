package com.aiops.backend.service;

import com.aiops.backend.persistence.MetricDetails;
import com.aiops.backend.security.ClusterPermission;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class UserMemoryService {
  private final JdbcTemplate jdbc;
  private final ClusterPermissionService access;
  private final ObjectMapper json = new ObjectMapper();

  public UserMemoryService(JdbcTemplate jdbc, ClusterPermissionService access) {
    this.jdbc = jdbc;
    this.access = access;
  }

  private long user() {
    return access.currentUser().getId();
  }

  private LocalDateTime now() {
    return LocalDateTime.now(ZoneOffset.UTC);
  }

  public boolean automatic() {
    return jdbc
        .queryForList(
            "SELECT automatic FROM ai_user_memory_setting WHERE user_id=?", Boolean.class, user())
        .stream()
        .findFirst()
        .orElse(false);
  }

  @Transactional
  public void automatic(boolean enabled) {
    long u = user();
    jdbc.update("DELETE FROM ai_user_memory_setting WHERE user_id=?", u);
    jdbc.update("INSERT INTO ai_user_memory_setting(user_id,automatic) VALUES(?,?)", u, enabled);
  }

  public Object memories() {
    return jdbc.queryForList(
        "SELECT id,content,source,created_at FROM ai_user_memory WHERE user_id=? ORDER BY"
            + " created_at DESC LIMIT 200",
        user());
  }

  public void remember(String content) {
    jdbc.update(
        "INSERT INTO ai_user_memory(id,user_id,content,source,created_at)"
            + " VALUES(?,?,?,'USER_CONFIRMED',?)",
        UUID.randomUUID().toString(),
        user(),
        content,
        now());
  }

  public void forget(String id) {
    if (jdbc.update("DELETE FROM ai_user_memory WHERE id=? AND user_id=?", id, user()) != 1)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  }

  public List<Map<String, Object>> cases(long server, boolean verified) {
    access.requireNode(server, ClusterPermission.AI_DIAGNOSE);
    var rows =
        jdbc.queryForList(
            "SELECT * FROM ops_incident_case WHERE server_id=? AND (user_id=? OR (scope='CLUSTER'"
                + " AND status='VERIFIED'))"
                + (verified ? " AND status='VERIFIED'" : "")
                + " ORDER BY updated_at DESC LIMIT 200",
            server,
            user());
    for (var row : rows)
      if (!Objects.equals(row.get("user_id"), user())) row.remove("candidate_cause");
    return rows;
  }

  public Map<String, Object> getCase(long server, String id) {
    access.requireNode(server, ClusterPermission.AI_DIAGNOSE);
    var rows =
        jdbc.queryForList(
            "SELECT * FROM ops_incident_case WHERE id=? AND server_id=? AND (user_id=? OR"
                + " (scope='CLUSTER' AND status='VERIFIED'))",
            id,
            server,
            user());
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    var row = rows.get(0);
    if (!Objects.equals(row.get("user_id"), user())) row.remove("candidate_cause");
    return row;
  }

  public String candidate(
      long server, Long incident, String title, String symptoms, String evidence, String cause) {
    access.requireNode(server, ClusterPermission.AI_DIAGNOSE);
    if (incident != null
        && jdbc.queryForObject(
                "SELECT COUNT(*) FROM ops_incident WHERE id=? AND server_id=?",
                Long.class,
                incident,
                server)
            == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    // 候选证据只允许 ID 引用，不保存调用方任意提交的完整日志。
    Object ids = json.readValue(evidence, List.class);
    if (!(ids instanceof List<?> list)
        || list.size() > 30
        || list.stream()
            .anyMatch(v -> !(v instanceof String s) || !s.matches("[A-Za-z0-9_.:-]{1,100}")))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "证据必须是有限的来源 ID");
    String id = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO"
            + " ops_incident_case(id,user_id,server_id,incident_id,title,symptoms,evidence_json,candidate_cause,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?)",
        id,
        user(),
        server,
        incident,
        title,
        symptoms,
        evidence,
        cause,
        now(),
        now());
    return id;
  }

  @Transactional
  public void review(long server, String id, String state, String resolution, boolean share) {
    var row = getCase(server, id);
    if (!Objects.equals(row.get("user_id"), user()))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能审核自己的私人经验");
    if ("VERIFIED".equals(state) && (resolution == null || resolution.isBlank()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请记录实际解决结果后验证");
    if (share)
      access.require(
          access.requireNode(server, ClusterPermission.AI_DIAGNOSE).getClusterId(),
          ClusterPermission.AGENT_MANAGE);
    jdbc.update(
        "UPDATE ops_incident_case SET status=?,resolution=?,scope=?,version=version+1,updated_at=?"
            + " WHERE id=? AND user_id=?",
        state,
        resolution,
        share ? "CLUSTER" : "PRIVATE",
        now(),
        id,
        user());
  }

  public void deleteCase(long server, String id) {
    var row = getCase(server, id);
    if (!Objects.equals(row.get("user_id"), user()))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    jdbc.update("DELETE FROM ops_incident_case WHERE id=? AND user_id=?", id, user());
  }

  /** 已有调查结果生成待核实条目，不调用第二次模型，也不自动发布到向量库。 */
  public void fromReport(Map<String, Object> run, String report) {
    if (!automatic()) return;
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM ops_incident_case WHERE incident_id=? AND user_id=?",
            Long.class,
            run.get("incident_id"),
            user())
        > 0) return;
    var data = MetricDetails.decode(report);
    var references = new ArrayList<String>();
    if (data.get("evidence") instanceof List<?> list)
      for (Object v : list)
        if (v instanceof Map<?, ?> m
            && m.get("id") instanceof String s
            && s.matches("[A-Za-z0-9_.:-]{1,100}")) references.add(s);
    var hypotheses = new ArrayList<String>();
    if (data.get("possible_causes") instanceof List<?> list)
      for (Object value : list)
        if (value instanceof Map<?, ?> cause && cause.get("hypothesis") instanceof String text)
          hypotheses.add(text.substring(0, Math.min(400, text.length())));
    var steps = new ArrayList<String>();
    if (data.get("suggested_steps") instanceof List<?> list)
      for (Object value : list)
        if (value instanceof String text)
          steps.add(text.substring(0, Math.min(200, text.length())));
    String cause =
        "尚未验证的候选原因："
            + String.join("；", hypotheses.stream().limit(3).toList())
            + "\n建议核实步骤："
            + String.join("；", steps.stream().limit(3).toList());
    var kinds = new TreeSet<String>();
    if (data.get("evidence") instanceof List<?> list)
      for (Object value : list)
        if (value instanceof Map<?, ?> evidence
            && evidence.get("kind") instanceof String kind
            && kind.matches("[A-Za-z0-9_]{1,60}")) kinds.add(kind);
    String symptoms =
        "故障窗口："
            + String.valueOf(data.getOrDefault("anomaly_time", "未知"))
            + "；观测类型："
            + String.join("、", kinds)
            + "；证据保存在关联 Incident 的原始报告中，尚未验证";
    candidate(
        ((Number) run.get("server_id")).longValue(),
        ((Number) run.get("incident_id")).longValue(),
        String.valueOf(run.get("title"))
            .substring(0, Math.min(160, String.valueOf(run.get("title")).length())),
        symptoms,
        json.writeValueAsString(references),
        cause);
  }
}
