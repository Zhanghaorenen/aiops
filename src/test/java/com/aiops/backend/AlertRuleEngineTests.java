package com.aiops.backend;

import static org.junit.jupiter.api.Assertions.*;

import com.aiops.backend.service.AlertRuleEngine;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AlertRuleEngineTests {
  private final AlertRuleEngine engine = new AlertRuleEngine();
  private final Instant now = Instant.parse("2026-11-05T00:00:00Z");

  private AlertRuleEngine.Decision evaluate(List<AlertRuleEngine.Sample> samples) {
    return engine.evaluate(samples, now, 60, 90, 30, 90, 75, "GE");
  }

  @Test
  void oneHighSampleCannotProveDuration() {
    assertFalse(evaluate(List.of(new AlertRuleEngine.Sample(now, 99.0))).firing());
  }

  @Test
  void freshContinuousOutOfOrderSamplesTrigger() {
    var points = new ArrayList<AlertRuleEngine.Sample>();
    for (int i = 0; i <= 4; i++)
      points.add(new AlertRuleEngine.Sample(now.minusSeconds(i * 15), 95.0));
    assertTrue(evaluate(points).firing());
  }

  @Test
  void missingAndStaleDataDoNotTriggerOrRecover() {
    assertFalse(
        evaluate(List.of(new AlertRuleEngine.Sample(now.minusSeconds(120), 0.0))).recovered());
    assertFalse(evaluate(List.of(new AlertRuleEngine.Sample(now, null))).firing());
    assertFalse(evaluate(List.of(new AlertRuleEngine.Sample(now, null))).recovered());
  }

  @Test
  void hysteresisAndGapsAreRespected() {
    assertFalse(evaluate(List.of(new AlertRuleEngine.Sample(now, 80.0))).recovered());
    assertTrue(evaluate(List.of(new AlertRuleEngine.Sample(now, 70.0))).recovered());
    assertFalse(
        evaluate(
                List.of(
                    new AlertRuleEngine.Sample(now.minusSeconds(60), 99.0),
                    new AlertRuleEngine.Sample(now, 99.0)))
            .firing());
  }

  @Test
  void conflictingDuplicateTimestampCannotProveRecovery() {
    var result =
        evaluate(
            List.of(new AlertRuleEngine.Sample(now, 99.0), new AlertRuleEngine.Sample(now, 0.0)));
    assertFalse(result.firing());
    assertFalse(result.recovered());
  }
}
