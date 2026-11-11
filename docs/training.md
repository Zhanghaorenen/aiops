# 训练任务

管理共享 GPU 任务的资源需求、进程关联与训练进度。

## 入口与使用顺序

1. 启动前备份 MySQL；后端启动时由 Flyway 执行新增 **V13**。V1–V12 未修改。V13 增加训练元数据、事件、用户进度、资源策略和 Linux 身份五张表，物理监控仍保存在原指标表。
2. 后端使用已有 `cluster,operations` 配置；前端正常启动后访问 `/#/training`。为获得 PSI、进程读写、进程树及退出观察，需要更新采集 Agent。
3. 管理员进入训练页面底部，设置合理的 RAM / VRAM 安全预留；磁盘和网络能力以 MB/s 配置，未知时留空。
4. 管理员绑定“平台用户 ID → 目标服务器 Linux 用户名”。普通用户不能自行冒用其他 Linux 用户。
5. 用户登记未来开始时间、GPU、共享模式及资源峰值；资源不足时检查报告或排队。**预约不会启动训练，也不提供硬件隔离。**
6. 在预约窗口内点击启动前检查，通过后自行在服务器启动程序，选择最新 GPU 快照中的进程进行关联。进程启动时间由采集数据提供，不能只填写 PID。
7. 点击性能分析查看实际历史与证据缺口；需要进一步的知识库解释时，可在 AI 助手指定服务器和任务 ID 查询。AI 工具只读，结论中会区分采样、用户上报和推测。

## 可选 Python SDK

安装本项目采集包后可以导入 `aiops_agent.training_sdk.TrainingMonitor`。不使用 SDK 也能登记和关联现有训练。

```python
import os
from aiops_agent.training_sdk import TrainingMonitor

monitor = TrainingMonitor(
    os.environ['AIOPS_BACKEND_URL'],
    os.environ['AIOPS_USER_JWT'],  # 用户 JWT，不是采集 Agent 凭据
    server_id=2,
    job_id=123,                  # 替换为真实且已经关联运行进程的任务
)
# 由用户选择何时和以何种频率报告，避免每个 batch 高频写库。
monitor.report(epoch=1, step=100, step_time=0.5,
               samples_per_second=64, phase='train')
# 仅在已知训练结果时调用；HTTP 异常由训练程序自行处理。
monitor.finish(success=True)
```

SDK 数据标记为 USER_REPORTED。系统 OOM 计数不能证明某个任务被 OOM Killer 终止，NVML 整卡利用率也不会作为单个进程的利用率。

## API

统一前缀：`/api/servers/{serverId}/training`，沿用用户 JWT 和工作空间权限。

| 方法与相对路径 | 作用 |
|---|---|
| `GET /jobs`、`POST /jobs` | 查询 / 创建 |
| `GET /jobs/{id}`、`PUT /jobs/{id}` | 详情与历史 / 编辑 |
| `POST /jobs/{id}/recheck` | 重新准入 |
| `POST /jobs/{id}/state` | STARTING、CANCELLED、SUCCEEDED、FAILED |
| `POST /jobs/{id}/process` | 关联 `{pid, startedAt}` |
| `POST /jobs/{id}/progress` | 上报可选进度 |
| `GET /jobs/{id}/analysis` | 性能规则、证据和历史 |
| `GET /policy`、`PUT /policy` | 查看 / 管理员设置资源策略 |
| `PUT /identity` | 管理员绑定 `{userId, linuxUsername}` |

训练请求使用 `expectedVramMb`、`expectedRamMb`（MiB）、可选 `expectedCpuCores`、`expectedDiskReadMbps`、`expectedDiskWriteMbps`、`expectedNetworkMbps`（MB/s），以及 taskName、description、gpuIndex、mode、startsAt、expectedEndAt、dataset、runConfiguration、taskType、queueIfUnavailable。


## 限制

- 当前采用手动启动与 PID 关联。取消登记不会终止系统进程，共享承诺不提供硬件隔离。
- 进程退出但退出码未知时，状态为 EXITED_UNKNOWN，由用户或 SDK 确认结果。
- 子进程树最多返回 128 个节点。性能规则读取最近 30 分钟内有限采样，不把相关性当成确定根因。
