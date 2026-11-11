# AI Service

FastAPI 提供模型对话、只读工具、知识检索和调查入口。模型通过用户 JWT 查询 Java API，不执行系统命令。

## 启动

在仓库根目录安装依赖并配置 `.env` 后，执行 `python scripts/run.py ai`。配置详见 [快速开始](../../docs/quickstart.md)。也可在本目录导出环境变量后运行：

```bash
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

- `AIOPS_BACKEND_URL`：Java API 地址。
- `AIOPS_LLM_BASE_URL`、`AIOPS_LLM_MODEL`、`AIOPS_LLM_API_KEY`：模型接口。
- `AIOPS_AI_SERVICE_KEY`、`AIOPS_RAG_SERVICE_KEY`：独立服务密钥，分别与 Java 配置一致。
- `AIOPS_EMBEDDING_PATH`、`AIOPS_EMBEDDING_VERSION`：本地向量模型与固定版本。
- `AIOPS_QDRANT_URL`、`AIOPS_QDRANT_KEY`：向量存储。

模型权重不随仓库分发。聊天接口与 Embedding 单独配置，见 [知识库](../../docs/knowledge-base.md)。

## 接口与源码

`GET /api/ai/health` 返回健康状态；`POST /api/chat` 返回一次性回答；`POST /api/chat/stream` 输出 SSE；`/api/rag/*` 管理文档索引与检索。

请求携带用户 JWT、question 和可选 history。服务先验证身份，再用同一身份执行工具查询；内部调查还需独立服务密钥。

`api` 处理请求，`agents` 管理有限工具循环，`tools` 包装查询，`client/platform_client.py` 连接 Java，`rag` 管理解析、索引与检索。

```bash
python -m unittest discover -s tests -v
```

普通回归无需真实模型密钥，Qdrant 专项需显式配置独立测试实例。
