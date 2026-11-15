package com.aiops.backend;

import static org.junit.jupiter.api.Assertions.*;
import com.aiops.backend.service.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Transactional
class LongTermMemoryTests extends ClusterTestSupport {
  @Autowired LongTermMemoryService memory;
  @Autowired ChatSessionService chats;
  @Autowired UserMemoryService oldMemory;
  private final ObjectMapper json=new ObjectMapper();
  private String session() { return chats.create("记忆测试").get("id").toString(); }
  private void turn(String sid,String question) {
    String request=UUID.randomUUID().toString();var lease=chats.begin(sid,request,question);
    chats.finish(sid,request,"COMPLETED","好的，已理解。",null,lease.get("lease").toString());
  }
  private Map<String,Object> job(String sid,String question) {
    for(int i=0;i<5;i++) turn(sid,question);memory.enqueue();
    var job=memory.claim(false);assertNotNull(job,()->testJdbc.queryForList("SELECT state,attempts,chat_epoch,memory_generation,next_attempt_at,lease_until FROM ai_chat_memory_task").toString());return job;
  }
  @SuppressWarnings("unchecked")
  private String result(Map<String,Object> job,String content,String key,String replace,Long version) {
    var payload=json.readValue(job.get("payload_json").toString(),Map.class);
    var messages=(List<Map<String,Object>>)payload.get("messages");
    var source=messages.stream().filter(m->m.get("role").equals("user")).findFirst().orElseThrow();
    var item=new LinkedHashMap<String,Object>();item.put("category","USER_PREFERENCE");item.put("key",key);item.put("content",content);
    item.put("source_kind","USER_STATED");item.put("source_message_id",source.get("id"));item.put("evidence_quote",source.get("content"));item.put("confidence",.95);
    if(replace!=null){item.put("replaces_memory_id",replace);item.put("expected_version",version);}
    return json.writeValueAsString(Map.of("summary","用户已说明持续偏好。","memories",List.of(item)));
  }
  private void complete(Map<String,Object> job,String content) {
    memory.record(job,result(job,content,"gpu_preference",null,null));memory.complete(job);
  }
  @Test void consentDefaultsOffAndDoesNotChangeIncidentExperienceSwitch() {
    assertEquals(false,memory.settings().get("automatic"));oldMemory.automatic(true);memory.automatic(true);
    assertTrue(oldMemory.automatic());oldMemory.automatic(false);assertEquals(true,memory.settings().get("automatic"));
    memory.automatic(false);assertFalse(oldMemory.automatic());assertEquals(false,memory.settings().get("automatic"));
  }
  @Test void fiveCompletedTurnsProduceOneIncrementalSummaryAndNoPerTurnExtraction() {
    memory.automatic(true);String sid=session();for(int i=0;i<4;i++)turn(sid,"我偏好 GPU 0");
    memory.enqueue();assertNull(memory.claim(false));turn(sid,"我偏好 GPU 0");memory.enqueue();var job=memory.claim(false);assertNotNull(job,()->testJdbc.queryForList("SELECT state,attempts,chat_epoch,memory_generation,next_attempt_at,lease_until FROM ai_chat_memory_task").toString());
    complete(job,"用户偏好 GPU 0");memory.complete(job);
    assertEquals(1,memory.memories().size());assertEquals(1L,((Number)((Map<?,?>)memory.summary(sid)).get("version")).longValue());
    turn(sid,"我偏好 GPU 0");memory.enqueue();assertNull(memory.claim(false));
  }
  @Test void archivedSessionSummarizesRemainderAndOldUnconsentedMessagesAreNotRead() {
    String old=session();for(int i=0;i<5;i++)turn(old,"我偏好旧环境");memory.automatic(true);memory.enqueue();assertNull(memory.claim(false));
    String sid=session();turn(sid,"我偏好 PyTorch");chats.update(sid,null,"ARCHIVED");memory.enqueue();var job=memory.claim(false);assertNotNull(job,()->testJdbc.queryForList("SELECT state,attempts,chat_epoch,memory_generation,next_attempt_at,lease_until FROM ai_chat_memory_task").toString());
    complete(job,"用户偏好 PyTorch");assertEquals(1,memory.memories().size());
  }
  @Test void duplicateExtractionKeepsOneMemoryAndPreferenceChangeUpdatesVersion() {
    memory.automatic(true);String sid=session();complete(job(sid,"我偏好 GPU 0"),"用户偏好 GPU 0");
    var first=memory.memories().get(0);String id=first.get("id").toString();
    complete(job(sid,"我偏好 GPU 0"),"用户偏好 GPU 0");assertEquals(1,memory.memories().size());assertEquals(1L,((Number)memory.get(id).get("version")).longValue());
    var changed=job(sid,"我更偏好 GPU 1");memory.record(changed,result(changed,"用户更偏好 GPU 1","gpu_preference",id,1L));memory.complete(changed);
    assertEquals(1,memory.memories().size());assertEquals(2L,((Number)memory.get(id).get("version")).longValue());
    assertTrue(memory.verify(List.of(Map.of("id",id,"version",1))).isEmpty());
    assertEquals(1,memory.verify(List.of(Map.of("id",id,"version",2))).size());
    assertEquals(1,testJdbc.queryForObject("SELECT COUNT(*) FROM ai_user_memory_revision WHERE memory_id=?",Integer.class,id));
    memory.manual(id,"USER_PREFERENCE","用户偏好 GPU 2");
    assertNull(memory.get(id).get("source_session_id"));assertNull(memory.get(id).get("evidence_quote"));
  }
  @Test void editingDeletingAndClearingImmediatelyInvalidateAllOldVectorReferences() {
    String id=memory.manual(null,"WORKFLOW","我喜欢先检查资源再训练");memory.manual(id,"WORKFLOW","我喜欢先做容量预算");
    assertTrue(memory.verify(List.of(Map.of("id",id,"version",1))).isEmpty());
    memory.delete(id);assertTrue(memory.verify(List.of(Map.of("id",id,"version",2))).isEmpty());
    assertEquals("DELETED",memory.get(id).get("status"));assertEquals("",memory.get(id).get("content"));
    String next=memory.manual(null,"ENVIRONMENT","我使用 PyTorch");memory.clear();assertTrue(memory.memories().isEmpty());
    assertTrue(memory.verify(List.of(Map.of("id",next,"version",1))).isEmpty());
    assertEquals("PENDING",testJdbc.queryForObject("SELECT state FROM ai_memory_index_task WHERE memory_id=?",String.class,next));
  }
  @Test void disableAndManualChangesFenceInFlightExtraction() {
    memory.automatic(true);String sid=session();var pending=job(sid,"我偏好 GPU 0");memory.record(pending,result(pending,"用户偏好 GPU 0","gpu_preference",null,null));
    memory.manual(null,"ENVIRONMENT","我使用 JAX");memory.complete(pending);
    assertEquals(1,memory.memories().size());assertEquals("ENVIRONMENT",memory.memories().get(0).get("category"));
    memory.automatic(false);memory.enqueue();assertNull(memory.claim(false));
  }
  @Test void savedModelResultSurvivesRetryWithoutReextractingAndOldLeasesAreRejected() {
    memory.automatic(true);var pending=job(session(),"我偏好 GPU 0");memory.record(pending,result(pending,"用户偏好 GPU 0","gpu_preference",null,null));
    testJdbc.update("UPDATE ai_chat_memory_task SET lease_until=? WHERE id=?",LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1),pending.get("id"));
    var retry=memory.claim(false);assertNotNull(retry.get("result_json"));
    assertThrows(ResponseStatusException.class,()->memory.complete(pending));memory.complete(retry);assertEquals(1,memory.memories().size());
  }
  @Test void failedCancelledAndReplayMessagesDoNotCountOrEnterPayload() {
    memory.automatic(true);String sid=session();String request=UUID.randomUUID().toString();var lease=chats.begin(sid,request,"失败内容");
    chats.finish(sid,request,"FAILED",null,null,lease.get("lease").toString());
    request=UUID.randomUUID().toString();lease=chats.begin(sid,request,"中断内容");chats.finish(sid,request,"CANCELLED",null,null,lease.get("lease").toString());
    var job=job(sid,"我偏好 GPU 0");assertFalse(job.get("payload_json").toString().contains("失败内容"));assertFalse(job.get("payload_json").toString().contains("中断内容"));
  }
  @Test void otherUsersCannotReadEditDeleteOrVerifyPrivateMemories() {
    String id=memory.manual(null,"WORKFLOW","我习惯夜间训练");
    testJdbc.update("INSERT INTO sys_user(username,password_hash,status,created_at,updated_at) VALUES('long-memory-other','test','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("long-memory-other",null,org.springframework.security.core.authority.AuthorityUtils.createAuthorityList("ROLE_USER")));
    assertEquals(404,assertThrows(ResponseStatusException.class,()->memory.get(id)).getStatusCode().value());
    assertThrows(ResponseStatusException.class,()->memory.manual(id,"WORKFLOW","新内容"));assertThrows(ResponseStatusException.class,()->memory.delete(id));
    assertTrue(memory.verify(List.of(Map.of("id",id,"version",1))).isEmpty());assertTrue(memory.memories().isEmpty());
  }
  @Test void secretsAndAssistantAuthoredPreferencesAreNotSavedAsFacts() {
    assertThrows(ResponseStatusException.class,()->memory.manual(null,"WORKFLOW","password=private-test-value"));
    memory.automatic(true);var job=job(session(),"请给我建议");
    String response=result(job,"用户偏好 GPU 0","gpu_preference",null,null).replace("请给我建议","助手建议用户偏好 GPU 0");
    memory.record(job,response);memory.complete(job);assertTrue(memory.memories().isEmpty());
  }
  @Test void longConversationDrainsOnlyOriginalBatchWithBoundedRollingSummaries() {
    memory.automatic(true);String sid=session();
    for(int i=0;i<5;i++) {
      String r=UUID.randomUUID().toString();var lease=chats.begin(sid,r,"我习惯先检查资源。"+"a".repeat(3000));
      chats.finish(sid,r,"COMPLETED","b".repeat(7000),null,lease.get("lease").toString());
    }
    int batches=0;
    for(int i=0;i<6;i++) {
      memory.enqueue();var claim=memory.claim(false);if(claim==null)break;
      assertTrue(claim.get("payload_json").toString().length()<=32000);
      memory.record(claim,"{\"summary\":\"已滚动压缩的用户工作流程。\",\"memories\":[]}");memory.complete(claim);batches++;
    }
    assertEquals(5,batches);turn(sid,"我偏好稳定环境");memory.enqueue();assertNull(memory.claim(false));
  }
  @Test void olderSessionBacklogCannotOverwriteNewerPreference() {
    memory.automatic(true);String older=session(),newer=session();
    for(int i=0;i<5;i++)turn(older,"我偏好 GPU 0");
    for(int i=0;i<5;i++)turn(newer,"我更偏好 GPU 1");
    memory.enqueue();
    testJdbc.update("UPDATE ai_chat_memory_task SET state='RUNNING',lease_until=? WHERE session_id=?",LocalDateTime.now(ZoneOffset.UTC).plusMinutes(3),older);
    var newJob=memory.claim(false);assertEquals(newer,newJob.get("session_id"));complete(newJob,"用户更偏好 GPU 1");
    testJdbc.update("UPDATE ai_chat_memory_task SET state='PENDING',lease_until=NULL WHERE session_id=?",older);
    var oldJob=memory.claim(false);assertEquals(older,oldJob.get("session_id"));complete(oldJob,"用户偏好 GPU 0");
    assertEquals(1,memory.memories().size());assertEquals("用户更偏好 GPU 1",memory.memories().get(0).get("content"));
  }
  @Test void unchangedReaffirmationAlsoFencesOlderQueuedPreferencesWithoutVersionChurn() {
    memory.automatic(true);complete(job(session(),"我偏好 GPU 0"),"用户偏好 GPU 0");
    String older=session(),newer=session();
    for(int i=0;i<5;i++)turn(older,"我更偏好 GPU 1");
    for(int i=0;i<5;i++)turn(newer,"我仍偏好 GPU 0");
    memory.enqueue();
    testJdbc.update("UPDATE ai_chat_memory_task SET state='RUNNING',lease_until=? WHERE session_id=?",LocalDateTime.now(ZoneOffset.UTC).plusMinutes(3),older);
    complete(memory.claim(false),"用户偏好 GPU 0");
    testJdbc.update("UPDATE ai_chat_memory_task SET state='PENDING',lease_until=NULL WHERE session_id=?",older);
    complete(memory.claim(false),"用户偏好 GPU 1");
    assertEquals("用户偏好 GPU 0",memory.memories().get(0).get("content"));
    assertEquals(1L,((Number)memory.memories().get(0).get("version")).longValue());
  }
}
