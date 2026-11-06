package com.aiops.backend.service;

import com.aiops.backend.repository.*;
import com.aiops.backend.security.ClusterPermission;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AgentManagementService {
  private final ClusterPermissionService access;
  private final ClusterRepository clusters;
  private final JdbcTemplate jdbc;
  private final AgentEnrollTokenRepository tokens;

  public AgentManagementService(
      ClusterPermissionService a,
      ClusterRepository c,
      JdbcTemplate j,
      AgentEnrollTokenRepository t) {
    access = a;
    clusters = c;
    jdbc = j;
    tokens = t;
  }

  public List<Map<String, Object>> agents() {
    var c = clusters.requireDefault();
    access.require(c.getId(), ClusterPermission.AGENT_MANAGE);
    return jdbc.queryForList(
        "SELECT a.id,a.node_id,a.agent_key,a.version,a.status,a.last_heartbeat_at,n.display_name"
            + " FROM ops_agent a JOIN ops_node n ON n.id=a.node_id WHERE n.cluster_id=? ORDER BY"
            + " a.id",
        c.getId());
  }

  @Transactional
  public void disable(Long id) {
    var rows = jdbc.queryForList("SELECT node_id FROM ops_agent WHERE id=?", id);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    var n =
        access.requireNode(
            ((Number) rows.get(0).get("node_id")).longValue(), ClusterPermission.AGENT_MANAGE);
    jdbc.update(
        "UPDATE ops_agent SET status='DISABLED',updated_at=? WHERE id=?",
        LocalDateTime.now(ZoneOffset.UTC),
        id);
    access.audit(n.getClusterId(), "AGENT_DISABLE", id, "需要新的管理员注册令牌才能重新注册");
  }

  public List<Map<String, Object>> tokens() {
    var c = clusters.requireDefault();
    access.require(c.getId(), ClusterPermission.AGENT_TOKEN_ISSUE);
    return jdbc.queryForList(
        "SELECT id,cluster_id,issued_by_user_id,expires_at,used_at,revoked_at,created_at FROM"
            + " ops_agent_enroll_token WHERE cluster_id=? ORDER BY id DESC LIMIT 200",
        c.getId());
  }

  @Transactional
  public void revoke(Long id) {
    var c = clusters.requireDefault();
    access.require(c.getId(), ClusterPermission.AGENT_TOKEN_ISSUE);
    var token =
        tokens
            .lockById(id)
            .filter(t -> c.getId().equals(t.getClusterId()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    if (token.getUsedAt() != null)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "令牌已消费，若要停用 Agent 请禁用对应 Agent");
    token.revoke(Instant.now());
    tokens.save(token);
    access.audit(c.getId(), "TOKEN_REVOKE", id, "revoked");
  }
}
