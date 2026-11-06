"""Expose read-only GPU queries as LangChain tools."""

from .gpu_manager import GPUManager
from langchain.tools import tool


class GpuTool:
    """GPU query facade and factory for tools bindable to a chat model."""

    _HISTORY_METRICS = {
        "utilization": ("utilization", "gpuUtilization", "gpu_utilization"),
        "memory_utilization": (
            "memoryUtilization",
            "memory_utilization",
            "gpu_memory_utilization",
        ),
        "gpu_memory_total": ("gpuMemoryTotal", "gpu_memory_total"),
        "gpu_memory_used": ("gpuMemoryUsed", "gpu_memory_used"),
        "gpu_memory_free": ("gpuMemoryFree", "gpu_memory_free"),
        "temperature": ("temperature",),
        "power_usage": ("powerUsage", "power_usage"),
        "fan_speed": ("fanSpeed", "fan_speed"),
        "clock_speed": ("clockSpeed", "clock_speed"),
    }

    def __init__(self, gpu_manager: GPUManager):
        self.gpu_manager = gpu_manager

    async def list_gpus(self, server_id: int) -> list[dict]:
        return await self.gpu_manager.list_gpus(server_id)

    async def get_gpu_status(self, server_id: int, gpu_id: int) -> dict:
        return await self.gpu_manager.get_gpu_status(server_id, gpu_id)

    async def get_gpu_overview(self, server_id: int) -> dict:
        return await self.gpu_manager.get_gpu_overview(server_id)

    async def get_gpu_processes(
        self, server_id: int, gpu_id: int | None = None
    ) -> list[dict]:
        return await self.gpu_manager.get_gpu_processes(server_id, gpu_id)

    async def get_gpu_history(
        self,
        server_id: int,
        gpu_id: int,
        metric_name: str,
        start_time: str,
        end_time: str,
        limit: int = 500,
    ) -> list[dict]:
        if metric_name not in self._HISTORY_METRICS:
            raise ValueError(
                f"不支持的 GPU 历史指标: {metric_name}；可选值: "
                f"{', '.join(self._HISTORY_METRICS)}"
            )
        if type(limit) is not int or not 1 <= limit <= 5000:
            raise ValueError("limit 必须是 1 至 5000 之间的整数")

        # Manager 沿用后端完整历史行；工具层只投影本次请求的指标并限制返回条数。
        rows = await self.gpu_manager.get_gpu_history(
            server_id, gpu_id, start_time, end_time
        )
        metric_fields = self._HISTORY_METRICS[metric_name]
        projected: list[dict] = []
        for row in rows:
            row_metric = row.get("metricName", row.get("metric_name"))
            if row_metric is not None:
                if row_metric == metric_name:
                    projected.append(row)
                continue

            for field in metric_fields:
                if field in row:
                    projected.append(
                        {
                            "server_id": server_id,
                            "gpu_index": gpu_id,
                            "collected_at": row.get(
                                "collectedAt", row.get("collected_at")
                            ),
                            "metric_name": metric_name,
                            "value": row[field],
                        }
                    )
                    break

        return projected[-limit:]

    async def analyze_gpu_health(self, server_id: int, gpu_id: int) -> dict:
        """按温度、显存占用等最新指标检查单卡健康状态。"""
        return await self.gpu_manager.analyze_gpu_health(server_id, gpu_id)

    async def compare_gpu_load(self, server_id: int) -> dict:
        """比较同一服务器多张 GPU 的利用率并识别负载偏斜。"""
        return await self.gpu_manager.compare_gpu_load(server_id)

    async def recommend_gpu_placement(
        self, server_id: int, required_memory_bytes: int = 0, gpu_count: int = 1
    ) -> dict:
        """按最新显存和利用率建议 GPU，附进程及历史负载供复核，不会启动任务。"""
        return await self.gpu_manager.recommend_gpu_placement(
            server_id, required_memory_bytes, gpu_count
        )

    def get_tools(self) -> list:
        """Return LangChain tools ready for ``model.bind_tools(...)``."""

        @tool
        async def list_gpus(server_id: int) -> list[dict]:
            """List all GPUs and their latest metrics on a managed server."""
            return await self.list_gpus(server_id)

        @tool
        async def get_gpu_status(server_id: int, gpu_id: int) -> dict:
            """查询单卡最新状态；gpu_id 必须取 list_gpus 返回的真实 gpu_index。"""
            return await self.get_gpu_status(server_id, gpu_id)

        @tool
        async def get_gpu_overview(server_id: int) -> dict:
            """Summarize GPU count, utilization, and memory for a server."""
            return await self.get_gpu_overview(server_id)

        @tool
        async def get_gpu_processes(
            server_id: int, gpu_id: int | None = None
        ) -> list[dict]:
            """List GPU processes, for one GPU or all GPUs when gpu_id is omitted."""
            return await self.get_gpu_processes(server_id, gpu_id)

        @tool
        async def get_gpu_history(
            server_id: int,
            gpu_id: int,
            metric_name: str,
            start_time: str,
            end_time: str,
            limit: int = 500,
        ) -> list[dict]:
            """Query one GPU metric in an ISO-8601 time range (timestamps need a timezone)."""
            return await self.get_gpu_history(
                server_id, gpu_id, metric_name, start_time, end_time, limit
            )

        @tool
        async def analyze_gpu_health(server_id: int, gpu_id: int) -> dict:
            """分析指定 GPU 的温度和显存健康指标，返回状态及数据证据。"""
            return await self.analyze_gpu_health(server_id, gpu_id)

        @tool
        async def compare_gpu_load(server_id: int) -> dict:
            """比较服务器上的多张 GPU 利用率，识别负载不均衡。"""
            return await self.compare_gpu_load(server_id)

        @tool
        async def recommend_gpu_placement(
            server_id: int, required_memory_bytes: int = 0, gpu_count: int = 1
        ) -> dict:
            """按新鲜显存和利用率推荐 GPU，并附进程/历史负载；不执行或预留任务。"""
            return await self.recommend_gpu_placement(
                server_id, required_memory_bytes, gpu_count
            )

        return [
            list_gpus,
            get_gpu_status,
            get_gpu_overview,
            get_gpu_processes,
            get_gpu_history,
            analyze_gpu_health,
            compare_gpu_load,
            recommend_gpu_placement,
        ]
