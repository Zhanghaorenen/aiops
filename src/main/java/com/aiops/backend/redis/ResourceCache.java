package com.aiops.backend.redis;

import com.aiops.backend.entity.*;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;

/** Only monitoring snapshots belong here; admission/commitment queries remain on MySQL. */
@Component
public class ResourceCache {
  @JsonAutoDetect(fieldVisibility=JsonAutoDetect.Visibility.ANY,
      getterVisibility=JsonAutoDetect.Visibility.NONE, isGetterVisibility=JsonAutoDetect.Visibility.NONE,
      setterVisibility=JsonAutoDetect.Visibility.NONE)
  private abstract static class Fields {}
  private final JsonMapper json = JsonMapper.builder()
      .addMixIn(HostMetricSampleEntity.class, Fields.class)
      .addMixIn(GpuSnapshotEntity.class, Fields.class)
      .addMixIn(GpuSampleEntity.class, Fields.class).build();
  private final StringRedisTemplate redis;
  private final boolean enabled;
  private final String prefix;
  private final Duration ttl;
  private final LongAdder hits=new LongAdder(), misses=new LongAdder(), errors=new LongAdder();

  public ResourceCache(StringRedisTemplate redis, @Value("${aiops.redis.enabled:true}") boolean enabled,
      @Value("${aiops.redis.key-prefix:aiops:}") String prefix,
      @Value("${aiops.redis.cache-ttl:5s}") Duration ttl) {
    if (prefix.isBlank() || ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofMinutes(1))>0)
      throw new IllegalArgumentException("Redis prefix must be nonempty; monitoring cache TTL must be in (0, 60s]");
    this.redis=redis; this.enabled=enabled; this.prefix=prefix; this.ttl=ttl;
  }

  public <T> T get(long node, String kind, Class<T> type, Supplier<T> database) {
    // Never publish uncommitted writes or serve a snapshot inside a write transaction.
    if (!enabled || TransactionSynchronizationManager.isActualTransactionActive()
        && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) return database.get();
    String key;
    try {
      String epoch=redis.opsForValue().get(prefix+"monitor:epoch:"+node);
      key=prefix+"monitor:"+node+":"+Objects.toString(epoch,"0")+":"+kind;
      String value=redis.opsForValue().get(key);
      if (value!=null) {
        T result=json.readValue(value,type); hits.increment(); return result;
      }
    } catch (RuntimeException e) { errors.increment(); misses.increment(); return database.get(); }
    misses.increment();
    T value=database.get();
    if (value!=null) try { redis.opsForValue().set(key,json.writeValueAsString(value),ttl); }
      catch (RuntimeException e) { errors.increment(); }
    return value;
  }

  public void invalidateAfterCommit(long node) {
    if (!enabled) return;
    Runnable invalidate=() -> {
      try {
        // Generation keys also fence a reader filling an old snapshot after this commit.
        redis.opsForValue().set(prefix+"monitor:epoch:"+node,UUID.randomUUID().toString(),Duration.ofDays(1));
      } catch (RuntimeException e) { errors.increment(); }
    };
    if (TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCommit() { invalidate.run(); }
      });
    else invalidate.run();
  }

  public Map<String,Object> stats() {
    String state="DISABLED";
    if (enabled) try (var connection=redis.getConnectionFactory().getConnection()) { state="PONG".equals(connection.ping())?"UP":"DOWN"; }
      catch (RuntimeException e) { state="DOWN"; }
    return Map.of("status",state,"cacheHits",hits.sum(),"cacheMisses",misses.sum(),"cacheErrors",errors.sum(),"cacheTtlMs",ttl.toMillis());
  }
}
