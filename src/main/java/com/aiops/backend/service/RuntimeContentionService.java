package com.aiops.backend.service;

import com.aiops.backend.persistence.MetricDetails;
import com.aiops.backend.security.ClusterPermission;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only, bounded analysis of existing snapshots; no registrations or new monitoring tables. */
@Service
public class RuntimeContentionService {
  private static final int MAX_SNAPSHOTS=1800, MAX_ROWS=50000, MAX_PAIRS=200, MAX_GROUPS=64;
  private final JdbcTemplate jdbc;
  private final ClusterPermissionService access;
  public RuntimeContentionService(JdbcTemplate jdbc,ClusterPermissionService access) { this.jdbc=jdbc; this.access=access; }
  private static Instant at(Map<String,Object> row) { return TrainingJobService.instant(row.get("collected_at")); }
  private static Double number(Object value) {
    return value instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue()>=0 ? n.doubleValue():null;
  }
  static String taskId(long server,String identity) {
    return "runtime-"+WorkloadService.hash(server+"|"+identity).substring(0,24);
  }
  private static boolean positive(Object v) { return number(v)!=null && number(v)>0; }
  private static boolean high(Object v,double threshold) { return number(v)!=null && number(v)>=threshold; }
  private static boolean low(Object v,double threshold) { return number(v)!=null && number(v)<=threshold; }
  private static Map<String,Object> values(Object... pairs) {
    var result=new LinkedHashMap<String,Object>();
    for(int i=0;i<pairs.length;i+=2) result.put((String)pairs[i],pairs[i+1]); return result;
  }
  private static Map<Long,List<Map<String,Object>>> bySnapshot(List<Map<String,Object>> rows) {
    var result=new HashMap<Long,List<Map<String,Object>>>();
    rows.forEach(r->result.computeIfAbsent(((Number)r.get("snapshot_id")).longValue(),k->new ArrayList<>()).add(r));
    return result;
  }
  private static class Signal {
    int consecutive; Instant previous,first,last,observedFrom,observedTo; boolean sustained;
    void add(Instant time,boolean present) {
      if (!present) { consecutive=0; previous=null; return; }
      if (time.equals(previous)) return; // One host sample reused by several GPU snapshots is still one observation.
      if(previous==null || Duration.between(previous,time).getSeconds()>90) consecutive=0;
      if (consecutive++==0) first=time;
      previous=time; last=time; if(consecutive>=3) { sustained=true; if(observedFrom==null) observedFrom=first; observedTo=time; }
    }
  }
  private static class Pair {
    final List<String> ids; final Set<String> domains=new TreeSet<>(),missing=new TreeSet<>();
    final Map<String,Signal> signals=new TreeMap<>();
    final List<Map<String,Object>> intervals=new ArrayList<>(),evidence=new ArrayList<>();
    Instant last; int count;
    Pair(String a,String b) { ids=List.of(a,b); }
    void add(Instant time,Instant previousSnapshot,Set<String> resources,Map<String,Object> point,Set<String> absent) {
      if(last==null || !last.equals(previousSnapshot) || Duration.between(last,time).getSeconds()>90) {
        signals.values().forEach(s->{s.consecutive=0;s.previous=null;});
        intervals.add(values("from",time.toString(),"to",time.toString(),"sampleCount",1));
      } else {
        var interval=intervals.get(intervals.size()-1); interval.put("to",time.toString());
        interval.put("sampleCount",((Number)interval.get("sampleCount")).intValue()+1);
      }
      last=time; count++; domains.addAll(resources); missing.addAll(absent);
      if(evidence.size()==12) evidence.remove(0); evidence.add(point);
    }
  }

  @Transactional(readOnly=true)
  public Map<String,Object> analyze(long server) {
    var node=access.requireNode(server,ClusterPermission.GPU_VIEW);
    access.requireNode(server,ClusterPermission.METRIC_VIEW);
    long user=access.currentUser().getId();
    boolean admin="ADMIN".equals(access.require(node.getClusterId(),ClusterPermission.GPU_VIEW).getRole());
    Instant now=Instant.now(), from=now.minusSeconds(1800);
    var missing=new TreeSet<String>();
    var snapshots=jdbc.queryForList("SELECT * FROM metric_gpu_snapshot WHERE node_id=? AND collected_at>=? AND collected_at<=? ORDER BY collected_at DESC,id DESC LIMIT 1801",server,TrainingJobService.utc(from),TrainingJobService.utc(now));
    var hostRows=jdbc.queryForList("SELECT * FROM metric_host_sample WHERE node_id=? AND collected_at>=? AND collected_at<=? ORDER BY collected_at DESC,id DESC LIMIT 1801",server,TrainingJobService.utc(from.minusSeconds(30)),TrainingJobService.utc(now));
    var processes=jdbc.queryForList("SELECT p.*,g.gpu_uuid,g.utilization_percent FROM metric_gpu_process_sample p JOIN metric_gpu_sample g ON g.snapshot_id=p.snapshot_id AND g.gpu_index=p.gpu_index JOIN metric_gpu_snapshot s ON s.id=p.snapshot_id WHERE s.node_id=? AND s.collected_at>=? AND s.collected_at<=? ORDER BY s.collected_at DESC,p.id DESC LIMIT 50001",server,TrainingJobService.utc(from),TrainingJobService.utc(now));
    var gpuRows=jdbc.queryForList("SELECT * FROM metric_gpu_sample WHERE node_id=? AND collected_at>=? AND collected_at<=? ORDER BY collected_at DESC,id DESC LIMIT 50001",server,TrainingJobService.utc(from),TrainingJobService.utc(now));
    boolean truncated=snapshots.size()>MAX_SNAPSHOTS || hostRows.size()>MAX_SNAPSHOTS || processes.size()>MAX_ROWS || gpuRows.size()>MAX_ROWS;
    if(truncated) missing.add("WINDOW_TRUNCATED：超过只读分析采样上限，不能完整判断整个窗口");
    var hosts=new TreeMap<Instant,Map<String,Object>>();
    hostRows.forEach(h->hosts.putIfAbsent(at(h),h));
    var chronological=new TreeMap<Instant,Map<String,Object>>();
    snapshots.stream().limit(MAX_SNAPSHOTS).forEach(s->chronological.putIfAbsent(at(s),s));
    var ps=bySnapshot(processes.stream().limit(MAX_ROWS).toList());
    var gs=bySnapshot(gpuRows.stream().limit(MAX_ROWS).toList());
    var latestGroups=new ArrayList<Map<String,Object>>();
    var observedRuns=new LinkedHashMap<String,Map<String,Object>>();
    var pairs=new LinkedHashMap<String,Pair>();
    var signals=new TreeMap<String,Signal>();
    var timeline=new ArrayList<Map<String,Object>>();
    var trends=new ArrayList<Map<String,Object>>();
    var performance=new RuntimePerformance();
    var policies=jdbc.queryForList("SELECT psi_threshold FROM ops_training_policy WHERE server_id=?",server);
    double threshold=policies.isEmpty()?10:((Number)policies.get(0).get("psi_threshold")).doubleValue();
    var mine=jdbc.queryForList("SELECT linux_uid,linux_username FROM ops_training_linux_identity WHERE server_id=? AND user_id=? AND linux_uid IS NOT NULL",server,user);
    Instant previousSnapshot=null;
    for (var sample:chronological.entrySet()) {
      Instant time=sample.getKey(); long sid=((Number)sample.getValue().get("id")).longValue();
      var absent=new TreeSet<String>();
      var aligned=hosts.floorEntry(time);
      Map<String,Object> host=Map.of(),details=Map.of();
      if(aligned==null || Duration.between(aligned.getKey(),time).getSeconds()>30)
        absent.add("HOST_ALIGNMENT_UNKNOWN：没有前 30 秒内的主机采样");
      else { host=aligned.getValue(); details=MetricDetails.decode((String)host.get("details_json")); }
      var hostEvidence=values("collectedAt",host.isEmpty()?null:at(host).toString(),"cpuPercent",host.get("cpu_percent"),
          "memoryTotalBytes",host.get("memory_total_bytes"),"memoryAvailableBytes",details.get("memoryAvailableBytes"));
      for (String key:List.of("cpuPsiAvg10","memoryPsiAvg10","ioPsiAvg10")) {
        hostEvidence.put(key,details.get(key)); if(number(details.get(key))==null) absent.add(key+"_UNKNOWN");
      }
      var cards=new ArrayList<Map<String,Object>>();
      for(var gpu:gs.getOrDefault(sid,List.of())) cards.add(values("gpuIndex",gpu.get("gpu_index"),"collectedAt",at(gpu).toString(),
          "utilizationPercent",gpu.get("utilization_percent"),"memoryUsedBytes",gpu.get("memory_used_bytes"),"memoryFreeBytes",gpu.get("memory_free_bytes"),"memoryTotalBytes",gpu.get("memory_total_bytes"),"scope","WHOLE_GPU"));
      var groups=ProcessGroups.collect(ps.getOrDefault(sid,List.of()),time);
      if(groups.size()>MAX_GROUPS) { truncated=true; absent.add("GROUPS_TRUNCATED：每快照最多分析64个进程组"); }
      var current=new ArrayList<Map<String,Object>>();
      var privateMetrics=new LinkedHashMap<String,Map<String,Object>>();
      var indices=new HashMap<String,Set<Integer>>();
      for(var group:groups.stream().limit(MAX_GROUPS).toList()) {
        String id=taskId(server,group.key());
        var d=(Map<?,?>)group.root().get("details");
        boolean own=admin || mine.stream().anyMatch(m->Objects.equals(ProcessGroups.integer(m.get("linux_uid")),ProcessGroups.integer(d.get("linuxUid"))) && Objects.equals(m.get("linux_username"),group.root().get("username")));
        var metrics=new LinkedHashMap<>(group.metrics()); metrics.remove("members");
        var gpus=new TreeSet<Integer>(); group.gpuProcesses().forEach(p->gpus.add(((Number)p.get("gpu_index")).intValue()));
        indices.put(id,gpus); privateMetrics.put(id,metrics);
        Object name=observedRuns.containsKey(id)?observedRuns.get(id).get("displayName"):"GPU 任务 "+(observedRuns.size()+1);
        var item=values("id",id,"displayName",name,"gpuIndices",gpus,"resources",metrics,
            "sampledAt",time.toString(),"identityStatus",!group.stable()?"SNAPSHOT_ONLY":d.get("linuxUid")==null?"UID_UNKNOWN":"TRACKED", "trainingConfirmed",false,"missingEvidence",group.missing());
        if(own) { item.put("pid",group.root().get("pid")); item.put("startedAt",d.get("startedAt")); item.put("linuxUid",d.get("linuxUid")); }
        // Do not return another user's PID, username, command, working directory, run config, or progress.
        current.add(item); observedRuns.put(id,item); absent.addAll(group.missing());
      }
      if(current.isEmpty() && !"NO_GPU".equals(sample.getValue().get("collection_status")))
        absent.add("PROCESS_VISIBILITY_UNKNOWN：空进程结果不能证明没有训练；可能存在权限或采集缺口");
      boolean trackable="AVAILABLE".equals(sample.getValue().get("collection_status"))
          && groups.stream().allMatch(g->g.stable() && ((Map<?,?>)g.root().get("details")).get("linuxUid")!=null);
      performance.observe(time,trackable ? indices.keySet() : Set.of());
      var flags=new TreeMap<String,Boolean>();
      flags.put("HOST_CPU_PRESSURE",high(details.get("cpuPsiAvg10"),threshold));
      flags.put("HIGH_CPU_LOAD",high(host.get("cpu_percent"),90));
      flags.put("HOST_IO_PRESSURE",high(details.get("ioPsiAvg10"),threshold));
      Double available=number(details.get("memoryAvailableBytes")),total=number(host.get("memory_total_bytes"));
      flags.put("RAM_PRESSURE",high(details.get("memoryPsiAvg10"),threshold) || available!=null && total!=null && total>0 && available/total<=.1);
      signals.keySet().forEach(k->flags.putIfAbsent(k,false));
      for(var card:cards) {
        boolean involved=indices.values().stream().anyMatch(is->is.contains(((Number)card.get("gpuIndex")).intValue()));
        if(!involved) continue;
        if(number(card.get("utilizationPercent"))==null) absent.add("GPU_UTILIZATION_UNKNOWN");
        flags.put("LOW_GPU_UTILIZATION:"+card.get("gpuIndex"),low(card.get("utilizationPercent"),20));
        flags.put("HIGH_GPU_LOAD:"+card.get("gpuIndex"),high(card.get("utilizationPercent"),90));
        Double free=number(card.get("memoryFreeBytes")),capacity=number(card.get("memoryTotalBytes"));
        flags.put("VRAM_PRESSURE:"+card.get("gpuIndex"),free!=null && capacity!=null && capacity>0 && free/capacity<=.05);
      }
      for(var flag:flags.entrySet()) {
        Instant source=flag.getKey().startsWith("HOST") || flag.getKey().equals("RAM_PRESSURE") ? aligned==null?time:aligned.getKey():time;
        signals.computeIfAbsent(flag.getKey(),k->new Signal()).add(source,flag.getValue());
      }
      var point=values("snapshotId",sid,"collectedAt",time.toString(),"host",hostEvidence,"gpus",cards,"runs",current);
      timeline.add(point);
      trends.add(values("at",time,"hostAt",hostEvidence.get("collectedAt"),"ioPsi",hostEvidence.get("ioPsiAvg10"),
          "gpus",cards.stream().map(c->values("index",c.get("gpuIndex"),"utilization",c.get("utilizationPercent"))).toList()));
      for(int a=0;a<current.size();a++) for(int b=a+1;b<current.size();b++) {
        String left=current.get(a).get("id").toString(),right=current.get(b).get("id").toString();
        String key=left.compareTo(right)<0?left+"|"+right:right+"|"+left;
        if(!pairs.containsKey(key) && pairs.size()>=MAX_PAIRS) { truncated=true; absent.add("PAIRS_TRUNCATED：最多返回200种组合"); continue; }
        var pair=pairs.computeIfAbsent(key,k->new Pair(left,right));
        var lm=privateMetrics.get(left); var rm=privateMetrics.get(right); var domains=new TreeSet<String>();
        if(positive(lm.get("rssEstimateBytes")) && positive(rm.get("rssEstimateBytes"))) domains.add("HOST_RAM");
        if(positive(lm.get("cpuPercent")) && positive(rm.get("cpuPercent"))) domains.add("HOST_CPU");
        if((positive(lm.get("readBytesPerSecond")) || positive(lm.get("writeBytesPerSecond"))) && (positive(rm.get("readBytesPerSecond")) || positive(rm.get("writeBytesPerSecond")))) domains.add("HOST_IO");
        for(int index:indices.get(left)) if(indices.get(right).contains(index) && positive(lm.get("gpuMemoryBytes")) && positive(rm.get("gpuMemoryBytes"))) domains.add("GPU_"+index);
        pair.add(time,previousSnapshot,domains,values("snapshotId",sid,"collectedAt",time.toString(),"sharedResources",domains,"host",hostEvidence,"gpus",cards,"runs",List.of(values("id",left,"resources",lm),values("id",right,"resources",rm))),absent);
        for(var flag:flags.entrySet()) {
          boolean relevant=!flag.getKey().contains(":") || indices.get(left).contains(Integer.parseInt(flag.getKey().split(":")[1])) || indices.get(right).contains(Integer.parseInt(flag.getKey().split(":")[1]));
          pair.signals.computeIfAbsent(flag.getKey(),k->new Signal()).add(flag.getKey().startsWith("HOST") || flag.getKey().equals("RAM_PRESSURE") ? aligned==null?time:aligned.getKey():time,relevant&&flag.getValue());
        }
      }
      missing.addAll(absent); latestGroups=current; previousSnapshot=time;
    }
    Instant last=chronological.isEmpty()?null:chronological.lastKey();
    boolean fresh=last!=null && Duration.between(last,now).getSeconds()<=90;
    if(!fresh) missing.add("STALE_OR_MISSING：GPU采样缺失或超过90秒，不判断当前状态");
    if(chronological.size()<3) missing.add("HISTORY_INSUFFICIENT：不足3个不同时间的采样");
    var measured=performance.evaluate(jdbc,server,user,admin,fresh && !truncated);
    if (measured.reason()!=null) missing.add("TRAINING_PROGRESS_UNKNOWN："+measured.reason());
    missing.add("DATASET_ACCESS_UNKNOWN：未追踪数据集/设备归属，不能确认数据集竞争");
    if(truncated) missing.add("WINDOW_TRUNCATED：部分组合或采样未纳入");
    var risks=signalResults(signals);
    var pressure=pressureOnly(risks);
    var overlaps=new ArrayList<Map<String,Object>>();
    for(var pair:pairs.values()) {
      var pairSignals=signalResults(pair.signals);
      var pairPressure=pressureOnly(pairSignals);
      var comparisons=measured.comparisons().stream().filter(c->pair.ids.contains(c.taskId()) && c.concurrentTasks().containsAll(pair.ids)).toList();
      String level=comparisons.stream().anyMatch(RuntimePerformance.Comparison::degraded) ? "PERFORMANCE_DEGRADATION" :
          !pairPressure.isEmpty() ? "RESOURCE_PRESSURE" : pair.domains.isEmpty() ? "UNKNOWN" : "RESOURCE_OVERLAP";
      overlaps.add(values("runIds",pair.ids,"level",level,"pressureSignals",pairPressure,"performanceComparisons",comparisons,
          "status",pair.domains.isEmpty()?"UNKNOWN":pairPressure.isEmpty()?"RESOURCE_OVERLAP_ONLY":"CANDIDATE",
          "window",values("from",pair.intervals.get(0).get("from"),"to",pair.last.toString()),"intervals",pair.intervals,
          "alignedSampleCount",pair.count,"observedOverlapSeconds",pair.intervals.stream().mapToLong(i->Duration.between(Instant.parse(i.get("from").toString()),Instant.parse(i.get("to").toString())).getSeconds()).sum(),
          "sharedResources",pair.domains,"evidence",pair.evidence,"possibleBottlenecks",pairSignals,"missingEvidence",List.copyOf(pair.missing),
          "conclusion","只表示同一采样窗口的资源共现和候选压力，不证明因果或数据集竞争"));
    }
    boolean metricUnknown=missing.stream().anyMatch(m->!m.startsWith("TRAINING_PROGRESS") && !m.startsWith("DATASET_ACCESS"));
    String status=!fresh || truncated || chronological.size()<3 ? "UNKNOWN":!pressure.isEmpty()?"OBSERVED_PRESSURE":metricUnknown?"UNKNOWN":overlaps.isEmpty()?"NO_OVERLAP_OBSERVED":"NO_PRESSURE_OBSERVED";
    String level="UNKNOWN", conclusion="证据不足";
    if (fresh && !truncated && chronological.size()>=3) {
      if ("PERFORMANCE_DEGRADATION".equals(measured.status())) { level=measured.status(); conclusion="存在性能下降证据"; }
      else if (!pressure.isEmpty()) { level="RESOURCE_PRESSURE"; conclusion="发现压力"; }
      else if ("NO_PRESSURE_OBSERVED".equals(status) && overlaps.stream().anyMatch(p->"RESOURCE_OVERLAP".equals(p.get("level")))) {
        level="RESOURCE_OVERLAP"; conclusion="正常共用";
      } else if ("NO_OVERLAP_OBSERVED".equals(status)) { level="NO_OVERLAP"; conclusion="未观察到任务并发"; }
    }
    var summary=values("level",level,"conclusion",conclusion,"activeTaskCount",fresh?latestGroups.size():null,
        "overlappingPairCount",overlaps.size(),"pressureSignalCount",pressure.size());
    return values("serverId",server,"status",status,"window",values("from",from.toString(),"to",now.toString(),"latestSampleAt",last==null?null:last.toString(),"fresh",fresh,"alignmentMaxSeconds",30,"observationGapMaxSeconds",90),
        "summary",summary,"activeTasks",fresh?latestGroups:List.of(),"overlappingPairs",overlaps,
        "pressureSignals",pressure,"performanceEvidence",measured,"trends",trends,
        "activeRuns",fresh?latestGroups:List.of(),"observedRuns",new ArrayList<>(observedRuns.values()),"overlappingRuns",overlaps,
        "evidence",timeline.stream().skip(Math.max(0,timeline.size()-120)).toList(),"sampleCount",chronological.size(),"evidenceLimit",120,
        "possibleBottlenecks",risks,"missingEvidence",List.copyOf(missing),"recommendations",recommend(risks),"gpuUtilizationScope","WHOLE_GPU_NOT_PER_PROCESS");
  }

  private static List<Map<String,Object>> pressureOnly(List<Map<String,Object>> signals) {
    return signals.stream().filter(s->s.get("type").toString().endsWith("_PRESSURE")).toList();
  }
  private static List<Map<String,Object>> signalResults(Map<String,Signal> signals) {
    var result=new ArrayList<Map<String,Object>>();
    signals.forEach((kind,signal)-> { if(signal.sustained) result.add(values("type",kind.split(":")[0],"gpuIndex",kind.contains(":")?Integer.parseInt(kind.split(":")[1]):null,"status","OBSERVED_SIGNAL",
        "from",signal.observedFrom.toString(),"to",signal.observedTo.toString(),"scope",kind.contains("GPU")||kind.contains("VRAM")?"WHOLE_GPU":"HOST",
        "conclusion","至少3个连续且不同时间的对齐采样出现该信号；尚不能归因于某个任务")); });
    return result;
  }
  private static List<String> recommend(List<Map<String,Object>> signals) {
    var kinds=new HashSet<String>(); signals.forEach(s->kinds.add(s.get("type").toString()));
    var result=new ArrayList<String>();
    if(kinds.contains("HOST_IO_PRESSURE")) result.add("核对存储设备吞吐、DataLoader并发和训练阶段；共同读盘不代表数据集竞争，不自动停止任务");
    if(kinds.contains("LOW_GPU_UTILIZATION") && kinds.contains("HOST_IO_PRESSURE")) result.add("输入流水线或存储等待是候选原因，需同窗口训练吞吐和设备指标验证");
    if(kinds.contains("RAM_PRESSURE")) result.add("检查Available RAM、内存PSI及共享内存，评估减少预取和批量；RSS是含共享页的上界估计");
    if(kinds.contains("HIGH_GPU_LOAD")) result.add("整卡高利用率可能表示正常高负载，不能据此认定竞争或单进程利用率");
    if (kinds.contains("HOST_CPU_PRESSURE")) result.add("对照主机 CPU 等待时间与训练每步耗时，检查数据准备线程是否排队。");
    if (kinds.contains("VRAM_PRESSURE")) result.add("检查对应显卡的可用显存和任务的峰值需求。");
    return result;
  }
}
