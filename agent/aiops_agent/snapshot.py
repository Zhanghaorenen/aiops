"""Collect a JSON-serializable local snapshot, including optional GPU data."""

from datetime import datetime, timezone

from .collectors.gpu import GPUInfo
from .collectors.system import SystemCollector


class HostSnapshot:
    def __init__(self, system_collector=None, gpu_collector=None):
        self.system_collector = system_collector or SystemCollector()
        self.gpu_collector = gpu_collector or GPUInfo()

    def get_snapshot(self) -> dict:
        return {
            "collected_at": datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
            "system": self.system_collector.collect_system_info(),
            "gpus": self.gpu_collector.get_gpu_info(),
        }
