"""Cross-platform host resource collection."""

import os
import platform

import psutil
import logging

logging.basicConfig(level=logging.INFO)

class SystemCollector:
    def __init__(self, disk_path: str | None = None):
        self.disk_path = disk_path or (
            os.environ.get("SystemDrive", "C:") + "\\" if os.name == "nt" else "/"
        )

    def collect_system_info(self) -> dict:
       
        
        try:
            palatform_info = platform.uname()
            memory = psutil.virtual_memory()
            count = psutil.cpu_count(logical=True)
            usege_percent = psutil.cpu_percent(interval=1)
            disk = psutil.disk_usage(self.disk_path)
            
            name=palatform_info.system
            version=palatform_info.release
            architecture=palatform_info.machine
            disk_percent=disk.percent
            disk_total=disk.total
            disk_used=disk.used
            memory_total=memory.total
            memory_used=memory.used
            data={
                "os": {
                    "name": name,
                    "version": version,
                    "architecture": architecture
                },
                "cpu": {
                    "count": count,
                    "percent": usege_percent
                },
                "memory": {
                    "total": memory_total,
                    "used": memory_used
                },
                "disk": {
                    "total": disk_total,
                    "used": disk_used,
                    "percent": disk_percent
                }
            }
            return data
        except psutil.Error:
            data={
                "os": {
                    "name": None,
                    "version": None,
                    "architecture": None
                },
                "cpu": {
                    "count": 0,
                    "percent": 0.0
                },
                "memory": {
                    "total": 0,
                    "used": 0
                },
                "disk": {
                    "total": 0,
                    "used": 0,
                    "percent": 0.0
                }
            }
            logging.warning("Failed to collect CPU info, returning default values.")
            return data
             
            
        
