package com.aiops.backend;

import static org.junit.jupiter.api.Assertions.*;
import com.aiops.backend.service.*;
import com.aiops.backend.service.AgentAccessService.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@Transactional
class RuntimeContentionTests extends ClusterTestSupport {
  @Autowired AgentAccessService agents;
  @Autowired RuntimeContentionService service;
  private final Instant base=Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MICROS);
  private EnrollmentResult node() {
    String key=UUID.randomUUID().toString();
    return agents.enroll(new EnrollmentRequest(issue(agents).token(),key,key,key,null,"Linux","test","amd64",8,10000L,key,"test"));
  }
  private Map<String,Object> details(long pid) {
    return new LinkedHashMap<>(Map.of("startedAt",base.minusSeconds(100).toString(),"linuxUid",1000,
        "parentPid",1,"cpuPercent",20,"memoryRssBytes",1000,"memoryUssBytes",700,"readBytesPerSecond",50,"writeBytesPerSecond",10,
        "processTree",List.of(),"processTreeTruncated",false));
  }
  private void sample(EnrollmentResult node,int second,double ioPsi,int gpuUtil,long... pids) {
    var ps=new ArrayList<GpuProcessRequest>();
    for(long pid:pids) ps.add(new GpuProcessRequest(pid,"python","private-user",100L,"Unknown",0,details(pid)));
    var gpu=new GpuSampleRequest(0,"GPU-fixture","Test GPU",1000L,300L,700L,BigDecimal.valueOf(gpuUtil),null,null,null,null,null,ps);
    agents.recordMetric(node.agentId(),new MetricRequest(40.,10000L,2000L,10000L,1000L,base.plusSeconds(second),"AVAILABLE",List.of(gpu),null,
        Map.of("cpuPsiAvg10",0,"memoryPsiAvg10",0,"ioPsiAvg10",ioPsi,"memoryAvailableBytes",8000)));
  }
  private List<?> list(Map<String,Object> report,String key) { return (List<?>)report.get(key); }
  private boolean risk(Map<String,Object> report,String type) { return list(report,"possibleBottlenecks").stream().anyMatch(r->type.equals(((Map<?,?>)r).get("type"))); }

  @Test void singleTaskUsesExistingTablesAndDoesNotMutateRuns() {
    var node=node(); for(int i=0;i<3;i++) sample(node,i*10,0,60,101);
    int before=testJdbc.queryForObject("SELECT COUNT(*) FROM ops_workload_sample",Integer.class);
    var report=service.analyze(node.nodeId());
    assertEquals(1,list(report,"activeRuns").size());assertTrue(list(report,"overlappingRuns").isEmpty());
    assertEquals("NO_OVERLAP_OBSERVED",report.get("status"));
    assertEquals(before,testJdbc.queryForObject("SELECT COUNT(*) FROM ops_workload_sample",Integer.class));
  }
  @Test void twoConcurrentReadersAreOnlyResourceOverlapWithoutPressure() {
    var node=node(); for(int i=0;i<3;i++) sample(node,i*10,0,60,101,202);
    var report=service.analyze(node.nodeId());assertEquals(2,list(report,"activeRuns").size());
    assertEquals(1,list(report,"overlappingRuns").size());
    var pair=(Map<?,?>)list(report,"overlappingRuns").get(0);
    assertEquals("RESOURCE_OVERLAP_ONLY",pair.get("status"));assertEquals(3,pair.get("alignedSampleCount"));
    assertTrue(((Collection<?>)pair.get("sharedResources")).contains("HOST_IO"));
    assertFalse(risk(report,"DATASET_CONTENTION"));assertFalse(risk(report,"HOST_IO_PRESSURE"));
  }
  @Test void lowWholeGpuUtilizationAndIoPsiAreSignalsWithoutProcessAttribution() {
    var node=node();for(int i=0;i<3;i++) sample(node,i*10,30,5,101,202);
    var report=service.analyze(node.nodeId());assertTrue(risk(report,"HOST_IO_PRESSURE"));assertTrue(risk(report,"LOW_GPU_UTILIZATION"));
    assertEquals("WHOLE_GPU_NOT_PER_PROCESS",report.get("gpuUtilizationScope"));
    assertTrue(list(report,"recommendations").toString().contains("候选原因"));
  }
  @Test void missingPsiAndUssStayUnknownInsteadOfZero() {
    var node=node();for(int i=0;i<3;i++) sample(node,i*10,0,60,101);
    testJdbc.update("UPDATE metric_host_sample SET details_json='{}' WHERE node_id=?",node.nodeId());
    testJdbc.update("UPDATE metric_gpu_process_sample SET details_json=? WHERE node_id=?",new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of("startedAt",base.minusSeconds(100).toString(),"linuxUid",1000)),node.nodeId());
    var report=service.analyze(node.nodeId());assertEquals("UNKNOWN",report.get("status"));
    var run=(Map<?,?>)list(report,"activeRuns").get(0);assertNull(((Map<?,?>)run.get("resources")).get("ussBytes"));
    assertTrue(list(report,"missingEvidence").toString().contains("ioPsiAvg10_UNKNOWN"));
  }
  @Test void staleSamplesDoNotDescribeCurrentTasks() {
    var node=node();for(int i=0;i<3;i++) sample(node,-600+i*10,30,5,101);
    var report=service.analyze(node.nodeId());assertEquals("UNKNOWN",report.get("status"));assertTrue(list(report,"activeRuns").isEmpty());
  }
  @Test void hostSamplesOutsideAlignmentToleranceDoNotCreateIoRisk() {
    var node=node();for(int i=0;i<3;i++) sample(node,i*10,30,60,101,202);
    testJdbc.update("UPDATE metric_host_sample SET collected_at=DATEADD('SECOND',-60,collected_at) WHERE node_id=?",node.nodeId());
    var report=service.analyze(node.nodeId());assertFalse(risk(report,"HOST_IO_PRESSURE"));
    assertTrue(list(report,"missingEvidence").toString().contains("HOST_ALIGNMENT_UNKNOWN"));
  }
  @Test void distinctTaskWindowsAreNotMergedAndGapsSplitPairIntervals() {
    var node=node();sample(node,0,0,60,101);sample(node,10,0,60);sample(node,20,0,60,202);
    assertTrue(list(service.analyze(node.nodeId()),"overlappingRuns").isEmpty());
    var peer=node();sample(peer,0,0,60,101,202);sample(peer,10,0,60,101,202);sample(peer,15,0,60,101);sample(peer,20,0,60,101,202);
    var pair=(Map<?,?>)list(service.analyze(peer.nodeId()),"overlappingRuns").get(0);
    assertEquals(2,((List<?>)pair.get("intervals")).size());assertEquals(10L,pair.get("observedOverlapSeconds"));
  }
  @Test void viewerGetsSharedMetricsButNotOtherUsersPrivateProcessIdentity() {
    var node=node();for(int i=0;i<3;i++) sample(node,i*10,0,60,101,202);
    testJdbc.update("INSERT INTO sys_user(username,password_hash,status,created_at,updated_at) VALUES('contention-viewer','test','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    grant("contention-viewer","VIEWER");
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("contention-viewer",null,AuthorityUtils.createAuthorityList("ROLE_USER")));
    var report=service.analyze(node.nodeId());assertEquals(2,list(report,"activeRuns").size());
    for(var r:list(report,"activeRuns")) {assertFalse(((Map<?,?>)r).containsKey("pid"));assertFalse(((Map<?,?>)r).containsKey("linuxUid"));}
    assertFalse(new tools.jackson.databind.ObjectMapper().writeValueAsString(report).contains("private-user"));
    testJdbc.update("UPDATE ops_cluster_member SET status='DISABLED' WHERE user_id=(SELECT id FROM sys_user WHERE username='contention-viewer')");
    assertEquals(403,assertThrows(ResponseStatusException.class,()->service.analyze(node.nodeId())).getStatusCode().value());
  }
}
