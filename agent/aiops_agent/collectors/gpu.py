##获取服务器端gpu信息

import pynvml

pynvml.nvmlInit()

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
            count =pynvml.nvmlDeviceGetCount()
            for i  in range(count):
                gpu= pynvml.nvmlDeviceGetHandleByIndex(i)
                name=pynvml.nvmlDeviceGetName(gpu)
                memory=pynvml.nvmlDeviceGetUtilizationRates(gpu)
                memoryinfo=pynvml.nvmlDeviceGetMemoryInfo(gpu)
                power=pynvml.nvmlDeviceGetPowerUsage(gpu)
                utilrate=pynvml.nvmlDeviceGetUtilizationRates(gpu)
                temperature=pynvml.nvmlDeviceGetTemperature(gpu,pynvml.NVML_TEMPERATURE_GPU)
                Process=pynvml.nvmlDeviceGetComputeRunningProcesses(gpu)
                memory_totalInfo={
                    gpu.name: {
                        "memory_total": memoryinfo.total,
                        "memory_used": memoryinfo.used,
                        "memory_free": memoryinfo.free,
                        "power": power,
                        "utilrate": utilrate.gpu,
                        "temperature": temperature,
                        "Process": Process
                    }
                }
                return memory_totalInfo
        except Exception as e:
            print("获取GPU信息失败:", e)
            return None