package com.aiops.backend.service;

import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** MySQL 是会话的唯一真相源；会话行锁用于串行化生成请求。 */
@Service
public class ChatSessionService {
  private final JdbcTemplate jdbc;
  private final ClusterPermissionService access;

  public ChatSessionService(JdbcTemplate jdbc, ClusterPermissionService access) {
    this.jdbc = jdbc;
    this.access = access;
  }

  private LocalDateTime now() {
    return LocalDateTime.now(ZoneOffset.UTC);
  }

  private ResponseStatusException error(int status, String message) {
    return new ResponseStatusException(HttpStatus.valueOf(status), message);
  }

  private Map<String, Object> own(String id, boolean lock) {
    var rows =
        jdbc.queryForList(
            "SELECT * FROM ai_chat_session WHERE id=? AND user_id=? AND status<>'DELETED'"
                + (lock ? " FOR UPDATE" : ""),
            id,
            access.currentUser().getId());
    if (rows.isEmpty()) throw error(404, "会话不存在");
    return rows.get(0);
  }

  public List<Map<String, Object>> list() {
    return jdbc.queryForList(
        "SELECT id,title,status,created_at,updated_at FROM ai_chat_session WHERE user_id=? AND"
            + " status<>'DELETED' ORDER BY updated_at DESC LIMIT 200",
        access.currentUser().getId());
  }

  public Map<String, Object> create(String title) {
    String id = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO ai_chat_session(id,user_id,title,created_at,updated_at) VALUES(?,?,?,?,?)",
        id,
        access.currentUser().getId(),
        title,
        now(),
        now());
    return own(id, false);
  }

  @Transactional
  public void update(String id, String title, String status) {
    var session = own(id, true);
    if (session.get("active_request") != null
        && session.get("lease_until") != null
        && TrainingJobService.instant(session.get("lease_until")).isAfter(Instant.now()))
      throw error(409, "请先停止正在生成的回答");
    jdbc.update(
        "UPDATE ai_chat_session SET title=?,status=?,updated_at=? WHERE id=?",
        title == null ? session.get("title") : title,
        status,
        now(),
        id);
  }

  public List<Map<String, Object>> messages(String id, long before, int limit) {
    own(id, false);
    var rows =
        jdbc.queryForList(
            "SELECT id,role,content,status,metadata_json,created_at FROM ai_chat_message WHERE"
                + " session_id=? AND id<? ORDER BY id DESC LIMIT ?",
            id,
            before,
            Math.max(1, Math.min(limit, 100)));
    Collections.reverse(rows);
    return rows;
  }

  @Transactional
  public Map<String, Object> begin(String id, String request, String question) {
    var session = own(id, true);
    if (!"ACTIVE".equals(session.get("status"))) throw error(409, "会话已归档");
    var existing =
        jdbc.queryForList(
            "SELECT * FROM ai_chat_message WHERE session_id=? AND request_id=? ORDER BY id",
            id,
            request);
    if (!existing.isEmpty()) {
      if (!question.equals(existing.get(0).get("content"))) throw error(409, "请求编号已被其他问题使用");
      var answer =
          existing.stream()
              .filter(m -> "assistant".equals(m.get("role")) && "COMPLETED".equals(m.get("status")))
              .findFirst();
      if (answer.isPresent())
        return Map.of("replay", answer.get().get("content"), "history", List.of());
    }
    if (session.get("active_request") != null
        && session.get("lease_until") != null
        && TrainingJobService.instant(session.get("lease_until")).isAfter(Instant.now()))
      throw error(409, "该会话正在生成回答");
    // 过期的租约只标记中断，不伪装为成功消息。
    jdbc.update(
        "UPDATE ai_chat_message SET status='INTERRUPTED' WHERE session_id=? AND status='PENDING'",
        id);
    var history =
        jdbc.queryForList(
            "SELECT role,content FROM ai_chat_message WHERE session_id=? AND status='COMPLETED'"
                + " ORDER BY id DESC LIMIT 20",
            id);
    Collections.reverse(history);
    int size = question.length();
    for (var m : history) size += m.get("content").toString().length();
    while (size > 50000 && !history.isEmpty()) {
      size -= history.remove(0).get("content").toString().length();
      if (!history.isEmpty() && "assistant".equals(history.get(0).get("role")))
        size -= history.remove(0).get("content").toString().length();
    }
    if (existing.isEmpty())
      jdbc.update(
          "INSERT INTO ai_chat_message(session_id,request_id,role,content,status,created_at)"
              + " VALUES(?,?,'user',?,'PENDING',?)",
          id,
          request,
          question,
          now());
    else
      jdbc.update(
          "UPDATE ai_chat_message SET status='PENDING' WHERE session_id=? AND request_id=? AND"
              + " role='user'",
          id,
          request);
    String lease = UUID.randomUUID().toString();
    jdbc.update(
        "UPDATE ai_chat_session SET active_request=?,lease_token=?,lease_until=?,updated_at=? WHERE"
            + " id=?",
        request,
        lease,
        now().plusMinutes(10),
        now(),
        id);
    return Map.of("history", history, "lease", lease);
  }

  @Transactional
  public void finish(
      String id, String request, String status, String answer, String metadata, String lease) {
    var session = own(id, true);
    if (!Objects.equals(session.get("active_request"), request)
        || !Objects.equals(session.get("lease_token"), lease)
        || session.get("lease_until") == null
        || TrainingJobService.instant(session.get("lease_until")).isBefore(Instant.now()))
      throw error(409, "生成请求已结束或被替换");
    if ("COMPLETED".equals(status)) {
      if (answer == null || answer.isBlank()) throw error(400, "回答为空");
      jdbc.update(
          "INSERT INTO"
              + " ai_chat_message(session_id,request_id,role,content,status,metadata_json,created_at)"
              + " VALUES(?,?,'assistant',?,'COMPLETED',?,?)",
          id,
          request,
          answer,
          metadata,
          now());
    }
    jdbc.update(
        "UPDATE ai_chat_message SET status=? WHERE session_id=? AND request_id=? AND role='user'",
        status,
        id,
        request);
    jdbc.update(
        "UPDATE ai_chat_session SET"
            + " active_request=NULL,lease_token=NULL,lease_until=NULL,updated_at=? WHERE id=?",
        now(),
        id);
  }
}
