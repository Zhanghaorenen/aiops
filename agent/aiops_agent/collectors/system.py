"""Cross-platform host resource collection."""

import os
import platform

import psutil


class SystemCollector:
    def __init__(self, disk_path: str | None = None):
        self.disk_path = disk_path or (
            os.environ.get("SystemDrive", "C:") + "\\" if os.name == "nt" else "/"
        )

    def collect_system_info(self) -> dict:
        memory = psutil.virtual_memory()
        disk = psutil.disk_usage(self.disk_path)
        return {
            "cpu": {
                "count": psutil.cpu_count(logical=True),
                "usage_percent": psutil.cpu_percent(interval=1),
            },
            "memory": {
                "total": memory.total,
                "available": memory.available,
                "used": memory.used,
                "usage_percent": memory.percent,
            },
            "disk": {
                "total": disk.total,
                "used": disk.used,
                "free": disk.free,
                "usage_percent": disk.percent,
            },
            "platform": platform.system(),
            "platform_version": platform.version(),
            "platform_release": platform.release(),
        }
