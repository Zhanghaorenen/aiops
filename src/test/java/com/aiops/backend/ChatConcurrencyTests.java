package com.aiops.backend;

import static org.junit.jupiter.api.Assertions.*;

import com.aiops.backend.service.ChatSessionService;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
class ChatConcurrencyTests extends ClusterTestSupport {
  @Autowired ChatSessionService chats;

  @Test
  void concurrentRequestsAcquireOnlyOneSessionLease() throws Exception {
    String session = chats.create("concurrent").get("id").toString();
    var executor = Executors.newFixedThreadPool(2);
    var gate = new CountDownLatch(1);
    try {
      Callable<Boolean> begin =
          () -> {
            asFixtureAdmin();
            try {
              gate.await();
              chats.begin(session, UUID.randomUUID().toString(), "question");
              return true;
            } catch (ResponseStatusException e) {
              assertEquals(409, e.getStatusCode().value());
              return false;
            } finally {
              SecurityContextHolder.clearContext();
            }
          };
      var a = executor.submit(begin);
      var b = executor.submit(begin);
      gate.countDown();
      assertNotEquals(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
      assertEquals(
          1,
          testJdbc.queryForObject(
              "SELECT COUNT(*) FROM ai_chat_message WHERE session_id=?", Integer.class, session));
    } finally {
      executor.shutdownNow();
      testJdbc.update("DELETE FROM ai_chat_message WHERE session_id=?", session);
      testJdbc.update("DELETE FROM ai_chat_session WHERE id=?", session);
    }
  }
}
