package com.aiops.backend;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

/** 老测试的资产准备也必须由真实的工作空间管理员进行，不在生产代码中添加测试后门。 */
public abstract class ClusterTestSupport {
  @Autowired protected JdbcTemplate testJdbc;

  @BeforeEach
  void authorizeFixture() {
    testJdbc.update(
        "INSERT INTO sys_user(username,password_hash,status,created_at,updated_at) SELECT"
            + " 'fixtureAdmin','test-hash','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT"
            + " EXISTS(SELECT 1 FROM sys_user WHERE username='fixtureAdmin')");
    grant("fixtureAdmin", "ADMIN");
    asFixtureAdmin();
  }

  protected void grant(String username, String role) {
    testJdbc.update(
        "INSERT INTO ops_cluster_member(cluster_id,user_id,role,status,created_at,updated_at)"
            + " SELECT c.id,u.id,?,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP FROM ops_cluster"
            + " c,sys_user u WHERE c.cluster_name='default-cluster' AND u.username=? AND NOT"
            + " EXISTS(SELECT 1 FROM ops_cluster_member m WHERE m.cluster_id=c.id AND"
            + " m.user_id=u.id)",
        role,
        username);
  }

  public static void asFixtureAdmin() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "fixtureAdmin", null, AuthorityUtils.createAuthorityList("ROLE_USER")));
  }

  public static com.aiops.backend.service.AgentAccessService.EnrollmentToken issue(
      com.aiops.backend.service.AgentAccessService service) {
    var old = SecurityContextHolder.getContext();
    var context = SecurityContextHolder.createEmptyContext();
    SecurityContextHolder.setContext(context);
    try {
      asFixtureAdmin();
      return service.issueEnrollmentToken();
    } finally {
      SecurityContextHolder.setContext(old);
    }
  }

  @AfterEach
  void clearFixtureAuthentication() {
    SecurityContextHolder.clearContext();
    testJdbc.update(
        "DELETE FROM ops_cluster_member WHERE user_id IN (SELECT id FROM sys_user WHERE"
            + " username='fixtureAdmin')");
  }
}
