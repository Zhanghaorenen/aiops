"""Expose read-only GPU queries as LangChain tools."""

from .gpu_manager import GPUManager
from langchain.tools import tool


class GpuTool:
    """GPU query facade and factory for tools bindable to a chat model."""

    _HISTORY_METRICS = {
        "utilization": ("utilization", "gpuUtilization", "gpu_utilization"),
        "memory_utilization": ("memoryUtilization", "memory_utilization", "gpu_memory_utilization"),
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
        # GPUManager's current API queries one GPU at a time; aggregate when
        # the model asks for all GPU processes.
        if gpu_id is not None:
            return await self.gpu_manager.get_gpu_processes(server_id, gpu_id)

        gpus = await self.gpu_manager.list_gpus(server_id)
        processes: list[dict] = []
        for gpu in gpus:
            index = gpu.get("gpu_index")
            if type(index) is not int or index < 0:
                raise ValueError("GPUManager 返回了无效的 gpu_index")
            processes.extend(
                await self.gpu_manager.get_gpu_processes(server_id, index)
            )
        return processes

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

        # The current manager returns complete history rows and accepts no
        # metric/limit arguments. Project the requested metric here.
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

    def get_tools(self) -> list:
        """Return LangChain tools ready for ``model.bind_tools(...)``."""

        @tool
        async def list_gpus(server_id: int) -> list[dict]:
            """List all GPUs and their latest metrics on a managed server."""
            return await self.list_gpus(server_id)

        @tool
        async def get_gpu_status(server_id: int, gpu_id: int) -> dict:
            """Get one GPU's latest status; gpu_id is its zero-based index."""
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

        return [
            list_gpus,
            get_gpu_status,
            get_gpu_overview,
            get_gpu_processes,
            get_gpu_history,
        ]
