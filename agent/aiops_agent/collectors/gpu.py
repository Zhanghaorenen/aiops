##获取服务器端gpu信息

import pynvml
import psutil
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
    def __del__(self):
        pynvml.nvmlShutdown()
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
        except Exception as e:
            print("获取GPU进程信息失败:", e)
            return None
    def Task_Associated_with_GPU(self):
       try:
            count =pynvml.nvmlDeviceGetCount()
            for i  in range(count):
                gpu= pynvml.nvmlDeviceGetHandleByIndex(i)
                Process=pynvml.nvmlDeviceGetComputeRunningProcesses(gpu)
                task_info = []
                for p in Process:
                    task_info.append({
                        "pid": p.pid,
                        "usedGpuMemory": p.usedGpuMemory
                        })
                    process=psutil.Process(p.pid)
                    task_info.append({
                        "pid": process.pid,
                        "name": process.name(),
                        "status": process.status(),
                        "create_time": process.create_time(),
                        "cpu_percent": process.cpu_percent(interval=1.0),
                        "memory_info": process.memory_info()._asdict(),
                        "open_files": [f.path for f in process.open_files()],
                        "connections": [c._asdict() for c in process.connections()],
                        "num_threads": process.num_threads(),
                        "threads": [t._asdict() for t in process.threads()],
                    })
                return task_info