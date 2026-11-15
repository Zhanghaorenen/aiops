"""Map platform DTOs to read-only tool results without collecting local metrics."""

from ..client.platform_client import PlatformClient
from .gpu_manager import GPUManager
from .numeric_evidence import capacity, percent


class ServerManager:
    def __init__(self, platform_client: PlatformClient):
        self.client = platform_client

    async def list_servers(self) -> list[dict]:
        return [self._asset(row) for row in await self.client.get_server_list()]

    @staticmethod
    def _asset(row):
        return {**row, 'memory_capacity_display': capacity(row.get('memoryBytes'))}

    ##获取服务器快照
    async def get_server_snapshot(self, server_id: int) -> dict:
        return self._asset(await self.client.get_server(server_id))

    ##获取服务器资源使用情况
    async def get_resource_usage(self, server_id: int) -> dict:
        sample = await self.client.get_server_latest_metrics(server_id)
        details = sample.get('details', {})
        cores = details.get('perCorePercent', [])
        return {
            'capacity_display': {key:capacity(sample.get(field)) for key,field in {
                'memory_total':'memoryTotalBytes','memory_used':'memoryUsedBytes',
                'disk_total':'diskTotalBytes','disk_used':'diskUsedBytes'}.items()},
            'available_ram_display':capacity(details.get('memoryAvailableBytes')),
            'cpu_core_summary': {'reported_count':len(cores),'fully_busy_indices':[i for i,v in enumerate(cores) if v == 100]},
            'evidence_limits': 'Single load averages do not prove a precise duration. Swap occupancy does not measure current paging. Disk percent aggregation is unspecified; do not infer averaging or weighting.',
            "server_id": server_id,
            "collected_at": sample.get("collectedAt"),
            "received_at": sample.get("receivedAt"),
            "freshness": GPUManager._freshness(sample.get("collectedAt")),
            "host_details": sample.get("details", {}),
            "cpu": {"percent": sample.get("cpuPercent")},
            "memory": {
                "total_bytes": sample.get("memoryTotalBytes"),
                "used_bytes": sample.get("memoryUsedBytes"),
                "percent": sample.get("memoryPercent"),
            },
            "disk": {
                "total_bytes": sample.get("diskTotalBytes"),
                "used_bytes": sample.get("diskUsedBytes"),
                "percent": sample.get("diskPercent"),
            },
        }

    ###获取服务器CPU状态
    async def get_cpu_status(self, server_id: int) -> dict:
        return (await self.get_resource_usage(server_id))["cpu"]

    async def get_memory_status(self, server_id: int) -> dict:
        return (await self.get_resource_usage(server_id))["memory"]

    async def get_disk_status(self, server_id: int) -> dict:
        return (await self.get_resource_usage(server_id))["disk"]

    async def get_metric_history(
        self, server_id: int, metric_name: str, start_time: str, end_time: str
    ) -> list[dict]:
        return await self.client.get_server_metric_history(
            server_id, metric_name, start_time, end_time
        )

    async def get_server_overview(self, server_id: int) -> dict:
        return await self.client.get_server_overview(server_id)

    async def get_cluster_overview(self) -> dict:
        return await self.client.get_cluster_overview()
