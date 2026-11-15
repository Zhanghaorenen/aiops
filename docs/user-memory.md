# 用户长期记忆

私人记忆按用户隔离，不共享给工作空间。沿用 MySQL 会话、UserMemoryService、现有 LLM/Embedding、Qdrant 客户端和 GraphAgent。MySQL 为唯一主数据源，Qdrant 仅保存提取后的短记忆向量，不保存完整聊天。

## 使用与配置

在「个人记录 → 私人记忆」明确开启「自动聊天记忆」。默认关闭，和故障调查的自动经验开关互不影响。开启只授权之后的新用户消息；关闭后不创建新记忆，已保存内容仍可管理和召回。删除、编辑和清空通过版本与授权代次立即阻止旧向量/在途任务写回，清空也清理摘要并跳过此前消息；原始会话不删除。

沿用根 `.env` 的 `AIOPS_AI_SERVICE_URL`、`AIOPS_AI_SERVICE_KEY`（双方一致、至少32字符）、`AIOPS_LLM_BASE_URL/MODEL/API_KEY`、`AIOPS_EMBEDDING_PATH/VERSION`、`AIOPS_QDRANT_URL/KEY`。Embedding 为现有离线模型，首次加载耗时不等于检索正常延迟。

```powershell
docker compose --env-file .env -f compose.rag.yaml up -d
# 若使用 Redis 限流，同时启动现有 Redis
docker compose --env-file .env -f compose.redis.yaml up -d
python scripts/run.py backend
agent/.venv/Scripts/python.exe scripts/run.py ai
python scripts/run.py frontend
```

分别在终端运行后三个命令。后端首次启动应用新迁移 **V16**，不修改 V14/V15。新增配置：

| 变量 | 默认值 | 用途 |
|---|---|---|
| `AIOPS_CHAT_MEMORY_WORKER_ENABLED` | `true` | 独立后台任务线程；仍需每用户授权 |
| `AIOPS_CHAT_MEMORY_ROUNDS` | `5` | 累计有效对话触发阈值，1–20轮 |
| `AIOPS_MEMORY_TOP_K` | `4` | 单次召回条数，1–5 |
| `AIOPS_MEMORY_SCORE_THRESHOLD` | `0.72` | 语义召回相似度阈值 |
| `AIOPS_MEMORY_CONTEXT_CHARS` | `2000` | 记忆正文上下文上限，200–4000 |
| `AIOPS_MEMORY_RECALL_TIMEOUT` | `2` | 召回超时秒数，0.1–5 |

## 调用链

1. ChatSessionService 持久化有效轮次。独立 Java worker 每5秒扫描已授权、达到轮数或已归档的会话，排除失败、中断和回放；在用户行锁下创建幂等任务并领取3分钟租约。聊天/SSE 不等待摘要。
2. Worker 使用服务密钥和该用户的临时请求 JWT 调用 FastAPI；FastAPI 再通过 Java 验证任务所有权、租约、授权代次及版本，从 MySQL 取得新增消息。滚动摘要最长2000字符；单批用户/助手原文最多12000字符，序列化输入最多32000字符。过长对话分批推进游标，只清空原触发批次的待办，不变成每句话都调用模型。
3. 现有 LLM 以 JSON 模式生成摘要和最多5条记忆，Pydantic 限制类型、类别、长度和来源。用户原句须与新增 user 消息逐字匹配，拒绝助手建议、推测、临时资源状态、凭据及敏感日志/第三方信息。语义检索补充更早的相关偏好，主题key/内容/向量相似度去重；显式偏好变化携带旧ID及预期版本。高相似但没有明确替代关系的内容保守跳过。
4. 模型结果先持久化为 EXTRACTED，再在事务中复核授权、手动编辑代次和摘要版本，写记忆/版本关系与索引待办，更新摘要和游标。已保存结果重试不会再次调用模型。租约过期可回收；最多5次，失败保留状态，可在页面点击重试。
5. 索引线程复用 Embedding 和 Qdrant 客户端，在独立 `aiops_user_memory_s{schema}_{embeddingHash}` collection 内按用户过滤。记忆编辑/删除后异步清理旧版本，乱序旧任务不能删除较新的版本；当前内容再次回 MySQL 验证。
6. GraphAgent 增加按需 memory 节点：偏好/习惯/历史经验/推荐等问题才检索，复用已验证身份 → Qdrant Top-K → MySQL 复核用户、状态和版本 → 有长度上限的不可信数据上下文 → 原 Tool Calling。私人记忆不作为知识库引用；实时 GPU/CPU/任务数据仍调用监控工具。Qdrant/Embedding 异常或无命中时跳过记忆，保留原聊天流程。

## 数据库与接口

扩展 `ai_user_memory_setting`（独立授权、代次、水位）和 `ai_user_memory`（类别、主题、来源会话/消息、引文、可信度、版本、状态、更新时间）。新增：

- `ai_chat_summary`：滚动摘要、游标、版本。
- `ai_chat_memory_task`：持久任务、输入边界、目标水位、授权版本、租约、重试和模型结果。
- `ai_user_memory_revision`：旧版本与来源关系；删除/清空同步抹去旧正文。
- `ai_memory_index_task`：索引 outbox，保存期望版本、租约和失败重试。

全部用户接口沿用 JWT 与当前用户校验，不接受 userId 作为授权：

| 接口 | 行为 |
|---|---|
| `GET/PUT /api/ai/chat-memory/settings` | 独立自动提取开关 |
| `GET/POST /api/ai/memories` | 列表/手动添加，支持category |
| `GET/PATCH/DELETE /api/ai/memories/{id}` | 本人记录读取/编辑/删除 |
| `DELETE /api/ai/memories` | 清空本人记忆和摘要，旧消息不再提取 |
| `POST /api/ai/memories/verify` | 最多5条ID+版本的当前有效性复核 |
| `GET /api/ai/sessions/{id}/summary` | 本人会话摘要 |
| `POST /api/ai/chat-memory/retry` | 重试本人失败任务/索引 |
| `GET /api/ai/chat-memory/tasks/{id}` | 仅 worker：额外服务密钥和租约头，返回本人任务输入 |

FastAPI内部 `POST /api/internal/chat-memory/extract`、`/index` 同时要求 `X-AI-Service-Key` 与有效用户 JWT。没有新增允许匿名的内部端点。

## 验证与限制

Java定向：`mvn -Dtest=LongTermMemoryTests,LongTermMemoryConcurrencyTests,ResourceMemoryTests,ChatConcurrencyTests test`。AI服务目录：`../.venv/Scripts/python.exe -m unittest discover -s tests -p 'test_*.py'`。前端：`npm test`、`npm run build`。

向量测试默认使用 Qdrant 官方内存实现与确定性测试Embedding；设置 `AIOPS_TEST_QDRANT_URL/KEY` 可对隔离真实Qdrant执行同一批测试，随机集合完成后清理。测试对话和向量样本不代表线上成绩。

模型语义和敏感内容过滤是保守规则，可能漏掉有价值记忆或误判，用户可手动纠正。Qdrant不可用时退化为 MySQL 主题key/文本去重，不能保证完整语义去重；检索故障不使用旧向量作无校验回退。向量清理为可重试的异步操作，MySQL版本复核立即阻止召回；不是同步物理删除保证。极端崩溃发生在模型返回与结果持久化之间时，重试可能再次请求外部模型；提供稳定幂等键，但供应商是否实现去重由其决定，数据库写入仍保持幂等。任务扫描每次最多200个可处理会话，检索及上下文有明确上限；未引入多Agent或另一套记忆框架。
