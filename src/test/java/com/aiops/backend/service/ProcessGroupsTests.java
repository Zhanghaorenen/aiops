package com.aiops.backend.service;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ProcessGroupsTests {
  private final Instant now=Instant.parse("2026-11-15T00:00:00Z");
  private Map<String,Object> detail(long pid,long parent,long uid) {
    return new LinkedHashMap<>(Map.of("pid",pid,"parentPid",parent,"linuxUid",uid,"startedAt",now.minusSeconds(100-pid).toString(),
        "cpuPercent",10,"memoryRssBytes",100,"memoryUssBytes",60,"readBytesPerSecond",20,"writeBytesPerSecond",5));
  }
  private Map<String,Object> row(long pid,int card,Map<String,Object> d) {
    var row=new LinkedHashMap<String,Object>();
    row.put("pid",pid);row.put("gpu_index",card);row.put("gpu_uuid","gpu-"+card);row.put("gpu_memory_usage_bytes",100L);
    row.put("details_json",new ObjectMapper().writeValueAsString(d));row.put("snapshot_id",1L);return row;
  }
  @Test void parentChildAndMultiCardMetricsAreCountedOnce() {
    var parent=detail(10,1,1000); var child=detail(11,10,1000); var grandchild=detail(12,11,1000);
    parent.put("processTree",List.of(child,grandchild));parent.put("processTreeTruncated",false);
    var groups=ProcessGroups.collect(List.of(row(10,0,parent),row(10,1,parent),row(11,0,child)),now);
    assertEquals(1,groups.size());assertTrue(groups.get(0).stable());
    assertEquals(3,groups.get(0).metrics().get("processCount"));
    assertEquals(30d,groups.get(0).metrics().get("cpuPercent"));
    assertEquals(60d,groups.get(0).metrics().get("readBytesPerSecond"));
    assertEquals(180d,groups.get(0).metrics().get("ussBytes"));
  }
  @Test void missingUidAndUnprovenParentDoNotMergeAndMissingRatesStayNull() {
    var first=detail(10,1,1000);var child=detail(11,10,1001);child.remove("writeBytesPerSecond");
    var groups=ProcessGroups.collect(List.of(row(10,0,first),row(11,0,child)),now);
    assertEquals(2,groups.size()); assertNull(groups.get(1).metrics().get("writeBytesPerSecond"));
    child.remove("linuxUid");assertEquals(2,ProcessGroups.collect(List.of(row(10,0,first),row(11,0,child)),now).size());
  }
  @Test void identityConflictsAndFutureStartsNeverBecomeStableHistory() {
    var first=detail(10,1,1000);var conflict=detail(10,1,1001);
    assertTrue(ProcessGroups.collect(List.of(row(10,0,first),row(10,1,conflict)),now).stream().noneMatch(ProcessGroups.Group::stable));
    first.put("startedAt",now.plusSeconds(100).toString());
    assertFalse(ProcessGroups.collect(List.of(row(10,0,first)),now).get(0).stable());
  }
}
