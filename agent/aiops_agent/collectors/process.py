
import psutil


class ProcessCollector:

    def get_process_info(self, pid: int) -> dict | None:
        try:
            process = psutil.Process(pid)

            return {
                "pid": process.pid,
                "name": process.name(),
                "status": process.status(),
                "cmdline": process.cmdline(),
            "cpu_percent": process.cpu_percent(interval=None),
                "create_time": process.create_time(),
                "num_threads": process.num_threads(),
                "username": process.username()
            }

        except (psutil.NoSuchProcess, psutil.AccessDenied, psutil.ZombieProcess):
            return None

    def classify_task(self, process_info: dict) -> str:
        # 进程名
        if not isinstance(process_info, dict):
            return "Unknown"
        name = (process_info.get("name") or "").lower()

        # 启动命令
        cmdline = " ".join(process_info.get("cmdline") or []).lower()

        # 1. 深度学习任务
        if any(k in cmdline for k in ["torchrun", "train.py", "deepspeed"]):
            return "Deep Learning Training"

        # 2. AI 推理服务
        elif any(k in cmdline for k in ["vllm", "ollama", "tritonserver"]):
            return "AI Inference"

        # 3. Web 服务
        elif any(k in cmdline for k in ["uvicorn", "gunicorn", "fastapi"]):
            return "Web Service"

        # 4. Java 服务
        elif "java" in name:
            return "Java Application"

        # 5. Nginx
        elif "nginx" in name:
            return "Web Server"

        # 6. 其他 Python 任务
        elif "python" in name:
            return "Python Task"

        return "Unknown"
