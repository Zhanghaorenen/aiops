package com.aiops.backend.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.aiops.backend.entity.GpuProcessSampleEntity;
import com.aiops.backend.entity.GpuSampleEntity;
import com.aiops.backend.entity.GpuSnapshotEntity;
import com.aiops.backend.repository.GpuProcessSampleRepository;
import com.aiops.backend.repository.GpuSampleRepository;
import com.aiops.backend.repository.GpuSnapshotRepository;
import com.aiops.backend.repository.NodeRepository;
import com.aiops.backend.service.GpuManagerService;

@Service
@Transactional(readOnly = true)
public class GpuManagerServiceImpl implements GpuManagerService {
    private static final int MAX_HISTORY_LIMIT = 1440;

    private final NodeRepository nodes;
    private final GpuSnapshotRepository snapshots;
    private final GpuSampleRepository samples;
    private final GpuProcessSampleRepository processes;

    public GpuManagerServiceImpl(NodeRepository nodes, GpuSnapshotRepository snapshots,
            GpuSampleRepository samples, GpuProcessSampleRepository processes) {
        this.nodes = nodes;
        this.snapshots = snapshots;
        this.samples = samples;
        this.processes = processes;
    }

    @Override
    public Map<String, Object> getServerGpus(Long serverId) {
        GpuSnapshotEntity snapshot = latestSnapshot(serverId);
        List<GpuSampleEntity> gpuSamples = samples.findAllBySnapshotId(snapshot.getId());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("serverId", serverId);
        response.put("collectionStatus", snapshot.getCollectionStatus());
        response.put("collectedAt", snapshot.getCollectedAt());
        response.put("receivedAt", snapshot.getReceivedAt());
        response.put("gpuCount", gpuSamples.size());
        response.put("gpuIndices", gpuSamples.stream().map(GpuSampleEntity::getGpuIndex).toList());
        response.put("gpuUuids", gpuSamples.stream().map(GpuSampleEntity::getGpuUuid).toList());
        response.put("gpuNames", gpuSamples.stream().map(GpuSampleEntity::getGpuName).toList());
        response.put("gpuMemoryTotal", gpuSamples.stream().map(GpuSampleEntity::getMemoryTotalBytes).toList());
        response.put("gpuMemoryUsed", gpuSamples.stream().map(GpuSampleEntity::getMemoryUsedBytes).toList());
        response.put("gpuMemoryFree", gpuSamples.stream().map(GpuSampleEntity::getMemoryFreeBytes).toList());
        response.put("utilization", gpuSamples.stream().map(GpuSampleEntity::getUtilizationPercent).toList());
        response.put("memoryUtilization", gpuSamples.stream().map(GpuSampleEntity::getMemoryUtilizationPercent).toList());
        response.put("temperature", gpuSamples.stream().map(GpuSampleEntity::getTemperatureC).toList());
        response.put("powerUsage", gpuSamples.stream().map(GpuSampleEntity::getPowerUsageW).toList());
        response.put("fanSpeed", gpuSamples.stream().map(GpuSampleEntity::getFanSpeedPercent).toList());
        response.put("clockSpeed", gpuSamples.stream().map(GpuSampleEntity::getClockSpeedMhz).toList());
        return response;
    }

    @Override
    public Map<String, Object> getGpuLatest(Long serverId, Integer gpuId) {
        validateGpu(serverId, gpuId);
        GpuSnapshotEntity snapshot = latestSnapshot(serverId);
        GpuSampleEntity sample = samples.findBySnapshotIdAndGpuIndex(snapshot.getId(), gpuId);
        if (sample == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "最新 GPU 快照中不存在 GPU index=" + gpuId);
        }
        return toGpuMap(sample);
    }

    @Override
    public Map<String, Object> getServerGpuOverview(Long serverId) {
        GpuSnapshotEntity snapshot = latestSnapshot(serverId);
        List<GpuSampleEntity> gpuSamples = samples.findAllBySnapshotId(snapshot.getId());
        List<BigDecimal> utilization = gpuSamples.stream().map(GpuSampleEntity::getUtilizationPercent).toList();
        List<BigDecimal> memoryUtilization = gpuSamples.stream()
                .map(GpuSampleEntity::getMemoryUtilizationPercent).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("serverId", serverId);
        result.put("collectionStatus", snapshot.getCollectionStatus());
        result.put("status", overviewStatus(snapshot.getCollectionStatus(), gpuSamples.size()));
        result.put("collectedAt", snapshot.getCollectedAt());
        result.put("receivedAt", snapshot.getReceivedAt());
        result.put("gpuCount", gpuSamples.size());
        result.put("averageGpuUtilization", average(utilization));
        result.put("averageMemoryUtilization", average(memoryUtilization));
        result.put("totalGpuMemory", sum(gpuSamples.stream().map(GpuSampleEntity::getMemoryTotalBytes).toList()));
        result.put("usedGpuMemory", sum(gpuSamples.stream().map(GpuSampleEntity::getMemoryUsedBytes).toList()));
        result.put("freeGpuMemory", sum(gpuSamples.stream().map(GpuSampleEntity::getMemoryFreeBytes).toList()));
        result.put("gpus", gpuSamples.stream().map(this::toGpuMap).toList());
        return result;
    }

    @Override
    public List<Map<String, Object>> getGpuProcesses(Long serverId) {
        GpuSnapshotEntity snapshot = latestSnapshot(serverId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (GpuProcessSampleEntity process : processes.findBySnapshotId(snapshot.getId())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("serverId", serverId);
            row.put("gpuIndex", process.getGpuIndex());
            row.put("gpuUuid", process.getGpuUuid());
            row.put("pid", process.getPid());
            row.put("processName", process.getProcessName());
            row.put("username", process.getUsername());
            row.put("gpuMemoryUsage", process.getGpuMemoryUsageBytes());
            row.put("taskType", process.getTaskType());
            row.put("collectedAt", process.getCollectedAt());
            row.put("receivedAt", process.getReceivedAt());
            result.add(row);
        }
        return result;
    }

    @Override
    public List<Map<String, Object>> getGpuHistory(Long serverId, Integer gpuId,
            Instant from, Instant to, int limit) {
        validateGpu(serverId, gpuId);
        if (from == null || to == null || from.isAfter(to) || to.isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GPU 历史时间范围无效");
        }
        int boundedLimit = Math.min(Math.max(limit, 1), MAX_HISTORY_LIMIT);
        return samples.findHistory(serverId, gpuId, from, to, boundedLimit).stream()
                .map(this::toGpuMap).toList();
    }

    private GpuSnapshotEntity latestSnapshot(Long serverId) {
        validateServer(serverId);
        return snapshots.findLatestByNodeId(serverId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "服务器尚无 GPU 上报快照"));
    }

    private void validateGpu(Long serverId, Integer gpuId) {
        validateServer(serverId);
        if (gpuId == null || gpuId < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "gpuId 必须为非负整数");
        }
    }

    private void validateServer(Long serverId) {
        if (serverId == null || serverId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "serverId 必须为正整数");
        }
        if (!nodes.existsById(serverId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "服务器不存在");
        }
    }

    private Map<String, Object> toGpuMap(GpuSampleEntity sample) {
        Map<String, Object> gpu = new LinkedHashMap<>();
        gpu.put("serverId", sample.getNodeId());
        gpu.put("gpuIndex", sample.getGpuIndex());
        gpu.put("gpuUuid", sample.getGpuUuid());
        gpu.put("gpuName", sample.getGpuName());
        gpu.put("gpuMemoryTotal", sample.getMemoryTotalBytes());
        gpu.put("gpuMemoryUsed", sample.getMemoryUsedBytes());
        gpu.put("gpuMemoryFree", sample.getMemoryFreeBytes());
        gpu.put("utilization", sample.getUtilizationPercent());
        gpu.put("memoryUtilization", sample.getMemoryUtilizationPercent());
        gpu.put("temperature", sample.getTemperatureC());
        gpu.put("powerUsage", sample.getPowerUsageW());
        gpu.put("fanSpeed", sample.getFanSpeedPercent());
        gpu.put("clockSpeed", sample.getClockSpeedMhz());
        gpu.put("collectedAt", sample.getCollectedAt());
        gpu.put("receivedAt", sample.getReceivedAt());
        return gpu;
    }

    private String overviewStatus(String collectionStatus, int gpuCount) {
        if ("NO_GPU".equals(collectionStatus)) return "no_gpu";
        if ("UNAVAILABLE".equals(collectionStatus) || "FAILED".equals(collectionStatus)) return "unavailable";
        if ("UNREPORTED".equals(collectionStatus)) return "unreported";
        return gpuCount == 0 ? "unknown" : "ok";
    }

    private BigDecimal average(List<BigDecimal> values) {
        if (values.isEmpty() || values.stream().anyMatch(value -> value == null)) return null;
        BigDecimal total = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }

    private Long sum(List<Long> values) {
        if (values.isEmpty()) return 0L;
        if (values.stream().anyMatch(value -> value == null)) return null;
        return values.stream().mapToLong(Long::longValue).sum();
    }
}
