"""只采样一次并输出摘要；不打印进程命令行、凭据，也不向平台发送测试数据。"""

import json
from aiops_agent.__main__ import Agent

agent = Agent()
payload = agent.collect()
print(json.dumps({
    "collectedAt": payload["collectedAt"],
    "cpuPercent": payload["cpuPercent"],
    "memoryTotalBytes": payload["memoryTotalBytes"],
    "diskTotalBytes": payload["diskTotalBytes"],
    "gpuStatus": payload["gpuStatus"],
    "gpus": [{k: gpu[k] for k in (
        "gpuIndex", "gpuUuid", "gpuName", "memoryTotalBytes",
        "memoryUsedBytes", "utilizationPercent", "temperatureC",
    )} | {"processCount": len(gpu["processes"])} for gpu in payload["gpus"]],
}, ensure_ascii=False, indent=2))
