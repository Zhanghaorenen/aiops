"""GPU PID 关联的有限进程信息；不读取命令参数或环境变量。"""
from datetime import datetime, timezone
import psutil
import time


class ProcessCollector:
    def __init__(self):
        self._processes = {}
        self._io = {}
        self._exits = []

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
                             ("parentPid", process.ppid), ("numThreads", process.num_threads), ("memoryRssBytes", lambda: process.memory_info().rss),
                             ("startedAt", lambda: datetime.fromtimestamp(identity[1], timezone.utc).isoformat())]:
            read(name, getter)
        read("cpuPercent", lambda: process.cpu_percent(interval=None))
        if first:
            output["cpuPercent"] = None
        try:
            io, now = process.io_counters(), time.monotonic()
            old = self._io.get(identity)
            output.update(readBytes=io.read_bytes, writeBytes=io.write_bytes)
            if old and now > old[0]:
                for field, current, before in [('readBytesPerSecond',io.read_bytes,old[1]), ('writeBytesPerSecond',io.write_bytes,old[2])]:
                    output[field] = (current-before)/(now-old[0]) if current >= before else None
            self._io[identity] = (now,io.read_bytes,io.write_bytes)
            if len(self._io)>2048: self._io = {identity:self._io[identity]}
        except (psutil.Error, OSError):
            pass
        try:
            children = process.children(recursive=True)
            tree = []
            for child in children[:128]:
                try:
                    tree.append({'pid':child.pid,'parentPid':child.ppid(),'name':child.name(),
                                 'startedAt':datetime.fromtimestamp(child.create_time(),timezone.utc).isoformat(),
                                 'memoryRssBytes':child.memory_info().rss})
                except (psutil.Error,OSError):
                    continue
            output['processTree'] = tree
            output['processTreeTruncated'] = len(children)>128
        except (psutil.Error,OSError):
            pass
        return output

    def collect_exits(self):
        # 只报告本采集器实际见过的身份；GPU 暂不活跃不等于进程退出。
        for identity in list(self._processes):
            try:
                exited = psutil.Process(identity[0]).create_time() != identity[1]
            except (psutil.NoSuchProcess, psutil.ZombieProcess):
                exited = True
            except (psutil.AccessDenied, OSError):
                continue
            if exited:
                self._exits.append({'pid':identity[0], 'startedAt':datetime.fromtimestamp(identity[1],timezone.utc).isoformat(),
                                    'observedAt':datetime.now(timezone.utc).isoformat(), 'status':'EXITED_UNKNOWN'})
                self._processes.pop(identity,None)
                self._io.pop(identity,None)
        self._exits = self._exits[-128:]
        return list(self._exits)

    def classify_task(self, process_info):
        name = (process_info.get("name") or "").lower() if isinstance(process_info, dict) else ""
        if "python" in name: return "Python Task"
        if "java" in name: return "Java Application"
        if any(s in name for s in ["ollama", "vllm", "triton"]): return "AI Inference"
        return "Unknown"
