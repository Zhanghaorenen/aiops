package com.aiops.backend;

import static org.junit.jupiter.api.Assertions.*;
import com.aiops.backend.service.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

@SpringBootTest
class LongTermMemoryConcurrencyTests {
  @Autowired JdbcTemplate jdbc;
  @Autowired LongTermMemoryService memory;
  @Autowired ChatSessionService chats;
  @Test void concurrentEnqueueAndClaimsDoNotDuplicateTasksOrModelWork() throws Exception {
    String name="memory-race-"+UUID.randomUUID();
    jdbc.update("INSERT INTO sys_user(username,password_hash,status,created_at,updated_at) VALUES(?,'test','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",name);
    long user=jdbc.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,name);
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name,null,AuthorityUtils.createAuthorityList("ROLE_USER")));
    ExecutorService pool=Executors.newFixedThreadPool(2);
    try {
      memory.automatic(true);String sid=chats.create("并发记忆测试").get("id").toString();
      for(int i=0;i<5;i++) {String r=UUID.randomUUID().toString();var lease=chats.begin(sid,r,"我偏好 PyTorch");chats.finish(sid,r,"COMPLETED","已理解",null,lease.get("lease").toString());}
      var barrier=new CyclicBarrier(2);
      var first=pool.submit(()->{barrier.await();memory.enqueue();return memory.claim(false);});
      var second=pool.submit(()->{barrier.await();memory.enqueue();return memory.claim(false);});
      var a=first.get(15,TimeUnit.SECONDS);var b=second.get(15,TimeUnit.SECONDS);
      assertEquals(1,(a==null?0:1)+(b==null?0:1));
      assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_memory_task WHERE session_id=?",Integer.class,sid));
      var claim=a==null?b:a;memory.record(claim,"{\"summary\":\"用户偏好框架。\",\"memories\":[]}");memory.complete(claim);memory.complete(claim);
      assertEquals(1L,((Number)((Map<?,?>)memory.summary(sid)).get("version")).longValue());
    } finally {
      pool.shutdownNow();SecurityContextHolder.clearContext();
      jdbc.update("DELETE FROM ai_chat_memory_task WHERE user_id=?",user);jdbc.update("DELETE FROM ai_chat_summary WHERE user_id=?",user);
      jdbc.update("DELETE FROM ai_chat_message WHERE session_id IN (SELECT id FROM ai_chat_session WHERE user_id=?)",user);
      jdbc.update("DELETE FROM ai_chat_session WHERE user_id=?",user);jdbc.update("DELETE FROM ai_user_memory_setting WHERE user_id=?",user);jdbc.update("DELETE FROM sys_user WHERE id=?",user);
    }
  }
}
