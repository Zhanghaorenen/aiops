# AIOps 智能运维平台：下一阶段功能完善与工程化开发计划

## 1. 你的角色

你是一名具有实际工程经验的高级 AI 应用开发工程师、Java 后端工程师和系统架构师。

请对当前 AIOps 仓库进行代码审计，按照本提示词分阶段实现尚未完成的功能，修复现有设计问题，并完成必要的自动化测试。

不要将任务理解为重新开发一套项目。

本次工作应基于现有代码进行增量完善，优先复用已经实现的功能。

## 2. 项目与真实应用场景

项目仓库：

https://github.com/Zhanghaorenen/aiops

当前技术栈：

- Java：Spring Boot、Spring Security、MyBatis-Plus、MySQL、Flyway。
- Python：FastAPI、psutil、pynvml。
- AI：LangChain 1.x、LangGraph、Tool Calling、RAG。
- 向量数据库：Qdrant。
- Embedding：HuggingFace Embeddings。
- 前端：Vue 3、TypeScript、Pinia、Ant Design Vue。
- 部署：中心业务服务 + 中心 AI Service + 被管理服务器上的轻量 Agent。

### 2.1 明确项目定位

项目定位为：

**面向实验室共享 GPU 服务器的智能运维管理平台。**

实际环境：

- 当前只有一台主要服务器。
- 服务器配置两张 GPU。
- 多名实验室成员共同使用服务器。
- 用户主要通过 Linux 账户和 SSH 使用计算资源。
- AIOps 平台负责统一监控、资源查看、告警和 AI 辅助诊断。

当前不实现：

- GPU 虚拟化。
- GPU 显存硬隔离。
- GPU 算力配额。
- Kubernetes 集群调度。
- 用户独占算力分配。
- 自动执行 Shell 或 SSH。
- 自动终止其他用户的进程。

系统应当支持未来扩展多服务器，但当前必须以一台服务器、两张 GPU 的场景完成真实验证。

不要为了扩展性预先实现复杂的多集群管理体系。

### 2.2 本次开发最终目标

完成以下闭环：

服务器指标采集 → 监控分析 → 异常检测 → 告警事件 → LangGraph 调查 → RAG 检索 → 诊断报告 → 人工处理。

同时保留普通 AI 对话和 GPU 查询功能。

---

# 3. 全局执行规则

## 3.1 先审计，不要直接修改

开始执行：

```bash
git status
git branch --show-current
git log -5 --oneline
```

随后扫描实际项目目录。

重点检查：

- Java Controller / Service / Mapper / Repository。
- 数据库 Migration。
- Python 采集 Agent。
- FastAPI AI Service。
- LangGraph。
- RAG。
- Vue 页面。
- 测试代码。
- 运行和部署文档。

不要直接按照本文给出的示例文件名创建重复代码。

如果当前仓库已经实现某项功能，则检查其正确性、完成测试后标记为已完成，不要重复实现。

## 3.2 不破坏已有工作

禁止：

- 未经允许覆盖用户未提交修改。
- 使用 git reset --hard。
- 使用 git clean 清理工作目录。
- 删除现有数据库数据。
- 重写已经执行的 Flyway Migration。
- 删除旧版 Qdrant Collection。
- 自动推送代码。
- 将真实 API Key、JWT、密码或 Agent Credential 写入仓库。

数据库改动必须使用新的 Flyway Migration。

涉及历史数据迁移时，先检查现有数据及其关系。

## 3.3 阶段推进原则

严格按照阶段执行。

每阶段完成：

1. 修改代码。
2. 执行该阶段测试。
3. 检查回归影响。
4. 更新阶段记录。
5. 输出本阶段结果。

阶段 0 完成后先等待人工审查，再进行数据库结构和权限调整。

对于不涉及敏感数据迁移的普通实现细节，可在已批准阶段内自主完成。

## 3.4 代码可读性、中文注释与工程风格

**以方便后续阅读、调试和维护为总原则：在合适的地方添加适量的中文注释，代码保持自然、直接，遵循项目已有的工程风格。**

1. 注释以中文为主，重点解释业务意图、设计原因和容易误解的约束。例如权限边界、状态流转、事务与并发控制、GPU index 映射、数据新鲜度、索引版本和失败补偿。
2. 对职责不直观的类、关键业务方法和复杂分支，简要说明其作用；输入输出、单位、时间语义或副作用不明显时，再补充必要说明。类名、函数名和技术术语沿用准确的英文表达。
3. 不逐行翻译代码，不给简单 getter/setter 或显而易见的操作堆砌注释；避免空泛口号、机械编号和大段模板化说明。优先通过清楚的命名和简洁的流程表达意图。
4. 写法贴近实际工程维护，避免为简单功能增加不必要的抽象、包装类和重复代码。不为展示技术而引入新框架，不把原本清楚的逻辑拆得过碎。
5. 修改代码时同步检查相关注释，确保注释与实际行为一致。注释说明事实与限制，不用注释代替实现，也不把未完成或未验证的能力写成已经可用。
6. 此原则适用于后续新增或实质修改的 Java、Python、Vue/TypeScript、SQL 和配置代码。优先说明本次改动涉及的关键逻辑，不为了补注释而批量改写无关文件。


---

# 4. 阶段 0：全面审计和开发基线

本阶段只读，不修改核心业务代码。

## 4.1 需要核实的现状

### Java 后端

检查：

- 账号与角色。
- Agent 注册令牌。
- Agent 注册和心跳。
- CPU、内存、磁盘采集。
- GPU 快照和历史查询。
- 数据库归属字段。
- Dashboard 统计。
- 知识库权限。

### Python AI Service

检查：

- PlatformClient。
- ServerManager。
- GPUManager。
- ServerTool。
- GpuTool。
- RagTool。
- GraphAgent。
- EmbeddingService。
- VectorStore。
- RetrievalService。
- IngestionService。

### 前端

检查：

- 登录和注册。
- 服务器列表和详情。
- GPU 监控。
- AI 对话和 SSE。
- 知识库管理。
- Agent 接入。

### 测试

确认：

- Java 测试是否通过。
- Python 测试是否通过。
- 前端类型检查是否通过。
- 前端构建是否通过。
- 真实外部 Qdrant 测试是否执行。
- 是否配置了真实本地 Embedding。
- 是否有真实 LLM + Agent 的端到端测试。

## 4.2 重点排查的问题

优先检查以下已知风险：

1. 普通 USER 是否能够签发 Agent 注册令牌。
2. 是否存在独立的管理员角色。
3. GPU 索引是否直接使用 Java 返回的 gpuIndices。
4. GPU 进程查询是否重复请求同一个接口。
5. RAG 是否完成真实环境联调。
6. GraphAgent 是否具有服务端会话持久化。
7. 是否存在告警规则和故障事件表。
8. 当前监控数据是否设置了过期判断和清理策略。
9. 是否存在可导致模型工具调用绕过权限的接口。
10. 当前测试是否过度依赖 Mock。

## 4.3 输出文件

生成：

`docs/aiops-v02-audit.md`

内容包括：

- 当前实现能力。
- 已完成但尚未真实验收的能力。
- 尚未实现的功能。
- 明确发现的代码问题。
- 优先级。
- 涉及文件。
- 风险和修改方案。
- 推荐开发顺序。

此阶段不要修改数据库和核心业务逻辑。

---

# 5. 阶段 1：基础修复和共享平台 RBAC

## 5.1 权限架构

当前项目是多人共享同一台服务器，不是每个用户独占一台服务器。

第一版采用简单的共享工作空间角色设计：

- ADMIN：平台管理员。
- OPERATOR：运维人员。
- VIEWER：只读用户。

当前可以只有一个共享工作空间。

不要求立即创建 Cluster、ClusterMember 等复杂多集群结构。

保留现有服务器注册和采集逻辑。

如果当前存在 owner_user_id 等历史字段，不要直接删除。

## 5.2 角色权限

建议权限：

| 功能 | ADMIN | OPERATOR | VIEWER |
|---|---|---|---|
| 查看服务器 | 允许 | 允许 | 允许 |
| 查看 GPU | 允许 | 允许 | 允许 |
| 查看监控历史 | 允许 | 允许 | 允许 |
| 使用只读 AI 诊断 | 允许 | 允许 | 允许 |
| 管理 Agent | 允许 | 拒绝 | 拒绝 |
| 签发 Agent 注册令牌 | 允许 | 拒绝 | 拒绝 |
| 管理用户角色 | 允许 | 拒绝 | 拒绝 |
| 管理告警规则 | 允许 | 允许 | 拒绝 |
| 确认和处理告警 | 允许 | 允许 | 拒绝 |

注意：

角色必须由后端可信数据决定。

不得允许用户通过注册请求、修改 JWT 内容或修改前端状态提升角色。

初始 ADMIN 必须通过可信的引导机制创建，不得将第一个公开注册用户自动设为管理员。

## 5.3 后端修改

重点检查：

- UserEntity。
- AuthService。
- SecurityConfig。
- JwtAuthenticationFilter。
- DataInitializer。
- AgentAccessController。
- AgentAccessService。

要求：

1. 支持用户角色。
2. 统一权限判断。
3. 只有 ADMIN 可以签发 Agent 注册令牌。
4. OPERATOR 和 VIEWER 不允许管理 Agent。
5. USER JWT 和 Agent Credential 保持独立。
6. 角色修改可审计。
7. 不允许删除或禁用最后一个管理员而导致平台失去管理能力。
8. 原有服务器和 GPU 读取功能保持正常。
9. 不将不同用户强行绑定到不同服务器。

## 5.4 GPU 查询修复

重点检查：

- `gpu_manager.py`
- `gpu_tool.py`
- `platform_client.py`
- Java GPU 查询接口。

要求：

1. 使用真实的 gpuIndices，而不是数组位置假设。
2. GPU index 不连续时仍能正确查询。
3. 根据 gpuIndex 准确匹配 GPU 进程。
4. 查询整台服务器 GPU 进程时只调用一次后端接口。
5. 保留真实 GPU UUID。
6. 缺测指标不能使用 0 伪装。
7. 显示原始采集时间。
8. 检测过期采样，不把历史值当成当前状态。
9. 不修改现有 NVML 指标上报协议，除非发现明确错误。

## 5.5 验收

- ADMIN 能签发 Token。
- OPERATOR 和 VIEWER 不能签发 Token。
- 三种角色均能读取共享服务器的授权监控数据。
- Agent 正常注册、心跳和上报。
- GPU 0 和 GPU 1 的指标、UUID 和进程正确对应。
- 不连续 GPU index 的测试通过。
- 不存在重复整机进程查询。
- 原有 AI 对话和 RAG 功能不受影响。

---

# 6. 阶段 2：真实 RAG 和 Agent 联调

目前已经使用 LangChain 官方组件，不需要重写。

重点使用：

- RecursiveCharacterTextSplitter。
- HuggingFaceEmbeddings。
- QdrantVectorStore。
- RetrievalService。
- RagTool。
- GraphAgent。

## 6.1 RAG 环境验证

检查：

- 本地 Embedding 模型。
- 模型路径。
- Embedding 版本。
- Qdrant 服务。
- Collection。
- MySQL 文档状态。
- 文件存储目录。

使用真实 GPU 运维资料进行测试。

完成：

PDF 上传 → 解析 → 分块 → Embedding → 向量写入 → 检索。

验证：

- 分块内容。
- Metadata。
- 文档页码。
- 相似度结果。
- 文档访问权限。
- 索引版本。
- 重复上传。
- 删除后无法检索。

如果本地未配置真实模型或 Qdrant，不能虚构测试成功；应提供明确的运行步骤，保留未验收状态。

## 6.2 Agent 工具联合调用

至少验证以下问题：

1. 现在两张 GPU 哪张显存更多？
2. 哪张 GPU 当前利用率更低？
3. GPU 0 运行了哪些进程？
4. GPU 显存充足但 CUDA OOM，应该检查什么？
5. GPU 温度持续偏高应该如何排查？
6. 当前显存指标和运维手册是否能支持这一诊断建议？

要求：

- 实时问题必须使用 ServerTool / GpuTool。
- 静态知识问题可使用 RagTool。
- 不能编造 GPU 数据。
- 不得将 RAG 文档中的提示当作系统指令。
- 回答必须能追溯采样时间。
- 文档引用必须来自真实检索结果。
- 不得声称执行了实际服务器操作。

## 6.3 建立小型评测集

新增：

`agent/ai-service/evaluation/`

包含：

- 测试问题。
- 参考事实。
- 期望使用的工具。
- 预期来源。
- 评测脚本。
- 评测结果。

初期建立 30～50 条真实或可核对的问题即可。

评测维度：

- 工具选择是否正确。
- 是否得到相关知识片段。
- 是否正确引用。
- 是否编造监控指标。
- 采集时间是否正确。
- 是否在数据不足时明确说明。
- 单次查询延迟和工具次数。

不要把单纯的单元测试通过率当成 RAG 召回准确率。

---

# 7. 阶段 3：智能告警规则引擎

这是下一阶段最核心的新功能。

参考成熟 AIOps 告警管理的基本思路，先实现确定性的规则引擎，不使用 LLM 判断是否触发告警。

## 7.1 初期告警类型

实现：

- CPU_HIGH。
- MEMORY_HIGH。
- DISK_HIGH。
- GPU_TEMP_HIGH。
- GPU_MEMORY_HIGH。
- SERVER_OFFLINE。
- GPU_COLLECTION_FAILED。

这些规则应支持配置。

单次高显存不应默认视为故障，可先作为提示或低等级资源占用告警。

## 7.2 规则机制

需要支持：

1. 指标类型。
2. 阈值。
3. 比较操作。
4. 持续时间。
5. 恢复阈值。
6. 告警等级。
7. 启用与禁用。
8. 采样新鲜度校验。
9. 告警冷却时间。
10. 告警去重。

持续时间必须根据真实采样时间和有效样本判断。

不能把缺失采样当成指标等于零。

不能仅因为一个样本超过阈值就判定持续异常。

## 7.3 后端结构

根据实际项目目录新增类似：

```text
service/
    AlertRuleService.java
    AlertRuleEngine.java
    AlertEvaluationScheduler.java

controller/
    AlertRuleController.java
    AlertController.java

Entity/
    AlertRuleEntity.java
    AlertEventEntity.java

repository/
    AlertRuleRepository.java
    AlertEventRepository.java
```

数据库增加新的 Flyway Migration。

核心数据：

AlertRule：

- id
- name
- metric
- operator
- threshold
- recoveryThreshold
- durationSeconds
- severity
- enabled

AlertEvent：

- id
- ruleId
- serverId
- gpuIndex（可空）
- fingerprint
- severity
- status
- firstTriggeredAt
- lastObservedAt
- resolvedAt

不要直接复制示例字段，如果当前已有相同实体则复用。

## 7.4 告警生命周期

实现：

- FIRING。
- ACKNOWLEDGED。
- RESOLVED。

允许人工确认，但人工确认不等于指标恢复。

同一服务器、同一 GPU、同一规则的持续异常应归并为一个活动告警。

恢复后再次触发，可以生成新的事件周期。

## 7.5 告警测试

重点测试：

- 单次异常不会立即触发持续告警。
- 持续异常正常触发。
- 连续相同异常不会生成大量重复事件。
- 指标恢复后告警恢复。
- 恢复阈值可以避免抖动。
- Agent 离线和 GPU 数据采集失败能区分。
- 时间戳乱序和过期采样不会造成误判。
- 并发规则计算不会重复创建活动告警。

---

# 8. 阶段 4：故障事件与 LangGraph 调查工作流

## 8.1 建立 Incident

告警是监控事件，Incident 是需要调查和处理的故障事件。

新增：

- Incident。
- IncidentHistory。
- InvestigationRun。
- DiagnosisReport。

第一版允许一个 Incident 关联一个或多个告警。

不要一开始实现复杂的跨节点根因关联算法。

优先支持人工确认的事件关联，后续再增加自动规则。

## 8.2 故障调查触发

第一版先实现：

用户在 Incident 详情页点击“AI 分析”。

后台创建 InvestigationRun，返回任务 ID。

AI 调查不应该阻塞 Java 请求线程。

需要支持：

- PENDING。
- RUNNING。
- SUCCEEDED。
- FAILED。

保存创建时间、开始时间、结束时间和错误信息。

第二版再考虑告警自动触发调查。

## 8.3 LangGraph IncidentGraph

保留当前 GraphAgent 作为普通问答入口。

新增独立的：

`app/agents/incident_graph.py`

建议工作流：

```text
Incident
   ↓
Triage
   ↓
Evidence Collection
   ↓
Knowledge Retrieval
   ↓
Root Cause Analysis
   ↓
Evidence Review
   ↓
Report
```

各节点职责：

Triage：
- 识别异常类型。
- 读取告警基础信息。
- 提取相关服务器和 GPU。

Evidence Collection：
- 查询真实监控指标。
- 查询历史时间窗口。
- 查询 GPU 进程。
- 保存来源和采集时间。

Knowledge Retrieval：
- 调用现有 RagTool。
- 查找 GPU、CUDA、Linux 等运维资料。
- 保留文档名称、页码、Chunk ID。

Root Cause Analysis：
- 结合已收集证据分析可能原因。
- 不得将相关性直接当成确定因果。

Evidence Review：
- 判断是否缺少重要证据。
- 有限次数补充调查。
- 不能无限循环。

Report：
- 输出结构化诊断报告。
- 保存到数据库。

## 8.4 诊断报告内容

建议包含：

- 事件 ID。
- 服务器 ID。
- GPU index。
- 异常时间。
- 异常事实。
- 已收集证据。
- 可能原因。
- 支持与反对证据。
- 缺失证据。
- 建议处理步骤。
- 引用来源。
- 模型及工作流版本。

必须区分：

- 已经确认的监控事实。
- 模型提出的根因假设。
- 缺乏证据的推测。

诊断报告不得虚构命令执行结果。

## 8.5 可靠性与安全

- 调查只允许使用只读 Tool。
- 不提供 execute_shell、SSH 命令执行或 kill_process。
- 调查次数和工具次数必须受限。
- 持久化任务需要幂等性和失败状态。
- 中途重启不能让任务永久卡在 RUNNING。
- 日志不能记录 API Key、JWT 或敏感环境变量。
- 必须保护当前用户无权访问的资源。
- 诊断报告可被人工审核和标记是否有帮助。

如果需要数据库任务 Worker，优先采用与当前单实例部署相符的简单可靠方案，避免直接引入复杂消息队列。

---

# 9. 阶段 5：GPU 多用户使用协作

面向实验室实际需求，增加轻量级 GPU 使用登记。

注意：

**这是使用信息管理，不是任务调度或 GPU 虚拟化。**

## 9.1 任务登记

支持登记：

- 使用者。
- 服务器。
- GPU 编号。
- 任务名称。
- 任务用途。
- 开始时间。
- 预计结束时间。
- 备注。
- 状态。

可以支持用户标记：

- PLANNED。
- RUNNING。
- COMPLETED。
- CANCELLED。

## 9.2 不强制占用 GPU

任务登记不能保证 GPU 被预留。

不允许将登记记录当作实际 GPU 任务运行的唯一事实来源。

界面中必须区分：

- 人工登记的任务状态。
- Agent 实际采集的 GPU 进程。
- 当前显存和利用率。

如果登记状态与真实监控矛盾，应显示为待核实。

## 9.3 GPU 资源建议

复用现有：

`recommend_gpu_placement()`

对两张 GPU 提供只读资源建议：

- 当前显存。
- 当前利用率。
- GPU 进程。
- 历史负载。
- 推荐理由。
- 数据采集时间。

不要声称已经预留或分配资源。

## 9.4 前端功能

在现有 GPU 监控区域增加：

- GPU 使用登记。
- 当前任务列表。
- 使用者备注。
- 预计结束时间。
- 最近使用记录。

尽量复用已有 ServerDetailView 和 GpuMonitor，不要新增大量重复导航页。

---

# 10. 阶段 6：工程化、测试和求职展示

## 10.1 测试

确保：

- Java 完整测试通过。
- Python Agent 测试通过。
- FastAPI 测试通过。
- RAG 测试通过。
- Vue 类型检查通过。
- Vue 生产构建通过。

新增针对：

- 权限绕过。
- GPU index 映射。
- 告警去重。
- 告警恢复。
- Incident 状态流转。
- AI 调查任务失败。
- 重复调查。
- SSE 中断。
- 数据过期。
- 工具调用超时。

的专项测试。

## 10.2 CI

检查当前仓库是否已有 GitHub Actions。

如果没有，新增基础 CI：

- Java 测试。
- Python 测试。
- Vue 类型检查。
- Vue 构建。

CI 不依赖真实 GPU、私有大模型或真实生产数据库。

真实 GPU 和模型联调采用独立的可选测试流程。

## 10.3 部署

完善 README 和部署指南：

- 开发环境要求。
- Java 启动。
- MySQL 配置。
- FastAPI 启动。
- Qdrant 启动。
- 本地 Embedding 配置。
- LLM 配置。
- Vue 启动。
- Agent 安装。
- Agent 注册。
- 一台双 GPU 服务器的完整使用示例。

所有敏感配置使用环境变量。

## 10.4 完整演示场景

最终至少验证三个场景：

场景 A：共享 GPU 资源查询

用户询问哪张 GPU 更适合运行新任务。

Agent 查询真实 GPU 数据并给出只读建议。

场景 B：CUDA OOM 诊断

用户提出 CUDA OOM 问题。

Agent 查询 GPU、进程和知识库，输出有来源的排查建议。

场景 C：GPU 异常告警

系统根据真实历史指标或明确标记的测试数据生成告警。

用户打开事件，触发 LangGraph 调查。

系统生成结构化报告并保存。

必须区分真实事件与模拟测试事件。

## 10.5 求职展示材料

新增：

`docs/project-demo.md`

包含：

- 项目背景。
- 整体架构。
- 技术选型。
- 主要功能。
- 真实运行流程。
- 核心接口。
- 关键技术难点。
- 实测结果。
- 已知限制。
- 项目截图建议。

可以补充实际测得的：

- 平均 API 响应时间。
- RAG 检索耗时。
- Agent 完成一次诊断的时间。
- 工具调用次数。
- 告警生成和恢复正确率。

不能编造性能指标或测试数据。

---

# 11. 不在本次范围内的功能

本次暂不开发：

1. Kubernetes 集群管理。
2. GPU 虚拟化。
3. 多租户 GPU 配额。
4. 自动 SSH。
5. 自动执行 Shell。
6. 自动杀死进程。
7. 大型 Multi-Agent 系统。
8. MCP 全面改造。
9. 完整 Prometheus、Grafana、Loki 技术栈。
10. 大规模分布式任务调度。

未来如确有实际需要，再单独规划。

---

# 12. 交付和阶段记录

在仓库新增：

`docs/aiops-v02-development-plan.md`

使用 Markdown 待办清单维护每阶段的开发进度。

建议结构：

### 阶段名称

- [ ] 任务一
- [ ] 任务二
- [ ] 任务三

每个阶段需记录：

- 已完成内容。
- 修改文件。
- 新增 API。
- 数据库变化。
- 测试命令。
- 测试结果。
- 遗留问题。
- 下一阶段任务。

最终生成：

`docs/aiops-v02-implementation-report.md`

报告必须区分：

- 代码已实现。
- 单元测试已通过。
- 集成测试已通过。
- 真实设备已验证。
- 尚未完成。

不要将 Mock 测试描述为真实 GPU 环境验收。

不要在没有完整运行验证时声称系统已经具备生产级可靠性。

---

# 13. 正式执行顺序

阶段 0：代码审计和测试基线。

阶段 1：共享平台权限 + GPU 查询修复。

阶段 2：RAG 与 Agent 真实联调。

阶段 3：告警规则引擎。

阶段 4：Incident + LangGraph 故障调查。

阶段 5：GPU 使用协作。

阶段 6：工程化与演示交付。

**每个阶段完成后必须进行验收，不允许为了尽快完成全部任务而跳过测试。**

阶段 0 完成后先等待人工审查。

后续需要实际服务器、真实模型、生产数据库或敏感凭据时，输出操作步骤，由用户决定如何提供或执行；不能默认使用真实环境进行破坏性测试。

不要自动 git commit 或 git push。

本次任务的最终目标不是增加最多的功能，而是实现：

**一个能够在真实双 GPU 服务器上运行，具备监控、告警、LangGraph 智能诊断、RAG 知识检索和多人协作能力的 AIOps 系统。**

要求项目具备清晰的工程结构、可执行的自动化测试、真实场景的验证结果和可用于求职展示的技术文档。

---

# 本轮实现进度（2026-11-05）

最新用户已要求连续实施六个阶段，本轮基于现有 V8 共享工作空间模型增量实现，不重复建立第二套全局角色或监控存储。下列勾选表示代码/指定测试已完成，真实设备项保持未勾选；详细 API、文件和限制见 aiops-v02-implementation-report.md。

## 阶段 0：基线

- [x] 保存现有未提交工作，核对 main 和实际源码。
- [x] 新增 aiops-v02-audit.md，确认权限、GPU index、旧接口、告警/事件缺口。
- [x] 后续阶段获最新用户指令授权，继续增量实施。

## 阶段 1：共享权限与 GPU 修复

- [x] 复用默认集群成员的 ADMIN/OPERATOR/VIEWER；统一 Service 授权，角色修改即时生效。
- [x] 管理成员、最后有效 ADMIN 保护、审计、管理员签发/撤销 Token、停用 Agent。
- [x] Server/GPU/指标/统计范围过滤，USER 与 AGENT 身份分离，旧采集写入口退役。
- [x] GPU 稀疏编号、UUID、进程单次查询、可空指标和新鲜度修复。
- [x] 三角色共享服务器、跨集群拒绝、并发与 GPU 边界测试。
- [ ] 在真实双 GPU 机器核对 NVML UUID、进程和指标。

## 阶段 2：RAG 与 Agent 联调

- [x] 保留官方 LangChain RAG 和现有 GraphAgent/只读工具。
- [x] 提供 30 条问题、参考事实来源、期望工具、真实服务评测脚本与 NOT_EXECUTED 状态说明。
- [x] 本地离线 Embedding / Qdrant local 模式、RAG 及 Tool/SSE 回归通过。
- [ ] 实际用户模型 + 外部 Qdrant + 真实 GPU PDF 上传/搜索/删除验收。
- [ ] 实际生成模型与双 GPU 联合问答及 30 条人工事实审核。

## 阶段 3：告警

- [x] 七类规则、阈值/持续时间/恢复阈值/等级/开关/新鲜度/间隔/冷却。
- [x] FIRING/ACKNOWLEDGED/RESOLVED，规则行锁与唯一活动 key 去重。
- [x] 缺测与 UNKNOWN 不补 0、不冒充恢复；乱序、同时间冲突、UTC 时区处理。
- [x] 告警管理页面、确认操作和规则启停。
- [x] 持续/恢复/去重/冷却/离线与 GPU 失败区别/并发测试。
- [ ] 真实设备阈值与采样间隔验证。

## 阶段 4：Incident 调查

- [x] Incident、关联告警、处理历史、调查任务、诊断报告和人工反馈持久化。
- [x] 幂等请求、PENDING/RUNNING/SUCCEEDED/FAILED、独立 Worker、超时/租约过期恢复。
- [x] IncidentGraph 固定目标，复用 ServerTool/GpuTool/RagTool，最多一次补查。
- [x] 使用告警原始发生时间查询对应 GPU 历史窗口，校验 E 编号和来源。
- [x] 真实工具证据与模型假设分开展示，私有 KB 报告仅发起人可查看。
- [x] 任务 HTTP 成功/失败、权限撤销、来源/轮数与报告隔离自动化测试。
- [ ] 真实 LLM、历史 GPU 告警和知识库报告人工验收。

## 阶段 5：GPU 使用协作

- [x] 在服务器详情登记用途、使用者、GPU 编号、时间、备注和状态。
- [x] PLANNED/RUNNING/COMPLETED/CANCELLED 流转，所有权和管理员维护校验。
- [x] 登记明确不预留资源、不视为真实任务，保持待核实并与监控进程分开。
- [x] 使用登记与终态回退拒绝测试。
- [x] 资源建议附候选卡进程、近 15 分钟负载统计和原始时间；补查缺失与权限撤销测试。
- [ ] 实验室成员与 Linux 进程的人工对照演示。

## 阶段 6：工程交付

- [x] Java、采集 Agent、AI/RAG、Vue 测试和前端构建。
- [x] 基础 CI 与可选真实 Qdrant 集成 workflow。
- [x] YAML 配置、部署指南、API/数据库/架构更新、实现报告与 project-demo.md。
- [ ] GitHub 远程 CI 实际触发（本次未推送）。
- [ ] 三个真实演示场景、截图和实测性能结果；不虚构数字。

## 阶段验证与文件记录

完整测试命令、各阶段对应文件/API/迁移及回归结果见实现报告。真实设备、外部模型和实际 MySQL 副本迁移仍待环境，不将单元测试或 H2/本地模式称为真实验收。下一步是按部署指南准备环境并补齐未勾选的验收项。

本次最终结果：Java 108 通过；采集 Agent 8 通过；AI Service 53 项（52 通过、1 外部 Qdrant 跳过）；Vue 18 通过，类型检查和构建通过。新增 Token 撤销/消费、Agent 停用/心跳的并发测试通过。远程 CI 和真实设备项目仍未验收。
