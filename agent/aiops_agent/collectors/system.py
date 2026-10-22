##获取系统信息

import psutil
import platform


class SystemCollector:
    def collect_system_info(self):
        try:
            system_info = {
                "cpu": {
                    "count": psutil.cpu_count(logical=True),
                    "usage_percent": psutil.cpu_percent(interval=1)
                },
                "memory": {
                    "total": psutil.virtual_memory().total,
                    "available": psutil.virtual_memory().available,
                    "used": psutil.virtual_memory().used,
                    "usage_percent": psutil.virtual_memory().percent
                },
                "disk": {
                    "total": psutil.disk_usage('/').total,
                    "used": psutil.disk_usage('/').used,
                    "free": psutil.disk_usage('/').free,
                    "usage_percent": psutil.disk_usage('/').percent
                },
                "platform": platform.system(),
                "platform_version": platform.version(),
                "platform_release": platform.release()
            }
            return system_info
        except Exception as e:
            print("获取系统信息失败:", e)
            return None