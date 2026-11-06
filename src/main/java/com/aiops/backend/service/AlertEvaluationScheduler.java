package com.aiops.backend.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "aiops.alerts.scheduling-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AlertEvaluationScheduler {
  private final AlertEvaluationService service;
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(AlertEvaluationScheduler.class);

  public AlertEvaluationScheduler(AlertEvaluationService service) {
    this.service = service;
  }

  @Scheduled(fixedDelayString = "${aiops.alerts.interval-ms:15000}")
  public void evaluate() {
    for (Long id : service.enabledIds())
      try {
        service.evaluate(id, java.time.Instant.now());
      } catch (Exception e) {
        log.warn("Alert evaluation failed rule={} type={}", id, e.getClass().getSimpleName());
      }
  }
}
