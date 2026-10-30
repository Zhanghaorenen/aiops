package com.aiops.backend.service;
import java.util.List;
import java.util.Map;

public interface GpuManagerService {
    Map<String, Object> getServerGpus(Long serverId);
    Map<String, Object> getGpuLatest(Long serverId, Integer gpuId);
    Map<String, Object> getServerGpuOverview(Long serverId);
    List<Map<String, Object>> getGpuProcesses(Long serverId);
}