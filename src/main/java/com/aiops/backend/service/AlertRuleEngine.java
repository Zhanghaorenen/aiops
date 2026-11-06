package com.aiops.backend.service;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** 只分析真实样本，不让 LLM 决定是否触发告警。缺测、乱序与过期数据不能证明持续异常或恢复。 */
@Component
public class AlertRuleEngine {
  public record Sample(Instant at, Double value) {}

  public record Decision(boolean firing, boolean recovered, Instant observedAt, Double value) {}

  public Decision evaluate(
      List<Sample> input,
      Instant now,
      int duration,
      int freshness,
      int maxGap,
      double threshold,
      double recovery,
      String comparison) {
    var samples =
        input.stream()
            .filter(s -> s.at() != null && !s.at().isAfter(now.plusSeconds(5)))
            .sorted(Comparator.comparing(Sample::at))
            .collect(
                java.util.stream.Collectors.toMap(
                    Sample::at,
                    s -> s,
                    (a, b) -> Objects.equals(a.value(), b.value()) ? a : new Sample(a.at(), null),
                    TreeMap::new))
            .values()
            .stream()
            .toList();
    if (samples.isEmpty()) return new Decision(false, false, null, null);
    var latest = samples.get(samples.size() - 1);
    if (latest.value() == null
        || !Double.isFinite(latest.value())
        || latest.at().isBefore(now.minusSeconds(freshness)))
      return new Decision(false, false, null, null);
    if (latest.value() <= recovery) return new Decision(false, true, latest.at(), latest.value());
    var tail = new ArrayList<Sample>();
    Instant previous = null;
    for (int i = samples.size() - 1; i >= 0; i--) {
      var sample = samples.get(i);
      boolean high =
          sample.value() != null
              && Double.isFinite(sample.value())
              && ("GT".equals(comparison)
                  ? sample.value() > threshold
                  : sample.value() >= threshold);
      if (!high
          || previous != null && Duration.between(sample.at(), previous).getSeconds() > maxGap)
        break;
      tail.add(sample);
      previous = sample.at();
    }
    boolean firing =
        !tail.isEmpty()
            && (duration == 0
                || tail.size() >= 2
                    && Duration.between(tail.get(tail.size() - 1).at(), latest.at()).getSeconds()
                        >= duration);
    return new Decision(firing, false, latest.at(), latest.value());
  }
}
