"""主机、多分区和网卡采样；首个速率样本未知，不补零。"""
import os
import platform
import time
import math
import psutil


class SystemCollector:
    def __init__(self, disk_path=None):
        self.disk_path = disk_path or (os.environ.get("SystemDrive", "C:") + "\\" if os.name == "nt" else "/")
        self._previous = None

    @staticmethod
    def collect_temperatures():
        """温度来自内核传感器，CPU、主板和硬盘分别标记，避免把硬盘温度当 CPU 温度。"""
        read = getattr(psutil, "sensors_temperatures", None)
        if read is None:
            return "UNSUPPORTED", []
        try:
            sensors = read(fahrenheit=False)
        except (OSError, psutil.Error, NotImplementedError):
            return "UNAVAILABLE", []
        if not sensors:
            return "UNSUPPORTED", []

        def celsius(value):
            if isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and -100 <= value <= 250:
                return round(value, 2)
            return None

        result = []
        for sensor, entries in sensors.items():
            name = sensor.lower()
            category = ("CPU" if any(key in name for key in ("coretemp", "k10temp", "zenpower", "cpu_thermal", "cpu-thermal"))
                        else "STORAGE" if any(key in name for key in ("nvme", "drivetemp", "hdd"))
                        else "BOARD" if any(key in name for key in ("acpitz", "pch_", "nct", "it87")) else "OTHER")
            for entry in entries:
                current = celsius(entry.current)
                if current is None:
                    continue
                result.append({"sensor": str(sensor)[:128], "label": str(entry.label or sensor)[:128],
                               "category": category, "currentCelsius": current,
                               "highCelsius": celsius(entry.high), "criticalCelsius": celsius(entry.critical)})
                if len(result) == 256:
                    return "AVAILABLE", result
        return ("AVAILABLE" if result else "UNAVAILABLE"), result

    def collect_system_info(self):
        system = platform.uname()
        memory, swap = psutil.virtual_memory(), psutil.swap_memory()
        cores = psutil.cpu_percent(interval=1, percpu=True)
        disk = psutil.disk_usage(self.disk_path)
        now = time.monotonic()
        net = psutil.net_io_counters(pernic=True)
        io = psutil.disk_io_counters(perdisk=True) or {}
        previous = self._previous
        elapsed = now - previous[0] if previous else 0

        def rate(current, before, field):
            if not previous or before is None or elapsed <= 0:
                return None
            delta = getattr(current, field) - getattr(before, field)
            return round(delta / elapsed, 2) if delta >= 0 else None

        networks = [{"name": name, "bytesReceived": item.bytes_recv, "bytesSent": item.bytes_sent,
                     "receiveBytesPerSecond": rate(item, previous[1].get(name) if previous else None, "bytes_recv"),
                     "sendBytesPerSecond": rate(item, previous[1].get(name) if previous else None, "bytes_sent")}
                    for name, item in list(net.items())[:256]]
        disks, seen = [], set()
        for partition in psutil.disk_partitions(all=False):
            if partition.mountpoint in seen or len(disks) >= 256:
                continue
            try:
                usage = psutil.disk_usage(partition.mountpoint)
            except (OSError, psutil.Error):
                continue
            seen.add(partition.mountpoint)
            device = os.path.basename(partition.device)
            counter = io.get(device)
            old = previous[2].get(device) if previous else None
            disks.append({"device": partition.device, "mountpoint": partition.mountpoint, "fstype": partition.fstype,
                          "totalBytes": usage.total, "usedBytes": usage.used, "freeBytes": usage.free, "percent": usage.percent,
                          "readBytesPerSecond": rate(counter, old, "read_bytes") if counter else None,
                          "writeBytesPerSecond": rate(counter, old, "write_bytes") if counter else None})
        self._previous = (now, net, io)
        try:
            load = os.getloadavg()
        except (AttributeError, OSError):
            load = (None, None, None)
        temperature_status, temperatures = self.collect_temperatures()
        return {"os": {"name": system.system, "version": system.release, "architecture": system.machine},
                "cpu": {"count": psutil.cpu_count(logical=True), "percent": round(sum(cores) / len(cores), 2) if cores else None},
                "memory": {"total": memory.total, "used": memory.used},
                "disk": {"total": disk.total, "used": disk.used, "percent": disk.percent},
                "details": {"perCorePercent": cores, "load1": load[0], "load5": load[1], "load15": load[2],
                            "memoryAvailableBytes": memory.available, "memoryCachedBytes": getattr(memory, "cached", None),
                            "swapTotalBytes": swap.total, "swapUsedBytes": swap.used, "swapPercent": swap.percent,
                            "disks": disks, "network": networks,
                            "temperatureStatus": temperature_status, "temperatures": temperatures}}
