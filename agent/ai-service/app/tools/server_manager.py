"""Map platform DTOs to read-only tool results without collecting local metrics."""

from ..client.platform_client import PlatformClient


class ServerManager:
    def __init__(self, platform_client: PlatformClient):
        self.client = platform_client

    async def list_servers(self) -> list[dict]:
        return await self.client.get_server_list()

    async def get_server_snapshot(self, server_id: int) -> dict:
        return await self.client.get_server(server_id)

    async def get_resource_usage(self, server_id: int) -> dict:
        sample = await self.client.get_server_latest_metrics(server_id)
        return {
            "server_id": server_id,
            "collected_at": sample.get("collectedAt"),
            "received_at": sample.get("receivedAt"),
            "cpu": {"percent": sample.get("cpuPercent")},
            "memory": {"total_bytes": sample.get("memoryTotalBytes"), "used_bytes": sample.get("memoryUsedBytes"), "percent": sample.get("memoryPercent")},
            "disk": {"total_bytes": sample.get("diskTotalBytes"), "used_bytes": sample.get("diskUsedBytes"), "percent": sample.get("diskPercent")},
        }

    async def get_cpu_status(self, server_id: int) -> dict:
        return (await self.get_resource_usage(server_id))["cpu"]

    async def get_memory_status(self, server_id: int) -> dict:
        return (await self.get_resource_usage(server_id))["memory"]

    async def get_disk_status(self, server_id: int) -> dict:
        return (await self.get_resource_usage(server_id))["disk"]

    async def get_metric_history(self, server_id: int, metric_name: str, start_time: str, end_time: str) -> list[dict]:
        return await self.client.get_server_metric_history(server_id, metric_name, start_time, end_time)

    async def get_server_overview(self, server_id: int) -> dict:
        return await self.client.get_server_overview(server_id)

    async def get_cluster_overview(self) -> dict:
        return await self.client.get_cluster_overview()

    async def get_gpu_process(self, server_id: int, gpu_id: int):
        return await self.client.get_gpu_process(server_id, gpu_id)

    async def get_gpu_history(self, server_id: int, gpu_id: int, start_time: str, end_time: str):
        raise NotImplementedError("GPU 历史尚无平台查询 API，阶段 B 完成前不可用")
