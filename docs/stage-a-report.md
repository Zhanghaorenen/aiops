# 阶段 A：功能审计与修改报告

日期：2026-10-27（Asia/Shanghai）。

结论：阶段 A 的现有代码修复、三个只读 Tool、Vue 聊天与可执行测试已完成。
真实 Java/MySQL/Agent 数据链路已运行；**真实 LLM 的选工具、推理和连续追问尚未验收**，因为当前环境未配置模型地址、名称、密钥。
模拟模型测试不能替代这项验收。工作停在阶段 A，未进入 B/C/D。

## 1. 审计来源与实际问题

以 `D:\work\aiops\backend` 当前工作树为准，origin 为 `https://github.com/Zhanghaorenen/aiops.git`，开始审计时 HEAD 为 `cfa5da0`。
扫描了仓库文件清单及 Vue、Java、Python Agent、AI Service、数据库迁移、测试和启动文档。
工作开始时已经存在 AI、注册、用户实体等未提交改动；本次在其上修复，没有回退或提交用户代码，也未声称本地工作树与远程最新内容完全一致。

| 审计发现 | 本次处理 |
| --- | --- |
| Java 指标控制器缺分号、实现类不完整，引用不存在的详情/集群类型 | 补齐现有历史/详情/概览，未实现集群明确返回501 |
| 用户新增注册代码引用不存在方法/不可用构造器，email无数据库迁移 | 修复编译，保留草稿；email暂不持久化，公开注册未启用 |
| `ServerTool` 实际类名是小写 `server_tool` | 统一为 ServerTool，保留旧名称兼容别名 |
| ServerManager错误相对导入、重复传URL/JWT、参数不匹配 | 统一 PlatformClient → ServerManager → ServerTool 接口 |
| `/snapshot`、`/metrics/history` 等Python路径不在正式节点API中 | 改用已有节点详情、latest和`?from&to&limit`查询 |
| PlatformClient缺少get_current_user/close，timeout未使用，每次请求新建连接 | 按请求创建可关闭的异步客户端，统一JWT、超时和返回类型验证 |
| LLMClient形参是message，调用方传messages；当前问题未加入模型消息 | 修复形参与消息顺序，严格关联tool_call_id |
| 仅提取Bearer不等于验证JWT；模型配置错误会掩盖认证错误 | 调用Java `/api/auth/me`，通过后才检查模型配置 |
| history角色、长度、上下文及循环边界不完整 | Pydantic限制与固定工具白名单，超时/轮数/调用数量受限 |
| Vue有聊天草稿，但请求、代理、重试和会话边界未完整闭环 | 完成聊天组合函数、页面、导航、独立AI客户端与测试 |
| 缺AI依赖、三服务统一启动、运行证据和源码说明 | 补requirements、环境模板、启动健康检查、测试与指南 |

文件存在不代表功能完成：`gpu_tool.py` 只有实验性导入、`server_metrics.py` 和另一个 `agent/llm/client.py` 未形成可用服务接口；未把它们当作已完成能力。
轻量 Agent 的 NVML/GPU与SSH实验文件继续保留；SSH实验文件不是生产入口，也没有注册到模型工具。

## 2. 文件清单与作用

以下路径相对仓库根目录。标记“补全”也包括在用户已有未提交草稿上完成的文件。
没有删除功能源码；原拼写 `shcemas` 以兼容导出保留。

### Python：修改

| 文件 | 作用 |
| --- | --- |
| `agent/ai-service/app/config.py` | 环境配置、URL检查、模型就绪状态、超时/轮数设置，不保存秘密 |
| `agent/ai-service/app/llm/client.py` | 统一messages参数、兼容SDK请求、错误结果检查与关闭 |
| `agent/ai-service/app/agents/diagnosis_agent.py` | 三工具白名单、消息拼接、有限循环、参数和模型协议校验 |
| `agent/ai-service/app/client/platform_client.py` | 实际Java GET路径、用户JWT转发、类型与nodeId校验 |
| `agent/ai-service/app/tools/server_manager.py` | 将同一条真实采样转成带单位与采集时间的工具结果 |
| `agent/ai-service/app/tools/server_tool.py` | 统一三个公开工具方法，兼容原小写类名 |
| `agent/ai-service/app/api/chat.py` | /api/chat认证、依赖组装、错误映射和资源释放 |
| `agent/ai-service/app/main.py` | 可注入应用工厂、限定CORS、AI健康检查 |
| `agent/ai-service/app/shcemas/chat.py` | 旧拼写兼容入口，转发到规范schemas包 |
| `agent/aiops_agent/__main__.py` | 仅删除不可达的`return data`，保留原有主机和GPU采集流程 |

### Python：新增

- `agent/ai-service/app/schemas/__init__.py`：请求模型包。
- `agent/ai-service/app/schemas/chat.py`：唯一请求/历史/响应模型。
- `agent/ai-service/requirements.txt`：FastAPI、HTTPX、OpenAI SDK、Pydantic、Uvicorn。
- `agent/ai-service/.env.example`：空密钥配置参考，不自动加载。
- `agent/ai-service/README.md`：中心部署、协议、测试和真实烟测说明。
- `agent/ai-service/tests/test_chat.py`：18项自动化测试，模型/平台HTTP模拟。
- `agent/ai-service/tests/live_smoke.py`：显式真实MySQL/本机Agent烟测，模型明确为fixture。

### Java：修改

| 文件 | 作用 |
| --- | --- |
| `src/main/java/com/aiops/backend/controller/MetricController.java` | 恢复原型历史、详情和概览，集群占位返回501 |
| `src/main/java/com/aiops/backend/service/MetricService.java` | 用record明确详情和概览契约 |
| `src/main/java/com/aiops/backend/service/impl/MetricServiceImpl.java` | 修复实现，保留原型GPU字段，限制历史条数，拒绝NaN/无穷值 |
| `src/main/java/com/aiops/backend/repository/MetricRepository.java` | 增加按原型Agent隔离的有界历史查询 |
| `src/main/java/com/aiops/backend/security/SecurityConfig.java` | 用户端点明确要求ROLE_USER |
| `src/main/java/com/aiops/backend/service/AuthService.java` | 修复已有注册草稿的构造和方法错误 |
| `src/main/java/com/aiops/backend/Entity/UserEntity.java` | 保留email草稿，使用Transient避免未经迁移修改MySQL表契约 |
| `src/test/java/com/aiops/backend/AuthFlowTests.java` | 使用实际注册生成的节点ID |
| `src/test/java/com/aiops/backend/service/MetricServiceTests.java` | 增加历史隔离、排序、限制和GPU字段保留测试 |

新增 `src/test/java/com/aiops/backend/MetricControllerTests.java`、`MonitoringAccessTests.java`。
用户原有 `AuthController.java` 注册入口草稿继续保留；本次没有将其匿名放行。

### Vue：修改或补全

- `frontend/src/App.vue`、`frontend/src/router/index.ts`：聊天导航和 `/ai` 页面；保留注册草稿路由。
- `frontend/src/api.ts`：独立`aiApi`、JWT/401处理、FastAPI detail错误和sendChat。
- `frontend/src/views/AiChatView.vue`：补全用户已有草稿，聊天、取消、重试、确认清空、新会话。
- `frontend/src/views/Register.vue`：保留草稿，阻止匿名和演示环境提交，不宣传公开注册。
- `frontend/src/views/LoginView.vue`：移除不可用的公开注册入口。
- `frontend/src/console.css`：适配新增第五项导航。
- `frontend/vite.config.ts`：AI与Java分流代理。
- `frontend/package.json`：加入`npm test`，无新增运行依赖或锁文件变更。
- `frontend/README.md`：补齐聊天配置和代码阅读说明。

新增 `frontend/src/composables/useAiChat.ts`、`frontend/tests/chat.test.mjs`。

### 启动与文档

- `start-local.ps1`：启动Java/FastAPI/Vue、IPv4/IPv6空闲端口检测、随机开发JWT密钥、独立日志与健康检查。
- `README.md`：三服务使用方法，移除固定数据库密码/JWT密钥示例。
- `docs/architecture.md`、`docs/requirements.md`、`docs/api-design.md`、`docs/development.md`：同步阶段A真实边界和安全启动示例。
- `docs/stage-a-report.md`：本报告。
- `docs/stage-a-source-guide.md`：建议阅读顺序、类和函数契约及跨文件调用关系。

## 3. 调用关系

```text
AiChatView → useAiChat.send → api.sendChat → Vite /api/chat proxy
  → FastAPI chat → PlatformClient.get_current_user → Java /api/auth/me
  → DiagnosisAgent.answer → LLMClient.chat
  → tool_calls → ServerTool → ServerManager → PlatformClient
  → Java用户API → Repository → 现有MySQL
  → tool消息 → LLMClient.chat → answer → Vue安全文本显示
```

正式监控仍为 `Python采集Agent → /api/agent/metrics → metric_host_sample`。
诊断Agent与采集Agent不共用凭证，不在AI服务本机采样来替代目标机器。

## 4. 新增或变更API

| API | 身份 | 行为 |
| --- | --- | --- |
| `POST /api/chat`（FastAPI，补全） | 用户JWT | `{question,history}` → `{answer}`，只读工具调度 |
| `GET /api/ai/health`（FastAPI，新增） | 无需登录 | `{status,service,llmConfigured}`，不暴露密钥 |
| `GET /api/metrics/agents/{agentId}/history`（原型，修复） | 用户JWT | 最多200条，按采集时间倒序 |
| `GET /api/metrics/agents/{agentId}/details`（原型，修复） | 用户JWT | `{agent,latestMetric}` |
| `GET /api/metrics/agents/{agentId}/overview`（原型，修复） | 用户JWT | `{agentId,hostname,status,latestMetric}` |
| 原型集群overview/details | 用户JWT | 明确501，未声称实现 |

三个Tool只调用已有 `/api/servers`、`/api/servers/{id}`、`/api/servers/{id}/metrics/latest`。
历史辅助查询使用已有 `/api/servers/{id}/metrics?from&to&limit`。没有新增第四个Tool，没有SSH/Shell/写入工具。
聊天错误：401/403认证授权、422模型校验、502上游响应异常、503模型缺配置、504超时、508循环上限。

## 5. 数据库与迁移

本阶段**没有新增表、字段或Flyway迁移**，继续使用V1–V4和`ddl-auto=validate`。
正式资产和主机采样继续落在`ops_node`、`ops_agent`、`metric_host_sample`。
旧原型`agents/metrics`保留，AI不读写一套新监控表；旧GPU字段未删除。
email暂不持久化，后续要启用须设计迁移、请求字段及校验，不能只靠增加Java字段。

本次显式真实烟测在现有MySQL注册了本机节点ID 1，保存一条真实采样，未写模拟监控记录。
本机凭证保存在被Git忽略的`agent/.aiops-agent/stage-a-state.json`；没有把凭证、JWT或模型密钥写入报告。
烟测是单次采样，未安装常驻Agent服务；超过心跳阈值后显示离线是预期行为。

## 6. 已实际运行的测试

| 项目 | 结果与范围 |
| --- | --- |
| Spring Boot | 36项，失败0/错误0/跳过0；H2、真实Security/JPA过滤链；可查看target/surefire-reports |
| AI Service | 18项通过；实际ASGI/Pydantic/OpenAI SDK，模拟HTTP模型与平台 |
| Vue | 12项通过；状态、上下文、取消/重试、身份隔离、HTML转义、注册草稿限制、代理分流 |
| 轻量Agent | 原有7项全部通过 |
| Python语法 | compileall检查AI入口、测试、采集Agent和现有Python代码通过；不等价于启用了所有实验模块 |
| Vue类型与生产构建 | npm run build通过（含vue-tsc）；仍有包体积提示 |
| 三服务启动 | Java8080、FastAPI8000、Vue5174实际启动；三个健康HTTP均200 |
| 开发代理 | Vue `/api/health`进入Java，`/api/ai/health`进入AI，非法JWT聊天401 |
| 真实数据烟测 | Windows本机psutil → Agent凭证上报202 → MySQL → 用户JWT查询200；非法/缺失JWT与Agent读取凭证均401 |
| 模拟模型+真实平台 | 三Tool实际读取同一真实节点和采样；连续两次提问完成，明确标记MOCK MODEL |
| 浏览器真实登录 | 已登录现有账户，读取MySQL中真实主机；AI发送后显示模型缺配置和重试按钮 |

四组自动测试共73项。自动测试不等于真实LLM能力测试。
AI测试额外覆盖：历史角色注入、额外字段、JSON参数、布尔server_id、无采样404、调用中权限撤销、模型错误脱敏、整体超时与客户端关闭、并发用户凭证隔离、畸形模型消息和循环上限。

复跑命令与目录：

```powershell
# 仓库根目录，Maven可用后
cmd.exe /d /c mvnw.cmd clean test -DforkCount=0

# frontend目录
npm test
npm run typecheck
npm run build

# agent/ai-service目录，使用安装过requirements的解释器
python -m unittest discover -s tests -v
python -m compileall -q app tests

# agent目录
python -m unittest discover -s tests -v
```

真实烟测命令和其写入行为见 `agent/ai-service/README.md`；不应把测试密钥或密码硬编码进脚本。

## 7. 未能执行与仍未实现

| 项目 | 原因或当前边界 |
| --- | --- |
| 真实LLM调用、自动选择工具、基于证据的回答质量及真实连续追问 | 当前没有AIOPS_LLM_BASE_URL/MODEL/API_KEY，不能声称通过；需配置后验收 |
| GPU/多GPU/NVML实际驱动验证 | 本机未安装NVML依赖，本阶段只验证不错误使用中心GPU；阶段B未实施 |
| 远端Linux机器采集、断网与真实重连 | 本次只运行Windows本机真实采样；自动测试不代替远端实机 |
| GPU指标上报/表/历史/页面/Tool | 未进入阶段B，原有采集代码保留 |
| 告警、LangGraph、诊断记录、持久化调用追踪 | 阶段C未实施 |
| RAG、容器监控、SSH/写操作审批、Compose、限流、多租户ACL | 阶段D未实施；当前用户ROLE_USER可读全平台资产，不能宣称细粒度隔离 |
| 公开自助注册、email持久化、注册并发和密码UTF-8字节边界 | 用户已有草稿保留，公开入口未开放，仍需专门设计和验收 |
| 旧实验SSH采集文件 | 不属于活动生产入口，不注册模型工具；缺少的外部实现未作为已实现功能交付 |

## 8. 用户需要提供的配置或操作

1. 在中心服务器部署可用的OpenAI兼容模型，确认它支持Chat Completions与function tool calling。
2. 设置 `AIOPS_LLM_BASE_URL`（按服务要求包含/v1）、`AIOPS_LLM_MODEL`、`AIOPS_LLM_API_KEY`，然后重启AI服务；不要把密钥提交到仓库。
3. 提供实际MySQL密码，确保MySQL运行；启动脚本会安全询问密码，不内置数据库口令。
4. 使用已有管理员登录；新数据库初始化管理员需显式设置AIOPS_ADMIN_USERNAME/PASSWORD。
5. 若要持续监控，在真实目标服务器运行轻量Agent；AI服务与LLM继续放在中心服务器。
6. 配置模型后用“列出服务器”“检查服务器1的资源”“那内存呢”三次交互验收，并核对真实数据和采集时间。

当前入口为 `http://localhost:5174/#/ai`；后续启动以脚本打印的端口为准。
阶段A真实模型验收完成前，不自动推进后续阶段。

## 9. 初学者入口

完整指南：[stage-a-source-guide.md](stage-a-source-guide.md)。
其中包含建议阅读顺序、每类职责、函数输入输出、跨文件调用和Python/FastAPI/Spring Boot/Agent知识点。
