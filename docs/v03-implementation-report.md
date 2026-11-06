# v0.3 体验与核心功能升级交付

日期：2026-11-06。基于现有 Vue、Spring Boot/MyBatis-Plus、Python Agent、LangGraph 和 RAG 修改，保留 RBAC、共享工作空间、历史数据及工作区原有改动；未提交或推送 Git。

## 完成的功能

- 导航调整为运行总览、资源管理、运维中心、AI 运维助手、运维知识库、系统管理。统一青绿色品牌、深色侧栏、浅色工作区和移动端布局。故障调查从告警详情进入。
- 六种告警预设支持单台/批量应用、阈值修改、启停和去重；自定义规则保留，复杂参数收进高级配置。批量操作先验证全部服务器权限，再逐台建立规则。
- 告警一键创建或复用 Incident，复用原有异步调查队列、Worker 和 LangGraph 节点。支持查看报告、历史与重试；同一告警、活动任务及幂等键防重。报告区分观测、原因推测、证据、缺失信息、建议、引用和复查方法，保持用户级任务可见性。
- 知识库提供四类 Markdown 模板、按用户/知识库隔离的本地草稿、编辑、预览、上传、后续编辑及重新索引；新增检索后生成 AI 回答，返回实际命中文档、章节、片段和 chunk_id。预览不执行原始 HTML。
- Agent 扩展 CPU 核数/各核/负载、内存可用/缓存/Swap、多分区与读写速率、各网卡计数及吞吐；GPU 增加功耗上限、显存频率。沿用原有监控表存储。
- 主机和 GPU 提供独立趋势、1h/6h/24h/7d 范围、单位、当前值、平均/峰值及时间、阈值和告警标记。服务端先用原始窗口计算统计再分桶，避免缩图遗漏峰值；缺测保持空值。沿用 TrendChart 入口和原始历史 API。
- GPU 进程关联 NVML PID 与 psutil，支持 CPU、RSS、线程、启动时间、状态、exe/cwd，以及筛选、排序和详情抽屉。处理进程退出及权限异常；不读取或上传环境变量和完整命令行。路径用于受权限控制的前端查看，未加入模型进程工具输出。
- 在原 GpuUsage 上升级日/周时间轴、快捷时长、自定义结束时间、编辑/取消/开始/结束和过期状态；旧登记保留为 LEGACY。预约与真实进程分别展示，采样过期时不显示“空闲”结论。创建、编辑均由后端验证半开区间、实际 GPU、未来时间、最长 7 天及所有者/管理员权限。

## 修改与新增文件

以下为本次功能相关文件，路径相对仓库根目录。

| 范围 | 修改 | 新增 |
|---|---|---|
| Vue 导航/页面 | `frontend/src/App.vue`、`router/index.ts`、`console.css`；`views/OverviewView.vue`、`ServerDetailView.vue`、`AlertsView.vue`、`IncidentsView.vue`、`KnowledgeView.vue` | `views/ResourceView.vue` |
| Vue 组件/工具 | `components/TrendChart.vue`、`GpuMonitor.vue`、`GpuUsage.vue`；`api.ts`、`chart.ts`、`utils/format.ts`、`tests/chat.test.mjs` | `components/MetricTrend.vue`、`HostResourcePanel.vue`、`MarkdownPreview.vue`；`utils/knowledgeTemplates.ts` |
| Java API | `controller/AlertController.java`、`IncidentController.java`、`GpuManagerController.java`、`GpuUsageController.java`、`KnowledgeDocumentController.java` | `controller/MonitoringTrendController.java` |
| Java 业务 | `service/AgentAccessService.java`、`AlertRuleService.java`、`IncidentService.java`、`GpuUsageService.java`、`KnowledgeBaseService.java`；`service/impl/GpuManagerServiceImpl.java`、`KnowledgeBaseServiceImpl.java` | `service/MonitoringTrendService.java`、`persistence/MetricDetails.java` |
| Java 持久化 | `Entity/HostMetricSampleEntity.java`、`GpuSampleEntity.java`、`GpuProcessSampleEntity.java`；`mapper/HostMetricSampleMapper.java`、`GpuSampleMapper.java` | `db/migration/V12__monitoring_details_and_reservations.sql` |
| 采集 Agent | `agent/aiops_agent/__main__.py`、`collectors/system.py`、`process.py`、`gpu.py` | `agent/tests/test_extended_collectors.py` |
| AI 服务 | `agent/ai-service/app/api/knowledge.py`、`rag/ingestion_service.py`、`client/platform_client.py`、`agents/incident_graph.py`、`tools/server_manager.py`、`tools/gpu_manager.py` | `agent/ai-service/tests/test_knowledge_experience.py` |
| Java 测试/文档 | `src/test/java/com/aiops/backend/V02WorkflowTests.java`（适配未来预约约束） | `V03ExperienceTests.java`、`V03ConcurrentTests.java`（同一测试目录）；本文、`docs/knowledge/agent-collection-sop.md` |

Java 文件除测试外均位于 `src/main/java/com/aiops/backend/`；迁移文件位于 `src/main/resources/`。未删除源码文件。已有 DeepSeek 配置、部署脚本、私有配置及其他未提交修改继续保留，不将它们重复列为本次新增功能。

## 新增与变更 API

| 方法与路径 | 用途 |
|---|---|
| `GET /api/alert-presets` | 六种预设及说明 |
| `POST /api/alert-presets/apply` | `presetKey`、`serverIds`、可选 `threshold`，逐台创建并去重 |
| `POST /api/alerts/{alertId}/investigation?retry=false` | 需要 `Idempotency-Key`；关联/复用 Incident 和异步任务 |
| `GET /api/servers/{serverId}/metrics/trends?from=&to=` | 主机分桶趋势与完整窗口统计 |
| `GET /api/servers/{serverId}/gpus/{gpuId}/trends?from=&to=` | 单 GPU 分桶趋势与统计；时间为 ISO-8601 |
| `PUT /api/servers/{serverId}/gpu-usage/{id}` | 编辑自己的 PLANNED 预约；管理员遵循原工作空间权限 |
| `GET /api/servers/{serverId}/gpu-usage?from=&to=` | 预约时间窗口查询；不传范围保留最近 200 条行为 |
| `POST /api/rag/knowledge-bases/{kbId}/documents/{docId}/reindex` | AI 服务读取原文件并重建当前索引 |
| `POST /api/knowledge-bases/{kbId}/documents/{docId}/reindex` | Java 内部授权状态变更，需要用户 JWT 与 RAG 服务密钥 |
| `POST /api/rag/answer` | `query`、`knowledge_base_ids`、`top_k`；返回回答和实际检索来源 |

原 Agent 上报和 GPU 快照/进程接口增加可选扩展字段，兼容旧 Agent。预约状态接口、原始指标历史和现有聊天流式接口继续复用。

## 数据库与部署

- 新增并已在当前 MySQL 执行 **V12**；V1–V11 未修改。上报前已备份 `data/local-run/aiops-before-v12.sql`（本地忽略文件）。
- 在三个原采样表增加 `details_json`；`MetricDetails` 负责字段白名单、大小限制和类型过滤。旧行无扩展字段时返回空数据，不另建指标仓库。
- GPU 使用登记增加 `reservation_kind` 和窗口索引；告警规则增加预设键及唯一索引；Incident 增加来源告警外键及唯一索引。
- 真实 MySQL 并发联调发现：默认 REPEATABLE READ 下，权限查询建立旧快照，等待服务器锁后仍可能漏看其他事务的新预约。已改用 READ COMMITTED，并在服务器行锁后对冲突记录执行当前读；预设批量应用和告警调查同样明确事务隔离。时间统一到微秒，避免数据库精度使相邻预约误判重叠。
- 经用户明确授权，已更新服务器现有 `/media/hooasia/data/xz/aiops-agent` 并仅重启 `aiops-collector`。更新前保留源码备份，未替换身份/令牌配置，未操作训练任务。
- 当前前端 `http://localhost:5173/`、Spring Boot 8080、FastAPI 8000、MySQL 和 Qdrant 已联通。密钥仍读取私有配置，不写入源码。

## 测试与实际验证

| 检查 | 结果 |
|---|---|
| Java `mvn test` | **118 通过**；含预设去重/权限、告警调查复用、扩展数据过滤与回读、趋势真实峰值、预约半开区间/权限/过期及并发 |
| Agent `python -m unittest discover -s agent/tests -v` | **12 通过**；包含无 GPU、权限不足、进程消失、扩展指标与计数器重置等 |
| AI `python -m unittest discover -s agent/ai-service/tests -v` | **62 项：61 通过、1 跳过**；包含重新索引成功/失败保留原文、知识问答无来源不调用模型、鉴权、引用保持、超时和错误脱敏 |
| Vue `npm test` | **20 通过** |
| Vue `npm run build` | **通过**，脚本包含 `vue-tsc --noEmit`；存在主包体积警告 |
| 远端 Agent 语法与采样 | `compileall` 通过，服务重启后已上报真实扩展数据 |
| MySQL + HTTP 并发预约 | 同 GPU 重叠请求结果 **200 / 409**；不同 GPU 可同时预约；过去时间返回 400；窗口查询成功。所有测试预约已 CANCELLED，保留带“功能验证预约”标识的历史记录 |
| 真实监控 UI | 128 核、29 个挂载点、25 个网卡上报；页面展示实际分区/所选网卡；两张 GPU 的设备字段及进程详情可见，exe/cwd 为真实值 |
| 知识库 UI | 从模板编辑器上传 SOP，READY/6 个片段；重新索引后版本 v2；实际检索命中 `doc2-v2-00005`，正确返回“观察至少三个采样周期”原文 |
| 页面检查 | 总览、主机资源、GPU/进程抽屉、日/周预约、告警预设、知识库编辑/检索已在浏览器操作核对；截图保留于本地 `data/v03-operations.png` |

自动化日志在忽略目录 `data/v03-*-tests.log`、`data/v03-vue-build.log`；真实库验证摘要在 `data/v03-live-results.json`。外部 Qdrant 专项测试缺少该测试所需显式环境变量而跳过，未将其记为通过；当前实例的真实入库/检索已单独完成。

## 尚未完成或未作真实验收的内容

- 当前历史只有实际已采集的时间跨度；7 天查询可用，但没有声称已经拥有连续 7 天数据。旧采样没有扩展字段，也不会回填虚假值。
- 真实环境未人为制造故障或触发新告警来调用大模型调查；告警 → Incident → Worker/Graph 的自动化测试通过，新增报告展示已接入。新增知识问答接口通过模拟模型回归，当前实机新 SOP 验证到真实检索；未把它记为一次真实模型回答。
- 系统日志和服务状态尚未增加专门采集通道；目前补充的是主机、网络及进程只读证据。没有向模型开放 SSH/Shell，也没有自动执行建议。
- 文档解析/分块/Embedding 仍共享现有 PROCESSING 聚合状态，界面明确表示处理中/失败/就绪，不伪造分步骤进度。更换 Embedding 模型后的跨版本迁移不属于此次重新索引验收范围。
- GPU 进程 CPU 首次采样、权限不足字段、无法映射到设备的分区速率均可为空；“未见计算进程”也不保证显存可独占。预约是协调记录，未实现强制抢占、训练启动或资源隔离。
- 生产构建主包约 1.42 MB（gzip 约 435 KB），仍有进一步按组件拆包优化空间。未触发远端 CI 或生产发布。

## 后续更新：科技风仪表盘与主机温度（2026-11-06）

- 主题调整为深蓝工作台，使用冰蓝、青色、紫色和暖色区分指标；Ant Design 弹窗/表单、表格、聊天、GPU 卡片和趋势图同步适配。
- 新增 `frontend/src/components/ResourceGauge.vue`，用于总览/主机详情的 CPU、内存、GPU、磁盘及温度，以及告警页的规则启用率、服务器覆盖率；未配置规则或缺测时显示空状态，保留真实零值。
- 新增 `frontend/src/utils/temperature.ts`，识别 CPU、主板、存储等传感器类别，并检查采样新鲜度。总览温度标题按实际来源显示，服务器详情提供全部传感器及硬件阈值。移动导航改为可折叠菜单，390px 断点无页面横向溢出。
- `agent/aiops_agent/collectors/system.py` 增加 psutil 温度采集；`MetricDetails.java` 白名单校验并保存到现有 `metric_host_sample.details_json`。继续使用原上报/最新样本/历史接口，**无新增表或 Migration**，兼容旧 Agent。
- 温度结构：`details.temperatureStatus` 为 AVAILABLE / UNSUPPORTED / UNAVAILABLE，`details.temperatures[]` 包含 sensor、label、category、currentCelsius、highCelsius、criticalCelsius。不可读值为空，不转换成零。
- 已更新远端采集文件，备份为 `system.py.before-thermal`，仅重启采集服务；本地后端已重启加载温度处理。真实数据已通过上报、MySQL 回读和浏览器详情验证。
- **当前服务器只暴露 3 个 NVMe 温度传感器**，实测约 35.85–40.85°C（随采样变化）。因此仪表盘明确显示“存储最高温度”；CPU 温度目前不可读，未将硬盘/GPU 温度冒充 CPU 温度，也未加载内核驱动或修改服务器配置。
- 自动化回归：Java **121 通过**（新增 `HostTemperatureTests.java`）；Agent **15 通过**；Vue **22 通过**，类型检查及生产构建通过。覆盖温度分类、负温度、非法值、权限异常、无传感器、来源回退、过期采样及仪表盘空值/零值。
- 本轮主要修改：`App.vue`、`console.css`、`api.ts`、`OverviewView.vue`、`AlertsView.vue`、`ServerDetailView.vue`、`HostResourcePanel.vue`、`MetricTrend.vue`、`GpuMonitor.vue`、`AiChatView.vue` 及对应测试。自动化日志为 `data/thermal-*-tests.log`、`data/thermal-vue-build.log`；浏览器截图为 `data/thermal-overview.png`、`data/thermal-alerts.png`。
