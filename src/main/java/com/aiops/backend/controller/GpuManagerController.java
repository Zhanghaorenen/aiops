package com.aiops.backend.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.aiops.backend.repository.NodeRepository;
import com.aiops.backend.service.GpuManagerService;

/**
 * 指定服务器的只读 GPU API。沿用全局 USER/JWT 授权，不在中心服务器采集 NVML 数据。
 *
 * 现有工程尚无 GPU 上报/持久化/查询服务。查询契约暂放在本文件内，以遵守仅修改
 * Controller 的约束；实际查询服务需实现 GpuQueryService 并注册为 Spring Bean。
 * 未接入服务时返回 501，不把缺少数据来源伪装成空列表或零利用率。
 */
@RestController
@RequestMapping("/api/servers/{serverId}/gpus")
public class GpuManagerController {
    private static final int MAX_HISTORY_LIMIT = 1440;

    private final NodeRepository nodes;
    private final ObjectProvider<GpuManagerService> queries;

    public GpuManagerController(NodeRepository nodes, ObjectProvider<GpuManagerService> queries) {
        this.nodes = nodes;
        this.queries = queries;
    }

    @GetMapping
    public Map<String, Object> getServerGpus(@PathVariable Long serverId) {
        ensureServer(serverId);
        return requireData(queryService().getServerGpus(serverId), "暂无 GPU 快照");
    }

    @GetMapping("/{gpuId}/latest")
    public Map<String, Object> getGpuLatest(@PathVariable Long serverId, @PathVariable Integer gpuId) {
        ensureGpuId(gpuId);
        ensureServer(serverId);
        return requireData(queryService().getGpuLatest(serverId, gpuId), "暂无指定 GPU 的最新采样");
    }

    @GetMapping("/overview")
    public Map<String, Object> getServerGpuOverview(@PathVariable Long serverId) {
        ensureServer(serverId);
        return requireData(queryService().getServerGpuOverview(serverId), "暂无 GPU 汇总数据");
    }

    @GetMapping("/processes")
    public List<Map<String, Object>> getGpuProcesses(@PathVariable Long serverId) {
        ensureServer(serverId);
        List<Map<String, Object>> processes = requireData(
                queryService().getGpuProcesses(serverId), "尚未采集 GPU 进程数据");
        // 仅返回约定字段，不将命令行、环境变量或其他进程敏感信息传播给 AI。
        return processes.stream().map(process -> {
            if (process == null || !(process.get("gpuIndex") instanceof Integer index) || index < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GPU 进程数据缺少有效的 gpuIndex");
            }
            Map<String, Object> safe = new java.util.LinkedHashMap<>();
            safe.put("serverId", serverId);
            for (String field : List.of("gpuIndex", "pid", "processName", "username", "gpuMemoryUsage", "taskType")) {
                safe.put(field, process.get(field));
            }
            if (process.containsKey("serverId") && !serverId.equals(process.get("serverId"))) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GPU 进程数据不属于指定服务器");
            }
            return safe;
        }).toList();
    }

    @GetMapping("/{gpuId}/history")
    public List<Map<String, Object>> getGpuHistory(@PathVariable Long serverId,
            @PathVariable Integer gpuId, @RequestParam Instant from, @RequestParam Instant to,
            @RequestParam(defaultValue = "500") int limit) {
        ensureGpuId(gpuId);
        if (from == null || to == null || from.isAfter(to) || to.isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "时间范围无效");
        }
        ensureServer(serverId);
        int boundedLimit = Math.min(Math.max(limit, 1), MAX_HISTORY_LIMIT);
        GpuManagerService service = queryService();
        if (!(service instanceof GpuQueryService historyService)) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "GPU 查询服务尚未接入历史查询能力");
        }
        List<Map<String, Object>> history = requireData(
                historyService.getGpuHistory(serverId, gpuId, from, to, boundedLimit), "GPU 历史查询暂不可用");
        if (history.size() > boundedLimit || history.stream().anyMatch(row -> row == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GPU 历史查询返回的数据不符合约定");
        }
        return history;
    }

    private void ensureServer(Long serverId) {
        if (serverId == null || serverId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "serverId 必须为正整数");
        }
        if (!nodes.existsById(serverId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "服务器不存在");
        }
    }

    private void ensureGpuId(Integer gpuId) {
        if (gpuId == null || gpuId < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "gpuId 必须为非负整数");
        }
    }

    private GpuManagerService queryService() {
        GpuManagerService service = queries.getIfAvailable();
        if (service == null) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "尚未接入 GPU 上报、存储及查询服务，无法提供真实 GPU 数据");
        }
        return service;
    }

    private <T> T requireData(T data, String message) {
        if (data == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
        return data;
    }

    /**
     * 供后续真实 GPU 查询服务实现的接入契约，不是数据存储或模拟服务。
     * 所有查询必须按 serverId 隔离，gpuId 为服务器内从零开始的 GPU 索引。
     */
    public interface GpuQueryService extends GpuManagerService {
        /**
         * 返回 serverId、collectedAt、receivedAt 和等长数组 gpuUuids、gpuNames、
         * gpuMemoryTotal/Used/Free、utilization、memoryUtilization，及可选温度等数组。
         * 已确认无 GPU 时 gpuUuids 为空；未采集/失败应抛出明确异常。
         */
        Map<String, Object> getServerGpus(Long serverId);

        /** 单卡采样包含 serverId、gpuIndex、gpuUuid、collectedAt 及真实指标；不存在时返回 null。 */
        Map<String, Object> getGpuLatest(Long serverId, Integer gpuId);

        /** 整机汇总包含 serverId、gpuCount、采样时间及真实汇总指标。 */
        Map<String, Object> getServerGpuOverview(Long serverId);

        /** 进程包含 gpuIndex、pid、processName、username、gpuMemoryUsage、taskType。 */
        List<Map<String, Object>> getGpuProcesses(Long serverId);

        /** 按 collectedAt 升序，包含 serverId/gpuIndex/collectedAt，闭区间查询且数量不超过 limit。 */
        List<Map<String, Object>> getGpuHistory(Long serverId, Integer gpuId, Instant from, Instant to, int limit);
    }
}
