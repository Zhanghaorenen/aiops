"""Collect a JSON-serializable local snapshot, including optional GPU data."""

from datetime import datetime, timezone

from .collectors.gpu import GPUInfo
from .collectors.system import SystemCollector
import logging

class HostSnapshot:
    def __init__(self, system_collector=None, gpu_collector=None):
        self.system_collector = system_collector or SystemCollector()
        self.gpu_collector = gpu_collector or GPUInfo()

    def get_snapshot(self) -> dict:
            try:
                 data=self.system_collector.collect_system_info()
                 return data
            except data is None :
                logging.error("Failed to collect system information.")
           
        