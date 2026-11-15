package com.aiops.backend.service;

import com.aiops.backend.entity.HostMetricSampleEntity;
import com.aiops.backend.redis.ResourceCache;
import com.aiops.backend.repository.HostMetricSampleRepository;
import com.aiops.backend.security.ClusterPermission;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MonitoringQueryService {
  private final ResourceCache cache;
  private final HostMetricSampleRepository metrics;
  private final ClusterPermissionService access;
  public MonitoringQueryService(ResourceCache c, HostMetricSampleRepository m, ClusterPermissionService a) {
    cache=c; metrics=m; access=a;
  }
  public HostMetricSampleEntity latest(long node) {
    access.requireNode(node,ClusterPermission.METRIC_VIEW);
    return cache.get(node,"host",HostMetricSampleEntity.class,() -> metrics
        .findTopByNodeIdOrderByCollectedAtDescIdDesc(node)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"暂无监控数据")));
  }
}
