package com.aiops.backend.service;

import com.aiops.backend.persistence.MetricDetails;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

/** MySQL source of truth, consent fences, durable extraction jobs, and an index outbox. */
@Service
public class LongTermMemoryService {
  private final JdbcTemplate jdbc;
  private final ClusterPermissionService access;
  private final int rounds;
  private final ObjectMapper json=new ObjectMapper();
  public LongTermMemoryService(JdbcTemplate jdbc,ClusterPermissionService access,@Value("${aiops.chat-memory.rounds:5}") int rounds) {
    if(rounds<1 || rounds>20) throw new IllegalArgumentException("Memory rounds must be 1..20");
    this.jdbc=jdbc;this.access=access;this.rounds=rounds;
  }
  private LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
  private long user() { return access.currentUser().getId(); }
  private long n(Object v) { return v instanceof Number num?num.longValue():0; }
  private ResponseStatusException error(int code) { return new ResponseStatusException(HttpStatus.valueOf(code)); }
  private Map<String,Object> canonical(Map<String,Object> row) {
    var result=new LinkedHashMap<String,Object>();row.forEach((k,v)->result.put(k.toLowerCase(Locale.ROOT),v));return result;
  }
  private Map<String,Object> lock(long user) {
    jdbc.queryForMap("SELECT id FROM sys_user WHERE id=? FOR UPDATE",user);
    var rows=jdbc.queryForList("SELECT * FROM ai_user_memory_setting WHERE user_id=? FOR UPDATE",user);
    if(rows.isEmpty()) {
      jdbc.update("INSERT INTO ai_user_memory_setting(user_id,automatic) VALUES(?,FALSE)",user);
      return jdbc.queryForMap("SELECT * FROM ai_user_memory_setting WHERE user_id=?",user);
    }
    return rows.get(0);
  }
  private long highWater(long user) {
    return jdbc.queryForObject("SELECT COALESCE(MAX(m.id),0) FROM ai_chat_message m JOIN ai_chat_session s ON s.id=m.session_id WHERE s.user_id=?",Long.class,user);
  }
  public Map<String,Object> settings() {
    var rows=jdbc.queryForList("SELECT chat_automatic,chat_epoch FROM ai_user_memory_setting WHERE user_id=?",user());
    return Map.of("automatic",!rows.isEmpty()&&Boolean.TRUE.equals(rows.get(0).get("chat_automatic")),"rounds",rounds);
  }
  @Transactional public void automatic(boolean enabled) {
    long u=user(); var previous=lock(u);
    if(Boolean.TRUE.equals(previous.get("chat_automatic"))==enabled) return;
    long after=highWater(u);
    jdbc.update("UPDATE ai_user_memory_setting SET chat_automatic=?,chat_epoch=chat_epoch+1,enabled_after_id=? WHERE user_id=?",enabled,after,u);
    jdbc.update("UPDATE ai_chat_memory_task SET state='CANCELLED',payload_json=NULL,result_json=NULL,lease_token=NULL WHERE user_id=? AND state<>'COMPLETED'",u);
  }
  public List<Map<String,Object>> memories() {
    return jdbc.queryForList("SELECT m.*,i.state AS index_status FROM ai_user_memory m LEFT JOIN ai_memory_index_task i ON i.memory_id=m.id WHERE m.user_id=? AND m.status='ACTIVE' ORDER BY m.updated_at DESC LIMIT 200",user()).stream().map(this::canonical).toList();
  }
  public Map<String,Object> get(String id) {
    var rows=jdbc.queryForList("SELECT * FROM ai_user_memory WHERE id=? AND user_id=?",id,user());
    if(rows.isEmpty()) throw error(404);return canonical(rows.get(0));
  }
  private void revision(Map<String,Object> old) {
    jdbc.update("INSERT INTO ai_user_memory_revision(memory_id,version,user_id,content,category,source_session_id,source_message_id,status,created_at) VALUES(?,?,?,?,?,?,?,'SUPERSEDED',?)",old.get("id"),old.get("version"),old.get("user_id"),old.get("content"),old.get("category"),old.get("source_session_id"),old.get("source_message_id"),now());
  }
  private void index(String id,long user,long version) {
    if(jdbc.update("UPDATE ai_memory_index_task SET desired_version=?,state='PENDING',lease_token=NULL,lease_until=NULL,attempts=0,next_attempt_at=?,error_code=NULL WHERE memory_id=?",version,now(),id)==0)
      jdbc.update("INSERT INTO ai_memory_index_task(memory_id,user_id,desired_version,next_attempt_at) VALUES(?,?,?,?)",id,user,version,now());
  }
  @Transactional public String manual(String id,String category,String content) {
    long u=user();lock(u);MemorySafety.require(category,content);
    long cutoff=highWater(u);
    if(id==null) {
      var duplicate=jdbc.queryForList("SELECT id FROM ai_user_memory WHERE user_id=? AND category=? AND content=? AND status='ACTIVE'",u,category,content.strip());
      if(!duplicate.isEmpty()) return duplicate.get(0).get("id").toString();
      id=UUID.randomUUID().toString();
      jdbc.update("INSERT INTO ai_user_memory(id,user_id,content,source,category,semantic_key,created_at,updated_at,write_after_id) VALUES(?,?,?,'USER_CONFIRMED',?,?,?,?,?)",id,u,content.strip(),category,"manual-"+id,now(),now(),cutoff);
      index(id,u,1);
    } else {
      var old=get(id);if(!"ACTIVE".equals(old.get("status"))) throw error(404);revision(old);
      jdbc.update("UPDATE ai_user_memory SET content=?,category=?,source='USER_CONFIRMED',source_session_id=NULL,source_message_id=NULL,evidence_quote=NULL,confidence=1,version=version+1,updated_at=?,write_after_id=? WHERE id=? AND user_id=?",content.strip(),category,now(),cutoff,id,u);
      index(id,u,n(old.get("version"))+1);
    }
    jdbc.update("UPDATE ai_user_memory_setting SET memory_generation=memory_generation+1 WHERE user_id=?",u);
    return id;
  }
  @Transactional public void delete(String id) {
    long u=user();lock(u);var old=get(id);if(!"ACTIVE".equals(old.get("status"))) throw error(404);
    jdbc.update("UPDATE ai_user_memory SET status='DELETED',content='',evidence_quote=NULL,version=version+1,updated_at=?,write_after_id=? WHERE id=? AND user_id=?",now(),highWater(u),id,u);
    jdbc.update("UPDATE ai_user_memory_revision SET content='',status='DELETED' WHERE memory_id=? AND user_id=?",id,u);
    jdbc.update("UPDATE ai_user_memory_setting SET memory_generation=memory_generation+1 WHERE user_id=?",u);
    index(id,u,n(old.get("version"))+1);
  }
  @Transactional public void clear() {
    long u=user();lock(u);long cutoff=highWater(u);
    var rows=jdbc.queryForList("SELECT id,version FROM ai_user_memory WHERE user_id=?",u);
    jdbc.update("UPDATE ai_user_memory SET status='DELETED',content='',evidence_quote=NULL,version=version+1,updated_at=?,write_after_id=? WHERE user_id=?",now(),cutoff,u);
    jdbc.update("UPDATE ai_user_memory_revision SET content='',status='DELETED' WHERE user_id=?",u);
    jdbc.update("UPDATE ai_chat_summary SET summary='',last_message_id=?,version=version+1,updated_at=? WHERE user_id=?",cutoff,now(),u);
    jdbc.update("UPDATE ai_user_memory_setting SET memory_generation=memory_generation+1,enabled_after_id=? WHERE user_id=?",cutoff,u);
    jdbc.update("UPDATE ai_chat_memory_task SET state='CANCELLED',payload_json=NULL,result_json=NULL,lease_token=NULL WHERE user_id=? AND state<>'COMPLETED'",u);
    rows.forEach(row->index(row.get("id").toString(),u,n(row.get("version"))+1));
  }
  public Object summary(String session) {
    if(jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_session WHERE id=? AND user_id=? AND status<>'DELETED'",Integer.class,session,user())!=1) throw error(404);
    var rows=jdbc.queryForList("SELECT session_id,summary,last_message_id,version,updated_at FROM ai_chat_summary WHERE session_id=? AND user_id=?",session,user());
    var result=rows.isEmpty()?new LinkedHashMap<String,Object>(Map.of("session_id",session,"summary","","version",0)):new LinkedHashMap<>(canonical(rows.get(0)));
    var tasks=jdbc.queryForList("SELECT state,attempts,error_code FROM ai_chat_memory_task WHERE session_id=? AND user_id=? ORDER BY created_at DESC LIMIT 1",session,user());
    if(!tasks.isEmpty()) { result.put("processingState",tasks.get(0).get("state"));result.put("attempts",tasks.get(0).get("attempts")); }
    return result;
  }
  public List<Map<String,Object>> verify(List<Map<String,Object>> references) {
    long u=user();var result=new ArrayList<Map<String,Object>>();
    for(var ref:references.stream().limit(5).toList()) {
      var rows=jdbc.queryForList("SELECT id,category,semantic_key,content,source,source_session_id,confidence,version,updated_at FROM ai_user_memory WHERE id=? AND user_id=? AND version=? AND status='ACTIVE'",ref.get("id"),u,ref.get("version"));
      if(!rows.isEmpty()) result.add(canonical(rows.get(0)));
    }
    return result;
  }

  /** Called only by the asynchronous worker. Each user's mutations share one short DB lock. */
  @Transactional public void enqueue() {
    var sessions=jdbc.queryForList("SELECT eligible.* FROM (SELECT s.id,s.user_id,s.status,"
        + " (SELECT COUNT(*) FROM ai_chat_message m JOIN ai_chat_message a ON a.session_id=m.session_id AND a.request_id=m.request_id AND a.role='assistant' AND a.status='COMPLETED' WHERE m.session_id=s.id AND m.role='user' AND m.status='COMPLETED' AND m.id>GREATEST(st.enabled_after_id,COALESCE(sm.last_message_id,0))) AS pending_turns,"
        + " EXISTS(SELECT 1 FROM ai_chat_memory_task t WHERE t.session_id=s.id AND t.state='COMPLETED' AND t.to_message_id=COALESCE(sm.last_message_id,0) AND t.target_message_id>t.to_message_id AND t.chat_epoch=st.chat_epoch) AS draining"
        + " FROM ai_chat_session s JOIN ai_user_memory_setting st ON st.user_id=s.user_id JOIN sys_user u ON u.id=s.user_id LEFT JOIN ai_chat_summary sm ON sm.session_id=s.id"
        + " WHERE st.chat_automatic=TRUE AND s.status<>'DELETED' AND u.status='ACTIVE' AND NOT EXISTS(SELECT 1 FROM ai_chat_memory_task t WHERE t.session_id=s.id AND t.chat_epoch=st.chat_epoch AND t.memory_generation=st.memory_generation AND t.state IN ('PENDING','RUNNING','EXTRACTED','RETRY','FAILED'))) eligible"
        + " WHERE pending_turns>0 AND (pending_turns>=? OR status='ARCHIVED' OR draining=TRUE) ORDER BY user_id,id LIMIT 200",rounds);
    for(var session:sessions) {
      long u=n(session.get("user_id"));var setting=lock(u);String sid=session.get("id").toString();
      if(!Boolean.TRUE.equals(setting.get("chat_automatic"))) continue;
      if(jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_memory_task WHERE session_id=? AND state IN ('PENDING','RUNNING','EXTRACTED','RETRY')",Integer.class,sid)>0) continue;
      var summaries=jdbc.queryForList("SELECT * FROM ai_chat_summary WHERE session_id=?",sid);
      long cursor=Math.max(n(setting.get("enabled_after_id")),summaries.isEmpty()?0:n(summaries.get(0).get("last_message_id")));
      var turns=jdbc.queryForList("SELECT u.id AS uid,u.content AS question,a.id AS aid,a.content AS answer FROM ai_chat_message u JOIN ai_chat_message a ON a.session_id=u.session_id AND a.request_id=u.request_id AND a.role='assistant' AND a.status='COMPLETED' WHERE u.session_id=? AND u.role='user' AND u.status='COMPLETED' AND u.id>? ORDER BY u.id LIMIT 20",sid,cursor);
      boolean draining=jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_memory_task WHERE session_id=? AND state='COMPLETED' AND to_message_id=? AND target_message_id>to_message_id AND chat_epoch=?",Integer.class,sid,cursor,setting.get("chat_epoch"))>0;
      if(turns.isEmpty() || turns.size()<rounds && !"ARCHIVED".equals(session.get("status")) && !draining) continue;
      if(summaries.isEmpty()) {
        jdbc.update("INSERT INTO ai_chat_summary(session_id,user_id,summary,last_message_id,updated_at) VALUES(?,?,'',?,?)",sid,u,cursor,now());
        summaries=jdbc.queryForList("SELECT * FROM ai_chat_summary WHERE session_id=?",sid);
      }
      var messages=new ArrayList<Map<String,Object>>();int length=0;long to=cursor;
      for(var turn:turns.stream().limit(rounds).toList()) {
        int size=turn.get("question").toString().length()+turn.get("answer").toString().length();
        if(length+size>12000 && !messages.isEmpty()) break;
        messages.add(Map.of("id",turn.get("uid"),"role","user","content",turn.get("question")));
        messages.add(Map.of("id",turn.get("aid"),"role","assistant","content",turn.get("answer")));
        length+=size;to=Math.max(to,n(turn.get("aid")));
      }
      var previous=new ArrayList<Map<String,Object>>();int budget=0;
      for(var memory:jdbc.queryForList("SELECT id,version,category,semantic_key,content FROM ai_user_memory WHERE user_id=? AND status='ACTIVE' ORDER BY updated_at DESC LIMIT 30",u)) {
        var normalized=new LinkedHashMap<String,Object>();memory.forEach((k,v)->normalized.put(k.toLowerCase(Locale.ROOT),v));memory=normalized;
        String text=memory.get("content").toString();memory.put("content",text.substring(0,Math.min(text.length(),500)));
        int size=json.writeValueAsString(memory).length();if(budget+size>6000) break;budget+=size;previous.add(memory);
      }
      String payload=json.writeValueAsString(Map.of("messages",messages,"previous_summary",summaries.get(0).get("summary"),"existing_memories",previous));
      while(payload.length()>32000 && !previous.isEmpty()) {
        previous.remove(previous.size()-1);
        payload=json.writeValueAsString(Map.of("messages",messages,"previous_summary",summaries.get(0).get("summary"),"existing_memories",previous));
      }
      if(payload.length()>32000) continue;
      String id=UUID.randomUUID().toString();
      // User lock prevents duplicate job creation across worker instances.
      if(jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_memory_task WHERE session_id=? AND from_message_id=? AND to_message_id=? AND chat_epoch=? AND memory_generation=?",Integer.class,sid,cursor,to,setting.get("chat_epoch"),setting.get("memory_generation"))>0) continue;
      jdbc.update("INSERT INTO ai_chat_memory_task(id,user_id,session_id,from_message_id,to_message_id,target_message_id,summary_version,chat_epoch,memory_generation,next_attempt_at,payload_json,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",id,u,sid,cursor,to,turns.get(turns.size()-1).get("aid"),summaries.get(0).get("version"),setting.get("chat_epoch"),setting.get("memory_generation"),now(),payload,now(),now());
    }
  }
  @Transactional public Map<String,Object> claim(boolean indexing) {
    String table=indexing?"ai_memory_index_task":"ai_chat_memory_task", pk=indexing?"memory_id":"id";
    var candidates=jdbc.queryForList("SELECT t.*,u.username FROM "+table+" t JOIN sys_user u ON u.id=t.user_id WHERE u.status='ACTIVE' AND (t.state='PENDING' OR t.next_attempt_at<=?) AND (t.state IN ('PENDING','RETRY','EXTRACTED') OR (t.state='RUNNING' AND t.lease_until<?)) AND t.attempts<5 ORDER BY t.next_attempt_at LIMIT 20",now(),now());
    for(var candidate:candidates) {
      var setting=lock(n(candidate.get("user_id")));
      var row=jdbc.queryForMap("SELECT * FROM "+table+" WHERE "+pk+"=? FOR UPDATE",candidate.get(pk));
      if(!indexing && (!Boolean.TRUE.equals(setting.get("chat_automatic")) || n(setting.get("chat_epoch"))!=n(row.get("chat_epoch")) || n(setting.get("memory_generation"))!=n(row.get("memory_generation")))) {
        jdbc.update("UPDATE ai_chat_memory_task SET state='CANCELLED',payload_json=NULL,result_json=NULL,lease_token=NULL,lease_until=NULL WHERE id=?",row.get("id"));continue;
      }
      if(Set.of("COMPLETED","CANCELLED","FAILED").contains(row.get("state")) || row.get("lease_until")!=null && TrainingJobService.instant(row.get("lease_until")).isAfter(Instant.now())) continue;
      if(!"PENDING".equals(row.get("state")) && TrainingJobService.instant(row.get("next_attempt_at")).isAfter(Instant.now())) continue;
      String lease=UUID.randomUUID().toString();
      jdbc.update("UPDATE "+table+" SET state='RUNNING',lease_token=?,lease_until=?,attempts=attempts+1 WHERE "+pk+"=?",lease,now().plusMinutes(3),row.get(pk));
      row.put("lease_token",lease);row.put("username",candidate.get("username"));return row;
    }
    return null;
  }
  public Map<String,Object> task(String id,String lease) {
    var rows=jdbc.queryForList("SELECT t.* FROM ai_chat_memory_task t JOIN ai_user_memory_setting st ON st.user_id=t.user_id JOIN ai_chat_session s ON s.id=t.session_id WHERE t.id=? AND t.user_id=? AND t.lease_token=? AND t.lease_until>? AND t.state='RUNNING' AND st.chat_automatic=TRUE AND st.chat_epoch=t.chat_epoch AND st.memory_generation=t.memory_generation AND s.status<>'DELETED'",id,user(),lease,now());
    if(rows.isEmpty()) throw error(409);return rows.get(0);
  }
  @Transactional public void record(Map<String,Object> claim,String result) {
    long u=n(claim.get("user_id"));lock(u);
    var row=jdbc.queryForMap("SELECT * FROM ai_chat_memory_task WHERE id=? FOR UPDATE",claim.get("id"));
    if(!Objects.equals(row.get("lease_token"),claim.get("lease_token")) || row.get("lease_until")==null || TrainingJobService.instant(row.get("lease_until")).isBefore(Instant.now())) throw error(409);
    if(result.length()>16000) throw error(400);
    jdbc.update("UPDATE ai_chat_memory_task SET result_json=?,state='EXTRACTED',updated_at=? WHERE id=?",result,now(),claim.get("id"));
  }
  @Transactional public void complete(Map<String,Object> claim) {
    long u=n(claim.get("user_id"));var setting=lock(u);
    var task=jdbc.queryForMap("SELECT * FROM ai_chat_memory_task WHERE id=? FOR UPDATE",claim.get("id"));
    if("COMPLETED".equals(task.get("state"))) return;
    if(!Objects.equals(task.get("lease_token"),claim.get("lease_token")) || task.get("lease_until")==null || TrainingJobService.instant(task.get("lease_until")).isBefore(Instant.now())) throw error(409);
    if(!Boolean.TRUE.equals(setting.get("chat_automatic")) || n(setting.get("chat_epoch"))!=n(task.get("chat_epoch")) || n(setting.get("memory_generation"))!=n(task.get("memory_generation"))
        || jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_session WHERE id=? AND user_id=? AND status<>'DELETED'",Integer.class,task.get("session_id"),u)!=1) {
      jdbc.update("UPDATE ai_chat_memory_task SET state='CANCELLED',lease_token=NULL,payload_json=NULL,result_json=NULL WHERE id=?",task.get("id"));return;
    }
    var summary=jdbc.queryForMap("SELECT * FROM ai_chat_summary WHERE session_id=?",task.get("session_id"));
    if(n(summary.get("version"))!=n(task.get("summary_version"))) throw error(409);
    var result=MetricDetails.decode((String)task.get("result_json"));
    Object text=result.get("summary");
    if(!(text instanceof String s) || s.length()>2000 || !MemorySafety.safe(s) || !(result.get("memories") instanceof List<?> list) || list.size()>5) throw error(400);
    var payload=MetricDetails.decode((String)task.get("payload_json"));
    for(Object value:list) if(value instanceof Map<?,?> memory) apply(u,task,memory,payload);
    jdbc.update("UPDATE ai_chat_summary SET summary=?,last_message_id=?,version=version+1,updated_at=? WHERE session_id=?",text,task.get("to_message_id"),now(),task.get("session_id"));
    jdbc.update("UPDATE ai_chat_memory_task SET state='COMPLETED',lease_token=NULL,lease_until=NULL,payload_json=NULL,result_json=NULL,updated_at=? WHERE id=?",now(),task.get("id"));
  }
  private void apply(long u,Map<String,Object> task,Map<?,?> memory,Map<String,Object> payload) {
    String category=Objects.toString(memory.get("category"),""),content=Objects.toString(memory.get("content"),"").strip(),slot=Objects.toString(memory.get("key"),"");
    long source=n(memory.get("source_message_id"));String quote=Objects.toString(memory.get("evidence_quote"),"");
    if(!MemorySafety.CATEGORIES.contains(category) || !slot.matches("[a-zA-Z0-9_.:-]{1,100}") || content.isBlank() || content.length()>500 || !MemorySafety.durable(content) || !MemorySafety.durable(quote)
        || !(memory.get("confidence") instanceof Number c) || !Double.isFinite(c.doubleValue()) || c.doubleValue()<.8 || c.doubleValue()>1 || quote.isBlank() || quote.length()>500) return;
    if(!Set.of("USER_STATED","USER_CONFIRMED").contains(memory.get("source_kind"))) return;
    if("CONFIRMED_EXPERIENCE".equals(category) && !"USER_CONFIRMED".equals(memory.get("source_kind"))) return;
    if(!(payload.get("messages") instanceof List<?> messages) || messages.stream().noneMatch(v->v instanceof Map<?,?> m && "user".equals(m.get("role")) && n(m.get("id"))==source && m.get("content") instanceof String original && original.contains(quote))) return;
    var rows=jdbc.queryForList("SELECT * FROM ai_user_memory WHERE user_id=? AND category=? AND semantic_key=?",u,category,slot);
    if(memory.get("replaces_memory_id") instanceof String replaced && !replaced.isBlank()) {
      rows=jdbc.queryForList("SELECT * FROM ai_user_memory WHERE id=? AND user_id=? AND category=?",replaced,u,category);
      if(rows.isEmpty() || n(memory.get("expected_version"))!=n(rows.get(0).get("version"))) return;
      slot=Objects.toString(rows.get(0).get("semantic_key"),"legacy-"+replaced);
    }
    if(!rows.isEmpty() && source<=Math.max(n(rows.get(0).get("write_after_id")),n(rows.get(0).get("source_message_id")))) return;
    if(!rows.isEmpty() && "ACTIVE".equals(rows.get(0).get("status")) && content.equals(rows.get(0).get("content"))) {
      jdbc.update("UPDATE ai_user_memory SET write_after_id=? WHERE id=? AND user_id=?",source,rows.get(0).get("id"),u);
      return;
    }
    String id;long version=1;
    if(rows.isEmpty()) {
      if(jdbc.queryForObject("SELECT COUNT(*) FROM ai_user_memory WHERE user_id=? AND category=? AND content=? AND status='ACTIVE'",Integer.class,u,category,content)>0) return;
      id=UUID.randomUUID().toString();
      jdbc.update("INSERT INTO ai_user_memory(id,user_id,content,category,semantic_key,source,source_session_id,source_message_id,evidence_quote,confidence,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",id,u,content,category,slot,memory.get("source_kind"),task.get("session_id"),source,quote,memory.get("confidence"),now(),now());
    } else {
      var old=rows.get(0);id=old.get("id").toString();version=n(old.get("version"))+1;revision(old);
      jdbc.update("UPDATE ai_user_memory SET content=?,semantic_key=?,source=?,source_session_id=?,source_message_id=?,evidence_quote=?,confidence=?,status='ACTIVE',version=?,updated_at=? WHERE id=?",content,slot,memory.get("source_kind"),task.get("session_id"),source,quote,memory.get("confidence"),version,now(),id);
    }
    index(id,u,version);
  }
  @Transactional public void finishIndex(Map<String,Object> claim) {
    lock(n(claim.get("user_id")));
    jdbc.update("UPDATE ai_memory_index_task SET state='COMPLETED',lease_token=NULL,lease_until=NULL,error_code=NULL WHERE memory_id=? AND desired_version=? AND lease_token=?",claim.get("memory_id"),claim.get("desired_version"),claim.get("lease_token"));
  }
  @Transactional public void retry() {
    long u=user();var setting=lock(u);
    jdbc.update("UPDATE ai_chat_memory_task SET state='PENDING',attempts=0,next_attempt_at=?,lease_token=NULL,lease_until=NULL,error_code=NULL WHERE user_id=? AND state='FAILED' AND chat_epoch=? AND memory_generation=?",now(),u,setting.get("chat_epoch"),setting.get("memory_generation"));
    jdbc.update("UPDATE ai_memory_index_task SET state='PENDING',attempts=0,next_attempt_at=?,lease_token=NULL,lease_until=NULL,error_code=NULL WHERE user_id=? AND state='FAILED'",now(),u);
  }
  @Transactional public void fail(Map<String,Object> claim,boolean indexing) {
    lock(n(claim.get("user_id")));String table=indexing?"ai_memory_index_task":"ai_chat_memory_task",pk=indexing?"memory_id":"id";
    jdbc.update("UPDATE "+table+" SET state=CASE WHEN attempts>=5 THEN 'FAILED' ELSE 'RETRY' END,lease_token=NULL,lease_until=NULL,next_attempt_at=?,error_code='MEMORY_DEPENDENCY_FAILED' WHERE "+pk+"=? AND lease_token=?",now().plusSeconds(30),claim.get(pk),claim.get("lease_token"));
  }
}
