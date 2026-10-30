package com.aiops.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.aiops.backend.service.AgentAccessService.EnrollmentRequest;
import com.aiops.backend.service.AgentAccessService.EnrollmentResult;
import com.aiops.backend.service.AgentAccessService.GpuProcessRequest;
import com.aiops.backend.service.AgentAccessService.GpuSampleRequest;
import com.aiops.backend.service.AgentAccessService.MetricRequest;

@SpringBootTest
@Transactional
class GpuManagerServiceTests {
    @Autowired private AgentAccessService agentAccess;
    @Autowired private GpuManagerService gpuManager;

    @Test
    void readsMultiGpuSnapshotProcessesOverviewAndBoundedHistoryFromOneNode() {
        EnrollmentResult node = enroll("gpu-multi");
        Instant base = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MICROS);
        GpuSampleRequest gpu0 = gpu(0, "GPU-uuid-0", 1000, 400, 600, "worker-a");
        GpuSampleRequest gpu1 = gpu(1, "GPU-uuid-1", 2000, 1000, 1000, "worker-b");
        record(node, base, "AVAILABLE", List.of(gpu0, gpu1));
        record(node, base.plusSeconds(1), "AVAILABLE", List.of(
                gpu(0, "GPU-uuid-0", 1000, 500, 500, "worker-a"),
                gpu(1, "GPU-uuid-1", 2000, 1200, 800, "worker-b")));

        Map<String, Object> list = gpuManager.getServerGpus(node.nodeId());
        assertEquals(List.of("GPU-uuid-0", "GPU-uuid-1"), list.get("gpuUuids"));
        assertEquals(2, list.get("gpuCount"));

        Map<String, Object> latest = gpuManager.getGpuLatest(node.nodeId(), 1);
        assertEquals("GPU-uuid-1", latest.get("gpuUuid"));
        assertEquals(1200L, latest.get("gpuMemoryUsed"));

        Map<String, Object> overview = gpuManager.getServerGpuOverview(node.nodeId());
        assertEquals("ok", overview.get("status"));
        assertEquals(2, overview.get("gpuCount"));
        assertEquals(1700L, overview.get("usedGpuMemory"));
        assertEquals(new BigDecimal("60.00"), overview.get("averageGpuUtilization"));

        List<Map<String, Object>> processes = gpuManager.getGpuProcesses(node.nodeId());
        assertEquals(2, processes.size());
        assertEquals("worker-a", processes.get(0).get("processName"));
        assertNull(processes.get(0).get("cmdline"));

        List<Map<String, Object>> history = gpuManager.getGpuHistory(
                node.nodeId(), 0, base, base.plusSeconds(1), 1);
        assertEquals(1, history.size());
        assertEquals(base, history.get(0).get("collectedAt"));
        assertEquals(400L, history.get(0).get("gpuMemoryUsed"));
    }

    @Test
    void distinguishesNoGpuFromUnavailableAndUnreportedSnapshots() {
        EnrollmentResult node = enroll("gpu-state");
        Instant base = Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.MICROS);

        record(node, base, "NO_GPU", List.of());
        assertEquals("NO_GPU", gpuManager.getServerGpus(node.nodeId()).get("collectionStatus"));
        assertEquals("no_gpu", gpuManager.getServerGpuOverview(node.nodeId()).get("status"));
        assertEquals(List.of(), gpuManager.getGpuProcesses(node.nodeId()));

        record(node, base.plusSeconds(1), "UNAVAILABLE", List.of());
        assertEquals("unavailable", gpuManager.getServerGpuOverview(node.nodeId()).get("status"));

        // A pre-GPU Agent can still report host metrics; its GPU state is unknown,
        // never silently represented as a machine with no GPU.
        record(node, base.plusSeconds(2), null, null);
        assertEquals("UNREPORTED", gpuManager.getServerGpus(node.nodeId()).get("collectionStatus"));
        assertEquals("unreported", gpuManager.getServerGpuOverview(node.nodeId()).get("status"));
    }

    private EnrollmentResult enroll(String prefix) {
        String key = prefix + "-" + UUID.randomUUID();
        return agentAccess.enroll(new EnrollmentRequest(agentAccess.issueEnrollmentToken().token(),
                key, "GPU test node", "gpu-host", "127.0.0.1", "Linux", "test",
                "amd64", 4, 4096L, key, "test"));
    }

    private void record(EnrollmentResult node, Instant at, String status, List<GpuSampleRequest> gpus) {
        agentAccess.recordMetric(node.agentId(), new MetricRequest(20.0, 1000L, 250L,
                2000L, 500L, at, status, gpus, null));
    }

    private GpuSampleRequest gpu(int index, String uuid, long total, long used, long free, String processName) {
        return new GpuSampleRequest(index, uuid, "NVIDIA Test GPU", total, used, free,
                BigDecimal.valueOf(60), BigDecimal.valueOf(45), BigDecimal.valueOf(55),
                BigDecimal.valueOf(120), null, null,
                List.of(new GpuProcessRequest(1234L + index, processName, "test-user",
                        128L, "compute", index)));
    }
}
