package com.aiops.backend.service;

import com.aiops.backend.persistence.MetricDetails;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Compares user-reported progress at an observed solo-to-concurrent transition. */
final class RuntimePerformance {
  record Point(Instant at, Instant snapshot, String phase, Double stepTime, Double samplesPerSecond,
               Object epoch, Object step) {}
  record Comparison(String taskId, Set<String> concurrentTasks, String phase, String metric,
                    double baselineMedian, double concurrentMedian, int baselineSamples, int concurrentSamples,
                    Instant baselineFrom, Instant baselineTo, Instant concurrentFrom, Instant concurrentTo,
                    boolean degraded) {}
  record Result(String status, List<Comparison> comparisons, Map<String,List<Point>> trends, String reason) {}
  private static final Duration ALIGNMENT = Duration.ofSeconds(30);
  private static final class Span {
    final Set<String> tasks;
    final NavigableSet<Instant> times = new TreeSet<>();
    final Map<String,List<Point>> progress = new HashMap<>();
    Span(Set<String> tasks) { this.tasks = Set.copyOf(tasks); }
  }
  private final NavigableMap<Instant,Span> spans = new TreeMap<>();

  void observe(Instant time, Set<String> tasks) {
    Span last = spans.isEmpty() ? null : spans.lastEntry().getValue();
    if (last == null || !last.tasks.equals(tasks) || Duration.between(last.times.last(),time).compareTo(ALIGNMENT)>0) {
      last = new Span(tasks);
      spans.put(time,last);
    }
    last.times.add(time);
  }

  Result evaluate(JdbcTemplate jdbc, long server, long user, boolean admin, boolean usable) {
    if (!usable || spans.isEmpty())
      return new Result("UNKNOWN", List.of(), Map.of(), "采样缺失、过期或窗口不完整，无法比较训练性能。");
    var rows = jdbc.queryForList("""
        SELECT r.root_pid,r.process_started_at,r.linux_uid,p.collected_at,p.payload_json
        FROM ops_training_progress p JOIN ops_training_job j ON j.id=p.job_id
        JOIN ops_gpu_usage u ON u.id=j.id
        JOIN ops_workload_run r ON r.job_id=j.id AND r.server_id=u.server_id AND r.user_id=u.user_id
          AND r.root_pid=j.pid AND r.process_started_at=j.process_started_at AND r.linux_username=j.linux_username
        WHERE u.server_id=? AND (? OR u.user_id=?) AND r.linux_uid IS NOT NULL
          AND p.collected_at BETWEEN ? AND ? ORDER BY p.collected_at,p.id LIMIT 6001
        """,server,admin,user,TrainingJobService.utc(spans.firstKey()),
        TrainingJobService.utc(spans.lastEntry().getValue().times.last()));
    if (rows.size()>6000)
      return new Result("UNKNOWN", List.of(), Map.of(), "窗口内训练进度超过分析上限，无法完整比较。");
    Map<String,List<Point>> trends = new LinkedHashMap<>();
    for (var row : rows) {
      Instant start = ProcessGroups.time(row.get("process_started_at"));
      if (start == null) continue;
      String id = RuntimeContentionService.taskId(server,row.get("root_pid")+"|"+start+"|"+row.get("linux_uid"));
      Instant time = TrainingJobService.instant(row.get("collected_at"));
      Span span = spans.floorEntry(time).getValue();
      if (!span.tasks.contains(id) || time.isAfter(span.times.last())) continue;
      var data = MetricDetails.decode((String)row.get("payload_json"));
      String phase = data.get("phase") instanceof String s ? s.strip() : "";
      var point = new Point(time,span.times.floor(time),phase,positive(data.get("stepTime")),
          positive(data.get("samplesPerSecond")),data.get("epoch"),data.get("step"));
      span.progress.computeIfAbsent(id,k->new ArrayList<>()).add(point);
      if (point.samplesPerSecond()!=null) trends.computeIfAbsent(id,k->new ArrayList<>()).add(point);
    }
    List<Comparison> comparisons = new ArrayList<>();
    Span previous = null;
    for (Span current : spans.values()) {
      if (previous!=null && previous.tasks.size()==1 && current.tasks.size()>1
          && Duration.between(previous.times.last(),current.times.first()).compareTo(ALIGNMENT)<=0) {
        String id = previous.tasks.iterator().next();
        var before = previous.progress.getOrDefault(id,List.of());
        var during = current.progress.getOrDefault(id,List.of());
        if (!before.isEmpty() && !during.isEmpty()) compare(id,current.tasks,before,during,comparisons);
      }
      previous = current;
    }
    String status = comparisons.isEmpty() ? "UNKNOWN" :
        comparisons.stream().anyMatch(Comparison::degraded) ? "PERFORMANCE_DEGRADATION" : "NO_DEGRADATION_OBSERVED";
    return new Result(status,comparisons,trends,comparisons.isEmpty()
        ? "需要可查看的关联任务，并在并发前后同一训练阶段各取得至少三个对齐的有效进度样本。" : null);
  }

  private static Double positive(Object value) {
    return value instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue()>0 ? n.doubleValue() : null;
  }
  private static void compare(String id,Set<String> tasks,List<Point> before,List<Point> during,List<Comparison> out) {
    String phase = during.get(0).phase();
    if (phase.isBlank() || !phase.equals(before.get(before.size()-1).phase())) return;
    int first = before.size()-1, end = 1;
    while (first>0 && phase.equals(before.get(first-1).phase())) first--;
    while (end<during.size() && phase.equals(during.get(end).phase())) end++;
    for (String metric : List.of("stepTime","samplesPerSecond")) {
      var baseline = sampleValues(before.subList(first,before.size()),metric);
      var concurrent = sampleValues(during.subList(0,end),metric);
      if (baseline.size()<3 || concurrent.size()<3) continue;
      double a = median(baseline.values()), b = median(concurrent.values());
      out.add(new Comparison(id,tasks,phase,metric,a,b,baseline.size(),concurrent.size(),
          baseline.firstKey(),baseline.lastKey(),concurrent.firstKey(),concurrent.lastKey(),
          metric.equals("stepTime") ? b>a*1.3 : b<a*.7));
    }
  }
  private static NavigableMap<Instant,Double> sampleValues(List<Point> points,String metric) {
    var values = new TreeMap<Instant,Double>();
    var steps = new HashSet<List<Object>>();
    for (Point p : points) {
      Double value = metric.equals("stepTime") ? p.stepTime() : p.samplesPerSecond();
      if (value==null || values.containsKey(p.snapshot())) continue;
      if (p.step()!=null && !steps.add(Arrays.asList(p.epoch(),p.step()))) continue;
      values.put(p.snapshot(),value);
    }
    return values;
  }
  private static double median(Collection<Double> values) {
    var sorted = values.stream().sorted().toList();
    return (sorted.get((sorted.size()-1)/2)+sorted.get(sorted.size()/2))/2;
  }
}
