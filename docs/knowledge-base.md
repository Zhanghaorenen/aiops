# 知识库

知识库保存服务器说明、操作手册和故障案例，供 AI 回答时检索引用。文档按账号隔离。

## 配置

1. 准备本地 SentenceTransformer 模型目录，设置 `AIOPS_EMBEDDING_PATH` 与固定的 `AIOPS_EMBEDDING_VERSION`。
2. Java 和 FastAPI 使用一致的 `AIOPS_RAG_SERVICE_KEY`。Qdrant 密钥独立配置为 `AIOPS_QDRANT_KEY`。
3. 启动 Qdrant 和两个中心服务，详见 [快速开始](quickstart.md)。

模型从本地加载；仓库不提供权重。更换模型或版本时需要规划索引迁移，不能仅更改标识复用旧向量。

## 使用

创建知识库后上传文档，或从模板创建 Markdown。状态为 READY 后，可在检索页输入问题，查看命中文档、章节和片段。AI 回答需要另行配置兼容的模型接口。

重新索引保留原文件；失败原因显示在文档列表。删除知识库会影响原文件及索引，操作前请确认范围。不要上传密码、访问令牌或未脱敏的环境配置。

## 数据流

Spring Boot 保存文档归属与状态；FastAPI 解析、分块并生成本地 Embedding；Qdrant 存储向量。检索以用户权限、知识库、文档和索引版本限定范围。


## 私人记忆与故障经验

“个人记录”提供本人的长期记忆、资源订阅和故障经验。自动积累默认关闭，开启后只从已有故障调查创建 PRIVATE / CANDIDATE 条目，保存证据引用；不会自动把模型猜测作为可信知识。

记录实际解决结果并确认后，案例才能成为 VERIFIED。共享需要所有者显式授权并具备工作空间管理员权限；提交前应删除私人日志、路径与配置。被拒绝或标记过时的案例不参与可信检索。

经验向量复用当前 Embedding 与 Qdrant 集合。检索前由 Java 提供允许访问的案例和版本，查询包含案例 ID、版本、服务器过滤条件；结果返回前再次向 Java 核对验证状态和当前权限。仅索引案例标题、症状与已验证解决结果，不索引原始私人聊天。

接口：`/api/ai/memories`、`/api/ai/memory/settings`、`/api/servers/{serverId}/training/incident-cases`，向量检索为 `POST /api/rag/incident-cases/search`。现有 RAG 配置无需新增数据库服务。

IncidentGraph 在原有文档检索节点同时检索获准读取的已验证故障经验，报告把 `historical_cases` 与当前 E 编号监控证据分开保存。
