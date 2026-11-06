package com.aiops.backend.service;

import com.aiops.backend.entity.*;
import com.aiops.backend.repository.*;
import com.aiops.backend.security.ClusterPermission;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 每次按数据库中的成员状态授权，JWT 只承担身份认证，降级和禁用立即生效。 */
@Service
public class ClusterPermissionService {
  private final UserRepository users;
  private final NodeRepository nodes;
  private final ClusterMemberRepository members;
  private final ClusterRepository clusters;
  private final JdbcTemplate jdbc;
  private static final Set<ClusterPermission> READ =
      EnumSet.of(
          ClusterPermission.CLUSTER_VIEW,
          ClusterPermission.SERVER_VIEW,
          ClusterPermission.METRIC_VIEW,
          ClusterPermission.GPU_VIEW,
          ClusterPermission.AI_DIAGNOSE);

  public ClusterPermissionService(
      UserRepository u,
      NodeRepository n,
      ClusterMemberRepository m,
      ClusterRepository c,
      JdbcTemplate j) {
    users = u;
    nodes = n;
    members = m;
    clusters = c;
    jdbc = j;
  }

  public UserEntity currentUser() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null
        || !auth.isAuthenticated()
        || auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_USER")))
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    return users
        .findByUsername(auth.getName())
        .filter(u -> "ACTIVE".equals(u.getStatus()))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
  }

  public ClusterMemberEntity require(Long clusterId, ClusterPermission permission) {
    var user = currentUser();
    var member =
        members
            .findMember(clusterId, user.getId())
            .filter(m -> "ACTIVE".equals(m.getStatus()))
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.FORBIDDEN, "尚未加入该工作空间或成员已被禁用"));
    var cluster =
        clusters
            .findById(clusterId)
            .filter(c -> "ACTIVE".equals(c.getStatus()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "工作空间不可用"));
    boolean allowed =
        "ADMIN".equals(member.getRole())
            || READ.contains(permission) && Set.of("OPERATOR", "VIEWER").contains(member.getRole())
            || "OPERATOR".equals(member.getRole())
                && Set.of(
                        ClusterPermission.ALERT_RULE_MANAGE,
                        ClusterPermission.ALERT_ACKNOWLEDGE,
                        ClusterPermission.INCIDENT_RESOLVE)
                    .contains(permission);
    if (!allowed) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前角色没有该操作权限");
    return member;
  }

  public NodeEntity requireNode(Long nodeId, ClusterPermission permission) {
    var node =
        nodes
            .findById(nodeId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "服务器不存在"));
    require(node.getClusterId(), permission);
    return node;
  }

  public List<Long> readableClusterIds() {
    return jdbc.queryForList(
        "SELECT c.id FROM ops_cluster c JOIN ops_cluster_member m ON m.cluster_id=c.id WHERE"
            + " m.user_id=? AND m.status='ACTIVE' AND c.status='ACTIVE' AND m.role IN"
            + " ('ADMIN','OPERATOR','VIEWER')",
        Long.class,
        currentUser().getId());
  }

  public void audit(Long cluster, String action, Long target, String detail) {
    jdbc.update(
        "INSERT INTO ops_action_audit(cluster_id,actor_user_id,action,target_id,detail,created_at)"
            + " VALUES(?,?,?,?,?,?)",
        cluster,
        currentUser().getId(),
        action,
        target,
        detail,
        java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
  }
}
