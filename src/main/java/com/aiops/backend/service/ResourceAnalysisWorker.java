package com.aiops.backend.service;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** 只做确定性计算和站内通知，不轮询 LLM，不启动训练。 */
@Component
@Profile("operations")
public class ResourceAnalysisWorker {
  private final JdbcTemplate jdbc;
  private final ResourceNotificationService notifications;
  private final WorkloadService workloads;

  public ResourceAnalysisWorker(JdbcTemplate j, ResourceNotificationService n, WorkloadService w) {
    jdbc = j;
    notifications = n;
    workloads = w;
  }

  @Scheduled(fixedDelayString = "${aiops.resources.check-delay-ms:60000}")
  public void tick() {
    var previous = SecurityContextHolder.getContext();
    try {
      var subscriptions =
          jdbc.queryForList(
              "SELECT s.id,u.username FROM ops_resource_subscription s JOIN sys_user u ON"
                  + " u.id=s.user_id WHERE s.status='ACTIVE' AND u.status='ACTIVE' ORDER BY"
                  + " s.created_at");
      for (var s : subscriptions) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
            new UsernamePasswordAuthenticationToken(
                s.get("username"), null, AuthorityUtils.createAuthorityList("ROLE_USER")));
        SecurityContextHolder.setContext(context);
        try {
          notifications.check(s.get("id").toString());
        } catch (org.springframework.web.server.ResponseStatusException ignored) {
          /* 每次重新授权，失去成员权限的订阅不会收到数据。 */
        }
      }
      var runs =
          jdbc.queryForList(
              "SELECT r.id,r.server_id,u.username FROM ops_workload_run r JOIN sys_user u ON"
                  + " u.id=r.user_id WHERE r.state IN ('RUNNING','MISSING') AND u.status='ACTIVE'");
      for (var r : runs) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
            new UsernamePasswordAuthenticationToken(
                r.get("username"), null, AuthorityUtils.createAuthorityList("ROLE_USER")));
        SecurityContextHolder.setContext(context);
        try {
          workloads.predict(((Number) r.get("server_id")).longValue(), r.get("id").toString());
        } catch (org.springframework.web.server.ResponseStatusException ignored) {
          /* 权限变化后停止私人预测。 */
        }
      }
    } finally {
      SecurityContextHolder.setContext(previous);
    }
  }
}
