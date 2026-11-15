package com.aiops.backend.service;

import com.aiops.backend.persistence.MetricDetails;
import java.time.Instant;
import java.util.*;

/** One snapshot only. No grouping by command name, username, or a shared unobserved launcher PID. */
final class ProcessGroups {
  record Identity(long pid, Instant started, Long uid) {}
  record Group(String key, Map<String,Object> root, List<Map<String,Object>> gpuProcesses,
      Map<String,Object> metrics, boolean stable, List<String> missing) {}
  private record Node(Identity id, Map<String,Object> details) {}

  static Long integer(Object value) {
    if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())
        || n.doubleValue()<0 || n.doubleValue()!=n.longValue()) return null;
    return n.longValue();
  }
  static Instant time(Object value) {
    try { return value instanceof String s ? Instant.parse(s) : null; }
    catch (RuntimeException e) { return null; }
  }
  private static Identity identity(Object pid, Map<String,Object> d, Instant at) {
    Long p=integer(pid); Instant start=time(d.get("startedAt"));
    return p==null || p<=0 || start==null || start.isAfter(at) ? null
        : new Identity(p,start,integer(d.get("linuxUid")));
  }
  @SuppressWarnings("unchecked")
  static List<Group> collect(List<Map<String,Object>> rows, Instant at) {
    var nodes=new LinkedHashMap<Identity,Node>();
    var byPid=new HashMap<Long,Set<Identity>>();
    var gpuRows=new LinkedHashMap<Identity,List<Map<String,Object>>>();
    var unknown=new ArrayList<Map<String,Object>>();
    for (var row:rows) {
      var d=MetricDetails.decode((String)row.get("details_json"));
      var normalized=new LinkedHashMap<String,Object>();
      row.forEach((key,value)->normalized.put(key.toLowerCase(Locale.ROOT),value));
      row=normalized; row.put("details",d);
      Identity id=identity(row.get("pid"),d,at);
      if (id==null) { unknown.add(row); continue; }
      nodes.put(id,new Node(id,d));
      byPid.computeIfAbsent(id.pid(),k->new HashSet<>()).add(id);
      gpuRows.computeIfAbsent(id,k->new ArrayList<>()).add(row);
    }
    // A directly observed GPU process is authoritative over a duplicate in a descendant tree.
    for (var rowsForProcess:gpuRows.values()) for (var row:rowsForProcess) {
      var d=(Map<String,Object>)row.get("details");
      if (d.get("processTree") instanceof List<?> children) for (var child:children)
        if (child instanceof Map<?,?> map) {
          var details=(Map<String,Object>)map;
          var id=identity(details.get("pid"),details,at);
          if (id!=null) {
            nodes.putIfAbsent(id,new Node(id,details));
            byPid.computeIfAbsent(id.pid(),k->new HashSet<>()).add(id);
          }
        }
    }
    var parents=new HashMap<Identity,Identity>();
    for (var node:nodes.values()) {
      Long pid=integer(node.details().get("parentPid"));
      var candidates=byPid.getOrDefault(pid,Set.of());
      if (node.id().uid()==null || candidates.size()!=1 || byPid.get(node.id().pid()).size()!=1) continue;
      var parent=candidates.iterator().next();
      if (parent.pid()!=node.id().pid() && Objects.equals(parent.uid(),node.id().uid())
          && !parent.started().isAfter(node.id().started())) parents.put(node.id(),parent);
    }
    var roots=new LinkedHashMap<Identity,List<Map<String,Object>>>();
    var members=new LinkedHashMap<Identity,List<Node>>();
    for (var node:nodes.values()) {
      Identity current=node.id(), root=gpuRows.containsKey(current)?current:null;
      var seen=new HashSet<Identity>(); boolean cycle=false;
      while (current!=null) {
        if (!seen.add(current)) { cycle=true; break; }
        if (gpuRows.containsKey(current)) root=current;
        current=parents.get(current);
      }
      if (cycle) root=gpuRows.containsKey(node.id())?node.id():null;
      if (root!=null) members.computeIfAbsent(root,k->new ArrayList<>()).add(node);
      if (gpuRows.containsKey(node.id())) roots.computeIfAbsent(root,k->new ArrayList<>()).addAll(gpuRows.get(node.id()));
    }
    var result=new ArrayList<Group>();
    for (var entry:roots.entrySet()) {
      var id=entry.getKey();
      var root=gpuRows.get(id).get(0);
      var missing=new ArrayList<String>();
      if (id.uid()==null) missing.add("UID_UNKNOWN：保持独立候选，不能确认用户归属或父子归组");
      boolean stable=byPid.get(id.pid()).size()==1;
      if (!stable) missing.add("PROCESS_IDENTITY_CONFLICT：同一快照 PID 身份冲突");
      for (var p:entry.getValue()) {
        var d=(Map<?,?>)p.get("details");
        if (d.get("processTree") instanceof List<?> children) for(Object value:children)
          if(value instanceof Map<?,?> child && Objects.equals(integer(child.get("linuxUid")),id.uid())
              && members.getOrDefault(id,List.of()).stream().noneMatch(n->Objects.equals(n.id().pid(),integer(child.get("pid")))))
            missing.add("PROCESS_TREE_UNVERIFIED：部分同UID子进程缺少完整父子身份链，未合计");
        if (Boolean.TRUE.equals(d.get("processTreeTruncated")) || "PARTIAL".equals(d.get("processTreeStatus")))
          missing.add("PROCESS_TREE_PARTIAL：子进程扫描不完整");
        else if (!"AVAILABLE".equals(d.get("processTreeStatus")) && !Boolean.FALSE.equals(d.get("processTreeTruncated")))
          missing.add("PROCESS_TREE_UNKNOWN：子进程可见性未知");
      }
      var metrics=metrics(entry.getValue(),members.getOrDefault(id,List.of()),missing);
      result.add(new Group(id.pid()+"|"+id.started()+"|"+id.uid(),root,entry.getValue(),metrics,stable,missing.stream().distinct().toList()));
    }
    // Missing start time never fabricates a stable cross-snapshot identity.
    var unknownByPid=new LinkedHashMap<Object,List<Map<String,Object>>>();
    unknown.forEach(p->unknownByPid.computeIfAbsent(p.get("pid"),k->new ArrayList<>()).add(p));
    for (var entry:unknownByPid.entrySet()) {
      var p=entry.getValue().get(0);
      var d=(Map<String,Object>)p.get("details");
      var missing=new ArrayList<>(List.of("START_TIME_UNKNOWN：仅当前快照，不关联历史任务"));
      var metric=metrics(entry.getValue(),List.of(new Node(null,d)),missing);
      result.add(new Group("snapshot:"+p.get("snapshot_id")+":"+entry.getKey(),p,entry.getValue(),metric,false,missing));
    }
    return result;
  }

  private static Map<String,Object> metrics(List<Map<String,Object>> rows,List<Node> nodes,List<String> missing) {
    var metrics=new LinkedHashMap<String,Object>();
    for (String[] field:List.of(new String[]{"cpuPercent","cpuPercent"},new String[]{"memoryRssBytes","rssEstimateBytes"},
        new String[]{"memoryUssBytes","ussBytes"},new String[]{"readBytesPerSecond","readBytesPerSecond"},new String[]{"writeBytesPerSecond","writeBytesPerSecond"})) {
      boolean known=!nodes.isEmpty(); double sum=0;
      for (var node:nodes) {
        Object value=node.details().get(field[0]);
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue()<0) known=false;
        else sum+=n.doubleValue();
      }
      metrics.put(field[1],known?sum:null);
      if (!known) missing.add(field[1]+"_UNKNOWN：进程采样缺失或权限不足");
    }
    var gpu=new TreeMap<String,Double>(); var gpuKnown=new HashMap<String,Boolean>();
    var cards=new TreeMap<String,Object>(); var seen=new HashSet<String>();
    for (var p:rows) {
      String card=Objects.toString(p.get("gpu_uuid"),"index:"+p.get("gpu_index"));
      if (!seen.add(p.get("pid")+"|"+p.get("gpu_index"))) continue;
      boolean known=p.get("gpu_memory_usage_bytes") instanceof Number;
      gpuKnown.merge(card,known,(a,b)->a&&b);
      if (known) gpu.merge(card,((Number)p.get("gpu_memory_usage_bytes")).doubleValue(),Double::sum);
      cards.put(card,p.get("utilization_percent"));
    }
    var gpuValues=new TreeMap<String,Object>();
    gpuKnown.forEach((card,known)->gpuValues.put(card,known?gpu.get(card):null));
    boolean allGpuKnown=!gpuKnown.isEmpty() && gpuKnown.values().stream().allMatch(Boolean::booleanValue);
    metrics.put("gpuMemoryByUuid",gpuValues);
    metrics.put("gpuMemoryBytes",allGpuKnown?gpu.values().stream().mapToDouble(Double::doubleValue).max().orElse(0):null);
    metrics.put("wholeGpuUtilization",cards);
    metrics.put("processCount",nodes.size());
    var identities=new ArrayList<Map<String,Object>>();
    for (var node:nodes) if(node.id()!=null) identities.add(Map.of("pid",node.id().pid(),"startedAt",node.details().get("startedAt")));
    metrics.put("members",identities);
    metrics.put("processCoverage",missing.stream().anyMatch(m->m.startsWith("PROCESS_TREE"))?"UNKNOWN":"OBSERVED_TREE");
    metrics.put("metricScope","Observed same-UID process identities only; RSS sums may double-count shared pages; USS is private memory; CPU is single-core percent; GPU utilization is whole-card, never per-process");
    return metrics;
  }
}
