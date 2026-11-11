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
| `PUT /identity` | 管理员绑定 `{userId, linuxUsername, linuxUid?}` |

训练请求使用 `expectedVramMb`、`expectedRamMb`（MiB）、可选 `expectedCpuCores`、`expectedDiskReadMbps`、`expectedDiskWriteMbps`、`expectedNetworkMbps`（MB/s），以及 taskName、description、gpuIndex、mode、startsAt、expectedEndAt、dataset、runConfiguration、taskType、queueIfUnavailable。


## 限制

- 当前采用手动启动与 PID 关联。取消登记不会终止系统进程，共享承诺不提供硬件隔离。
- 进程退出但退出码未知时，状态为 EXITED_UNKNOWN，由用户或 SDK 确认结果。
- 子进程树最多返回 128 个节点。性能规则读取最近 30 分钟内有限采样，不把相关性当成确定根因。


## 运行画像与资源建议

更新采集端后，已认证的 Agent 上报 GPU 进程 UID、启动时间和有限的子进程资源。后端按服务器、PID、启动时间建档；无法唯一映射平台用户时显示为未映射，不按 Python 进程名认定训练。

在“训练任务协同”的运行记录中确认任务类型、配置标识与环境标识。相同标识应代表相同模型、数据规模、参数与运行环境。GPU 型号和所有者也参与画像匹配。GPU 父子进程按同 UID 保守归组（不猜测不同父进程是否属于同一训练）；GPU 进程与同 UID 子进程按 PID 去重；RSS 汇总是含共享页的上界估计，USS 不含共享页，整卡利用率与主机 PSI 独立保存。采集权限不足的字段保留未知。

预算复用训练准入规则，从新鲜 Available RAM、空闲显存中扣除尚未运行的有效承诺和管理员预留。已经运行的任务不再次扣减。设备吞吐能力未配置时不会承诺 IO 或网络容量。历史辅助建议使用同配置画像 P95 加余量与观测峰值中的较高值，实际登记和启动仍需重新检查。

SDK 支持 `report(step=30, total_steps=100, step_time=0.5, phase="training")`。至少三个近期同阶段速度样本用于估计剩余区间；只有 Epoch 总量时按同阶段连续 Epoch 的稳定速度估算；历史方法需要至少三次相似配置的成功运行。预测只是区间估计，无法保证验证、Checkpoint 阶段的时长。结束后保存预测误差，画像显示按方法统计的 MAE；不对准确率作承诺。

| 相对路径 | 用途 |
|---|---|
| `GET /workloads?active=false` | 授权运行记录，他人仅共享摘要 |
| `GET /workloads/{runId}` | 任务所有者 / 管理员详情与最近 200 个采样 |
| `POST /workloads/{runId}/confirm` | 确认任务特征，按已验证进程身份关联登记 |
| `GET /workloads/{runId}/profile` | 私人资源画像 |
| `GET /workloads/{runId}/prediction` | 结束时间区间与方法 |
| `GET /capacity?gpuIndex=0&vramMb=10240&ramMb=8192` | 当前预算，`runId` 可关联私人画像 |

后台预测与通知使用 `operations` 配置，每分钟执行确定性规则，不调用 LLM。画像每十个快照及确认/归档时更新。单个画像统计最近 100 个匹配运行、每次最多 5000 个最近样本；观测峰值不能保证覆盖采样间隙。
