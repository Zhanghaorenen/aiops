# RAG LangChain 官方组件重构报告

日期：2026-11-05。实际仓库 D:/work/aiops/backend。开始时 git status 无未提交变更，本次未自动提交或推送。

## 架构与业务边界

中心 Spring Boot / MyBatis-Plus / MySQL 继续负责用户身份、KB 归属与文档状态。FastAPI 保留现有安全解析器、上传限制、SHA256、原件保存与失败补偿。RagTool 和 LangGraph 的 StateGraph 保持原有调用方式；Vue HTTP API 与 SSE sources 协议不变。

核心流程现在采用 LangChain：

- `langchain_core.documents.Document` 保存文本、page、section、start_index。
- `RecursiveCharacterTextSplitter` 使用中英文分隔符、keep_separator='end'、add_start_index=True；chunk_size 以字符计，范围 100–1600，overlap 0 到 chunk_size-1，最多 10000 块。
- `HuggingFaceEmbeddings` 通过本地绝对目录、local_files_only=True、trust_remote_code=False、normalize_embeddings=True 加载用户模型；支持 device 与 batch_size，线程锁保护延迟初始化。
- `QdrantClient` 管理复用连接、Collection 创建与维度校验，`QdrantVectorStore.aadd_documents` 完成 Embedding 与写入，`asimilarity_search_with_score` 完成带分数检索，`as_retriever` 提供标准内部适配接口。
- `RetrievalService` 保留 Java 鉴权、ACTIVE/READY 筛选、动态文档版本、检索后复核及 SearchResult 来源规范化。

## 安装和版本兼容

本次安装验证：langchain-text-splitters **1.1.3**、langchain-huggingface **1.2.3**、langchain-qdrant **1.1.0**、qdrant-client **1.20.0**，在 requirements.txt 固定版本。未增加 langchain-community。

既有 langchain **1.4.7**、langchain-core **1.7.3**、langgraph **1.2.18** 保持不变；原 sentence-transformers>=5,<6 声明保留，本机首次安装解析到 **5.7.0**（此前未安装）；torch **2.15.0**、transformers **5.22.1** 随模型依赖安装。`pip check` 通过，完整官方组件和 sentence_transformers 导入通过，实际检查 SentenceTransformer 签名支持 local_files_only。运行环境 Python 3.14.6 / Windows，生产环境应按部署平台选择 CPU/CUDA 对应 torch 包。

## 新旧 Payload 与迁移

旧 Collection：`aiops_<sha256(embedding_version)前24位>`；Payload 根节点为 content、knowledge_base_id、document_id、index_version 等。

新 Collection：`aiops_s{schema_version}_<sha256(embedding_version)前24位>`；Payload 为：

```json
{"page_content":"证据原文", "metadata":{"chunk_id":"doc12-v2-00000", "document_id":12, "knowledge_base_id":1, "index_version":2, "embedding_version":"immutable-revision", "status":"READY", "document_title":"gpu.pdf", "page":3, "section":"显存", "start_index":0}}
```

新检索只访问新 schema Collection。强制过滤 metadata.knowledge_base_id、metadata.status、metadata.embedding_version，以及由 Java 当前 READY 文档生成的 `(kb_id,document_id,index_version)` 组合；不再固定 index_version=1。命中后再次读取 Java 元数据，拒绝权限、状态、版本已变化的文档，保留 12500 字符证据预算和 top_k 限制。

Chunk ID 由文档 ID、索引版本、块序号确定，再通过 UUID5 生成 Qdrant point ID；重复执行相同索引不会新增重复 point。新索引版本的旧块会被 Java 版本过滤排除。

**升级不会自动迁移、清空或删除旧 Collection，也不会替换用户模型。** 原 READY 文档可能只在旧 Collection 有向量，因此升级后需重新建立索引：

1. 保留 MySQL、原件目录和旧 Collection 的一致备份。
2. 使用现有授权 download 接口或原件备份保存文件；确认原件可用后，在知识库页删除指定旧文档，再上传原文件。
3. 删除流程仅按 KB ID+文档 ID 清理其 points，包含该模型版本的旧平铺 Collection 和各新 schema Collection；Collection 本身保留。其他文档 points 不受影响。
4. 新上传按 Java 当前 indexVersion 建索引，验证 READY、页码、原文与搜索结果。内容去重要求先删除旧元数据再上传同一文件。
5. 不建议仅改 Collection 名、混拷旧 Payload、复用旧模型版本标识或直接把旧文档状态伪造为 READY。

本次没有新增自动重建 API、异步队列或 Java 状态机。需要保留原文档 ID 的自动迁移应后续在 Java 状态转换支持下实施，不能绕过状态权限。

## 资源与生命周期

`RagResources` 由 create_app 创建，Embedding/QdrantClient 按配置复用，在 FastAPI lifespan 退出时关闭连接。共享对象没有 JWT、PlatformClient 或用户权限集合。每次请求仍创建独立 PlatformClient，检索时实时获取授权。

不同模型版本文档删除使用临时客户端，仅做定向删除，finally 关闭，不加载 Embedding。Qdrant 连接/HTTP 响应异常在 RAG API 中映射为无敏感细节的 503。索引失败不标 READY，保留 FAILED/DELETING 补偿和人工重试流程。

## 文件修改清单

| 文件（仓库相对路径） | 修改 |
|---|---|
| agent/ai-service/requirements.txt | 增加并固定四个官方集成包版本，保留现有模型依赖 |
| agent/ai-service/app/rag/text_splitter.py | 官方 RecursiveCharacterTextSplitter，保留接口和限制 |
| agent/ai-service/app/rag/embedding_service.py | Embeddings 适配器委托 HuggingFaceEmbeddings，离线、延迟、设备与批量配置 |
| agent/ai-service/app/rag/vector_store.py | QdrantClient + QdrantVectorStore，嵌套过滤、schema 隔离、确定性 ID、批量写入/检索、标准 Retriever、按文档删除 |
| agent/ai-service/app/rag/ingestion_service.py | 去除手动生成 vectors，让官方 VectorStore 完成 Embedding；临时客户端关闭 |
| agent/ai-service/app/rag/retrieval_service.py | 授权后取得 Java READY 文档动态版本，保留检索后二次复核 |
| agent/ai-service/app/rag/resources.py（新增） | 无用户身份的应用级资源池和关闭方法 |
| agent/ai-service/app/api/knowledge.py | 接入应用级资源，保留路径/请求/返回协议，Qdrant 异常映射 |
| agent/ai-service/app/main.py | lifespan 管理连接关闭，模型仍延迟加载 |
| agent/ai-service/app/api/chat.py | 两个入口向 RagTool 工厂传应用级资源池；StateGraph 不变 |
| agent/ai-service/app/config.py | 新增设备、批量、schema 配置与范围校验 |
| agent/ai-service/.env.rag.example | 补充新配置，保留密钥空值和本地模型说明 |
| agent/ai-service/tests/test_rag.py | 更新旧 REST mock 与版本参数，保留原业务回归 |
| agent/ai-service/tests/test_rag_components.py（新增） | 官方切分、离线 Embedding、本地 Qdrant、版本/Schema 隔离与真实服务集成测试 |
| docs/RAG实现报告.md | 本报告及迁移、环境与验证结果 |

未更改 Spring Boot CRUD、V7/JWT、Vue、ServerTool/GpuTool/RagTool、GraphAgent 的 StateGraph；没有新增数据库迁移。

## 环境变量与运行

保留 AIOPS_BACKEND_URL、AIOPS_RAG_SERVICE_KEY、AIOPS_EMBEDDING_PATH、AIOPS_EMBEDDING_VERSION、AIOPS_QDRANT_URL、AIOPS_QDRANT_KEY、AIOPS_RAG_STORAGE_DIR、AIOPS_RAG_CHUNK_SIZE、AIOPS_RAG_CHUNK_OVERLAP。

新增：

| 环境变量 | 默认 | 校验/作用 |
|---|---|---|
| AIOPS_EMBEDDING_DEVICE | cpu | cpu/cuda/cuda:N/mps，须匹配本机模型运行环境 |
| AIOPS_EMBEDDING_BATCH_SIZE | 16 | 1–256，编码批量 |
| AIOPS_RAG_VECTOR_SCHEMA_VERSION | 2 | 2–9999 的整数字符串，隔离 Payload schema |

模型路径/版本允许启动时为空，首次模型调用给出 NOT_CONFIGURED / DIRECTORY_NOT_FOUND / LOAD_FAILED；版本最长 100 字符。空存储路径、无效设备、批量或 chunk 配置会在 Settings 创建时拒绝。

本地操作顺序：

1. 在虚拟环境安装 `python -m pip install -r requirements.txt`；配置模板中的变量到实际进程环境，模板文件不自动加载。
2. 提供原有本地模型目录与不可变版本，选择 cpu 或实际 CUDA 设备。不要为这次重构换模型。
3. 启动 Docker Desktop Linux 引擎并设置 AIOPS_QDRANT_KEY，然后在仓库根目录 `docker compose -f compose.rag.yaml up -d`。
4. Spring Boot 配置与 Python 一致的独立 AIOPS_RAG_SERVICE_KEY，在根目录执行 `mvn spring-boot:run "-Dspring-boot.run.profiles=dev"`。
5. 在 agent/ai-service 执行 `python -m uvicorn app.main:app --host 127.0.0.1 --port 8000`；frontend 执行 `npm run dev`。
6. 用户登录创建 KB，上传真实 GPU PDF；确认 READY、PDF 页码和语义搜索。AI 生成还需既有 AIOPS_LLM_* 配置，核对实时 GpuTool 与 search_knowledge 都调用、Vue 来源卡片可追溯。

## 测试证据与未完成验证

实际执行：

- `python -m compileall -q app`：通过。
- `python -m unittest discover -s tests -v`：共 **42** 项，**41 通过、1 跳过**。包含原有 Server/GPU/SSE/跨用户回归，中文/英文/overlap/start_index、实际离线 HuggingFaceEmbeddings、QdrantClient 本地模式真实存储、官方 VectorStore 写入检索、indexVersion=2、幂等、删除、维度和 schema 隔离。离线模型测试用官方 BoW+Normalize 在临时目录生成微型测试模型，无下载，不能代表真实 GPU 知识库语义召回效果。
- 新增外部真实 Qdrant 服务测试：设置 `AIOPS_TEST_QDRANT_URL`（及可选 `AIOPS_TEST_QDRANT_KEY`）后运行 `python -m unittest discover -s tests -p test_rag_components.py -v`。用随机版本创建专属测试 Collection，验证写入/去重/版本与权限过滤/删除/旧 Collection 保留，最终只清理本测试创建的 Collection。
- 前端 `npm run typecheck`、`npm test`：通过，**16 项**。
- Java `mvn -s data/local-run/mybatis-maven-settings.xml "-Dtest=KnowledgeTests" test -B`：**4 项通过**。
- 集成包完整导入与 `pip check`：通过，既有 LangChain 核心版本未升级。

Windows 沙箱禁止本地 socketpair 时会阻塞 asyncio 初始化，前端代理也会因回环请求失败；本次允许回环网络后上述回归通过，未更改测试来绕开问题。

**未完成真实端到端验收**：Docker Desktop Linux 引擎未运行；未提供用户的实际本地 Embedding 模型路径/版本、真实 GPU PDF 及联调生成模型配置，因此外部 Qdrant 测试跳过，没有启动完整服务链或声称真实 PDF→MySQL READY→GPU/知识联合诊断通过。需要按以上步骤配置后补验。

已知边界：无 BM25/Hybrid/rerank/MMR/OCR/队列/自动重试；无自动保留文档 ID 的迁移；真实召回质量与大规模过滤性能未测。metadata READY 是向量候选标记，最终可信状态仍取 MySQL；官方 Retriever 是内部组件，外部请求必须经过 RetrievalService 的授权及复核。

## 官方接口参考

- https://reference.langchain.com/python/langchain-qdrant/qdrant/QdrantVectorStore
- https://qdrant.tech/documentation/integrations/langchain/

优先阅读顺序：config → text_splitter / embedding_service → vector_store → ingestion_service / retrieval_service → resources / main lifespan → api/knowledge → 原 RagTool / GraphAgent。输入/输出协议保持原有业务含义；向量生成与持久化委托给官方组件。
