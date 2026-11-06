"""GPU PID 关联的有限进程信息；不读取命令参数或环境变量。"""
from datetime import datetime, timezone
import psutil


class ProcessCollector:
    def __init__(self):
        self._processes = {}

    def get_process_info(self, pid):
        try:
            current = psutil.Process(pid)
            identity = (pid, current.create_time())
            process = self._processes.get(identity, current)
            first = identity not in self._processes
            self._processes[identity] = process
            if len(self._processes) > 2048:
                self._processes = {identity: process}
        except (psutil.NoSuchProcess, psutil.AccessDenied, psutil.ZombieProcess):
            return None
        output = {"pid": pid}

        def read(name, getter):
            try:
                output[name] = getter()
            except (psutil.NoSuchProcess, psutil.AccessDenied, psutil.ZombieProcess, OSError):
                output[name] = None

        for name, getter in [("name", process.name), ("username", process.username), ("status", process.status),
                             ("executablePath", process.exe), ("workingDirectory", process.cwd),
                             ("numThreads", process.num_threads), ("memoryRssBytes", lambda: process.memory_info().rss),
                             ("startedAt", lambda: datetime.fromtimestamp(identity[1], timezone.utc).isoformat())]:
            read(name, getter)
        read("cpuPercent", lambda: process.cpu_percent(interval=None))
        if first:
            output["cpuPercent"] = None
        return output

    def classify_task(self, process_info):
        name = (process_info.get("name") or "").lower() if isinstance(process_info, dict) else ""
        if "python" in name: return "Python Task"
        if "java" in name: return "Java Application"
        if any(s in name for s in ["ollama", "vllm", "triton"]): return "AI Inference"
        return "Unknown"
