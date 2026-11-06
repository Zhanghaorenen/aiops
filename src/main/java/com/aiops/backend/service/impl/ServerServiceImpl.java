package com.aiops.backend.service.impl;

import com.aiops.backend.entity.HostMetricSampleEntity;
import com.aiops.backend.entity.NodeEntity;
import com.aiops.backend.exception.ResourceNotFoundException;
import com.aiops.backend.repository.HostMetricSampleRepository;
import com.aiops.backend.repository.NodeRepository;
import com.aiops.backend.service.ServerService;
import com.aiops.backend.vo.ServerVO;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ServerServiceImpl implements ServerService {

  private final NodeRepository nodeRepository;
  private final com.aiops.backend.service.ClusterPermissionService access;
  private final HostMetricSampleRepository metricRepository;

  public ServerServiceImpl(
      NodeRepository nodeRepository,
      HostMetricSampleRepository metricRepository,
      com.aiops.backend.service.ClusterPermissionService access) {
    this.access = access;
    this.nodeRepository = nodeRepository;
    this.metricRepository = metricRepository;
  }

  @Override
  public List<ServerVO> listServers() {
    return nodeRepository.findByClusterIds(access.readableClusterIds()).stream()
        .map(this::toView)
        .toList();
  }

  @Override
  public ServerVO getServerById(Long id) {
    access.requireNode(id, com.aiops.backend.security.ClusterPermission.SERVER_VIEW);
    return nodeRepository
        .findById(id)
        .map(this::toView)
        .orElseThrow(() -> new ResourceNotFoundException("Server not found: " + id));
  }

  private ServerVO toView(NodeEntity node) {
    HostMetricSampleEntity latest =
        metricRepository.findTopByNodeIdOrderByCollectedAtDescIdDesc(node.getId()).orElse(null);
    return new ServerVO(
        node.getId(),
        node.getDisplayName(),
        node.getIp(),
        node.getStatus(),
        latest == null ? null : latest.getCpuPercent().doubleValue(),
        latest == null ? null : latest.getMemoryPercent().doubleValue(),
        latest == null ? null : latest.getDiskPercent().doubleValue(),
        node.getHostname(),
        node.getOsName(),
        node.getOsVersion(),
        node.getArchitecture(),
        node.getCpuCores(),
        node.getMemoryBytes(),
        node.getLastSeenAt(),
        latest == null ? null : latest.getCollectedAt());
  }
}
