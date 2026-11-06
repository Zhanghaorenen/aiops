# V0.4 训练资源协同：实现与使用

本次按附件中完整的第三至第五节实现。附件在“六、数据集与模型文件”标题处结束，因此数据集和运行配置目前作为任务元数据登记，没有推测后续文件管理需求。

## 已实现

- 新增 `/training` 训练任务页面，支持创建、编辑未启动任务、排队、重新检查、取消登记、启动前检查、手动关联进程、确认成功/失败、事件历史及可选训练进度。
- 时间、GPU、用户与任务名称沿用 `ops_gpu_usage`，没有建立第二套时间预约。SHARED 按资源峰值检查；EXCLUSIVE 与同 GPU 的活动预约互斥，旧预约按独占处理。旧预约接口不能绕过训练状态机修改训练记录。
- 准入在 READ COMMITTED 事务中先锁服务器行，再读取冲突与资源承诺。RAM 在整台服务器范围计算；GPU 显存在目标卡范围计算。近期开启实时可用量检查，已 RUNNING 的估计需求不会再从实际可用量重复扣除；远期基于总容量承诺，启动时必须重新检查。所有相交预约采取保守累加，可能拒绝部分本可错峰容纳的组合。
- 报告包含申请量、准入基准、实际 Available RAM / Free VRAM、未启动承诺、安全预留、具体拒绝原因、风险提示、竞争预约及当前 GPU 用户/进程显存。支持管理员设置预留量、PSI 阈值和磁盘/网络能力。性能能力或采样未知时明确标记，不能保证吞吐。
- `operations` 配置启用后台队列，每 30 秒按任务所有者身份重新授权并检查。资源释放后可进入 RESERVED；排队用户可取消。单轮最多处理 30 条，更新检查时间以轮转处理。
- 手动进程关联验证服务器、GPU、PID、启动时间与管理员登记的 Linux 用户。避免 PID 复用误关联，拒绝同一活动进程身份重复关联。
- 采集端补充 CPU/Memory/IO PSI、系统 OOM kill 计数、共享内存使用、磁盘 IOPS/忙碌率、网卡错误/丢包、进程读写计数及速率、子进程树。沿用原主机及 GPU 进程采样表的扩展字段；无权限、无接口和速率预热时保持缺测。
- Agent 对曾实际见过的进程身份检测退出或 PID 复用，并上报退出观察。后台仅按精确身份匹配更新任务。手动启动拿不到退出码时使用补充状态 **EXITED_UNKNOWN**，释放逻辑承诺，由用户或 SDK 确认 SUCCEEDED / FAILED；不会把 GPU 暂时不活跃直接当成进程退出。
- 规则分析输出 PSI、RAM、Swap、GPU 显存、磁盘/网络吞吐、共享数据集、其他任务及用户上报的 Step Time / Samples Per Second 变化。页面提供根进程 CPU、RAM、GPU 显存及磁盘读历史图表、进程树与原始证据。
- LangGraph 通过原 ServerTool 增加两个只读工具 `list_training_jobs`、`analyze_training_contention`，继续复用现有流式对话、RAG 和诊断框架。没有注册训练启动、终止或任意 Shell 工具。

## 入口与使用顺序

1. 启动前备份 MySQL；后端启动时由 Flyway 执行新增 **V13**。V1–V12 未修改。V13 增加训练元数据、事件、用户进度、资源策略和 Linux 身份五张表，物理监控仍保存在原指标表。
2. 后端使用已有 `dev,cluster,operations` 配置；前端正常启动后访问 `/#/training`。为获得 PSI、进程读写、进程树及退出观察，需要更新采集 Agent。
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

## 源码阅读顺序

1. `frontend/src/views/TrainingView.vue`：表单、状态操作、准入报告、进程选择和趋势图；页面路由及导航在原 router / App 中。
2. `TrainingJobController.java`：校验 HTTP 输入，交给服务层；写入功能没有暴露给 LLM。
3. `TrainingJobService.java`：权限、服务器行锁、准入、状态机、Linux 身份及进度。`save` 输入资源请求，输出详情和完整准入报告；`link` 输入 PID / 启动时间，输出关联后的 RUNNING 任务。
4. `TrainingQueueWorker.java`：每次使用真实任务所有者重新检查数据库权限，再调用同一个服务，保持前台与后台的规则一致。
5. `TrainingAnalysisService.java`：只读取原采样、进度与共享任务元数据，生成规则观察和证据缺口。
6. `agent/aiops_agent/collectors/system.py`、`process.py`、`gpu.py`、`snapshot.py`：psutil / NVML / procfs 采样与退出身份观察；`MetricDetails.java` 负责进入数据库前的白名单。
7. `agent/ai-service/app/client/platform_client.py` → `tools/server_tool.py` → 原 `agents/graph_agent.py`：用户 JWT 查询 Java API，再由 LangChain Tool 接入 LangGraph。

新增测试为 `TrainingJobTests.java`、`agent/tests/test_training_metrics.py`、`agent/ai-service/tests/test_training_tools.py`，前端权限检查加入原 `chat.test.mjs`。另补回历史 `shcemas` 拼写的模型导入兼容入口。

## 验证与当前限制

- Java：129 项通过，含共享/独占冲突、跨 GPU RAM 竞争、取消释放、过期采样、运行任务不重复扣 RAM、PID 身份匹配、未知退出、缺失性能证据和 VIEWER 权限。
- Agent：20 项通过，含 PSI/OOM 解析、缺测、SDK、PID 复用与权限失败。
- AI：64 项中 63 项通过，1 项外部 Qdrant 专项测试因未配置测试地址跳过。
- Vue：类型检查、构建及 23 项回归通过；仍有已有的大包体积警告。
- 日志位于本机忽略目录 `data/v04-*.log`。
- **本轮没有恢复已停止的前后端/AI 服务，也没有更新或启动远端采集服务。V13 尚未在真实 MySQL 执行，未进行 V0.4 真实 GPU、多用户并发和模型联合验证。** 自动化使用 H2、模拟 HTTP 和模拟传感器，不作为真实训练结果。
- 当前采用文档允许的手动 PID 模式，没有平台远程训练启动/终止、操作系统级显存隔离。取消登记不会杀进程。
- 子进程树最多 128 个节点并标记截断；无权限字段保持未知。Agent 重启丢失的“之前见过的进程身份”不能凭空恢复退出观察，仍可由 SDK / 用户确认结果。
- 历史规则采用最近 30 分钟内最多 120 个主机 / GPU / 进程采样。没有授权日志采集和退出码时不能给出确定失败根因；新知识库和真实模型分析未自动执行。
