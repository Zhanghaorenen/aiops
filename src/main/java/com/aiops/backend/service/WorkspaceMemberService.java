package com.aiops.backend.service;

import com.aiops.backend.entity.ClusterMemberEntity;
import com.aiops.backend.repository.*;
import com.aiops.backend.security.ClusterPermission;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkspaceMemberService {
  private final ClusterRepository clusters;
  private final ClusterMemberRepository members;
  private final UserRepository users;
  private final ClusterPermissionService access;
  private final JdbcTemplate jdbc;

  public WorkspaceMemberService(
      ClusterRepository c,
      ClusterMemberRepository m,
      UserRepository u,
      ClusterPermissionService a,
      JdbcTemplate j) {
    clusters = c;
    members = m;
    users = u;
    access = a;
    jdbc = j;
  }

  public List<Map<String, Object>> audit() {
    var c = clusters.requireDefault();
    access.require(c.getId(), ClusterPermission.MEMBER_MANAGE);
    return jdbc.queryForList(
        "SELECT id,actor_user_id,action,target_id,detail,created_at FROM ops_action_audit WHERE"
            + " cluster_id=? ORDER BY id DESC LIMIT 200",
        c.getId());
  }

  public Map<String, Object> mine() {
    var c = clusters.requireDefault();
    var m = access.require(c.getId(), ClusterPermission.CLUSTER_VIEW);
    return Map.of(
        "id", c.getId(), "name", c.getClusterName(), "role", m.getRole(), "status", m.getStatus());
  }

  public List<Map<String, Object>> list() {
    var c = clusters.requireDefault();
    access.require(c.getId(), ClusterPermission.MEMBER_MANAGE);
    return jdbc.queryForList(
        "SELECT m.user_id AS userId,u.username,m.role,m.status FROM ops_cluster_member m JOIN"
            + " sys_user u ON u.id=m.user_id WHERE m.cluster_id=? ORDER BY m.id",
        c.getId());
  }

  @Transactional
  public void add(String username, String role) {
    var c = clusters.lockDefault();
    access.require(c.getId(), ClusterPermission.MEMBER_MANAGE);
    validRole(role);
    var u =
        users
            .findByUsername(username)
            .filter(x -> "ACTIVE".equals(x.getStatus()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "已注册的有效用户不存在"));
    if (members.findMember(c.getId(), u.getId()).isPresent())
      throw new ResponseStatusException(HttpStatus.CONFLICT, "成员已存在，可修改其状态");
    members.save(new ClusterMemberEntity(c.getId(), u.getId(), role));
    access.audit(c.getId(), "MEMBER_ADD", u.getId(), role);
  }

  @Transactional
  public void update(Long userId, String role, String status) {
    // 同一工作空间的成员变更串行化，避免两个请求同时移除最后一个管理员。
    var c = clusters.lockDefault();
    access.require(c.getId(), ClusterPermission.MEMBER_MANAGE);
    var m =
        members
            .findMember(c.getId(), userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    String nextRole = role == null ? m.getRole() : role,
        nextStatus = status == null ? m.getStatus() : status;
    validRole(nextRole);
    if (!Set.of("ACTIVE", "DISABLED").contains(nextStatus))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "成员状态无效");
    if ("ADMIN".equals(m.getRole())
        && "ACTIVE".equals(m.getStatus())
        && (!"ADMIN".equals(nextRole) || !"ACTIVE".equals(nextStatus))) {
      Long admins =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM ops_cluster_member m JOIN sys_user u ON u.id=m.user_id WHERE"
                  + " m.cluster_id=? AND m.role='ADMIN' AND m.status='ACTIVE' AND"
                  + " u.status='ACTIVE'",
              Long.class,
              c.getId());
      if (admins <= 1)
        throw new ResponseStatusException(HttpStatus.CONFLICT, "不能降级、禁用或移除最后一个有效管理员");
    }
    jdbc.update(
        "UPDATE ops_cluster_member SET role=?,status=?,updated_at=? WHERE id=?",
        nextRole,
        nextStatus,
        java.time.LocalDateTime.now(java.time.ZoneOffset.UTC),
        m.getId());
    access.audit(
        c.getId(),
        "MEMBER_UPDATE",
        userId,
        m.getRole() + "/" + m.getStatus() + " -> " + nextRole + "/" + nextStatus);
  }

  private void validRole(String role) {
    if (!Set.of("ADMIN", "OPERATOR", "VIEWER").contains(role))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色无效");
  }
}
