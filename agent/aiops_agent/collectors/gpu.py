##获取服务器端gpu信息

import pynvml
import psutil
from process import ProcessCollector

class GPUInfo:
    def __init__(self):
        self.name = ""
        self.memory = 0
        self.memoryinfo = 0
        self.power = 0
        self.utilrate = 0
        self.temperature = 0
        self.Process = []

    def get_gpu_info(self):
        try:
            pynvml.nvmlInit()
            count =pynvml.nvmlDeviceGetCount()
            memory_totalInfo = {}
            for i  in range(count):
                gpu= pynvml.nvmlDeviceGetHandleByIndex(i)
                Uiid=pynvml.nvmlDeviceGetUUID(gpu)
                name=pynvml.nvmlDeviceGetName(gpu)
                memoryRate=pynvml.nvmlDeviceGetUtilizationRates(gpu)
                memoryinfo=pynvml.nvmlDeviceGetMemoryInfo(gpu)
                power=pynvml.nvmlDeviceGetPowerUsage(gpu)
                utilrate=pynvml.nvmlDeviceGetUtilizationRates(gpu)
                temperature=pynvml.nvmlDeviceGetTemperature(gpu,pynvml.NVML_TEMPERATURE_GPU)
                Process=pynvml.nvmlDeviceGetComputeRunningProcesses(gpu)
                memory_totalInfo={
                    name: {
                        "uuid": Uiid,
                        "memory_total": memoryinfo.total,
                        "memory_used": memoryinfo.used,
                        "memory_free": memoryinfo.free,
                        "power": power,
                        "memoryRate": memoryRate.gpu,
                        "utilrate": utilrate.gpu,
                        "temperature": temperature,
                        "Process": Process
                    }
                }
            return memory_totalInfo
        except self.error_info as code:
            print(self.error_info(code))
            return self.error_info(code)
        finally:
            pynvml.nvmlShutdown()

    def error_info(self, error_code):
        error_messages = {
            pynvml.NVML_ERROR_UNKNOWN: "未知错误",
            pynvml.NVML_ERROR_NOT_SUPPORTED: "不支持",
            pynvml.NVML_ERROR_NO_PERMISSION: "权限不足",
            pynvml.NVML_ERROR_NOT_FOUND: "未找到",
            pynvml.NVML_ERROR_INSUFFICIENT_SIZE: "缓冲区不足",
            pynvml.NVML_ERROR_UNINITIALIZED: "未初始化",
            pynvml.NVML_ERROR_INVALID_ARGUMENT: "无效参数",
            pynvml.NVML_ERROR_LIBRARY_NOT_FOUND: "库未找到",
            pynvml.NVML_ERROR_FUNCTION_NOT_FOUND: "函数未找到",
            pynvml.NVML_ERROR_CORRUPTED_INFOROM: "信息ROM损坏",
            pynvml.NVML_ERROR_GPU_IS_LOST: "GPU丢失",
            pynvml.NVML_ERROR_RESET_REQUIRED: "需要重置",
            pynvml.NVML_ERROR_OPERATING_SYSTEM: "操作系统错误",
            pynvml.NVML_ERROR_LIB_RM_VERSION_MISMATCH: "库和驱动版本不匹配",
            pynvml.NVML_ERROR_IN_USE: "资源正在使用",
            pynvml.NVML_ERROR_MEMORY: "内存错误",
            pynvml.NVML_ERROR_NO_DATA: "无数据",
            pynvml.NVML_ERROR_VGPU_ECC_NOT_SUPPORTED: "vGPU ECC不支持",  
        }
        if error_code in error_messages:
            return error_messages[error_code]
        return "未知错误"
    def get_gpu_process_info(self):
        try:
            count =pynvml.nvmlDeviceGetCount()
            for i  in range(count):
                gpu= pynvml.nvmlDeviceGetHandleByIndex(i)
                Process=pynvml.nvmlDeviceGetComputeRunningProcesses(gpu)
                process_info = []
                for p in Process:
                    process_info.append({
                        "pid": p.pid,
                        "usedGpuMemory": p.usedGpuMemory
                    })
                return process_info
        except self.error_info as e:
            print(self.error_info(e))
            return self.error_info(e)
    