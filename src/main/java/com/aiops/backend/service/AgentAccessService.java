package com.aiops.backend.service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.aiops.backend.entity.AgentEnrollTokenEntity;
import com.aiops.backend.entity.HostMetricSampleEntity;
import com.aiops.backend.entity.NodeEntity;
import com.aiops.backend.entity.OpsAgentEntity;
import com.aiops.backend.repository.AgentEnrollTokenRepository;
import com.aiops.backend.repository.HostMetricSampleRepository;
import com.aiops.backend.repository.NodeRepository;
import com.aiops.backend.repository.OpsAgentRepository;

@Service
public class AgentAccessService {
    private final AgentEnrollTokenRepository tokens;
    private final OpsAgentRepository agents;
    private final NodeRepository nodes;
    private final HostMetricSampleRepository metrics;
    private final SecureRandom random = new SecureRandom();

    public AgentAccessService(AgentEnrollTokenRepository tokens, OpsAgentRepository agents,
            NodeRepository nodes, HostMetricSampleRepository metrics) {
        this.tokens = tokens; this.agents = agents; this.nodes = nodes; this.metrics = metrics;
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
            return existing;
        }).orElseGet(() -> agents.save(new OpsAgentEntity(node.getId(), request.agentKey(),
                hash(credential), request.agentVersion(), now)));
        node.markOnline(now);
        return new EnrollmentResult(node.getId(), agent.getId(), credential);
    }

    @Transactional
    public void heartbeat(Long agentId, String version) {
        Instant now = Instant.now();
        OpsAgentEntity agent = agents.findById(agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        agent.heartbeat(now, version);
        nodes.findById(agent.getNodeId()).ifPresent(node -> node.markOnline(now));
    }

    @Transactional
    public HostMetricSampleEntity recordMetric(Long agentId, MetricRequest request) {
        OpsAgentEntity agent = agents.findById(agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        validate(request);
        Instant now = Instant.now();
        Instant collectedAt = request.collectedAt() == null ? now : request.collectedAt();
        agent.heartbeat(now, null);
        nodes.findById(agent.getNodeId()).ifPresent(node -> node.markOnline(now));
        return metrics.save(new HostMetricSampleEntity(agent.getNodeId(), agentId,
                request.cpuPercent(), request.memoryTotalBytes(), request.memoryUsedBytes(),
                percent(request.memoryUsedBytes(), request.memoryTotalBytes()), request.diskTotalBytes(),
                request.diskUsedBytes(), percent(request.diskUsedBytes(), request.diskTotalBytes()),
                collectedAt, now));
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
                nodeId, from, to, PageRequest.of(0, Math.min(Math.max(limit, 1), 1000)));
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
                .forEach(agent -> agent.markOffline(now));
        nodes.findAllByStatusAndLastSeenAtBefore("ONLINE", cutoff)
                .forEach(node -> node.markOffline(now));
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
            Instant collectedAt) {}
}
