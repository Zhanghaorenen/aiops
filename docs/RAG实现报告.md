# RAG 首版修改报告（2026-10-30）

实际工作仓库：D:/work/aiops/backend。实现范围与调用关系详见 [RAG知识库使用与源码指南](RAG知识库使用与源码指南.md)。

已实现私人知识库 CRUD、PDF/DOCX/MD/TXT 上传与格式/大小检查、来源分块、本地 Embedding 适配、Qdrant 写入/强制授权检索/删除、MySQL 状态机、RagTool、两个聊天入口接入、流式来源卡片及 Vue 管理页。删除后端按 MySQL→向量/原件→元数据顺序清理，失败可重试。

## 数据库与 API

新增 V7，无修改旧迁移；knowledge_base 归属 sys_user，knowledge_document 以 kb_id/sha256 去重。本次在 H2 执行迁移并测试；未启动实际 MySQL 后端应用 V7，实际 MySQL 会在下次后端启动时由 Flyway 迁移。

Java 新增 /api/knowledge-bases 及文档元数据接口；内部服务接口使用 /api/internal/knowledge/documents/{doc}/index-status 和受控删除接口。FastAPI 新增 /api/rag/search、文档上传/删除/授权下载与知识库完整清理。聊天 JSON 协议保持 answer；SSE 新增 sources 事件。详细方法、路径和请求字段见使用指南。

## 已实际执行的验证

- Java 编译成功；KnowledgeTests 4 项通过，含 H2/Flyway V7、用户归属隔离、内容去重、状态/删除流程、内部凭据及 HTTP 拒绝测试。
- Python AI Service 共 32 项通过：既有聊天回归 20 项，新增 RAG 12 项。PDF、DOCX 使用真实解析器；模型、Embedding、Qdrant 使用测试替身。
- Vue TypeScript 检查通过，生产构建成功；16 项前端测试通过，新增来源去重与追问历史隔离测试。
- Python app 28 个源码文件语法检查通过；git diff --check 通过。

## 未执行的真实联调与仍未实现项

Docker CLI 存在，但 Docker Desktop Linux 引擎未运行；本地 SentenceTransformer 模型目录和固定版本尚未提供，因此未运行真实 Qdrant/本地 Embedding/模型联合诊断，不宣称真实语义召回通过。解析依赖已安装，完整模型依赖仍需按 requirements.txt 在目标运行环境安装。真实 MySQL V7 应用待下次后端启动。

需要用户提供：本地 Embedding 文件及不可变版本标识、独立 RAG 服务密钥、Qdrant 密钥，并启动 Docker 引擎；AI 回答还需既有生成模型配置。环境模板见 agent/ai-service/.env.rag.example，Qdrant 配置见 compose.rag.yaml。

P1/P2：混合检索/BM25/rerank、异步队列与崩溃自动恢复、重建索引、50 条真实语料评测、OCR 尚未实现。首版同步索引，崩溃留下的 PROCESSING 文档可以删除后重传。文档内容始终作为外部证据，不是模型或服务器执行指令。

## 文件清单

无删除文件。下表包含本次新增和修改文件；实体目录大小写沿用仓库现状。

| 文件 | 变更 | 作用 |
|---|---|---|
| `"docs/RAG\347\237\245\350\257\206\345\272\223\344\275\277\347\224\250\344\270\216\346\272\220\347\240\201\346\214\207\345\215\227.md"` | 新增 | Graph 工具接入、请求内平台客户端、RAG 配置和依赖 |
| `agent/ai-service/.env.rag.example` | 新增 | Qdrant 部署或环境变量模板 |
| `agent/ai-service/app/agents/graph_agent.py` | 修改 | Graph 工具接入、请求内平台客户端、RAG 配置和依赖 |
| `agent/ai-service/app/api/chat.py` | 修改 | Graph 工具接入、请求内平台客户端、RAG 配置和依赖 |
| `agent/ai-service/app/api/knowledge.py` | 新增 | Graph 工具接入、请求内平台客户端、RAG 配置和依赖 |
| `agent/ai-service/app/client/platform_client.py` | 修改 | Graph 工具接入、请求内平台客户端、RAG 配置和依赖 |
| `agent/ai-service/app/config.py` | 修改 | Graph 工具接入、请求内平台客户端、RAG 配置和依赖 |
| `agent/ai-service/app/main.py` | 修改 | Graph 工具接入、请求内平台客户端、RAG 配置和依赖 |
| `agent/ai-service/app/rag/__init__.py` | 新增 | RAG 解析、切分、本地 Embedding、Qdrant 与授权检索/索引流程 |
| `agent/ai-service/app/rag/document_parser.py` | 新增 | RAG 解析、切分、本地 Embedding、Qdrant 与授权检索/索引流程 |
| `agent/ai-service/app/rag/embedding_service.py` | 新增 | RAG 解析、切分、本地 Embedding、Qdrant 与授权检索/索引流程 |
| `agent/ai-service/app/rag/ingestion_service.py` | 新增 | RAG 解析、切分、本地 Embedding、Qdrant 与授权检索/索引流程 |
| `agent/ai-service/app/rag/retrieval_service.py` | 新增 | RAG 解析、切分、本地 Embedding、Qdrant 与授权检索/索引流程 |
| `agent/ai-service/app/rag/schemas.py` | 新增 | RAG 解析、切分、本地 Embedding、Qdrant 与授权检索/索引流程 |
| `agent/ai-service/app/rag/text_splitter.py` | 新增 | RAG 解析、切分、本地 Embedding、Qdrant 与授权检索/索引流程 |
| `agent/ai-service/app/rag/vector_store.py` | 新增 | RAG 解析、切分、本地 Embedding、Qdrant 与授权检索/索引流程 |
| `agent/ai-service/app/tools/rag_tool.py` | 新增 | Graph 工具接入、请求内平台客户端、RAG 配置和依赖 |
| `agent/ai-service/requirements.txt` | 修改 | Graph 工具接入、请求内平台客户端、RAG 配置和依赖 |
| `agent/ai-service/tests/test_rag.py` | 新增 | 解析、权限、索引生命周期、Graph 来源与前端回归测试 |
| `compose.rag.yaml` | 新增 | Qdrant 部署或环境变量模板 |
| `docs/RAG实现报告.md` | 新增 | 部署、接口、实现边界和初学者源码阅读说明 |
| `frontend/src/App.vue` | 修改 | Vue 知识库页面、上传/状态展示、SSE 来源卡片或路由代理 |
| `frontend/src/api.ts` | 修改 | Vue 知识库页面、上传/状态展示、SSE 来源卡片或路由代理 |
| `frontend/src/components/KnowledgeDocumentTable.vue` | 新增 | Vue 知识库页面、上传/状态展示、SSE 来源卡片或路由代理 |
| `frontend/src/components/KnowledgeUpload.vue` | 新增 | Vue 知识库页面、上传/状态展示、SSE 来源卡片或路由代理 |
| `frontend/src/composables/useAiChat.ts` | 修改 | Vue 知识库页面、上传/状态展示、SSE 来源卡片或路由代理 |
| `frontend/src/router/index.ts` | 修改 | Vue 知识库页面、上传/状态展示、SSE 来源卡片或路由代理 |
| `frontend/src/views/AiChatView.vue` | 修改 | Vue 知识库页面、上传/状态展示、SSE 来源卡片或路由代理 |
| `frontend/src/views/KnowledgeView.vue` | 新增 | Vue 知识库页面、上传/状态展示、SSE 来源卡片或路由代理 |
| `frontend/tests/chat.test.mjs` | 修改 | 解析、权限、索引生命周期、Graph 来源与前端回归测试 |
| `frontend/vite.config.ts` | 修改 | Vue 知识库页面、上传/状态展示、SSE 来源卡片或路由代理 |
| `src/main/java/com/aiops/backend/DTO/knowledge/KnowledgeRequests.java` | 新增 | MyBatis-Plus 实体/Mapper 或带校验的请求 DTO |
| `src/main/java/com/aiops/backend/Entity/KnowledgeBaseEntity.java` | 新增 | MyBatis-Plus 实体/Mapper 或带校验的请求 DTO |
| `src/main/java/com/aiops/backend/Entity/KnowledgeDocumentEntity.java` | 新增 | MyBatis-Plus 实体/Mapper 或带校验的请求 DTO |
| `src/main/java/com/aiops/backend/controller/KnowledgeBaseController.java` | 新增 | 知识库元数据或独立服务身份 API |
| `src/main/java/com/aiops/backend/controller/KnowledgeDocumentController.java` | 新增 | 知识库元数据或独立服务身份 API |
| `src/main/java/com/aiops/backend/controller/KnowledgeInternalController.java` | 新增 | 知识库元数据或独立服务身份 API |
| `src/main/java/com/aiops/backend/mapper/KnowledgeBaseMapper.java` | 新增 | MyBatis-Plus 实体/Mapper 或带校验的请求 DTO |
| `src/main/java/com/aiops/backend/mapper/KnowledgeDocumentMapper.java` | 新增 | MyBatis-Plus 实体/Mapper 或带校验的请求 DTO |
| `src/main/java/com/aiops/backend/security/SecurityConfig.java` | 修改 | Graph 工具接入、请求内平台客户端、RAG 配置和依赖 |
| `src/main/java/com/aiops/backend/service/KnowledgeBaseService.java` | 新增 | 归属校验、事务、状态流转或服务凭据校验 |
| `src/main/java/com/aiops/backend/service/KnowledgeServiceCredential.java` | 新增 | 归属校验、事务、状态流转或服务凭据校验 |
| `src/main/java/com/aiops/backend/service/impl/KnowledgeBaseServiceImpl.java` | 新增 | 归属校验、事务、状态流转或服务凭据校验 |
| `src/main/resources/db/migration/V7__create_knowledge_tables.sql` | 新增 | V7 知识库/文档表、用户外键与去重索引 |
| `src/test/java/com/aiops/backend/KnowledgeTests.java` | 新增 | 解析、权限、索引生命周期、Graph 来源与前端回归测试 |
