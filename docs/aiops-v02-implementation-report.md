# v0.2 实施与验证报告（2026-11-05）

## 交付状态

六阶段的代码、接口、前端、测试和工程文件已增量接入。**真实双 GPU、用户实际 Embedding、外部 Qdrant/LLM 和生产 MySQL 副本升级尚未验收**，不能称为全部真实阶段验收通过。用户已要求连续实施，原阶段 0 审查暂停由本次明确指令继续；阶段记录保留在开发计划末尾。

开始时已有未提交的 V8 集群模型、bootstrap、测试和报告。本次保留这些工作，复用 default-cluster 作为当前共享工作空间；没有再添加一套平台角色表，没有删除历史服务器、GPU、指标或知识库记录，没有提交或推送 Git。

## 六阶段实现

| 阶段 | 已实现与验证 | 环境验收 |
|---|---|---|
| 1 权限/GPU | 统一权限、成员管理、审计、Token 签发/撤销、Agent 停用、数据查询授权；稀疏编号、单次进程请求、缺测/新鲜度 | 三角色/跨集群自动化通过；真实双 GPU 待核对 |
| 2 RAG 联调支持 | 保留官方 RAG/普通 GraphAgent；30 条问题、参考来源与真实请求评测脚本；RAG/工具/SSE 回归 | 用户模型/真实 PDF/外部 Qdrant/生成模型尚未提供，评测 NOT_EXECUTED |
| 3 告警 | CPU/MEMORY/DISK/GPU_TEMP/GPU_MEMORY/OFFLINE/COLLECTION_FAILED；持续、恢复、冷却、去重与 UTC；管理页 | H2/并发/规则测试通过；真实阈值待验证 |
| 4 故障调查 | Incident/关联告警/历史、异步任务/租约/报告/反馈、独立线程池、IncidentGraph、私有来源报告隔离 | Java HTTP 运输/失败状态与 Python 图回归通过；真实联合诊断待验收 |
| 5 使用协作 | GPU 使用登记、状态与所有权、人工待核实提示、复用 GPU 监控 | 自动化通过；实验室实际操作与进程对照待验收 |
| 6 工程化 | CI、可选 Qdrant 流程、配置/部署/源码/API/数据库/演示文档 | 本地检查通过；GitHub CI 未触发，不编造性能与截图 |

## 权限与数据边界

ClusterPermissionService 按当前 ACTIVE 用户、ACTIVE 集群/成员和角色授权；JWT 角色声明/前端状态不作为授权依据，uid 与账号也会核对。角色/禁用状态每次查数据库。Node 的历史 owner_user_id 保留但不作为隔离依据；知识库 owner_user_id 继续生效。

ADMIN 管理成员、Agent 和注册令牌；OPERATOR 可配置/确认告警及处理事件；三种成员均可读取授权服务器/GPU/历史并发起只读 AI。未加入或禁用成员不能访问工作空间资源。成员变更锁工作空间，防止并发降级最后有效 ADMIN。

服务器列表由 SQL 按可读 cluster_id 过滤，详情/GPU/指标在 Service 侧核权；Dashboard 的 Agent 计数也按授权 Node 范围。当前工作空间页面集中管理默认集群，不开发复杂多集群导航。

Token 绑定集群和真实签发人，明文只创建时返回；列表无 hash/原文，支持撤销。Agent 只能写认证绑定 Node，不能在请求体选 clusterId。跨集群重注册会回滚。停用后 Credential 不能继续心跳/上报，恢复接入需新 Token。旧无归属 Token 拒绝注册，记录保留。

旧 agents/metrics 表和历史读取保留给 ADMIN；旧 POST /api/metrics 与旧 /api/agents/{id}/heartbeat 返回 410，不能用 USER JWT 冒充采集端。受控管理与内部维护仍使用权限校验；新采集协议与 GPU JSON 没有更换。

RAG 不改成共享知识库。诊断报告可含个人 KB 原文，查询/审核必须是原发起人且仍有服务器权限，防止共享 Incident 间接泄露私人资料。

## 告警与调查实现细节

AlertRuleEngine 是纯确定性规则，基于原始采集时间，去重时间戳、检查新鲜度/最大间隔；缺测和同时间冲突不能证明持续异常或恢复。默认持续规则需要多点覆盖持续时长，显式 duration=0 才支持即时规则。恢复阈值形成滞回，确认不等于恢复。

AlertEvaluationService 锁规则行并使用 active_key 唯一约束；多实例/并发不会创建重复活动事件。恢复清空 active_key，冷却期内不开始新周期。已有活动告警期间不混改判定条件；禁用阻止新周期，原事件仍可根据真实样本恢复。查询窗口有 40000 行预算，面向当前一台双 GPU、至少 5 秒采样的场景。

UNKNOWN/UNREPORTED 不当作 0；SERVER_OFFLINE 基于最后心跳时长，GPU_COLLECTION_FAILED 是另一个规则。JDBC DATETIME 按 UTC 墙钟解释，API 响应对 Timestamp/LocalDateTime 明确转为 UTC ISO 字符串，避免本机时区造成误判/错时。

IncidentGraph：Triage→Evidence→Knowledge→Analysis→Review→Report。固定目标服务器与授权用户，复用既有 ServerTool/GpuTool/RagTool；读取关联告警、原始时间和对应 GPU 历史，最多一次补查/两轮模型分析。真实证据/采样时间、模型假设、支持/反对 E 编号、缺失信息、步骤和实际引用分开。虚构 E 编号会失败，引用由实际检索返回值构建。

后台任务存 requested_by、幂等 key、状态、时间、租约和错误码，不存 JWT。Worker 执行时重新签发原请求人的身份，并在调用/完成前复查权限；独立服务密钥与用户 JWT 必须同时有效。每 Incident 一个活动任务；请求重试不重复创建。Java 180 秒请求、FastAPI 150 秒总超时、4 分钟租约；重启失效任务标记 FAILED，不永远停在 RUNNING。模型调用用独立调度线程池，不阻塞监控。

报告存 MEDIUMTEXT，响应有大小预算；事件/报告仅保存受控证据和结果，不包含明文 API Key/JWT。系统没有 Shell、SSH、kill_process 或实际 GPU 预留工具。

GPU 使用登记 PLANNED→RUNNING→COMPLETED，PLANNED/RUNNING 可 CANCELLED，终态不回退；普通用户只维护自己的登记，ADMIN 可维护他人。人工状态不对应 OS 任务身份，统一显示待核实，不能据此判断 GPU 实际运行状态。

recommend_gpu_placement 继续按新鲜快照中的显存和利用率排序，补充候选 GPU 的实际进程及近 15 分钟利用率统计、有效样本数量和原始时间。整机进程只请求一次，历史补查并发且各有 10 秒超时；缺失单独标记，401/403 继续拒绝，不降级成“无进程”。资源建议不预留卡，不把历史平均负载当成当前值。过期快照也不会继续给出负载迁移建议。

Token 撤销与注册锁同一条记录，并发只允许一方成功。心跳/上报在事务中重新锁定 Agent，离线扫描也重新核对候选记录，避免旧实体覆盖管理员的停用状态；对应两类竞态已加入实际双线程回归测试。

## 新增 API

| 路径 | 功能 / 权限 |
|---|---|
| GET /api/workspace | 当前成员角色 |
| GET/POST /api/workspace/members | 列出/添加已注册成员，ADMIN |
| PATCH /api/workspace/members/{userId}/role 或 /status | 修改角色/状态，ADMIN，保护最后管理员 |
| DELETE /api/workspace/members/{userId} | 软移除，ADMIN |
| GET /api/workspace/audit | 管理操作审计，ADMIN |
| POST/GET /api/agent/enrollment-tokens | 签发/仅列状态，ADMIN |
| DELETE /api/agent/enrollment-tokens/{id} | 撤销未消费 Token，ADMIN |
| GET /api/managed-agents | 真实采集 Agent 管理列表，ADMIN |
| POST /api/managed-agents/{id}/disable | 停用平台凭据，ADMIN |
| GET/POST /api/alert-rules；PUT /api/alert-rules/{id} | 读规则/配置（写需 ADMIN/OPERATOR） |
| GET /api/alerts；GET /api/alerts/{id} | 读取授权告警 |
| POST /api/alerts/{id}/acknowledge | 人工确认，ADMIN/OPERATOR |
| GET/POST /api/incidents；GET /api/incidents/{id} | 列表/创建（写需 ADMIN/OPERATOR）/详情 |
| PATCH /api/incidents/{id}/status | 人工维护事件状态 |
| POST /api/incidents/{id}/investigations | 只读调查；Idempotency-Key 必填；返回任务记录 |
| POST /api/diagnosis-reports/{id}/review | 原发起人且有运维权限时审核 |
| POST FastAPI /api/internal/investigate | Java Worker 专用；独立服务密钥 + 原用户 JWT |
| GET/POST /api/servers/{id}/gpu-usage | 读取/登记 GPU 使用 |
| PATCH /api/servers/{id}/gpu-usage/{taskId}/status | 本人/管理员维护状态 |

新页面对 JDBC 列名做大小写兼容，生产 API 使用现有 MySQL 的字段命名。HTTP 和 SSE 的身份失效都触发前端清除登录态；服务端仍负责权限。

## 数据库与配置

保留现有 V1–V8；新增 V9（操作审计、Token 撤销）、V10（规则/事件）、V11（Incident、告警关联、历史、调查任务、诊断报告、GPU 使用登记）。迁移测试在独立 H2 中执行，没有对实际 MySQL 执行升级。

新增 application-operations.yml：AIOPS_AI_SERVICE_URL、AIOPS_AI_SERVICE_KEY、AIOPS_ALERTS_ENABLED / ALERT_INTERVAL_MS、AIOPS_INVESTIGATION_ENABLED / INVESTIGATION_INTERVAL_MS。FastAPI 增加 AIOPS_AI_SERVICE_KEY。保持 RAG 服务密钥、本地模型和其他配置。密钥不填默认值、不写仓库。

操作步骤见 [部署指南](aiops-v02-deployment.md)；初始化 ADMIN 需显式确认 userId，不能给公开首个注册用户自动授权。

## 已执行测试

- Java `mvn -s data/local-run/mybatis-maven-settings.xml test -B`：**108 项通过，0 失败、0 错误、0 跳过**，覆盖原回归和角色、GPU、告警、任务、报告隔离与并发。数据库为独立 H2 的 MySQL 兼容模式。
- AI Service `python -m compileall -q app`、`python -m unittest discover -s tests -v`：**53 项，52 通过，1 外部 Qdrant 测试跳过**。本地 Qdrant 和离线微型 Embedding 测试实际运行；LLM/设备上下文使用明确的测试替身。
- 采集 Agent：**8 项通过**。
- Vue `npm run typecheck`、`npm test`：**18 项通过**；生产构建通过。主包仍约 1.5 MB，有原有体积警告，尚未专项优化图表分包。
- `pip check`、评测脚本 `--dry-run`、`git diff --check`：通过。dry-run 只确认 30 条问题，不是实测准确率。

Windows 沙箱的 socketpair/本地回环限制导致初次前端代理、asyncio/Java 工具无法完成；允许本地通信后正常执行。已修复实际测试发现的 UTC 偏移、GPU 可空字段误拒绝、H2 保留字、UNKNOWN 数值拆箱和报告私有来源泄露风险；没有削弱生产权限来迁就旧测试。旧测试数据准备现要求真实管理员成员身份。

## 未执行与限制

未启动真实生产服务或修改生产数据；未提供实际模型/双 GPU/外部 Qdrant/真实 PDF。真实评测结果保持 NOT_EXECUTED，无虚构延迟、召回率、报告质量、截图或生产可靠性声明。CI workflow 已添加但未推送/触发。MySQL 副本升级与 UTC 会话设置仍需按指南验证。

没有实现 GPU 硬隔离、算力配额、自动执行命令或杀进程。普通聊天仍是原有对话历史方式，未新增服务端会话持久化。没有自动监控数据清理；保留已有数据，后续需按容量规划保留周期。调查默认单实例小队列，有限租约支持失败恢复，不是大型分布式调度平台。

## 初学者阅读顺序

1. ClusterPermissionService / WorkspaceMemberService：身份来自哪里、角色映射、事务行锁、最后管理员与审计。
2. AgentAccessService：签发人/集群 Token、Node 绑定、独立 Credential、可空 GPU 指标。
3. GPUManager / GpuTool：真实 gpuIndices、一次进程调用、原始时间与 stale 状态。
4. AlertRuleEngine → AlertEvaluationService → Scheduler：纯规则判定与持久化去重、时间窗口、恢复/冷却。
5. IncidentService → InvestigationTaskService → Worker：用户请求返回任务，数据库幂等与租约，内部服务调用，报告权限。
6. incident_graph.py：固定目标只读取证、RagTool、受约束 JSON 分析、有限补查与来源；api/investigation.py 验证两种凭据。
7. GpuUsageService：人工任务状态与监控事实为什么不能混用。
8. Vue Workspace/Alerts/Incidents/GpuUsage/AgentAdministration：角色提示、表单、异步任务轮询、转义渲染和人工审核。
9. V02WorkflowTests / ConcurrentOperationsTests / test_v02.py：理解测试夹具、模拟时间和真实服务验收之间的区别。

### 关键函数的输入、输出与调用关系

| 文件 / 类 / 函数 | 输入 → 输出 | 向下调用与知识点 |
|---|---|---|
| WorkspaceController → WorkspaceMemberService.update | userId、role/status → 成员更新或 403/409 | ClusterRepository.lockDefault → ClusterPermissionService.require → 更新/审计；Spring 事务与行锁 |
| ClusterPermissionService.requireNode | nodeId、业务权限 → NodeEntity 或 401/403/404 | NodeRepository、用户/成员/集群 Repository；认证身份与业务授权分离 |
| AgentAccessService.enroll | 一次性 Token、机器元信息 → nodeId、agentId、独立 Credential | Token 行锁 → 校验归属 → Node/OpsAgentRepository；事务失败整体回滚 |
| AgentAccessService.recordMetric | 认证绑定的 agentId、采样 DTO → HostMetricSampleEntity | 写既有 metric_host_sample/GPU 表；NVML 缺测保留 null，USER 不能冒充 AGENT |
| GPUManager._read_gpus / get_gpu_processes | server_id、可选 gpu_id → GPU/脱敏进程列表 | PlatformClient → Java GPU API → GPU Repository；并行数组按 gpuIndices 对齐 |
| GPUManager.recommend_gpu_placement | server_id、每卡显存需求、卡数 → 候选卡与证据 | 快照排序 → 一次整机进程查询 + 每候选卡历史查询；asyncio 并发、超时与授权错误传播 |
| AlertRuleEngine.evaluate | 采样序列、now、阈值和窗口参数 → Decision | 不访问数据库；Java record、排序去重、有限数值和持续时间判断 |
| AlertEvaluationService.evaluate | ruleId、now → 规则对应事件写入/恢复 | 规则行锁 → 既有指标表 → 引擎 → ops_alert_event；唯一键与事务保证去重 |
| IncidentService.investigate | incidentId、幂等请求 key → InvestigationRun | 检查当前用户和活动任务 → 写 PENDING；HTTP 请求不等待 LLM |
| InvestigationWorker.poll → InvestigationTaskService | 无外部输入 → 一项任务推进或空闲 | 领取/租约 → 临时用户身份 → FastAPI HTTP → 验证/保存报告；后台线程与请求线程分离 |
| api/investigation.py: investigate | 请求模型、用户 JWT、独立服务密钥 → 报告 JSON 或 HTTP 错误 | 平台核权 → 创建 IncidentGraph → 150 秒总超时；FastAPI Depends、Pydantic、异步资源清理 |
| IncidentGraph.run / collect / analyze | 事件、固定 server_id、问题、真实告警 → report | ServerTool/GpuTool → PlatformClient；RagTool → RetrievalService；StateGraph 节点返回局部状态，证据审核最多补查一次 |
| GpuUsageService.create / status | serverId、登记 DTO 或目标状态 → 保存/拒绝 | 真实 GPU 编号校验 → 当前用户绑定 → 合法状态迁移；人工登记与 OS 事实分离 |
| Vue Workspace/Alerts/Incidents/GpuUsage | 表单或定时刷新 → 展示服务端返回状态 | api.ts → Java/FastAPI；Pinia 角色只控制交互提示，后端始终重新核权 |

建议跟踪一次完整调用：告警页创建规则 → 定时器 evaluate → 告警事件 → 故障页创建 Incident → investigate 返回任务 → Worker 调 FastAPI → 图中读取监控/检索资料 → 报告入库 → 页面轮询显示。普通聊天仍走原 GraphAgent 和 SSE，不经过数据库调查队列。

测试替身的作用是固定模型答案、监控时间和错误来验证代码分支；H2 能验证事务和数据约束，但不能代替 MySQL 驱动、真实 NVML 或真实模型的最终验收。

## 待审查文件清单

以下为当前整个工作区的变更（包含此前未提交的阶段 1 文件），不代表全部都由本轮首次创建；无删除文件。详细职责由文件名及上文模块映射说明。

- `未跟踪/新增` `.github/workflows/ci.yml`
- `未跟踪/新增` `.github/workflows/qdrant-integration.yml`
- `修改` `.gitignore`
- `修改` `README.md`
- `修改` `agent/ai-service/.env.rag.example`
- `未跟踪/新增` `agent/ai-service/app/agents/incident_graph.py`
- `未跟踪/新增` `agent/ai-service/app/api/investigation.py`
- `修改` `agent/ai-service/app/config.py`
- `修改` `agent/ai-service/app/main.py`
- `修改` `agent/ai-service/app/tools/gpu_manager.py`
- `修改` `agent/ai-service/app/tools/gpu_tool.py`
- `修改` `agent/ai-service/app/tools/server_manager.py`
- `未跟踪/新增` `agent/ai-service/evaluation/README.md`
- `未跟踪/新增` `agent/ai-service/evaluation/cases.json`
- `未跟踪/新增` `agent/ai-service/evaluation/evaluate.py`
- `修改` `agent/ai-service/tests/test_chat.py`
- `未跟踪/新增` `agent/ai-service/tests/test_v02.py`
- `未跟踪/新增` `docs/aiops-v02-audit.md`
- `未跟踪/新增` `docs/aiops-v02-deployment.md`
- `未跟踪/新增` `docs/aiops-v02-development-plan.md`
- `未跟踪/新增` `docs/aiops-v02-implementation-report.md`
- `未跟踪/新增` `docs/project-demo.md`
- `未跟踪/新增` `docs/shared-cluster-rbac-audit.md`
- `未跟踪/新增` `docs/shared-cluster-rbac-implementation-report.md`
- `修改` `frontend/src/App.vue`
- `修改` `frontend/src/api.ts`
- `未跟踪/新增` `frontend/src/components/AgentAdministration.vue`
- `修改` `frontend/src/components/GpuMonitor.vue`
- `未跟踪/新增` `frontend/src/components/GpuUsage.vue`
- `修改` `frontend/src/router/index.ts`
- `修改` `frontend/src/stores/workspace.ts`
- `修改` `frontend/src/views/AccessView.vue`
- `未跟踪/新增` `frontend/src/views/AlertsView.vue`
- `未跟踪/新增` `frontend/src/views/IncidentsView.vue`
- `修改` `frontend/src/views/ServerDetailView.vue`
- `未跟踪/新增` `frontend/src/views/WorkspaceView.vue`
- `修改` `frontend/tests/chat.test.mjs`
- `修改` `src/main/java/com/aiops/backend/Entity/AgentEnrollTokenEntity.java`
- `未跟踪/新增` `src/main/java/com/aiops/backend/Entity/ClusterEntity.java`
- `未跟踪/新增` `src/main/java/com/aiops/backend/Entity/ClusterMemberEntity.java`
- `修改` `src/main/java/com/aiops/backend/Entity/NodeEntity.java`
- `修改` `src/main/java/com/aiops/backend/config/DataInitializer.java`
- `未跟踪/新增` `src/main/java/com/aiops/backend/config/TaskSchedulerConfig.java`
- `修改` `src/main/java/com/aiops/backend/controller/AgentController.java`
- `未跟踪/新增` `src/main/java/com/aiops/backend/controller/AgentManagementController.java`
- `未跟踪/新增` `src/main/java/com/aiops/backend/controller/AlertController.java`
- `修改` `src/main/java/com/aiops/backend/controller/GpuManagerController.java`
- `未跟踪/新增` `src/main/java/com/aiops/backend/controller/GpuUsageController.java`
- `未跟踪/新增` `src/main/java/com/aiops/backend/controller/IncidentController.java`
- `修改` `src/main/java/com/aiops/backend/controller/MetricController.java`
- `未跟踪/新增` `src/main/java/com/aiops/backend/controller/OperationsResponseAdvice.java`
- `修改` `src/main/java/com/aiops/backend/controller/ServerMetricController.java`
- `未跟踪/新增` `src/main/java/com/aiops/backend/controller/WorkspaceController.java`
- `修改` `src/main/java/com/aiops/backend/mapper/AgentEnrollTokenMapper.java`
- `修改` `src/main/java/com/aiops/backend/repository/AgentEnrollTokenRepository.java`
- `未跟踪/新增` `src/main/java/com/aiops/backend/mapper/ClusterMapper.java`
- `未跟踪/新增` `src/main/java/com/aiops/backend/mapper/ClusterMemberMapper.java`
- `未跟踪/新增` `src/main/java/com/aiops/backend/repository/ClusterMemberRepository.java`
- `未跟踪/新增` `src/main/java/com/aiops/backend/repository/ClusterRepository.java`
- `修改` `src/main/java/com/aiops/backend/repository/NodeRepository.java`
- `修改` `src/main/java/com/aiops/backend/repository/OpsAgentRepository.java`
- `未跟踪/新增` `src/main/java/com/aiops/backend/security/ClusterPermission.java`
- `修改` `src/main/java/com/aiops/backend/security/JwtAuthenticationFilter.java`
- `修改` `src/main/java/com/aiops/backend/security/SecurityConfig.java`
- `修改` `src/main/java/com/aiops/backend/service/AgentAccessService.java`
- `未跟踪/新增` `src/main/java/com/aiops/backend/service/AgentManagementService.java`
- `修改` `src/main/java/com/aiops/backend/service/AgentService.java`
- `未跟踪/新增` `src/main/java/com/aiops/backend/service/AlertEvaluationScheduler.java`
- `未跟踪/新增` `src/main/java/com/aiops/backend/service/AlertEvaluationService.java`
- `未跟踪/新增` `src/main/java/com/aiops/backend/service/AlertRuleEngine.java`
- `未跟踪/新增` `src/main/java/com/aiops/backend/service/AlertRuleService.java`
- `未跟踪/新增` `src/main/java/com/aiops/backend/service/ClusterBootstrapService.java`
- `未跟踪/新增` `src/main/java/com/aiops/backend/service/ClusterPermissionService.java`
- `修改` `src/main/java/com/aiops/backend/service/DashboardService.java`
- `未跟踪/新增` `src/main/java/com/aiops/backend/service/GpuUsageService.java`
- `未跟踪/新增` `src/main/java/com/aiops/backend/service/IncidentService.java`
- `未跟踪/新增` `src/main/java/com/aiops/backend/service/InvestigationTaskService.java`
- `未跟踪/新增` `src/main/java/com/aiops/backend/service/InvestigationWorker.java`
- `修改` `src/main/java/com/aiops/backend/service/MetricService.java`
- `未跟踪/新增` `src/main/java/com/aiops/backend/service/WorkspaceMemberService.java`
- `修改` `src/main/java/com/aiops/backend/service/impl/AgentServiceImpl.java`
- `修改` `src/main/java/com/aiops/backend/service/impl/GpuManagerServiceImpl.java`
- `修改` `src/main/java/com/aiops/backend/service/impl/MetricServiceImpl.java`
- `修改` `src/main/java/com/aiops/backend/service/impl/ServerServiceImpl.java`
- `未跟踪/新增` `src/main/resources/application-cluster.yml`
- `未跟踪/新增` `src/main/resources/application-operations.yml`
- `未跟踪/新增` `src/main/resources/db/migration/V10__create_alert_rules_and_events.sql`
- `未跟踪/新增` `src/main/resources/db/migration/V11__incidents_and_gpu_usage.sql`
- `未跟踪/新增` `src/main/resources/db/migration/V8__create_shared_cluster_tables.sql`
- `未跟踪/新增` `src/main/resources/db/migration/V9__workspace_audit_and_token_revocation.sql`
- `未跟踪/新增` `src/test/java/com/aiops/backend/AlertRuleEngineTests.java`
- `修改` `src/test/java/com/aiops/backend/AuthFlowTests.java`
- `未跟踪/新增` `src/test/java/com/aiops/backend/ClusterTestSupport.java`
- `未跟踪/新增` `src/test/java/com/aiops/backend/ConcurrentOperationsTests.java`
- `修改` `src/test/java/com/aiops/backend/MetricControllerTests.java`
- `修改` `src/test/java/com/aiops/backend/MonitoringAccessTests.java`
- `未跟踪/新增` `src/test/java/com/aiops/backend/SharedClusterMigrationTests.java`
- `未跟踪/新增` `src/test/java/com/aiops/backend/SharedClusterModelTests.java`
- `未跟踪/新增` `src/test/java/com/aiops/backend/V02WorkflowTests.java`
- `修改` `src/test/java/com/aiops/backend/persistence/AgentAccessPersistenceTests.java`
- `修改` `src/test/java/com/aiops/backend/persistence/MetricPersistenceTests.java`
- `修改` `src/test/java/com/aiops/backend/service/AgentServiceImplTests.java`
- `修改` `src/test/java/com/aiops/backend/service/DashboardServiceTests.java`
- `修改` `src/test/java/com/aiops/backend/service/GpuManagerServiceTests.java`
- `修改` `src/test/java/com/aiops/backend/service/MetricServiceTests.java`
- `修改` `src/test/resources/application.properties`
