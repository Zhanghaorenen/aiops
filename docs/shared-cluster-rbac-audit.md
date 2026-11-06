# 共享服务器集群与 RBAC：阶段 0 只读审计

审计日期：2026-11-05。本文记录实际本地代码、证据、拟议方案与未核验事项，**不是 RBAC 已实现声明**。按照本次任务的阶段规则，交付本文后等待人工审查；不进入阶段 1，不执行迁移或权限升级。

## 1. 工作区基线与审计边界

- 实际仓库：`D:/work/aiops/backend`；当前工作区路径 `D:/documents/ChatGPT/aiops` 没有项目源码，本次沿用此前实际开发仓库。
- `git status`：开始时工作区干净；分支 `main`；本地 Git 显示与缓存的 `origin/main` 一致。本次没有 fetch，不据此判断远程实时状态。
- 最近五条提交：

```text
be759f7 Refactor RAG components to integrate LangChain libraries and enhance functionality
02ff040 feat: add knowledge base and document management features
c3e161b feat: implement streaming chat functionality and GPU monitoring
9b8585e feat: Implement GPU metrics tracking with snapshot and sample entities
0e09b0a feat: 添加 GPU 查询工具并更新依赖项
```

检查范围：V1–V7 SQL、Java Entity/Mapper/Repository/Service/Controller 与两类认证 Filter、初始化器、Python Agent 注册/上传、PlatformClient/ServerManager/GPUManager/三个 Tool/GraphAgent/RAG、Vue 路由/接入页/状态管理、现有访问与持久化测试及架构文档。

仓库未发现适用的 AGENTS.md。未修改业务代码、历史 Migration、配置、用户、Token、服务器或监控数据；本次仓库文件变更仅本文。

### 实际数据库核验限制

使用现有本地 properties 配置和已安装 JDBC 驱动，尝试直接连接 MySQL；没有启动 Spring Boot，避免 Flyway/初始化器/定时任务引发写入。审计程序设计为只读连接和 SELECT，不输出密码、凭据摘要或 Token。

实际连接被拒绝：SQLState **28000**（认证失败）。未继续尝试猜测密码，没有成功建立连接，也未执行任何 SQL 写入。因此下文数据库结构依据迁移文件，**不能确认本机实际已应用版本、历史行数、归属字段取值、现存管理员账号或历史完整性**。初次尝试误选 JDBC 源码 JAR，换用运行 JAR 后才得到上述认证结果。

没有证据表明存在多个实际逻辑集群，也没有数据库证据能排除这种可能；禁止据此自动把全部历史资产合并。数据库核验与归属确认是阶段 1 数据回填的前置条件。

## 2. 当前认证架构

### USER

`AuthController → AuthService.login → JwtService.issue`：校验 ACTIVE 用户及 BCrypt 密码，记录 sys_login_log，签发包含 `sub=username`、`uid=userId`、iat、exp 的签名 JWT；没有集群或业务角色声明。

`JwtAuthenticationFilter` 每次请求按 JWT subject 回查用户 ACTIVE 状态，统一赋予 `ROLE_USER`。当前不按 uid 校验对应账号；后续应同时核对不可变用户 ID 与 subject，避免删除再创建同名账号时旧 JWT 被接受。角色、成员状态以后应从数据库动态读取，不能仅依赖 JWT 中的旧角色。

`SecurityConfig.java:31–36`：健康、登录、注册和 enroll 公共；新 heartbeat/metrics 要求 AGENT；其余默认 USER。普通注册用户立即拥有全局 USER 能力，当前没有 ADMIN/OPERATOR/VIEWER 区分。

### AGENT

`AgentAuthenticationFilter` 只在 `/api/agent/heartbeat`、`/api/agent/metrics` 解析 Agent Bearer Credential，调用 `AgentAccessService.authenticate` 查询 SHA256 摘要，生成 `AgentPrincipal(agentId,nodeId)` 与 `ROLE_AGENT`。JWT Filter 对 enroll/heartbeat/metrics 跳过用户 JWT。

Controller 从认证 principal 取 agentId，Service 再查绑定 Node；MetricRequest 不包含可选择目标的 nodeId/clusterId。新协议具有明确的绑定服务器写入边界，用户 JWT 不能替代新 Agent Credential，Agent Credential 不能凭 USER 身份调用管理接口。

authenticate 当前只检查 credential_hash 是否存在，不支持独立撤销/禁用凭据。ONLINE/OFFLINE 是连接状态，不能直接用 OFFLINE 拒绝合法重连；以后应设计独立 DISABLED/撤销标记。

### RAG 内部服务身份

`/api/internal/knowledge/**` 在过滤链 permitAll，但各内部 Controller 方法验证独立 `KnowledgeServiceCredential`，不是匿名可操作接口。文档登记还要求用户身份与服务密钥。该独立服务身份须保留，不等同于集群 ADMIN。

## 3. 当前数据库及关系

| 表 / 迁移 | 当前用途和约束 | 对共享集群的结论 |
|---|---|---|
| sys_user / V1 | username 唯一、password_hash、status、时间；无角色列 | 需增加成员角色模型，不能把 USER 当 ADMIN |
| sys_login_log / V1 | 登录结果、username、remote_ip、时间 | 不是成员管理/Token 签发审计 |
| ops_node / V2、V5 | node_key 唯一；V5 owner_user_id 可空，外键 sys_user | 无 cluster_id；owner 字段未映射进 NodeEntity |
| ops_agent_enroll_token / V3、V5 | token_hash 唯一、expires_at、used_at；V5 issued_by_user_id 可空 | 无 cluster_id/status/revoked_at；签发人未映射/未写入 |
| ops_agent / V3 | agent_key 与 credential_hash 唯一；node_id 外键和普通索引 | 每个 Agent 绑定一个 Node，但一个 Node 在数据库上允许多个 Agent |
| metric_host_sample / V3 | node_id、agent_id 外键，CPU/内存/磁盘；采集/接收时间索引 | 新主机监控事实来源，保留全部 ID 和记录 |
| agents、metrics / V4 | 独立旧版 Agent 与指标模型，metrics 按 agent_id 查询 | 与 ops_node 没有可信集群关联，不能按数值 ID 混用 |
| metric_gpu_snapshot / V6 | node/agent、采集状态、时间 | 保留 Node/Agent 外键链 |
| metric_gpu_sample / V6 | snapshot/node/agent、gpu_index/uuid、指标；snapshot+index 唯一 | 多 GPU 指标和历史，不迁移到第二套存储 |
| metric_gpu_process_sample / V6 | snapshot/node、gpu_index/uuid、进程必要字段、时间 | 服务器集群授权适用于进程数据，不做用户进程隔离 |
| knowledge_base / V7 | owner_user_id 非空外键，名称、ACTIVE/DELETING | 私有知识库，不能随服务器共享而放宽 |
| knowledge_document / V7 | KB 外键、SHA/原件键、状态、index_version；KB+SHA 唯一 | 保留 RAG 生命周期、模型版本与来源 |

源码未发现 ops_cluster、ops_cluster_member 或实际角色/权限表。`/api/metrics/clusters/{id}/overview|details` 只是返回 501 的占位接口，不代表集群已实现。

关系现状：

```text
sys_user --(V5 nullable,业务未使用)--> ops_node
sys_user --(V5 nullable,业务未使用)--> ops_agent_enroll_token
ops_agent --node_id--> ops_node
host/GPU sample --> ops_node / ops_agent / GPU snapshot
sys_user --> private knowledge_base --> knowledge_document
agents --> metrics（旧独立模型，不能等同于 ops_agent）
```

### V5 实际用途

V5 已增加字段、外键和索引，必须保留原文件及历史值。NodeEntity、AgentEnrollTokenEntity 没有 ownerUserId/issuedByUserId；签发/注册不写这些字段，Server/GPU/指标 Service 也没有按 owner 过滤。当前是“已登录用户全局访问”，不是“已实现按 owner 隔离”。实际库是否被人工填过 V5 字段未知。

### 历史完整性

SQL 设计保留 V4 旧表和 V6/V7 数据，没有理由为本次 RBAC 删除任何采样。实际历史是否完整、是否存在孤儿或重复 Agent/Node，必须在成功连接只读数据库后核验；无法凭有外键或存在迁移文件证明历史完整。

## 4. 注册、上报与并发控制

现有流程：任意 USER → `POST /api/agent/enrollment-tokens` → 随机 32 字节 Token，固定 30 分钟，库内仅 SHA256 → Python `/api/agent/enroll` → Token FOR UPDATE、检查有效期/used_at、消费 → 按 nodeKey 找或建 Node → 按 agentKey 创建 Agent 或轮换旧 Credential → 返回独立凭据并保存在 Agent 本机状态文件。

`AgentAccessService.enroll` 有事务，`AgentEnrollTokenMapper.lockByTokenHash` 使用 FOR UPDATE。具备一次性 Token 与失败回滚的基础，但本阶段没有运行并发测试，不能宣称并发验收通过。不同 Token 同时使用相同 nodeKey/agentKey 的唯一键冲突也需后续测试。

重要风险：当前有效 Token 可用于指定已有 nodeKey；若 agentKey 也匹配同 Node，Service 会轮换旧 Agent Credential。以后必须核对 Token.cluster_id 与现存 Node.cluster_id，并要求受控的同集群重新注册策略，不能借持有另一集群 Token 接管旧 Agent。

上报：Agent 默认每 15 秒采集，POST `/api/agent/metrics`，主机与 GPU 同一事务写入；目标 Node 从凭据绑定确定。心跳更新时间，服务端约 90 秒未见心跳标记离线。Python 请求体没有 clusterId，无需让 Agent 参与选择集群，也无需改变 GPU JSON 协议。

Token 当前没有列表、撤销、可配置有效期和签发操作审计。Vue Token 只存在 Pinia 内存，明文仅创建返回，没发现长期持久化 Token 列表；重新签发不会撤销前一个有效 Token。

## 5. 权限问题与代码证据

| 优先级 | 已确认问题 | 证据与影响 |
|---|---|---|
| P0 | 缺少集群成员和角色校验 | SecurityConfig:36 与 JwtAuthenticationFilter:49；任意 ACTIVE USER 同权 |
| P0 | 任意用户可签发注册 Token | AgentAccessController.issueToken；AgentAccessService:60 不读取用户/集群，签发人也未写入 |
| P0 | 服务器及指标按 ID 可读全部资源 | ServerServiceImpl:27/32 无条件列表/详情；ServerMetricController:29/36 只查 Node 存在，且直接访问 Repository |
| P0 | GPU 服务没有授权 | GpuManagerServiceImpl.validateServer:152 只验证 ID 与存在；GPU/历史/进程均需统一 Service 权限 |
| P0 | 旧接口仍允许用户写入旧资产/指标 | AgentController 的 POST/PUT/DELETE/heartbeat 和 MetricController POST；MetricServiceImpl:31 使用请求 agentId 关联旧 agents。若仅修新接口会留下另一套写入入口 |
| P0 | 已有节点重注册缺少归属校验 | AgentAccessService.enroll:69；需防跨集群 nodeKey/agentKey 复用及凭据轮换接管 |
| P1 | Dashboard 全局统计 | DashboardService:23、OpsAgentRepository.count/countByStatus 没有用户范围；只过滤 ServerService 仍会泄露 Agent 总数 |
| P1 | 缺少管理审计和凭据撤销 | 仅有登录日志；Token 无签发人、撤销，Agent 无独立管理禁用状态 |
| P1 | JWT uid 未核对账号 | JwtAuthenticationFilter:42–49 按 subject 查用户；将来用稳定 userId 定位成员并核对账号 |
| P1 | CORS 未允许 PATCH | SecurityConfig 的 allowedMethods；成员角色/状态 PATCH 跨域会失败，开发代理环境可能掩盖 |
| P2 | 旧文档与当前代码有偏差 | database-design.md 主要停在 V1–V4、仍提到 Hibernate；后续阶段 8 更新，不能作为当前事实来源 |

旧 agents/metrics 与新 ops_agent/metric_host_sample 分离：旧写接口当前问题不等于能直接写新表，但仍违背“用户 JWT 不允许替代 Agent 上报”的总体边界；需要单独决策旧接口只读兼容或停用策略。

## 6. AI Service 与私有知识库审计

PlatformClient 为每个请求绑定本次用户 Bearer JWT，ServerManager/GPUManager 通过 Java 查询对应 serverId；没有让模型自己选择用户凭据。两个聊天入口都验证 `/api/auth/me` 并建立请求级 PlatformClient。GraphAgent 只注册只读 Server/GPU/RAG 工具，401/403 会中止而非吞成普通监控数据；GPU 健康/负载比较复用平台指标，不在 AI 机器采样。

因此当前 AI 的问题是 **Java 缺少集群授权**，并非 AI 已提供独立授权。Java 改造后应复用同一链路，禁止加入绕过 Java 的直连资产数据库或任意 Shell/SSH 工具。

`recommend_gpu_placement` 已存在但只是只读建议，未执行/预留资源；本次不新增实际调度、配额或虚拟化。

SSE 的状态语义需回归：stream 路由在平台身份验证前检查模型配置；流开始后 401/403 用 error 事件表示。前端 api.ts:289 把 SSE error 转为普通异常，没有像 HTTP 401 那样清除登录态。后续应统一身份拒绝和用户提示，但没有证据说明该 UX 差异会允许读取未授权数据。

RAG 继续遵循 KnowledgeBaseServiceImpl 的 owner 检查、当前用户授权 KB 范围、向量过滤和 MySQL 复核。不要将 knowledge_base.owner_user_id 与 Node 的历史 owner 字段一起弃用。共享集群和私有知识库是不同授权域。

## 7. 拟议共享集群模型与 RBAC

```text
sys_user --< ops_cluster_member >-- ops_cluster --< ops_node --< ops_agent
                   role/status                         |           |
                                                    host/GPU metrics
ops_agent_enroll_token --cluster_id--> ops_cluster
                      --issued_by_user_id--> sys_user（审计）
```

新增 ops_cluster、ops_cluster_member；cluster_name 建议唯一以支持幂等默认集群创建；成员 UNIQUE(cluster_id,user_id)，以状态保留移除记录。Node 单一 cluster_id，保留原 node_id；Agent 继续跟随绑定 Node，不额外让 Agent 请求声明 cluster_id。cluster/member/user 均须处于允许访问状态。

统一 ClusterPermissionService 在 Service 层验证真实用户、当前 ACTIVE 成员、角色权限与目标服务器归属；Controller 不重复硬编码角色判断。JWT 保持身份载体，角色更改/禁用实时回查数据库，首版不做会导致撤权延迟的权限缓存。

| 权限 | ADMIN | OPERATOR | VIEWER |
|---|---|---|---|
| CLUSTER_VIEW、SERVER_VIEW、METRIC_VIEW、GPU_VIEW、AI_DIAGNOSE | 是 | 是 | 是 |
| AGENT_MANAGE、AGENT_TOKEN_ISSUE、MEMBER_MANAGE | 是 | 否 | 否 |
| ALERT_RULE_MANAGE、ALERT_ACKNOWLEDGE、INCIDENT_RESOLVE | 是 | 是 | 否 |

源码尚无告警/事件实现；后续可以预留权限枚举，不应为满足矩阵虚构可用 API。执行服务器操作不作为 ADMIN 的隐含权限，本次没有执行工具。

列表和统计应由 SQL 联接/EXISTS 过滤 cluster_member、cluster、node，不能读取所有服务器后在 Java 内存过滤。服务器详情/主机指标/GPU 服务入口均检查节点集群。Dashboard 的所有指标和 Agent 统计必须复用同一范围；留意现有每节点查询最新指标的 N+1，后续可用批量查询优化。

标准 Retriever、Tool 或前端参数都不是权限事实来源；当前用户所属多个集群时返回有权访问的并集，不写死默认 clusterId。

## 8. 历史兼容与迁移方案（未执行）

仓库最高版本 V7；**V8 当前在本地未使用**。阶段 1 开始前再次检查，以当时最新版本选择新迁移。V1–V7 特别是 V5 绝不改写或删除。

建议方案：

1. 成功进行只读数据盘点后，人工确认现有 ops_node 是否确属同一个共享集群；多个 owner 值本身不证明多个逻辑集群，不能盲目按 owner 拆分或强行合并。
2. 新迁移创建 cluster/member、索引与外键，为 Node/Enrollment Token 增加 cluster_id；确认归属后才回填默认集群并约束 Node 非空。不要重建 Node 或改变主键。
3. 保留 ops_node.owner_user_id 及原值，仅退出服务器授权逻辑；继续使用 issued_by_user_id 做未来签发审计，不捏造旧 Token 签发人。
4. 新 Token cluster_id 必须来自管理员所选且有权限的集群，issued_by_user_id 从当前认证读取；新增撤销/状态和有效期校验。旧无 cluster_id 的未消费 Token 建议保留记录但拒绝继续注册，重新由 ADMIN 签发。若要求旧 Token 继续有效，须额外人工确认并明确回填范围，不能默认为可信授权。
5. host/GPU/进程数据仍按原 node_id、agent_id、snapshot_id 存储，不拷贝到新表；私有知识库及 Qdrant 不迁移权限。
6. legacy agents/metrics 先保留数据；旧 Agent 与 Node 无可信关联，不能把同值 agentId 当成新 Agent/Node。建议旧写接口停用（返回明确兼容提示），或经审批建立映射后受控兼容；旧读数据也需确定授权范围。
7. 加入成员变更/角色调整/Token 签发/撤销审计，记录 actor、目标、集群、前后状态和时间；不记录明文 Token、Credential、密码。

MySQL DDL 不能按普通业务事务假定可整体回滚。正式升级前应备份并在副本验证空库、旧库升级；校验每个历史表行数、ID、外键、采集时间范围和代表性数据。只做新增表/列也必须排期，不应未经数据盘点执行 UPDATE 回填。

本次没有发现多集群的代码实体，但实际数据库不可读，无法确认该回填方案适用于历史数据。不要在下一阶段直接执行“全部节点归入默认集群”。

## 9. 初始管理员方案与需要审查的决策

`DataInitializer.java:16–27` 使用 aiops.admin.username/password；为空则跳过，账号不存在才创建，存在则不修改。它是“初始账号创建器”，当前没有任何管理员身份或集群绑定。

**仅匹配配置用户名后自动授予已有账号 ADMIN 不够安全**：现存同名账号可能来自公开注册；仅启动参数中的密码也没有被该初始化器用于校验已有账号。本阶段无法从实际数据库确认哪个账号应获权。

建议复用该配置的账号创建入口，配合独立、显式的一次性 bootstrap 授权：

- 若由受控初始化流程新建账号，可在同一受控初始化流程中绑定默认集群 ADMIN，并写持久化完成标记。
- 若账号已存在，由部署管理员先确认不可变 userId 和账号身份，再显式授权 bootstrap；不能默认把所有现有用户、全部 owner 或同名用户升级为管理员。
- bootstrap 在事务中锁定集群并写唯一完成标记，仅第一次完成；已完成后即使成员被禁用/降级/移除，也不能每次启动重新补回 ADMIN。缺少 ADMIN 的恢复使用独立人工恢复流程。
- 普通注册不自动加入任何集群，管理员后续添加已注册用户。
- 成员管理应锁定同一 cluster 行，串行校验并修改成员，防止两个并发降级/移除操作删掉最后一个有效 ADMIN。统计有效 ADMIN 应考虑用户本身 ACTIVE；账号禁用操作也不能绕过该约束。

需要审查确认：

1. 历史节点是否全部属于单一默认集群，以及旧 assets 的映射/只读兼容范围。
2. 哪个经过确认的账号成为首次 ADMIN，已有同名账号的授权流程及一次性标记方案。
3. 旧未消费 Token 是拒绝并重新签发，还是经审批保留有效性。
4. legacy Agent/Metric 写接口停用还是受控兼容；建议停用旧写入口、保留数据。

这些决策在阶段 0 仅给出方案，不执行数据归属修改或权限升级。

## 10. 后续计划与预计文件清单

| 阶段 | 预计新增/修改文件（按仓库现有目录规范） | 关键验收 |
|---|---|---|
| 1 | 新 Flyway Migration；Entity/ClusterEntity、ClusterMemberEntity；Mapper/Repository；NodeEntity、AgentEnrollTokenEntity；DataInitializer/独立 Bootstrap | 空库/旧库迁移，原 ID/监控/RAG 保留；受控幂等管理员初始化 |
| 2 | ClusterPermission 枚举/角色映射；ClusterService、ClusterMemberService、ClusterPermissionService 及 impl；成员/集群 Controller、DTO、审计组件；SecurityConfig | ADMIN 管理，成员状态/角色立即生效，防最后 ADMIN 并发删除、无自行加入/提权 |
| 3 | ServerServiceImpl、NodeRepository/NodeMapper；ServerMetricController 与指标查询 Service；GpuManagerServiceImpl；DashboardService、OpsAgentRepository/相关 Mapper；旧 Metric/Agent Service/Controller | SQL 层范围过滤，所有 ID 查询和聚合不越权，新旧路径均受控 |
| 4 | AgentAccessController/Service；AgentEnrollTokenEntity/Repository/Mapper；OpsAgentEntity/Repository 与认证 Filter（按需） | Token 绑定集群/签发人、撤销、并发消费与回滚、同集群受控重注册、Agent 只写绑定 Node |
| 5 | PlatformClient/ServerManager/GPUManager/API 参数仅按接口兼容需要；AI 测试 | USER JWT 透传，跨集群拒绝，SSE/非流式一致；不重构 StateGraph/RAG |
| 6 | Vue ClusterView/ClusterMemberView（可合并）、AccessView、api.ts、workspace.ts、router、App 菜单 | 按当前权限展示管理入口；Token 一次展示、选择集群/有效期、状态/撤销 |
| 7 | Java cluster/RBAC/迁移/并发/接口测试；Python HTTP/Tool/SSE 回归；Vue 集群与成员测试 | ADMIN/OPERATOR/VIEWER 共享同一三台服务器，跨集群、禁用、变更、旧接口、Agent 绑定验证 |
| 8 | shared-cluster-rbac-implementation-report.md、README、database-design/api-design/architecture 文档 | 报告实际执行结果、部署/回退限制、未完成项 |

不为阶段 1 自动创建新 Migration；该表是实施范围，非本次修改清单。成员 API 使用请求中的目标 userId 作为待管理对象，而操作者必须来自认证上下文；ADMIN 可添加已注册用户，不提供公开自行加入接口。

## 11. 阶段 0 验证记录

| 命令/检查 | 结果 |
|---|---|
| git status / git branch --show-current / git log -5 --oneline | 成功，记录了上述干净工作区和提交基线 |
| rg 文件清单、认证/归属/角色/集群引用与定向源码阅读 | 成功，未发现有效集群/RBAC 模型；有两个 501 集群占位路由 |
| V1–V7、实体/Mapper、Agent 与 AI 链路、Vue 接入页及现有测试静态检查 | 完成；以上结论为代码审计结论，不等同于动态漏洞复现或测试通过 |
| MySQL JDBC 只读盘点 | 失败：认证 SQLState 28000；真实数据与实际 Flyway 版本未核验 |
| 自动化单元/集成、迁移、构建 | 本阶段未运行。无业务改动；避免启动后端或跑会写入数据库的流程；不引用前一任务的测试结果作为本阶段结果 |
| 文档 diff / 仓库变更范围 | 交付前检查，仅新增本报告；历史 Migration 与业务文件无变更 |

后续经审查进入实现阶段后，测试至少包括：Java 全套（含 H2 空库与升级/并发）、真实 MySQL 副本迁移、Agent 新协议/旧接口安全、Python `python -m compileall -q app` 和 `python -m unittest discover -s tests -v`、Vue typecheck/test/build。真实 GPU/模型不可用项应单列，不声明端到端通过。

**阶段 0 已完成；数据库真实盘点仍待可用只读连接。按用户明确阶段规则，暂停在人工审查点，等待审查后再进入阶段 1。**
