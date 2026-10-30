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
 * 查询数据来自被管 Agent 的 GPU 上报及数据库快照；控制器本身不访问中心服务器的 NVML。
 */
@RestController
@RequestMapping("/api/servers/{serverId}/gpus")
public class GpuManagerController {
    private static final int MAX_HISTORY_LIMIT = 1440;

    private final NodeRepository nodes;
    private final ObjectProvider<GpuManagerService> queries;
    private final GpuManagerService queryService;
    public GpuManagerController(NodeRepository nodes, ObjectProvider<GpuManagerService> queries) {
        this.nodes = nodes;
        this.queries = queries;
        this.queryService = queries.getIfAvailable();
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
        List<Map<String, Object>> history = requireData(
                service.getGpuHistory(serverId, gpuId, from, to, boundedLimit), "GPU 历史查询暂不可用");
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

}
