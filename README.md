# AIOps

**GPU 训练资源协作与诊断**

[![CI](https://github.com/Zhanghaorenen/aiops/actions/workflows/ci.yml/badge.svg)](https://github.com/Zhanghaorenen/aiops/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

面向实验室与小型团队共用 GPU 服务器的场景，提供主机/GPU 监控、训练资源准入、告警调查及带知识库引用的 AI 对话。受管服务器仅运行轻量 Python Agent，模型与管理服务集中部署。

[快速开始](docs/quickstart.md) · [架构设计](docs/architecture.md) · [演示流程](docs/project-demo.md) · [文档索引](docs/README.md)

![运行总览与资源仪表盘](docs/images/overview.png)

> 界面演示使用标注的示例数据；缺少 GPU 与历史采样时显示空状态。

会话历史按用户保存在 MySQL。训练页提供进程运行记录、历史资源画像、当前容量预算和结束时间区间；个人记录中可管理私人记忆、核实故障经验与订阅站内资源提醒。预测由后端统计规则计算，AI 按需查询和解释。

## 解决的问题

多人共用 GPU 时，显存空闲并不等于整机资源足够：RAM、CPU、磁盘和网络也可能成为瓶颈。AIOps 将资源观测、使用承诺、实际进程与诊断建议关联起来，帮助使用者判断谁在占用资源、任务是否可以接入、训练变慢有哪些证据。

## 功能

| 能力 | 当前实现 |
|---|---|
| 主机 / GPU 遥测 | CPU/各核、RAM/Swap、多分区、网卡、多 GPU、传感器温度与进程；缺测保持未知 |
| 训练资源协同 | SHARED/EXCLUSIVE、整机 RAM 与 GPU 显存联合准入、安全预留、队列复查、生命周期与历史 |
| 进程身份关联 | PID＋启动时间＋Linux 用户，避免 PID 复用误关联；可选 SDK 上报 Epoch/Step/吞吐 |
| 告警与调查 | 预设 / 自定义规则、持续窗口、恢复阈值、去重；告警关联 Incident 后异步调查 |
| AI 运维助手 | LangGraph 调度监控工具，SSE 输出文本与工具状态；管理员可请求受控资源保护检查，沿用用户 JWT |
| 内存资源保护 | 默认关闭；本地持续压力判定、业务UID授权、系统/平台排除、TERM→KILL、冷却与审计，见[部署与边界](docs/resource-guard.md) |
| RAG 知识库 | 模板编辑、文档解析、分块与向量检索、重新索引、原文引用 |
| 共享权限 | ADMIN/OPERATOR/VIEWER；用户 JWT 与独立 Agent Credential 分离 |

**能力边界：** 当前训练采用手动启动与 PID 关联；共享预约是资源承诺，不提供硬件显存隔离。模型没有任意 SSH/Shell 执行能力。整卡 GPU 利用率不归因于单个进程，未知退出码不推断为成功。

## 架构

```mermaid
flowchart LR
    User[用户] --> Web[Vue 3 控制台]
    Agent[Python Agent<br/>psutil / NVML / procfs] -->|Agent Credential| API[Spring Boot<br/>资产 / RBAC / 监控 / 训练 / 告警]
    Web -->|用户 JWT · REST| API
    API --> DB[(MySQL · Flyway)]
    Web -->|用户 JWT · SSE| AI[FastAPI / LangGraph]
    AI -->|同一用户身份 · 监控查询或受控检查请求| API
    AI --> LLM[OpenAI 兼容接口]
    AI --> RAG[LangChain RAG<br/>本地 Embedding / Qdrant]
    API -->|数据库任务与租约| Worker[调查 Worker]
    Worker --> AI
```

## 值得阅读的实现

- **联合准入的并发保护：** 先锁服务器行再检查资源承诺；RAM 按整机计算；运行任务不从实际可用量重复扣除估计资源。见 [TrainingJobService](src/main/java/com/aiops/backend/service/TrainingJobService.java)。
- **可追溯进程身份：** 启动时间补足 PID，区分进程退出事实与训练结果。见 [ProcessCollector](agent/aiops_agent/collectors/process.py)。
- **规则与模型分工：** 准入和告警触发由业务代码决定；模型解释工具证据并指出缺失信息。见 [AlertRuleEngine](src/main/java/com/aiops/backend/service/AlertRuleEngine.java) 和 [GraphAgent](agent/ai-service/app/agents/graph_agent.py)。
- **异步调查与授权：** 数据库队列、幂等键和租约；工具调用重新执行权限校验。见 [InvestigationTaskService](src/main/java/com/aiops/backend/service/InvestigationTaskService.java)。

## 快速开始

环境：JDK 17+、Maven Wrapper、Node.js 22.12+（CI 使用 24）、Python 3.10+、MySQL 8。RAG 另需 Qdrant 与本地 Embedding 模型。

```bash
git clone https://github.com/Zhanghaorenen/aiops.git
cd aiops
```

只看界面：在 `frontend` 执行 `npm ci`、`npm run dev`，在登录页选择标明的“演示环境”。示例数据不用于证明监控或训练结果。

完整运行请按 [快速开始](docs/quickstart.md) 配置数据库、JWT 和管理员身份。根目录 [.env.example](.env.example) 集中列出配置；[scripts/run.py](scripts/run.py) 可加载本机 `.env` 分别启动三个开发服务。没有默认管理员密码或模型密钥。

## 仓库结构

```text
src/main/java/com/aiops/backend/   API、业务规则和持久化
src/main/resources/db/migration/  Flyway V1–V13，只追加迁移
src/test/                        Java 测试与隔离配置
agent/aiops_agent/                采集 Agent 与可选训练 SDK
agent/ai-service/app/             FastAPI、LangGraph、Tools、RAG
frontend/src/                    Vue 控制台与流式对话
scripts/                         开发启动与仓库检查
docs/                            当前架构、使用与演示说明
.github/workflows/               Java / Python / Vue 检查
```

## 开发检查

CI 覆盖 Java、Python 和 Vue。物理采样、模型效果与多用户训练需在实际环境中验证；共享模式是资源承诺，不是硬件隔离。

```bash
./mvnw test
cd agent && python -m unittest discover -s tests -v
cd ai-service && python -m unittest discover -s tests -v
# 在 frontend 目录执行
npm run typecheck && npm test && npm run build
```

## 参与与许可

提交问题或改进前请阅读 [CONTRIBUTING](CONTRIBUTING.md)。安全问题按 [SECURITY](SECURITY.md) 处理，避免公开凭据、原始日志和私有文档。

本项目采用 [MIT License](LICENSE)。依赖组件及模型权重遵循其自身许可；仓库不分发模型权重、服务器凭据或真实用户数据。

## Redis 接入

监控短缓存、Agent 用户限流、告警/资源通知实时刷新配置和验证见 [docs/redis.md](docs/redis.md)。
