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
