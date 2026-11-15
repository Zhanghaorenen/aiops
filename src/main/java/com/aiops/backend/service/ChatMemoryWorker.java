package com.aiops.backend.service;

import com.aiops.backend.security.JwtService;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name="aiops.chat-memory.worker-enabled",havingValue="true",matchIfMissing=true)
public class ChatMemoryWorker {
  private final LongTermMemoryService tasks;
  private final JwtService jwt;
  private final String url,key;
  private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final ObjectMapper json=new ObjectMapper();
  public ChatMemoryWorker(LongTermMemoryService tasks,JwtService jwt,
      @Value("${aiops.ai-service.url:http://127.0.0.1:8000}") String url,
      @Value("${aiops.ai-service.service-key:${AIOPS_AI_SERVICE_KEY:}}") String key) {
    this.tasks=tasks;this.jwt=jwt;this.url=url;this.key=key;
  }
  @Bean("chatMemoryScheduler") static ThreadPoolTaskScheduler scheduler() {
    var scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(2);scheduler.setThreadNamePrefix("chat-memory-");
    scheduler.setAwaitTerminationSeconds(10);scheduler.setWaitForTasksToCompleteOnShutdown(false);return scheduler;
  }
  @Scheduled(scheduler="chatMemoryScheduler",fixedDelayString="${aiops.chat-memory.interval-ms:5000}")
  public void summarize() {
    if(key.length()<32) return;
    tasks.enqueue();var job=tasks.claim(false);if(job==null) return;
    try {
      if(job.get("result_json")==null) {
        String result=call(job,"extract",Map.of("task_id",job.get("id"),"lease",job.get("lease_token")));
        tasks.record(job,result);
      }
      tasks.complete(job);
    } catch(Exception e) { tasks.fail(job,false); }
  }
  @Scheduled(scheduler="chatMemoryScheduler",fixedDelayString="${aiops.chat-memory.interval-ms:5000}")
  public void index() {
    if(key.length()<32) return;
    var job=tasks.claim(true);if(job==null) return;
    try {
      call(job,"index",Map.of("memory_id",job.get("memory_id"),"version",job.get("desired_version")));
      tasks.finishIndex(job);
    } catch(Exception e) { tasks.fail(job,true); }
  }
  private String call(Map<String,Object> job,String action,Map<String,Object> payload) throws Exception {
    String token=jwt.issue(((Number)job.get("user_id")).longValue(),job.get("username").toString());
    var request=HttpRequest.newBuilder(URI.create(url+"/api/internal/chat-memory/"+action)).timeout(Duration.ofSeconds(120))
        .header("Content-Type","application/json").header("Authorization","Bearer "+token).header("X-AI-Service-Key",key)
        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
    var response=client.send(request,HttpResponse.BodyHandlers.ofString());
    if(response.statusCode()!=200 || response.body().length()>20000) throw new IllegalStateException("MEMORY_DEPENDENCY_FAILED");
    return response.body();
  }
}
