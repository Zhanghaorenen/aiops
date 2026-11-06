# v0.2 开发与双 GPU 部署指南

## 环境

中心服务建议 JDK 21、Maven、MySQL 8、Python 3.12+（选择与 torch 匹配的版本）、Node 24。Java 构建 release 17；本次实际使用 Java 21 / Python 3.14 / Windows。被管 Linux 双 GPU 机器只需要轻量 Agent、NVIDIA 驱动和 NVML，不部署 LLM、Embedding 或 Qdrant。

## 1. 数据库和身份

备份现有数据库。V1–V7 保留；已有 V8 归属方案继续使用用户确认的 default-cluster，新增 V9–V11 自动创建审计/Token 撤销、告警、Incident、调查任务/报告和 GPU 使用登记表。不要手工改 Flyway checksum。

后端进程设置：AIOPS_DB_URL、AIOPS_DB_USERNAME、AIOPS_DB_PASSWORD、AIOPS_JWT_SECRET（Base64 编码至少 32 字节随机值）。MySQL 建议配置 UTC 会话时区，JDBC 数据读取按 UTC Instant 处理。已有 application-dev.properties 是本机私有文件，不应提交真实凭据。

通过既有 AIOPS_ADMIN_USERNAME/AIOPS_ADMIN_PASSWORD 创建初始化账号。先以 bootstrap 关闭状态启动，核对该账号不可变 userId，再设置：

```text
AIOPS_CLUSTER_BOOTSTRAP_ENABLED=true
AIOPS_CLUSTER_BOOTSTRAP_USER_ID=<人工核实的账号 ID>
```

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=dev,cluster,operations"
```

首次成功后将 bootstrap 标志关闭。初始化标记确保重启不会重置已降级/禁用成员。普通注册不自动入群，更不会自动成为 ADMIN。管理员在“工作空间”页添加已注册用户，分别选择 OPERATOR/VIEWER。角色撤销实时由后端检查；前端菜单只是提示。

ADMIN 管理成员、Agent 和注册令牌；OPERATOR 管理告警/事件；三种有效成员均可查看同一服务器、GPU、历史并发起只读调查。私人知识库权限不变；调查报告可能带有私有知识片段，因此仅发起人能查看报告。

## 2. Qdrant、Embedding、AI Service

设置 AIOPS_QDRANT_KEY，在仓库根目录执行：

```powershell
docker compose -f compose.rag.yaml up -d
```

FastAPI 进程配置：

```text
AIOPS_BACKEND_URL=http://127.0.0.1:8080
AIOPS_LLM_BASE_URL=<实际 OpenAI 兼容接口地址>
AIOPS_LLM_MODEL=<实际模型名>
AIOPS_LLM_API_KEY=<进程环境提供，不提交>
AIOPS_EMBEDDING_PATH=<本地固定版本模型目录>
AIOPS_EMBEDDING_VERSION=<不可变 revision 或校验值>
AIOPS_EMBEDDING_DEVICE=cpu
AIOPS_EMBEDDING_BATCH_SIZE=16
AIOPS_RAG_VECTOR_SCHEMA_VERSION=2
AIOPS_QDRANT_URL=http://127.0.0.1:6333
AIOPS_QDRANT_KEY=<独立 Qdrant 密钥>
AIOPS_RAG_STORAGE_DIR=<中心服务私有绝对目录>
AIOPS_RAG_SERVICE_KEY=<至少32字符独立随机值，Java/FastAPI一致>
AIOPS_AI_SERVICE_KEY=<另一组至少32字符随机值，Java/FastAPI一致>
```

RAG_SERVICE_KEY 用于文档状态回写；AI_SERVICE_KEY 用于后台调查入口，二者不能替代用户 JWT，也不要复用数据库密码。调查任务不把 JWT 存入数据库；Worker 在每次执行时为原发起人签发身份，Java 查询仍检查用户/成员的当前状态。

```powershell
cd agent/ai-service
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

Linux 使用 `.venv/bin/python`。GPU Embedding 如需要设置 cuda:0 并安装对应 CUDA torch；不更换已配置模型。首次加载可能较慢，先在知识库页测试检索。旧平铺 Collection 保留；新 schema 的重新上传/索引步骤见 RAG 实现报告。

Java 的 operations profile 读取 AIOPS_AI_SERVICE_URL（默认 127.0.0.1:8000）、AIOPS_AI_SERVICE_KEY、AIOPS_ALERTS_ENABLED、AIOPS_ALERT_INTERVAL_MS、AIOPS_INVESTIGATION_ENABLED、AIOPS_INVESTIGATION_INTERVAL_MS。默认告警间隔 15 秒、调查轮询 5 秒；后台调查使用独立线程池，不占 HTTP 请求线程或监控调度线程。

## 3. Vue

```powershell
cd frontend
npm ci
npm run dev
```

Vite 将 /api/chat、/api/rag 和 /api/ai/health 发给 FastAPI，其他业务 API 发给 Java。生产反向代理同样配置这些路径，SSE 关闭代理缓冲并设置合理超时；内部 /api/internal/investigate 不需要暴露给浏览器。

页面入口：工作空间、Agent 接入、服务器详情/GPU 与使用登记、运维知识库、告警中心、故障调查、普通 AI 聊天。

## 4. 被管 Linux 双 GPU 机器

管理员在 Agent 接入页创建一次性令牌，复制到目标机器的环境。令牌明文只在创建时显示，列表只给记录和状态，可撤销未使用令牌。

```bash
cd agent
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
export AIOPS_BACKEND_URL='http://中心服务器:8080'
# 用安全的进程环境设置 AIOPS_ENROLLMENT_TOKEN，不将真实值写入仓库。
.venv/bin/python -m aiops_agent
```

确保已安装正确驱动且 NVIDIA NVML 可用。默认 15 秒采样；Agent 保存独立 Credential 到本机状态文件，文件权限应限制为运行用户。Token 绑定集群，Agent 请求不能自行指定 clusterId/nodeId；旧无归属 Token 需要重新签发。重新注册必须使用目标节点同集群的 Token；跨集群不能轮换旧凭据。

停用 Agent 只是撤销平台上报资格，不会终止服务器上的训练任务；重新接入需管理员新签发令牌。旧 POST /api/metrics 和旧 /api/agents/{id}/heartbeat 已返回 410，不能再用用户 JWT 冒充采集端。旧历史表保留，管理/读取限制 ADMIN，不将旧数值 ID 当新 ops_agent ID。

## 5. 告警规则

在“告警中心”选择服务器创建规则。默认持续 60 秒、样本有效期 90 秒、最大样本间隔 45 秒、冷却 300 秒；界面可设置触发/恢复阈值、持续时间、等级，完整参数也可通过 API 设置。

- CPU/MEMORY/DISK：百分比。
- GPU_TEMP_HIGH：摄氏度。
- GPU_MEMORY_HIGH：已用显存/总显存百分比，默认 INFO，单纯占用不等于故障。
- SERVER_OFFLINE：距最后心跳的秒数；再加持续时间后确认，不把缺测 CPU 当 0。
- GPU_COLLECTION_FAILED：失败/不可用为 1，成功或确认无 GPU 为 0；UNKNOWN/未上报不冒充恢复。

持续异常归并为一个活动事件；人工确认进入 ACKNOWLEDGED，真实指标达到恢复阈值才进入 RESOLVED。缺测、旧时间戳不会触发或恢复；禁用规则阻止新告警，但已有事件仍可根据真实恢复样本关闭。活动事件期间不允许更改阈值/指标等判定条件，以免混用旧周期。

## 6. 故障调查与人工处理

OPERATOR/ADMIN 在故障调查页建立 Incident，可关联同服务器多个告警。三种角色都可点击只读 AI 调查：立即返回 PENDING 任务，后台推进 RUNNING，再 SUCCEEDED/FAILED。

状态图：Triage → Evidence → Knowledge → Analysis → Review → Report。最多一次补查，模型分析最多两轮；使用现有 ServerTool/GpuTool/RagTool。报告区分原始证据、根因假设、支持/反对证据、缺失信息、建议步骤和实际检索来源。模型虚构 E 编号会被拒绝。

同一请求使用 Idempotency-Key 重试不会重复创建任务；同一 Incident 只允许一个活动调查。Worker 180 秒 HTTP 超时，FastAPI 150 秒总超时；4 分钟任务租约过期会标记 FAILED，重启不会永远卡住 RUNNING。错误码保留类型，不保存密钥/JWT。AI_SERVICE_NOT_CONFIGURED 表示缺服务密钥，AI_DEPENDENCY_FAILED 表示 AI 端拒绝或未完成，请检查真实模型/平台授权/服务日志。

人工审核报告，可标记有帮助/需改进，事件状态由运维人员维护；告警是否恢复仍由监控事实决定。系统没有 Shell/SSH/kill_process 工具，不会自动执行建议。

## 7. GPU 使用协作

服务器详情“GPU 使用登记”填写真实 GPU 编号、任务名/用途、开始和预计结束、备注。状态 PLANNED→RUNNING→COMPLETED，PLANNED/RUNNING 可取消，终态不回退。普通成员只改自己的登记，ADMIN 可维护其他人的记录。

登记不保证预留 GPU，不与 Linux 账户自动等同，也不证明任务实际运行；统一显示待核实，结合 GPU 页的进程、UUID、显存与原始采样时间核对。过期采样会警告，资源建议不会用旧数据给出当前推荐。

在 AI 聊天中可询问“服务器 ID 为 X，给需要 Y 字节显存的任务推荐一张 GPU”。recommend_gpu_placement 返回当前空闲显存/利用率、候选卡进程、近 15 分钟历史负载及各自时间；历史或进程接口失败会标明证据不完整，推荐不表示资源已被预留。

## 8. 验证命令

```text
mvn test
# agent 目录
python -m unittest discover -s tests -v
# agent/ai-service 目录
python -m compileall -q app
python -m unittest discover -s tests -v
python evaluation/evaluate.py --dry-run
# frontend 目录
npm run typecheck
npm test
npm run build
```

30 条真实服务评测的环境与运行命令见 agent/ai-service/evaluation/README.md。自动化本地模式/Mock 测试不等同于真实双 GPU 或语义质量验收。外部 Qdrant 集成和 CI 提供独立可选流程。本次未启动生产服务、未执行真实库迁移，也没有真实设备性能数据。

数据保留尚无自动清理任务；请先监测容量与备份，后续再按实际保留周期规划，当前不会自动删除已有服务器/GPU/知识库数据。
