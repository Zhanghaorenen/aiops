package com.aiops.backend.service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;

import com.aiops.backend.entity.AgentEnrollTokenEntity;
import com.aiops.backend.entity.HostMetricSampleEntity;
import com.aiops.backend.entity.GpuProcessSampleEntity;
import com.aiops.backend.entity.GpuSampleEntity;
import com.aiops.backend.entity.GpuSnapshotEntity;
import com.aiops.backend.entity.NodeEntity;
import com.aiops.backend.entity.OpsAgentEntity;
import com.aiops.backend.repository.AgentEnrollTokenRepository;
import com.aiops.backend.repository.HostMetricSampleRepository;
import com.aiops.backend.repository.GpuProcessSampleRepository;
import com.aiops.backend.repository.GpuSampleRepository;
import com.aiops.backend.repository.GpuSnapshotRepository;
import com.aiops.backend.repository.NodeRepository;
import com.aiops.backend.repository.OpsAgentRepository;

@Service
public class AgentAccessService {
    private final AgentEnrollTokenRepository tokens;
    private final OpsAgentRepository agents;
    private final NodeRepository nodes;
    private final HostMetricSampleRepository metrics;
    private final GpuSnapshotRepository gpuSnapshots;
    private final GpuSampleRepository gpuSamples;
    private final GpuProcessSampleRepository gpuProcesses;
    private final SecureRandom random = new SecureRandom();

    public AgentAccessService(AgentEnrollTokenRepository tokens, OpsAgentRepository agents,
            NodeRepository nodes, HostMetricSampleRepository metrics, GpuSnapshotRepository gpuSnapshots,
            GpuSampleRepository gpuSamples, GpuProcessSampleRepository gpuProcesses) {
        this.tokens = tokens; this.agents = agents; this.nodes = nodes; this.metrics = metrics;
        this.gpuSnapshots = gpuSnapshots; this.gpuSamples = gpuSamples; this.gpuProcesses = gpuProcesses;
    }

    @Transactional
    public EnrollmentToken issueEnrollmentToken() {
        String raw = randomSecret();
        Instant now = Instant.now();
        Instant expiry = now.plus(30, ChronoUnit.MINUTES);
        tokens.save(new AgentEnrollTokenEntity(hash(raw), expiry, now));
        return new EnrollmentToken(raw, expiry);
    }

    @Transactional
    public EnrollmentResult enroll(EnrollmentRequest request) {
        Instant now = Instant.now();
        AgentEnrollTokenEntity token = tokens.lockByTokenHash(hash(request.enrollmentToken()))
                .filter(value -> value.isUsableAt(now))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "注册令牌无效或已过期"));
        token.markUsed(now);
        tokens.save(token);
        NodeEntity node = nodes.findByNodeKey(request.nodeKey()).orElseGet(() ->
                nodes.save(new NodeEntity(request.nodeKey(), request.displayName(), request.hostname(),
                        request.ip(), request.osName(), request.osVersion(), request.architecture(),
                        request.cpuCores(), request.memoryBytes())));
        String credential = randomSecret();
        OpsAgentEntity agent = agents.findByAgentKey(request.agentKey()).map(existing -> {
            if (!existing.getNodeId().equals(node.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Agent 标识已绑定其他节点");
            }
            existing.rotateCredential(hash(credential), request.agentVersion(), now);
            return agents.save(existing);
        }).orElseGet(() -> agents.save(new OpsAgentEntity(node.getId(), request.agentKey(),
                hash(credential), request.agentVersion(), now)));
        node.markOnline(now);
        nodes.save(node);
        return new EnrollmentResult(node.getId(), agent.getId(), credential);
    }

    @Transactional
    public void heartbeat(Long agentId, String version) {
        Instant now = Instant.now();
        OpsAgentEntity agent = agents.findById(agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        agent.heartbeat(now, version);
        agents.save(agent);
        nodes.findById(agent.getNodeId()).ifPresent(node -> {
            node.markOnline(now);
            nodes.save(node);
        });
    }

    @Transactional
    public HostMetricSampleEntity recordMetric(Long agentId, MetricRequest request) {
        OpsAgentEntity agent = agents.findById(agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        validate(request);
        Instant now = Instant.now();
        Instant collectedAt = request.collectedAt() == null ? now : request.collectedAt();
        agent.heartbeat(now, null);
        agents.save(agent);
        nodes.findById(agent.getNodeId()).ifPresent(node -> {
            node.markOnline(now);
            nodes.save(node);
        });
        HostMetricSampleEntity saved = metrics.save(new HostMetricSampleEntity(agent.getNodeId(), agentId,
                request.cpuPercent(), request.memoryTotalBytes(), request.memoryUsedBytes(),
                percent(request.memoryUsedBytes(), request.memoryTotalBytes()), request.diskTotalBytes(),
                request.diskUsedBytes(), percent(request.diskUsedBytes(), request.diskTotalBytes()),
                collectedAt, now));
        recordGpuSnapshot(agent, request, collectedAt, now);
        return saved;
    }

    private void recordGpuSnapshot(OpsAgentEntity agent, MetricRequest request, Instant collectedAt, Instant receivedAt) {
        String status = request.gpuStatus() == null ? "UNREPORTED" : request.gpuStatus();
        List<GpuSampleRequest> gpus = request.gpus() == null ? List.of() : request.gpus();
        if (!List.of("AVAILABLE", "NO_GPU", "UNAVAILABLE", "FAILED", "UNKNOWN", "UNREPORTED").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "gpuStatus 不受支持");
        }
        if (!gpus.isEmpty() && !"AVAILABLE".equals(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "存在 GPU 样本时 gpuStatus 必须为 AVAILABLE");
        }
        if ("AVAILABLE".equals(status) && gpus.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "AVAILABLE 状态必须包含 GPU 样本");
        }

        Set<Integer> indices = new HashSet<>();
        Set<String> uuids = new HashSet<>();
        for (GpuSampleRequest gpu : gpus) {
            validateGpu(gpu, indices, uuids);
        }

        String error = request.gpuError();
        if (error != null && error.length() > 1024) error = error.substring(0, 1024);
        GpuSnapshotEntity snapshot = gpuSnapshots.save(new GpuSnapshotEntity(
                agent.getNodeId(), agent.getId(), status, error, collectedAt, receivedAt));
        for (GpuSampleRequest gpu : gpus) {
            GpuSampleEntity sample = gpuSamples.save(new GpuSampleEntity(snapshot.getId(), agent.getNodeId(),
                    agent.getId(), gpu.gpuIndex(), gpu.gpuUuid(), gpu.gpuName(), gpu.memoryTotalBytes(),
                    gpu.memoryUsedBytes(), gpu.memoryFreeBytes(), gpu.utilizationPercent(),
                    gpu.memoryUtilizationPercent(), gpu.temperatureC(), gpu.powerUsageW(),
                    gpu.fanSpeedPercent(), gpu.clockSpeedMhz(), collectedAt, receivedAt));
            if (gpu.processes() == null) continue;
            for (GpuProcessRequest process : gpu.processes()) {
                if (process.gpuIndex() != null && !gpu.gpuIndex().equals(process.gpuIndex())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "GPU 进程 gpuIndex 与所属 GPU 不一致");
                }
                gpuProcesses.save(new GpuProcessSampleEntity(snapshot.getId(), agent.getNodeId(),
                        gpu.gpuIndex(), gpu.gpuUuid(), process.pid(), process.processName(),
                        process.username(), process.gpuMemoryUsageBytes(), process.taskType(),
                        collectedAt, receivedAt));
            }
        }
    }

    private void validateGpu(GpuSampleRequest gpu, Set<Integer> indices, Set<String> uuids) {
        if (gpu == null || gpu.gpuIndex() == null || gpu.gpuIndex() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GPU index 必须为非负整数");
        }
        if (!indices.add(gpu.gpuIndex())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GPU index 不能重复");
        }
        if (gpu.gpuUuid() != null && !uuids.add(gpu.gpuUuid())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GPU UUID 不能重复");
        }
        nonNegative(gpu.memoryTotalBytes(), "memoryTotalBytes");
        nonNegative(gpu.memoryUsedBytes(), "memoryUsedBytes");
        nonNegative(gpu.memoryFreeBytes(), "memoryFreeBytes");
        if (gpu.memoryTotalBytes() != null && (gpu.memoryUsedBytes() != null
                && gpu.memoryUsedBytes() > gpu.memoryTotalBytes()
                || gpu.memoryFreeBytes() != null && gpu.memoryFreeBytes() > gpu.memoryTotalBytes())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GPU 显存用量不能超过总显存");
        }
        percent(gpu.utilizationPercent(), "utilizationPercent");
        percent(gpu.memoryUtilizationPercent(), "memoryUtilizationPercent");
        percent(gpu.fanSpeedPercent(), "fanSpeedPercent");
        nonNegative(gpu.temperatureC(), "temperatureC");
        nonNegative(gpu.powerUsageW(), "powerUsageW");
        nonNegative(gpu.clockSpeedMhz(), "clockSpeedMhz");
        if (gpu.processes() != null) {
            for (GpuProcessRequest process : gpu.processes()) {
                if (process == null || process.pid() == null || process.pid() <= 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GPU 进程 PID 必须为正整数");
                }
                nonNegative(process.gpuMemoryUsageBytes(), "gpuMemoryUsageBytes");
            }
        }
    }

    private void percent(java.math.BigDecimal value, String name) {
        if (value != null && (value.signum() < 0 || value.compareTo(java.math.BigDecimal.valueOf(100)) > 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " 必须在 0 到 100 之间");
        }
    }

    private void nonNegative(java.math.BigDecimal value, String name) {
        if (value != null && value.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " 不能为负数");
        }
    }

    @Transactional(readOnly = true)
    public HostMetricSampleEntity latestMetric(Long nodeId) {
        return metrics.findTopByNodeIdOrderByCollectedAtDescIdDesc(nodeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "暂无监控数据"));
    }

    @Transactional(readOnly = true)
    public List<HostMetricSampleEntity> metricHistory(Long nodeId, Instant from, Instant to, int limit) {
        if (from == null || to == null || from.isAfter(to) || to.isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "时间范围无效");
        }
        return metrics.findAllByNodeIdAndCollectedAtBetweenOrderByCollectedAtAscIdAsc(
                nodeId, from, to, Math.min(Math.max(limit, 1), 1000));
    }

    public OpsAgentEntity authenticate(String credential) {
        if (credential == null || credential.isBlank()) return null;
        return agents.findByCredentialHash(hash(credential)).orElse(null);
    }

    @Scheduled(fixedDelay = 30000)
    @Transactional
    public void markDisconnectedAgentsOffline() {
        Instant cutoff = Instant.now().minusSeconds(90);
        Instant now = Instant.now();
        agents.findAllByStatusAndLastHeartbeatAtBefore("ONLINE", cutoff)
                .forEach(agent -> {
                    agent.markOffline(now);
                    agents.save(agent);
                });
        nodes.findAllByStatusAndLastSeenAtBefore("ONLINE", cutoff)
                .forEach(node -> {
                    node.markOffline(now);
                    nodes.save(node);
                });
    }

    private void validate(MetricRequest value) {
        range(value.cpuPercent(), "cpuPercent");
        nonNegative(value.memoryTotalBytes(), "memoryTotalBytes");
        nonNegative(value.memoryUsedBytes(), "memoryUsedBytes");
        nonNegative(value.diskTotalBytes(), "diskTotalBytes");
        nonNegative(value.diskUsedBytes(), "diskUsedBytes");
        if (value.memoryTotalBytes() == 0 || value.diskTotalBytes() == 0
                || value.memoryUsedBytes() > value.memoryTotalBytes()
                || value.diskUsedBytes() > value.diskTotalBytes()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "资源使用量必须在 0 和总量之间，且总量大于 0");
        }
        if (value.collectedAt() != null && value.collectedAt().isAfter(Instant.now().plusSeconds(60))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "采集时间不能晚于当前时间");
        }
    }

    private void range(Double value, String name) {
        if (value == null || !Double.isFinite(value) || value < 0 || value > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " 必须在 0 到 100 之间");
        }
    }
    private void nonNegative(Long value, String name) {
        if (value == null || value < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " 不能为负数");
    }
    private double percent(long used, long total) { return Math.round((double) used * 10000.0 / total) / 100.0; }
    private String randomSecret() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes); return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private String hash(String raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public record EnrollmentToken(String token, Instant expiresAt) {}
    public record EnrollmentResult(Long nodeId, Long agentId, String credential) {}
    public record EnrollmentRequest(@NotBlank String enrollmentToken,
            @NotBlank @Size(max = 100) String nodeKey,
            @NotBlank @Size(max = 160) String displayName,
            @NotBlank @Size(max = 160) String hostname,
            @Size(max = 64) String ip, @Size(max = 120) String osName,
            @Size(max = 160) String osVersion, @Size(max = 80) String architecture,
            @Positive Integer cpuCores, @Positive Long memoryBytes,
            @NotBlank @Size(max = 100) String agentKey,
            @NotBlank @Size(max = 32) String agentVersion) {}
    public record MetricRequest(@NotNull @DecimalMin("0.0") @DecimalMax("100.0") Double cpuPercent,
            @NotNull @Positive Long memoryTotalBytes, @NotNull @DecimalMin("0") Long memoryUsedBytes,
            @NotNull @Positive Long diskTotalBytes, @NotNull @DecimalMin("0") Long diskUsedBytes,
            Instant collectedAt, String gpuStatus, @Size(max = 32) List<@Valid GpuSampleRequest> gpus,
            @Size(max = 1024) String gpuError) {
        public MetricRequest(Double cpuPercent, Long memoryTotalBytes, Long memoryUsedBytes,
                Long diskTotalBytes, Long diskUsedBytes, Instant collectedAt) {
            this(cpuPercent, memoryTotalBytes, memoryUsedBytes, diskTotalBytes,
                    diskUsedBytes, collectedAt, null, null, null);
        }
    }

    public record GpuSampleRequest(@NotNull @Min(0) Integer gpuIndex, @NotBlank @Size(max = 160) String gpuUuid,
            @Size(max = 255) String gpuName, @Positive Long memoryTotalBytes,
            @DecimalMin("0") Long memoryUsedBytes, @DecimalMin("0") Long memoryFreeBytes,
            @DecimalMin("0") @DecimalMax("100") java.math.BigDecimal utilizationPercent,
            @DecimalMin("0") @DecimalMax("100") java.math.BigDecimal memoryUtilizationPercent,
            @DecimalMin("0") java.math.BigDecimal temperatureC,
            @DecimalMin("0") java.math.BigDecimal powerUsageW,
            @DecimalMin("0") @DecimalMax("100") java.math.BigDecimal fanSpeedPercent,
            @DecimalMin("0") java.math.BigDecimal clockSpeedMhz,
            @Size(max = 256) List<@Valid GpuProcessRequest> processes) {}

    public record GpuProcessRequest(@NotNull @Positive Long pid, @Size(max = 255) String processName,
            @Size(max = 160) String username, @DecimalMin("0") Long gpuMemoryUsageBytes,
            @Size(max = 80) String taskType, @Min(0) Integer gpuIndex) {}
}
