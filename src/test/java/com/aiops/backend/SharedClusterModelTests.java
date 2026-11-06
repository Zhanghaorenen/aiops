package com.aiops.backend;
import static org.junit.jupiter.api.Assertions.*;
import com.aiops.backend.entity.*;
import com.aiops.backend.repository.*;
import com.aiops.backend.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
@SpringBootTest @Transactional
class SharedClusterModelTests {
 @Autowired ClusterRepository clusters;@Autowired ClusterMemberRepository members;@Autowired UserRepository users;
 @Autowired NodeRepository nodes;@Autowired AgentEnrollTokenRepository tokens;@Autowired ClusterBootstrapService bootstrap;
 @Autowired AuthService auth;@Autowired JdbcTemplate jdbc;
 private UserEntity account(String name){return users.save(new UserEntity(name,"unused-hash"));}
 @Test void defaultClusterAndNewEnrollmentPreserveExistingProtocol(){
  var c=clusters.requireDefault();assertEquals("default-cluster",c.getClusterName());
  var node=nodes.save(new NodeEntity("cluster-node","node","host",null,null,null,null,1,1024L));
  assertEquals(c.getId(),nodes.findById(node.getId()).orElseThrow().getClusterId());
  assertThrows(IllegalStateException.class,()->node.assignCluster(c.getId()+100));
 }
 @Test void membershipUniqueAndForeignKeysAreEnforced(){
  var c=clusters.requireDefault();var user=account("cluster-unique");members.save(new ClusterMemberEntity(c.getId(),user.getId(),"VIEWER"));
  assertThrows(DataIntegrityViolationException.class,()->members.save(new ClusterMemberEntity(c.getId(),user.getId(),"ADMIN")));
 }
 @Test void bootstrapIsOneTimeAndDoesNotRepairChangedMember(){
  var c=clusters.requireDefault();var user=account("cluster-bootstrap");assertTrue(bootstrap.bootstrap(user.getId()));
  var member=members.findMember(c.getId(),user.getId()).orElseThrow();assertEquals("ADMIN",member.getRole());
  jdbc.update("UPDATE ops_cluster_member SET role='VIEWER',status='DISABLED' WHERE id=?",member.getId());
  assertFalse(bootstrap.bootstrap(user.getId()));assertEquals("DISABLED",members.findMember(c.getId(),user.getId()).orElseThrow().getStatus());
  assertEquals("VIEWER",members.findMember(c.getId(),user.getId()).orElseThrow().getRole());
  members.deleteById(member.getId());assertFalse(bootstrap.bootstrap(user.getId()));assertEquals(0,members.countForCluster(c.getId()));
  assertNotNull(clusters.requireDefault().getBootstrapCompletedAt());
 }
 @Test void registrationDoesNotCreateClusterMembership(){
  auth.register("clusterreg","local-test-password-2026");var user=users.findByUsername("clusterreg").orElseThrow();
  assertTrue(members.findMember(clusters.requireDefault().getId(),user.getId()).isEmpty());
 }
 @Test void bootstrapRejectsUnverifiedMissingOrDisabledAccount(){
  assertThrows(IllegalArgumentException.class,()->bootstrap.bootstrap(0L));
  assertThrows(IllegalStateException.class,()->bootstrap.bootstrap(99999999L));
  var user=account("disabledbootstrap");jdbc.update("UPDATE sys_user SET status='DISABLED' WHERE id=?",user.getId());
  assertThrows(IllegalStateException.class,()->bootstrap.bootstrap(user.getId()));
  assertNull(clusters.requireDefault().getBootstrapCompletedAt());
 }
 @Test void bootstrapDoesNotPromoteAnExistingMemberImplicitly(){
  var c=clusters.requireDefault();var user=account("existingviewer");members.save(new ClusterMemberEntity(c.getId(),user.getId(),"VIEWER"));
  assertThrows(IllegalStateException.class,()->bootstrap.bootstrap(user.getId()));assertEquals("VIEWER",members.findMember(c.getId(),user.getId()).orElseThrow().getRole());
 }
 @Test void tokenAuditFieldsSurviveLockAndConsume(){
  var c=clusters.requireDefault();var u=account("tokenissuer");var now=java.time.Instant.now();
  var token=tokens.save(new AgentEnrollTokenEntity("a".repeat(64),now.plusSeconds(300),now,c.getId(),u.getId()));
  var locked=tokens.lockByTokenHash("a".repeat(64)).orElseThrow();assertEquals(c.getId(),locked.getClusterId());assertEquals(u.getId(),locked.getIssuedByUserId());
  locked.markUsed(now);tokens.save(locked);var loaded=tokens.findById(token.getId()).orElseThrow();assertEquals(c.getId(),loaded.getClusterId());assertEquals(u.getId(),loaded.getIssuedByUserId());
 }
}
