

from __future__ import annotations

from datetime import datetime
import math
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from ..client.platform_client import PlatformClient


class GPUCollectionError(RuntimeError):
    """The node has not provided a usable GPU sample."""

    def __init__(self, status: str):
        self.status = status
        super().__init__(f"GPU 数据状态为 {status}")


class GPUManager:
    def __init__(self, platform_client: PlatformClient):
        self.client = platform_client


    async def list_gpus(self, server_id: int) -> list[dict]:
        """沿用平台并行数组结构；缺测保留 None，不采集本机 GPU。"""
        self._validate_ids(server_id)
        info = await self._call("get_server_gpus", server_id)
        if not isinstance(info, dict) or not isinstance(info.get("gpuUuids"), list):
            raise ValueError("平台 GPU 响应必须包含 gpuUuids 列表")
        if info.get("serverId", server_id) != server_id:
            raise ValueError("GPU 数据不属于指定服务器")
        fields = {
            "gpu_name": "gpuNames", "gpu_memory_total": "gpuMemoryTotal",
            "gpu_memory_used": "gpuMemoryUsed", "gpu_memory_free": "gpuMemoryFree",
            "utilization": "utilization", "memory_utilization": "memoryUtilization",
            "temperature": "temperature", "power_usage": "powerUsage",
            "fan_speed": "fanSpeed", "clock_speed": "clockSpeed",
        }
        uuids = info["gpuUuids"]
        collection_status = info.get("collectionStatus")
        if not uuids and collection_status != "NO_GPU":
            raise GPUCollectionError(collection_status or "UNKNOWN")
        if uuids and collection_status not in (None, "AVAILABLE"):
            raise GPUCollectionError(collection_status)
        if any(not isinstance(uuid, str) or not uuid.strip() for uuid in uuids):
            raise ValueError("GPU UUID 必须为非空字符串")
        if len(set(uuids)) != len(uuids):
            raise ValueError("GPU UUID 不能重复")
        for source in fields.values():
            if source in info and (not isinstance(info[source], list) or len(info[source]) != len(uuids)):
                raise ValueError(f"{source} 与 gpuUuids 长度不一致")
        result = []
        for index, uuid in enumerate(uuids):
            gpu = {"server_id": server_id, "gpu_index": index, "gpu_uuid": uuid,
                   "collected_at": info.get("collectedAt"), "received_at": info.get("receivedAt")}
            for field, source in fields.items():
                value = info[source][index] if source in info else None
                if field != "gpu_name" and value is not None:
                    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
                        raise ValueError(f"GPU 指标 {field} 必须为非负有限数值")
                    if field in {"utilization", "memory_utilization", "fan_speed"} and value > 100:
                        raise ValueError(f"GPU 指标 {field} 必须在 0 至 100 之间")
                gpu[field] = value
            total = gpu["gpu_memory_total"]
            if total is not None and any(value is not None and value > total for value in (
                    gpu["gpu_memory_used"], gpu["gpu_memory_free"])):
                raise ValueError("GPU 已用或空闲显存不能大于总显存")
            gpu["memeory_utilization"] = gpu["memory_utilization"]  # 兼容原拼写
            result.append(gpu)
        return result
        
    async def get_gpu_status(self, server_id: int, gpu_id: int) -> dict:
        self._validate_ids(server_id, gpu_id)
        gpu = dict(self._select(await self.list_gpus(server_id), gpu_id))
        gpu["gpu_utilization"] = gpu["utilization"]
        gpu["gpu_memory_utilization"] = gpu["memory_utilization"]
        return gpu

    async def get_gpu_overview(self, server_id: int) -> dict:
        gpus = await self.list_gpus(server_id)
        total = self._total([gpu["gpu_memory_total"] for gpu in gpus])
        used = self._total([gpu["gpu_memory_used"] for gpu in gpus])
        return {
            "server_id": server_id,
            "status": "ok" if gpus else "no_gpu",
            "gpu_count": len(gpus),
            "average_gpu_utilization": self._average([gpu["utilization"] for gpu in gpus]),
            "total_gpu_memory_utilization": self._average([gpu["memory_utilization"] for gpu in gpus]),
            "total_gpu_memory": total, "used_gpu_memory": used,
            "free_gpu_memory": self._total([gpu["gpu_memory_free"] for gpu in gpus]),
            "memory_usage_percent": round(used / total * 100, 2) if total and used is not None else None,
            "gpus": gpus,
        }

    async def get_gpu_processes(self, server_id: int, gpu_id: int) -> list[dict]:
        """只返回必要进程字段，不传播命令行或环境变量。"""
        self._validate_ids(server_id, gpu_id)
        self._select(await self.list_gpus(server_id), gpu_id)
        info = await self._call("get_gpu_process", server_id, gpu_id)
        if not isinstance(info, list) or any(not isinstance(process, dict) for process in info):
            raise ValueError("GPU 进程响应必须为对象列表")
        gpu_process_info = []
        for process in info:
            if process.get("gpuIndex", gpu_id) != gpu_id:
                raise ValueError("进程不属于指定 GPU")
            gpu_process_info.append({
                "pid": process.get("pid"),
                "process_name": process.get("processName"),
                "username": process.get("username"),
                "gpu_memory_usage": process.get("gpuMemoryUsage"),
                "task_type": process.get("taskType"),
                "gpu_index": gpu_id,
            })

        return gpu_process_info

    async def get_gpu_history(self, server_id: int, gpu_id: int, start_time: str, end_time: str):
        """委托平台历史接口，保留原始采样指标并按时间升序返回。"""
        self._validate_ids(server_id, gpu_id)
        start, end = self._time(start_time), self._time(end_time)
        if start > end:
            raise ValueError("开始时间不能晚于结束时间")
        history = await self._call("get_gpu_history", server_id, gpu_id, start_time, end_time)
        if not isinstance(history, list) or any(not isinstance(row, dict) for row in history):
            raise ValueError("GPU 历史响应必须为对象列表")
        result = []
        for row in history:
            if row.get("serverId", server_id) != server_id or row.get("gpuIndex", gpu_id) != gpu_id:
                raise ValueError("历史数据不属于指定服务器或 GPU")
            timestamp = self._time(row.get("collectedAt", row.get("collected_at")))
            if not start <= timestamp <= end:
                raise ValueError("GPU 历史记录不在查询时间范围内")
            result.append(dict(row))
        return sorted(result, key=lambda row: self._time(row.get("collectedAt", row.get("collected_at"))))

    async def get_gpu_process(self, server_id: int, gpu_id: int) -> list[dict]:
        """保留已有的单数接口。"""
        return await self.get_gpu_processes(server_id, gpu_id)

    async def analyze_gpu_health(self, server_id: int, gpu_id: int) -> dict:
        """快照规则提示：温度 85/95℃、显存占用 90/98% 告警。

        这些是本模块提示阈值，不代替厂商标准；高 GPU 利用率不直接判为故障。
        """
        gpu = await self.get_gpu_status(server_id, gpu_id)
        total, used = gpu["gpu_memory_total"], gpu["gpu_memory_used"]
        memory = used / total * 100 if total and used is not None else None
        alerts, missing = [], []
        for metric, value, warning, critical in (
                ("temperature", gpu["temperature"], 85, 95),
                ("memory_usage_percent", memory, 90, 98)):
            if value is None:
                missing.append(metric)
            elif value >= warning:
                alerts.append({"metric": metric, "value": value,
                               "level": "critical" if value >= critical else "warning",
                               "threshold": critical if value >= critical else warning})
        status = "critical" if any(item["level"] == "critical" for item in alerts) else (
            "warning" if alerts else "unknown" if missing else "normal")
        return {"server_id": server_id, "gpu_index": gpu_id, "gpu_uuid": gpu["gpu_uuid"],
                "status": status, "analysis_type": "snapshot_rules", "alerts": alerts,
                "missing_metrics": missing, "memory_usage_percent": memory, "evidence": gpu}

    async def compare_gpu_load(self, server_id: int) -> dict:
        """比较同一快照全部显卡，利用率相差至少 30 个百分点时提示不均衡。"""
        gpus = await self.list_gpus(server_id)
        ranked = sorted((gpu for gpu in gpus if gpu["utilization"] is not None),
                        key=lambda gpu: gpu["utilization"])
        spread = ranked[-1]["utilization"] - ranked[0]["utilization"] if ranked else None
        status = ("no_gpu" if not gpus else "insufficient_data" if len(ranked) != len(gpus) else
                  "single_gpu" if len(gpus) == 1 else "imbalanced" if spread >= 30 else "balanced")
        return {"server_id": server_id, "gpu_count": len(gpus), "status": status,
                "average_gpu_utilization": self._average([gpu["utilization"] for gpu in gpus]),
                "utilization_spread": spread, "imbalance_threshold": 30,
                "least_loaded_gpu": ranked[0] if ranked else None,
                "most_loaded_gpu": ranked[-1] if ranked else None,
                "missing_gpu_indices": [gpu["gpu_index"] for gpu in gpus if gpu["utilization"] is None],
                "suggestions": ["可评估将新任务分配到较空闲 GPU，结合显存容量和任务约束决定。"]
                if status == "imbalanced" else [], "gpus": gpus}

    async def recommend_gpu_placement(
        self, server_id: int, required_memory_bytes: int = 0, gpu_count: int = 1
    ) -> dict:
        """Return a read-only placement recommendation based on the latest sample."""
        self._validate_ids(server_id)
        if type(required_memory_bytes) is not int or required_memory_bytes < 0:
            raise ValueError("required_memory_bytes 必须为非负整数")
        if type(gpu_count) is not int or not 1 <= gpu_count <= 16:
            raise ValueError("gpu_count 必须在 1 至 16 之间")

        gpus = await self.list_gpus(server_id)
        if not gpus:
            return {"server_id": server_id, "status": "no_gpu", "recommendations": [],
                    "message": "该服务器的最新采样确认没有 GPU。"}

        eligible, excluded = [], []
        for gpu in gpus:
            free = gpu["gpu_memory_free"]
            utilization = gpu["utilization"]
            if free is None or utilization is None:
                excluded.append({"gpu_index": gpu["gpu_index"], "reason": "missing_capacity_or_utilization"})
            elif free < required_memory_bytes:
                excluded.append({"gpu_index": gpu["gpu_index"], "reason": "insufficient_free_memory"})
            else:
                total, used = gpu["gpu_memory_total"], gpu["gpu_memory_used"]
                memory_fraction = used / total if total and used is not None else 1.0
                eligible.append((utilization, memory_fraction, gpu))

        ranked = [entry[2] for entry in sorted(eligible, key=lambda entry: (entry[0], entry[1]))]
        selected = ranked[:gpu_count]
        status = "recommended" if len(selected) == gpu_count else (
            "insufficient_capacity" if ranked else "insufficient_data")
        return {
            "server_id": server_id,
            "status": status,
            "requested_gpu_count": gpu_count,
            "required_memory_bytes_per_gpu": required_memory_bytes,
            "recommendations": [{
                "gpu_index": gpu["gpu_index"], "gpu_uuid": gpu["gpu_uuid"],
                "gpu_name": gpu["gpu_name"], "utilization_percent": gpu["utilization"],
                "free_memory_bytes": gpu["gpu_memory_free"],
                "collected_at": gpu["collected_at"],
            } for gpu in selected],
            "excluded_gpus": excluded,
            "execution_performed": False,
            "message": "这是只读分配建议；尚未预留或启动任何任务。",
        }

    async def _call(self, method: str, *args):
        handler = getattr(self.client, method, None)
        if not callable(handler):
            raise NotImplementedError(f"PlatformClient 尚未实现 {method}，请先补齐平台 GPU 查询接口")
        return await handler(*args)

    @staticmethod
    def _validate_ids(server_id: int, gpu_id: int | None = None) -> None:
        if type(server_id) is not int or server_id <= 0:
            raise ValueError("server_id 必须为正整数")
        if gpu_id is not None and (type(gpu_id) is not int or gpu_id < 0):
            raise ValueError("gpu_id 必须为非负整数")

    @staticmethod
    def _select(gpus: list[dict], gpu_id: int) -> dict:
        if gpu_id >= len(gpus):
            raise ValueError("GPU ID 超出范围")
        return gpus[gpu_id]

    @staticmethod
    def _average(values: list) -> float | None:
        return round(sum(values) / len(values), 2) if values and all(v is not None for v in values) else None

    @staticmethod
    def _total(values: list):
        return sum(values) if all(value is not None for value in values) else None

    @staticmethod
    def _time(value: str) -> datetime:
        if not isinstance(value, str):
            raise ValueError("时间必须为带时区的 ISO-8601 字符串")
        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError as error:
            raise ValueError("时间必须为带时区的 ISO-8601 字符串") from error
        if parsed.tzinfo is None:
            raise ValueError("时间必须包含时区，例如 2026-10-30T00:00:00Z")
        return parsed
