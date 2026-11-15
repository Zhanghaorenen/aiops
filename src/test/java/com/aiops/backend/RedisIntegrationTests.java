package com.aiops.backend;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.aiops.backend.entity.HostMetricSampleEntity;
import com.aiops.backend.redis.*;
import com.aiops.backend.service.*;
import com.aiops.backend.service.AgentAccessService.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="AIOPS_TEST_REDIS_PORT",matches=".+")
class RedisIntegrationTests extends ClusterTestSupport {
  private static final String PREFIX="aiops:test:"+UUID.randomUUID()+":";
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
    r.add("aiops.redis.enabled",()->true);
    r.add("aiops.redis.cache-ttl",()->"200ms");
    r.add("aiops.redis.key-prefix",()->PREFIX);
    r.add("spring.data.redis.host",()->"127.0.0.1");
    r.add("spring.data.redis.port",()->System.getenv("AIOPS_TEST_REDIS_PORT"));
    r.add("spring.data.redis.password",()->System.getenv("AIOPS_TEST_REDIS_PASSWORD"));
    r.add("spring.data.redis.connect-timeout",()->"200ms");
    r.add("spring.data.redis.timeout",()->"200ms");
  }
  @Autowired ResourceCache cache;
  @Autowired StringRedisTemplate redis;
  @Autowired NotificationEvents events;
  @Autowired PlatformTransactionManager transactions;
  @Autowired AgentAccessService agents;
  @Autowired MonitoringQueryService monitoring;
  @Autowired GpuManagerService gpus;
  @Autowired ResourceNotificationService notifications;
  @Autowired MockMvc mvc;

  @AfterEach void clearRedis() {
    Set<String> keys=redis.keys(PREFIX+"*");
    if (keys!=null && !keys.isEmpty()) redis.delete(keys);
  }

  @Test void typedCacheHitTtlInvalidationAndOriginalSamplingTime() throws Exception {
    var calls=new AtomicInteger(); Instant sampled=Instant.now().minusSeconds(60);
    java.util.function.Supplier<HostMetricSampleEntity> db=()-> {
      calls.incrementAndGet();
      var host=new HostMetricSampleEntity(42L,9L,20.,1000L,100L,10.,2000L,500L,25.,sampled,sampled);
      host.setDetailsJson("{\"memoryAvailableBytes\":800}"); return host;
    };
    cache.get(42,"host",HostMetricSampleEntity.class,db);
    var hit=cache.get(42,"host",HostMetricSampleEntity.class,db);
    assertEquals(1,calls.get()); assertEquals(sampled,hit.getCollectedAt());
    assertEquals(800,((Number)hit.getDetails().get("memoryAvailableBytes")).intValue());
    Thread.sleep(260);
    cache.get(42,"host",HostMetricSampleEntity.class,db); assertEquals(2,calls.get());
    new TransactionTemplate(transactions).executeWithoutResult(s -> cache.invalidateAfterCommit(42));
    cache.get(42,"host",HostMetricSampleEntity.class,db); assertEquals(3,calls.get());
    assertTrue(((Number)cache.stats().get("cacheHits")).longValue()>0);
  }

  @Test void disconnectedRedisFallsBackWithoutChangingDatabaseResult() {
    var factory=new LettuceConnectionFactory("127.0.0.1",1);
    factory.afterPropertiesSet(); factory.start();
    try {
      var offline=new ResourceCache(new StringRedisTemplate(factory),true,PREFIX,Duration.ofSeconds(5));
      assertEquals("from-mysql",offline.get(42,"offline",String.class,()->"from-mysql"));
      assertEquals(1L,offline.stats().get("cacheErrors"));
    } finally { factory.destroy(); }
  }

  private EnrollmentResult node() {
    return agents.enroll(new EnrollmentRequest(agents.issueEnrollmentToken().token(),
        "redis-"+UUID.randomUUID(),"Redis fixture","redis-host","127.0.0.1","Linux","test","amd64",4,4096L,
        UUID.randomUUID().toString(),"test"));
  }

  @Test void realGpuCacheReauthorizesAndIngestionInvalidatesAfterCommit() {
    var node=node(); var at=Instant.now().minusSeconds(2).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    var gpu=new GpuSampleRequest(0,"GPU-test","Test GPU",1000L,100L,900L,
        java.math.BigDecimal.TEN,null,null,null,null,null,List.of());
    agents.recordMetric(node.agentId(),new MetricRequest(10.,4096L,512L,2048L,100L,at,"AVAILABLE",List.of(gpu),null));
    gpus.getServerGpuOverview(node.nodeId());
    long before=((Number)cache.stats().get("cacheHits")).longValue();
    var hit=gpus.getServerGpuOverview(node.nodeId());
    assertTrue(((Number)cache.stats().get("cacheHits")).longValue()>before);
    assertEquals(at,hit.get("collectedAt"));
    assertEquals(900L,gpus.getGpuLatest(node.nodeId(),0).get("gpuMemoryFree"));
    assertEquals(at,monitoring.latest(node.nodeId()).getCollectedAt());
    monitoring.latest(node.nodeId());
    testJdbc.update("UPDATE ops_cluster_member SET status='DISABLED' WHERE user_id=(SELECT id FROM sys_user WHERE username='fixtureAdmin')");
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->gpus.getServerGpuOverview(node.nodeId()));
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->monitoring.latest(node.nodeId()));
    testJdbc.update("UPDATE ops_cluster_member SET status='ACTIVE' WHERE user_id=(SELECT id FROM sys_user WHERE username='fixtureAdmin')");
    agents.recordMetric(node.agentId(),new MetricRequest(11.,4096L,513L,2048L,100L,at.plusSeconds(1),"NO_GPU",List.of(),null));
    assertEquals(0,gpus.getServerGpuOverview(node.nodeId()).get("gpuCount"));
    assertEquals(at.plusSeconds(1),monitoring.latest(node.nodeId()).getCollectedAt());
  }

  @Test void mysqlNotificationCommitRollbackAndUserScopedSse() throws Exception {
    long user=testJdbc.queryForObject("SELECT id FROM sys_user WHERE username='fixtureAdmin'",Long.class);
    var node=node(); String sub=notifications.subscribe(node.nodeId(),0,1,1,null,3600);
    String notice=UUID.randomUUID().toString();
    var mine=mvc.perform(get("/api/resources/notifications/stream").with(SecurityMockMvcRequestPostProcessors.user("fixtureAdmin").roles("USER"))).andReturn();
    assertTrue(mine.getRequest().isAsyncStarted());
    assertEquals(200,mine.getResponse().getStatus());
    assertTrue(mine.getResponse().getContentAsString().contains("event:refresh"));
    testJdbc.update("INSERT INTO sys_user(username,password_hash,status,created_at,updated_at) VALUES('redisObserver','test-hash','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    var other=mvc.perform(get("/api/resources/notifications/stream").with(SecurityMockMvcRequestPostProcessors.user("redisObserver").roles("USER"))).andReturn();
    assertEquals(401,mvc.perform(get("/api/resources/notifications/stream")).andReturn().getResponse().getStatus());
    var tx=new TransactionTemplate(transactions);
    tx.executeWithoutResult(status -> {
      testJdbc.update("INSERT INTO ops_user_notification(id,user_id,subscription_id,kind,content_json,dedup_key,created_at) VALUES(?,?,?,'TEST','{}',?,CURRENT_TIMESTAMP)",notice,user,sub,notice);
      events.userAfterCommit(user); status.setRollbackOnly();
    });
    assertEquals(0,testJdbc.queryForObject("SELECT COUNT(*) FROM ops_user_notification WHERE id=?",Integer.class,notice));
    assertFalse(mine.getResponse().getContentAsString().contains("notifications_changed"));
    long before=((Number)events.stats().get("published")).longValue();
    events.subscribe();
    tx.executeWithoutResult(status -> {
      testJdbc.update("INSERT INTO ops_user_notification(id,user_id,subscription_id,kind,content_json,dedup_key,created_at) VALUES(?,?,?,'TEST','{}',?,CURRENT_TIMESTAMP)",notice,user,sub,notice);
      events.userAfterCommit(user);
      assertEquals(before,((Number)events.stats().get("published")).longValue());
    });
    for(int i=0;i<50&&!mine.getResponse().getContentAsString().contains("notifications_changed");i++) Thread.sleep(20);
    assertTrue(mine.getResponse().getContentAsString().contains("event:notifications_changed"));
    assertFalse(other.getResponse().getContentAsString().contains("notifications_changed"));
    assertEquals(before+1,((Number)events.stats().get("published")).longValue());
    assertEquals(1,testJdbc.queryForObject("SELECT COUNT(*) FROM ops_user_notification WHERE id=?",Integer.class,notice));
    assertFalse(mine.getResponse().getContentAsString().contains(notice)); // No private content in Pub/Sub/SSE.
    asFixtureAdmin();
    assertTrue(notifications.notifications().toString().contains(notice));
    notifications.read(notice);
    assertTrue(testJdbc.queryForObject("SELECT is_read FROM ops_user_notification WHERE id=?",Boolean.class,notice));
    other.getRequest().getAsyncContext().complete();
    mine.getRequest().getAsyncContext().complete();
  }
}
