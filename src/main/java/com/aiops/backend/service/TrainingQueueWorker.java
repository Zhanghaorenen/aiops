package com.aiops.backend.service;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** 使用任务所有者身份重新授权；成员禁用或降权后，后台同样不能继续操作。 */ @Component
@Profile("operations")
public class TrainingQueueWorker {

  private final JdbcTemplate jdbc;
  private final TrainingJobService jobs;

  public TrainingQueueWorker(JdbcTemplate jdbc, TrainingJobService jobs) {
    this.jdbc = jdbc;
    this.jobs = jobs;
  }

  @Scheduled(fixedDelayString = "${aiops.training.queue-delay-ms:30000}")
  public void tick() {
    var previous = SecurityContextHolder.getContext();
    try {
      var rows = jdbc.queryForList(
        "SELECT j.id,j.state,u.server_id,s.username FROM ops_training_job j JOIN ops_gpu_usage u ON u.id=j.id JOIN sys_user s ON s.id=u.user_id JOIN ops_node n ON n.id=u.server_id JOIN ops_cluster_member m ON m.cluster_id=n.cluster_id AND m.user_id=s.id JOIN ops_cluster c ON c.id=n.cluster_id WHERE j.state IN ('QUEUED','RUNNING') AND s.status='ACTIVE' AND m.status='ACTIVE' AND m.role IN ('ADMIN','OPERATOR') AND c.status='ACTIVE' ORDER BY u.updated_at,j.id LIMIT 30"
      );
      for (var row : rows) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
          new UsernamePasswordAuthenticationToken(
            row.get("username"),
            null,
            AuthorityUtils.createAuthorityList("ROLE_USER")
          )
        );
        SecurityContextHolder.setContext(context);
        try {
          if ("RUNNING".equals(row.get("state"))) jobs.observeExit(
            ((Number) row.get("server_id")).longValue(),
            ((Number) row.get("id")).longValue()
          );
          else jobs.recheck(
            ((Number) row.get("server_id")).longValue(),
            ((Number) row.get("id")).longValue()
          );
        } catch (
          org.springframework.web.server.ResponseStatusException ignored
        ) {
          /* 权限变化或并发终态由下轮重新读取。 */
        }
      }
    } finally {
      SecurityContextHolder.setContext(previous);
    }
  }
}
