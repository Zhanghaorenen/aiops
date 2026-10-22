
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
                "cpu_percent": process.cpu_percent(interval=1.0),
                "create_time": process.create_time(),
                "num_threads": process.num_threads(),
                "username": process.username()
            }

        except (psutil.NoSuchProcess, psutil.AccessDenied) as e:
            print("获取进程信息失败:", e)
            return "进程信息获取失败"

    def classify_task(self, process_info: dict) -> str:
        # 进程名
        name = process_info.get("name", "").lower()

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
