
"""NVIDIA GPU 信息及计算进程采集"""

import json
import logging

try:
    import pynvml
except ImportError:
    pynvml = None

from .process import ProcessCollector

logger = logging.getLogger(__name__)


class GPUInfo:
    """采集当前服务器上全部 NVIDIA GPU 的监控信息。"""

    def __init__(self):
        self.process_collector = ProcessCollector()

    @staticmethod
    def _text(value):
        """把 NVML 返回的 bytes 转换为普通字符串。"""
        if value is None:
            return None

        if isinstance(value, bytes):
            return value.decode("utf-8", errors="replace")

        return str(value)

    @staticmethod
    def _read(label, getter, errors):
        """
        安全读取单项 GPU 指标。
        某项指标不支持时，不影响其他指标。
        """
        try:
            return getter()
        except pynvml.NVMLError as exc:
            errors.append(f"{label}: {exc}")
            return None

    def _processes(self, handle, errors):
        """获取 GPU 计算进程，并关联 psutil 进程信息。"""

        raw_processes = self._read(
            "compute_processes",
            lambda: pynvml.nvmlDeviceGetComputeRunningProcesses(handle),
            errors,
        )

        if raw_processes is None:
            return []

        result = []

        for item in raw_processes:
            pid = int(item.pid)

            raw_memory = getattr(item, "usedGpuMemory", None)

            # NVML 某些情况下使用特殊值表示显存数据不可用
            if raw_memory is None or int(raw_memory) == (2**64 - 1):
                used_bytes = None
            else:
                used_bytes = int(raw_memory)

            record = {
                "pid": pid,
                "gpu_memory_bytes": used_bytes,
                "name": None,
                "username": None,
                "task_type": "Unknown",
            }

            try:
                detail = self.process_collector.get_process_info(pid)

                if isinstance(detail, dict):
                    record["name"] = detail.get("name")
                    record["username"] = detail.get("username")

                    record["task_type"] = (
                        self.process_collector.classify_task(detail)
                    )

                # 不上传原始 cmdline，避免泄漏密码和 Token

            except Exception as exc:
                errors.append(f"process {pid}: {exc}")

            result.append(record)

        return result

    def get_gpu_info(self) -> list[dict]:
        """获取所有 GPU 信息，返回可 JSON 序列化的列表。"""

        if pynvml is None:
            logger.warning(
                "GPU 采集不可用：请安装 nvidia-ml-py"
            )
            return []

        # 初始化 NVML
        try:
            pynvml.nvmlInit()

        except pynvml.NVMLError as exc:
            logger.warning(
                "GPU 采集不可用（驱动/NVML）：%s", exc
            )
            return []

        gpus = []

        try:
            try:
                count = pynvml.nvmlDeviceGetCount()

            except pynvml.NVMLError as exc:
                logger.warning("无法获取 GPU 数量：%s", exc)
                return []

            for index in range(count):

                try:
                    handle = pynvml.nvmlDeviceGetHandleByIndex(index)

                except pynvml.NVMLError as exc:
                    logger.warning(
                        "无法访问 GPU %d：%s", index, exc
                    )
                    continue

                errors = []

                # GPU 基础信息
                name = self._read(
                    "name",
                    lambda: pynvml.nvmlDeviceGetName(handle),
                    errors,
                )

                uuid = self._read(
                    "uuid",
                    lambda: pynvml.nvmlDeviceGetUUID(handle),
                    errors,
                )

                # 显存
                memory = self._read(
                    "memory",
                    lambda: pynvml.nvmlDeviceGetMemoryInfo(handle),
                    errors,
                )

                # GPU 与显存控制器利用率
                util = self._read(
                    "utilization",
                    lambda: pynvml.nvmlDeviceGetUtilizationRates(handle),
                    errors,
                )

                # 温度
                temperature = self._read(
                    "temperature",
                    lambda: pynvml.nvmlDeviceGetTemperature(
                        handle,
                        pynvml.NVML_TEMPERATURE_GPU,
                    ),
                    errors,
                )

                # 功率，NVML 返回毫瓦
                power_mw = self._read(
                    "power",
                    lambda: pynvml.nvmlDeviceGetPowerUsage(handle),
                    errors,
                )

                gpu_info = {
                    "index": index,
                    "uuid": self._text(uuid),
                    "name": self._text(name),

                    "memory_total_bytes": (
                        int(memory.total)
                        if memory is not None else None
                    ),

                    "memory_used_bytes": (
                        int(memory.used)
                        if memory is not None else None
                    ),

                    "memory_free_bytes": (
                        int(memory.free)
                        if memory is not None else None
                    ),

                    "utilization_percent": (
                        int(util.gpu)
                        if util is not None else None
                    ),

                    "memory_utilization_percent": (
                        int(util.memory)
                        if util is not None else None
                    ),

                    "temperature_c": (
                        int(temperature)
                        if temperature is not None else None
                    ),

                    "power_w": (
                        round(power_mw / 1000.0, 2)
                        if power_mw is not None else None
                    ),

                    "processes": self._processes(handle, errors),

                    "errors": errors,
                }

                # 追加到列表，不覆盖之前的 GPU
                gpus.append(gpu_info)

            return gpus

        finally:
            # NVML 成功初始化之后才会执行关闭
            try:
                pynvml.nvmlShutdown()

            except pynvml.NVMLError as exc:
                logger.warning(
                    "关闭 NVML 时发生异常：%s", exc
                )

    def get_gpu_process_info(self) -> list[dict]:
        """获取全部 GPU 的计算进程列表。"""

        return [
            {
                "gpu_index": gpu["index"],
                "gpu_uuid": gpu["uuid"],
                **process,
            }
            for gpu in self.get_gpu_info()
            for process in gpu["processes"]
        ]

