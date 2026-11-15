package com.aiops.backend.redis;

import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Pub/Sub carries invalidation hints only. MySQL remains the notification source of truth. */
@Component
public class NotificationEvents {
  private final StringRedisTemplate redis;
  private final JdbcTemplate jdbc;
  private final String channel;
  private final boolean enabled;
  private final RedisMessageListenerContainer listener;
  private final ConcurrentMap<Long,Set<SseEmitter>> clients=new ConcurrentHashMap<>();
  private final LongAdder published=new LongAdder(), failures=new LongAdder();

  public NotificationEvents(StringRedisTemplate redis, RedisConnectionFactory factory, JdbcTemplate jdbc,
      @Value("${aiops.redis.enabled:true}") boolean enabled,
      @Value("${aiops.redis.key-prefix:aiops:}") String prefix) {
    this.redis=redis; this.jdbc=jdbc; this.enabled=enabled; channel=prefix+"notifications";
    listener=new RedisMessageListenerContainer();
    listener.setConnectionFactory(factory);
    listener.setRecoveryInterval(5000);
    listener.setMaxSubscriptionRegistrationWaitingTime(1000);
    listener.addMessageListener((message, pattern) -> {
      try {
        var fields=new String(message.getBody(),StandardCharsets.UTF_8).split(":",2);
        if (fields.length==2 && Set.of("notifications_changed","alerts_changed").contains(fields[1]))
          deliver(Long.parseLong(fields[0]),fields[1]);
      } catch (RuntimeException ignored) { failures.increment(); }
    },new ChannelTopic(channel));
    listener.afterPropertiesSet();
  }

  @Scheduled(initialDelay=1000,fixedDelay=5000)
  public void subscribe() {
    if (enabled && !listener.isRunning()) try { listener.start(); }
      catch (RuntimeException e) { failures.increment(); }
  }

  public void userAfterCommit(long user) { afterCommit(() -> publish(user,"notifications_changed")); }

  public void clusterAfterCommit(long cluster) {
    afterCommit(() -> {
      // No resource details travel in the event; readers reauthorize when reloading MySQL.
      try {
        for (long id:jdbc.queryForList("SELECT m.user_id FROM ops_cluster_member m JOIN ops_cluster c ON c.id=m.cluster_id WHERE m.cluster_id=? AND m.status='ACTIVE' AND c.status='ACTIVE' AND m.role IN ('ADMIN','OPERATOR','VIEWER')",Long.class,cluster))
          publish(id,"alerts_changed");
      } catch (RuntimeException e) { failures.increment(); }
    });
  }

  private void afterCommit(Runnable action) {
    if (!enabled) return;
    if (TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCommit() { action.run(); }
      });
    else action.run(); // JdbcTemplate outside an explicit transaction has already auto-committed.
  }

  private void publish(long user,String event) {
    try { redis.convertAndSend(channel,user+":"+event); published.increment(); }
    catch (RuntimeException e) { failures.increment(); } // Persistent write must still succeed.
  }

  public synchronized SseEmitter open(long user) {
    var set=clients.computeIfAbsent(user,k -> ConcurrentHashMap.newKeySet());
    if (set.size()>=4) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"最多四条通知连接");
    SseEmitter emitter=new SseEmitter(55000L);
    set.add(emitter);
    Runnable cleanup=() -> remove(user,emitter);
    emitter.onCompletion(cleanup);
    emitter.onTimeout(() -> { cleanup.run(); emitter.complete(); });
    emitter.onError(e -> cleanup.run());
    send(user,emitter,"refresh"); // Reconnect always restores unread records via authenticated MySQL API.
    return emitter;
  }

  private synchronized void remove(long user,SseEmitter emitter) {
    var set=clients.get(user);
    if (set!=null) { set.remove(emitter); if (set.isEmpty()) clients.remove(user,set); }
  }

  private void send(long user,SseEmitter emitter,String event) {
    try { emitter.send(SseEmitter.event().name(event).data("{}")); }
    catch (Exception e) { remove(user,emitter); emitter.complete(); }
  }
  private void deliver(long user,String event) {
    for (var emitter:clients.getOrDefault(user,Set.of())) send(user,emitter,event);
  }
  @Scheduled(fixedDelay=15000)
  public void heartbeat() { clients.forEach((user,set) -> set.forEach(e -> send(user,e,"heartbeat"))); }
  public Map<String,Object> stats() {
    return Map.of("subscriberRunning",listener.isRunning(),"published",published.sum(),"publishOrSubscribeErrors",failures.sum(),
        "sseConnections",clients.values().stream().mapToInt(Set::size).sum());
  }
  @PreDestroy public void close() throws Exception {
    clients.values().forEach(set -> set.forEach(SseEmitter::complete)); clients.clear(); listener.destroy();
  }
}
