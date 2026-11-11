# v0.2 开发基线（2026-11-05）
已读取实际工作区、最近提交及阶段 0 审计。现有未提交的 V8 集群模型、bootstrap 和报告保留。最新用户指令要求连续实施六个阶段，作为本次阶段推进授权。

确认问题：统一 ROLE_USER 无业务 RBAC；Token 可由普通用户签发；旧 agents/metrics 写接口仍开放；GPUManager 将数组下标当 gpuIndex、整机进程重复请求；无采样新鲜度；无告警/Incident/调查任务/使用登记/CI。现有 RAG、GraphAgent、SSE、CPU/GPU 上报保留。

使用现有 default-cluster 作为当前共享工作空间，避免再建第二套平台角色/服务器归属。知识库保持私有。未接入真实 GPU、生产 MySQL、LLM 或本地模型，不声称真实验收完成。

## 审计范围与处理结果

| 优先级 | 审计时的风险 / 缺口 | 涉及位置 | 本轮处理 |
|---|---|---|---|
| P0 | ROLE_USER 只识别登录身份，不能区分三种运维权限 | JwtAuthenticationFilter、SecurityConfig、各业务 Service | 复用 V8 成员数据统一核权，uid 与账号匹配，注册不自动提权 |
| P0 | 普通用户可签发注册令牌，旧 JWT 采集入口仍可写 | AgentAccessService、AgentController、MetricController | ADMIN 签发/撤销、Agent 停用；旧写入口 410，真实 Agent Credential 独立 |
| P0 | 资源查询未统一按共享工作空间范围过滤 | Server/GPU/指标/Dashboard | SQL 范围过滤、详情核权；三角色共享授权节点，跨集群/未加入拒绝 |
| P0 | GPU 数组位置不等于真实编号；整机进程被重复查询 | gpu_manager.py、gpu_tool.py、GpuManagerController | 真实 gpuIndices/UUID，单次整机进程查询，可空指标和采样新鲜度 |
| P1 | 无确定性告警规则与事件状态 | 既有 metric_host_sample、metric_gpu_snapshot/sample | 新增 V10，复用原指标表；持续/缺测/恢复/冷却/去重与规则行锁 |
| P1 | 无持久化调查任务/报告，LLM 不应阻塞 Java 请求 | GraphAgent、Java 运维接口 | 保留普通 GraphAgent；另加 IncidentGraph、V11、数据库任务租约与独立 Worker |
| P1 | 私有知识片段可能经共享 Incident 报告泄露 | 原有私有知识库、IncidentService | 报告只允许原发起人查看，且仍需有效服务器权限 |
| P1 | 无轻量 GPU 多用户使用登记 | ServerDetailView、GpuMonitor | 增加登记与合法状态流转，明确不预留卡、人工状态待核实 |
| P2 | 没有可重复的真实 Agent/RAG 评测记录 | evaluation、CI、部署文档 | 30 条问题与真实 SSE 评测脚本，CI/可选 Qdrant 流程；真实结果保留未执行 |

审计时 Java 基线 88 项通过；最终扩展至 108 项。其他当前测试结果与完整文件清单见实现报告，不能用当前单元测试倒推真实设备验收。

## 复用与保留

- 不新建监控存储，继续使用 Agent 上报 → Spring Boot → 既有 CPU/内存/磁盘/GPU 指标表 → Vue/只读 Tools 的链路。
- 保留现有 LangChain 官方 Embedding、分块、Qdrant、检索和普通 GraphAgent/SSE；新增调查工作流复用 ServerTool、GpuTool、RagTool。
- V8 为开始工作时已有未提交方案，用户此前确认现有节点归入默认共享集群。本轮不改 V1–V8 历史文件，新增 V9–V11；真实库升级前仍需备份和副本演练。
- 无 GPU 虚拟化、SSH/Shell/杀进程、自动训练任务调度；只实现计划明确要求的使用协作和只读资源建议。

## 尚需环境核验

1. 实际 MySQL 连接审计曾返回认证失败，因此未执行真实库升级；H2 迁移通过不能代替 MySQL 验收。
2. 未提供真实双 GPU 服务器访问环境，UUID、进程与 nvidia-smi 的对照仍待用户执行。
3. 尚无用户本地 Embedding 路径/revision、可用外部 Qdrant 和真实 LLM，真实 PDF 索引和联合诊断未验收。
4. 普通聊天仍未新增服务端会话持久化；监控历史也未自动清理，当前保留已有数据。
5. 新增 GitHub Actions 未推送或远程触发。实施顺序、每阶段代码状态和剩余验收详见开发计划。
