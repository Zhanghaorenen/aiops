from datetime import datetime, timezone

from .collectors.gpu import GPUInfo
from .collectors.system import SystemCollector
import logging

class HostSnapshot:
    def __init__(self):
        self.system_collector = SystemCollector()
        self.gpu_collector = GPUInfo()

    def get_snapshot(self) -> dict:

        errors = []

        try:
            system = self.system_collector.collect_system_info()
        except Exception as exc:
            system = None
            errors.append(f"system: {exc}")

        try:
            gpus = self.gpu_collector.get_gpu_info()
        except Exception as exc:
            gpus = []
            errors.append(f"gpu: {exc}")

        return {
            "collected_at": datetime.now(timezone.utc).isoformat(),
            "system": system,
            "gpus": gpus,
            "errors": errors,
        }
