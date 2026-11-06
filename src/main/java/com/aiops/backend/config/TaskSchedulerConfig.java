package com.aiops.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 模型调用使用独立线程池，避免慢调查阻塞监控和告警调度。 */
@Configuration
public class TaskSchedulerConfig {
  @Bean(name = "taskScheduler")
  public ThreadPoolTaskScheduler monitoring() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(2);
    scheduler.setThreadNamePrefix("monitoring-");
    return scheduler;
  }

  @Bean(name = "investigationScheduler")
  public ThreadPoolTaskScheduler investigation() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("investigation-");
    return scheduler;
  }
}
