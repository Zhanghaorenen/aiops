package com.aiops.backend;

import com.aiops.backend.service.*;
import com.aiops.backend.service.AgentAccessService.*;
import com.aiops.backend.controller.InvestigationProgressController;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@Transactional
class AutonomousInvestigationTests extends ClusterTestSupport {
  @Autowired AgentAccessService agents;
  @Autowired IncidentService incidents;
  @Autowired InvestigationTaskService tasks;
  @Autowired InvestigationPolicyService policies;
  @Autowired AlertRuleService rules;
  @Autowired AlertEvaluationService evaluation;

  private long server() {
    String key=UUID.randomUUID().toString();
    return agents.enroll(new EnrollmentRequest(issue(agents).token(),key,key,key,null,"Linux","test","amd64",8,10000L,key,"test")).nodeId();
  }
  private String run(long server) {
    String id=UUID.randomUUID().toString();
    long user=testJdbc.queryForObject("SELECT id FROM sys_user WHERE username='fixtureAdmin'",Long.class);
    testJdbc.update("INSERT INTO ops_workload_run(id,server_id,user_id,root_pid,process_started_at,linux_uid,linux_username,state,confirmed,grouping_json,first_seen,last_seen) VALUES(?,?,?,123,'2026-01-01T00:00:00Z',1000,'test','RUNNING',FALSE,'{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",id,server,user);
    return id;
  }
  @Test void workloadEntryReusesIncidentAndRequestAndProgressSurvivesReload() {
    long server=server(); String run=run(server);
    var first=incidents.fromWorkload(server,run,"workload_request_001");
    var again=incidents.fromWorkload(server,run,"workload_request_001");
    assertEquals(first.get("incidentId"),again.get("incidentId"));
    var task=tasks.claim();
    assertNotNull(task);
    long id=((Number)task.get("id")).longValue();
    String lease=task.get("lease_token").toString();
    tasks.progress(id,"wrong","{}");
    assertNull(testJdbc.queryForObject("SELECT progress_json FROM ops_investigation_run WHERE id=?",String.class,id));
    tasks.progress(id,lease,"{\"stage\":\"检查主机\"}");
    var detail=incidents.detail(((Number)first.get("incidentId")).longValue());
    assertTrue(detail.get("runs").toString().contains("检查主机"));
    tasks.complete(task,"{\"evidence\":[],\"possible_causes\":[]}",null);
    assertEquals("SUCCEEDED",testJdbc.queryForObject("SELECT status FROM ops_investigation_run WHERE id=?",String.class,id));
    incidents.fromWorkload(server,run,"workload_request_001");
    assertEquals(1,testJdbc.queryForObject("SELECT COUNT(*) FROM ops_investigation_run",Integer.class));
  }
  @Test void crossServerAndOtherUserCannotStartOrReadPrivateRunProgress() {
    long server=server(); String run=run(server);
    assertEquals(404,assertThrows(ResponseStatusException.class,()->incidents.fromWorkload(server(),run,"wrong_server_001")).getStatusCode().value());
    var result=incidents.fromWorkload(server,run,"owner_request_001");
    testJdbc.update("INSERT INTO sys_user(username,password_hash,status,created_at,updated_at) VALUES('investigation-viewer','test','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    grant("investigation-viewer","VIEWER");
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("investigation-viewer",null,AuthorityUtils.createAuthorityList("ROLE_USER")));
    assertEquals(403,assertThrows(ResponseStatusException.class,()->incidents.fromWorkload(server,run,"viewer_request_001")).getStatusCode().value());
    assertTrue(((List<?>)incidents.detail(((Number)result.get("incidentId")).longValue()).get("runs")).isEmpty());
    assertEquals(403,assertThrows(ResponseStatusException.class,()->policies.save(server,new InvestigationPolicyService.Policy(true,"CRITICAL",900,3))).getStatusCode().value());
  }
  @Test void automaticPolicyHonorsDefaultOffCooldownAndDailyBudget() {
    long server=server();
    long agent=testJdbc.queryForObject("SELECT id FROM ops_agent WHERE node_id=?",Long.class,server);
    agents.recordMetric(agent,new MetricRequest(95.,10000L,2000L,10000L,1000L,java.time.Instant.now(),"NO_GPU",List.of(),null,Map.of()));
    for(int i=0;i<2;i++) {
      rules.save(null,new AlertRuleService.Rule("CPU "+i,server,"CPU_HIGH","GE",90,75,0,90,30,0,"CRITICAL",true));
      long rule=testJdbc.queryForObject("SELECT MAX(id) FROM ops_alert_rule",Long.class);
      evaluation.evaluate(rule,java.time.Instant.now().plusSeconds(1));
    }
    policies.trigger(server);
    assertEquals(0,testJdbc.queryForObject("SELECT COUNT(*) FROM ops_investigation_run",Integer.class));
    policies.save(server,new InvestigationPolicyService.Policy(true,"CRITICAL",900,1));
    policies.trigger(server);
    policies.trigger(server);
    assertEquals(1,testJdbc.queryForObject("SELECT COUNT(*) FROM ops_investigation_run",Integer.class));
    testJdbc.update("UPDATE ops_investigation_policy SET last_triggered_at=NULL WHERE server_id=?",server);
    policies.trigger(server);
    assertEquals(1,testJdbc.queryForObject("SELECT COUNT(*) FROM ops_investigation_run",Integer.class));
  }

  @Test void policyDefaultsOffAndRequiresAdminAndProgressRequiresServiceKey() {
    long server=server();
    assertTrue(policies.get(server).toString().contains("enabled=false"));
    policies.save(server,new InvestigationPolicyService.Policy(true,"CRITICAL",900,2));
    assertEquals(List.of(server),policies.enabled());
    var controller=new InvestigationProgressController(tasks,"x".repeat(32));
    assertEquals(403,assertThrows(ResponseStatusException.class,()->controller.progress(1,"lease","bad",new InvestigationProgressController.Progress("检查",0,0,List.of()))).getStatusCode().value());
  }
}
