# RAG 私人运维知识库

本次实现规划 R01–R12 的首版代码。中心 Spring Boot 使用 MyBatis-Plus/MySQL 管理归属与状态；FastAPI 解析和向量化；Qdrant 保存分块；既有 GraphAgent 增加一个只读 search_knowledge 工具。没有在被管理服务器部署模型。

## 启动配置

1. 在中心服务器准备本地 SentenceTransformer 模型目录，例如支持中文的多语言模型。固定模型仓库 revision 或文件校验值，并将它作为 AIOPS_EMBEDDING_VERSION；禁止更改文件后继续使用旧版本标记。模型仅从本地加载，trust_remote_code=False，不会自动下载或执行模型仓库代码。
2. 两个服务均设置独立、随机且至少 32 字符的 AIOPS_RAG_SERVICE_KEY。它只用于受控元数据注册和索引状态更新，不能使用用户 JWT、数据库密码或模型密钥代替。
3. 配置 AIOPS_QDRANT_KEY 和 AIOPS_QDRANT_URL；在仓库根目录执行 `docker compose -f compose.rag.yaml up -d`。配置固定 Qdrant 版本且端口只绑定本机；生产跨主机部署须提供受控网络/TLS。
4. FastAPI 配置 AIOPS_EMBEDDING_PATH、AIOPS_EMBEDDING_VERSION、AIOPS_RAG_STORAGE_DIR（私有绝对目录）。设置项见 `agent/ai-service/.env.rag.example`；此文件只是模板，不会自动加载，须加入实际进程环境。
5. 在 Python 虚拟环境安装 `agent/ai-service/requirements.txt`，在 `agent/ai-service` 目录执行 `python -m uvicorn app.main:app --host 127.0.0.1 --port 8000`。本地 Embedding 首次加载较慢；建议 CPU 中心服务使用独立虚拟环境和受支持的 Python/torch 版本。
6. 启动 Spring Boot，Flyway 自动执行新增 V7；启动 Vue 后进入“运维知识库”。Vite 将 /api/rag 发给 FastAPI，其余知识库元数据 API 发给 Spring Boot。生产反向代理须同样配置路由、10 MiB 以上 multipart 请求限制和索引请求超时。
7. 创建知识库，上传文件，确认状态 READY，再执行测试搜索；之后可在 AI 聊天页提问。AI 对话仍需要原有 AIOPS_LLM_* 配置。仅测试文档上传/检索不需要生成模型。

## 数据与业务规则

V7 新增 knowledge_base 和 knowledge_document，外键连接 sys_user，并添加 `(kb_id, sha256)` 唯一约束。此前 V1–V6 不变。原件由随机 storage_key 保存，展示文件名不会用于服务器路径。重复内容返回 409；FAILED/PENDING/PROCESSING 文档可删除后重新上传。

状态链：PENDING → PROCESSING → READY 或 FAILED；删除时先标记 DELETING，再删除向量、原件，最后移除元数据。失败保留 DELETING，可在页面再次点击删除；不会提前把清理误报为成功。进程崩溃留下的 PROCESSING 首版需删除后重新上传，后台任务恢复/队列属于 P1。

上传支持 PDF/DOCX/UTF-8 MD/TXT，10 MiB 上限。PDF 最多 300 页；扫描件无文本返回 OCR_UNSUPPORTED，DOCX 检查展开大小、条目数及宏文件。分块保留 PDF 页码或标题章节。索引采用固定模型版本独立 collection 和稳定 chunk UUID；版本变化会隔离旧向量，不会混用维度。第一版 index_version 固定为 1，重建版本功能属于 P1。

每次检索从 Java 获取当前用户知识库，显式传入无权访问的 ID 会拒绝；Qdrant 必须过滤知识库 ID、READY、模型版本及 index_version。命中后再次查询 Java，排除已删除、非 READY、版本过期文档。来源文件名取 Java 元数据；片段总量受预算限制。相似度分数不是概率。

普通用户 JWT 可以管理自己的 KB；文档元数据注册额外需要服务密钥。`/api/internal/knowledge/**` 不使用用户角色作为服务身份，每个内部操作均验证独立密钥；未配置或短密钥一律拒绝。返回 404 隐藏其他用户资产是否存在。不要向浏览器暴露内部服务密钥。

## API

| 服务 | 方法与路径 | 作用 |
|---|---|---|
| Java | POST/GET /api/knowledge-bases | 创建 / 列出自己的知识库 |
| Java | GET/PUT/DELETE /api/knowledge-bases/{id} | 详情 / 编辑 / 标记删除 |
| Java | GET/POST /api/knowledge-bases/{kb}/documents | 列出 / 注册元数据（注册需服务密钥） |
| Java | GET/DELETE /api/knowledge-bases/{kb}/documents/{doc} | 查询 / 标记删除 |
| Java | PATCH /api/internal/knowledge/documents/{doc}/index-status | 独立服务更新状态 |
| Java | DELETE /api/internal/knowledge/documents/{doc} | 清理已标记删除的元数据 |
| Java | DELETE /api/internal/knowledge/bases/{kb} | 清理空的 DELETING 知识库 |
| FastAPI | POST /api/rag/knowledge-bases/{kb}/documents | multipart file 上传、同步索引 |
| FastAPI | DELETE /api/rag/knowledge-bases/{kb}/documents/{doc} | 完整清理文档 |
| FastAPI | DELETE /api/rag/knowledge-bases/{kb} | 完整清理知识库 |
| FastAPI | GET /api/rag/knowledge-bases/{kb}/documents/{doc}/download | 授权下载原件 |
| FastAPI | POST /api/rag/search | query、knowledge_base_ids（可省略）、top_k（1–10） |

来源结果包括 chunk_id、document_id、knowledge_base_id、document_title、page、section、content、score。两个聊天入口都注入 RagTool；SSE 增加 `sources` 事件供页面展示来源卡片。非流式聊天继续返回 answer，并要求模型根据实际工具结果引用来源。未创建第二个 RagAgent。

## 源码阅读顺序

1. `src/main/resources/db/migration/V7__create_knowledge_tables.sql`：先认识知识库、文档与用户的关系；Flyway 负责增量迁移。
2. `KnowledgeBaseController`、`KnowledgeDocumentController`：请求输入为校验后的 DTO 和 Spring Authentication；输出为当前用户元数据。owner 从 UserRepository 查出，不信任前端 owner 字段。
3. `KnowledgeBaseServiceImpl`：事务内检查归属、SHA 去重和状态；MyBatis-Plus Mapper 对接 MySQL；条件更新阻止 DELETING 被迟到的 READY 覆盖。
4. `KnowledgeInternalController`、`KnowledgeServiceCredential`：服务身份校验和状态回写；MessageDigest.isEqual 避免直接比较密钥。
5. Python `api/knowledge.py`：FastAPI multipart、JWT 透传、依赖错误映射；每次请求独立 PlatformClient，不全局缓存用户凭据。
6. `ingestion_service.py`：输入 KB ID 与 UploadFile；先核权、保存原件、注册 PENDING，再 PROCESSING、解析、分块、Embedding、向量写入、READY。返回 Java 文档元数据。异常清理部分向量，保留可管理的失败记录。
7. `document_parser.py`：输入 Path 与服务端识别的 MIME；输出 LangChain Document 列表，metadata 含页码/章节；`text_splitter.py` 将其拆成带重叠的 Document 块。
8. `embedding_service.py`：输入文本列表，输出浮点向量；首次调用加载本地模型。`vector_store.py` 负责 Qdrant REST 写入、强制过滤检索、按文档清理。
9. `retrieval_service.py`：输入问题、可选 KB ID 与 top_k；授权后检索，再回查 MySQL；输出带来源的证据。`rag_tool.py` 将其暴露为唯一只读工具 search_knowledge。
10. `graph_agent.py`：Server/GPU/RAG 工具统一注册进 ToolNode；模型决定调用顺序，沿用轮数、参数及输出限制。文档是外部数据，不可作为系统指令。
11. `KnowledgeView.vue` → `KnowledgeUpload.vue` / `KnowledgeDocumentTable.vue`：认识 Vue ref、事件、multipart FormData 和状态轮询；`useAiChat.ts` 接收 sources 事件并把来源放入已完成回答，`AiChatView.vue` 转义显示证据。

## 验证与后续

自动化测试使用真实 PDF/DOCX 解析、Java H2/Flyway，以及可控的 Embedding/Qdrant/模型替身；替身只用于测试，不会在运行时返回模拟向量或回答。真实本地模型、Qdrant、MySQL 和生成模型联合验收需实际配置后执行；不能把替身测试视为真实语义召回通过。

手动验收：用户 A 上传 GPU 手册→ READY→中文和 CUDA OOM 搜索→验证页码原文→用户 B 尝试搜索/下载/删除→ A 删除→确认检索无结果、原件已清理。再问同一 GPU 的实时指标与排查建议，核对 GPU 工具和 search_knowledge 都被调用。模型切换后旧文档需删除并重新索引。

P1/P2 尚未包含：BM25/混合检索、rerank、异步任务队列、自动恢复/重建索引、50 条真实语料召回率与 P95 评测、OCR。原件与 MySQL/Qdrant 须一起备份，避免只恢复某一存储造成状态不一致。

Qdrant 协议参考：https://api.qdrant.tech/api-reference/search/query-points
