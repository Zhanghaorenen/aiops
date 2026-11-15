package com.aiops.backend.service;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name="aiops.investigation.worker-enabled",havingValue="true",matchIfMissing=true)
public class InvestigationPolicyWorker {
  private final InvestigationPolicyService policies;
  private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(InvestigationPolicyWorker.class);
  public InvestigationPolicyWorker(InvestigationPolicyService policies) { this.policies=policies; }
  @Scheduled(fixedDelayString="${aiops.investigation.policy-interval-ms:15000}")
  public void poll() {
    for(long server:policies.enabled()) {
      try { policies.trigger(server); }
      catch(Exception e) { log.warn("Automatic investigation skipped server={} type={}",server,e.getClass().getSimpleName()); }
    }
  }
}
