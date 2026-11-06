# DeepSeek 与服务器管理知识库接入记录

验证日期：2026-11-06。中心服务运行在本机，被管 Linux 服务器继续只运行轻量 Agent。

## 本次已配置

- 模型地址：`https://api.deepseek.com`。真实 `/models` 返回 deepseek-flash、deepseek-v4-pro；本机选择 **deepseek-flash**，没有沿用已变化的旧模型名称。
- API Key：仅保存于 Git 忽略的 `data/local-run/ai-service-secrets.json`，由启动脚本注入环境变量；不在本文、源码或知识库记录明文。
- FastAPI：`http://127.0.0.1:8000`；Vue 继续把 `/api/chat`、`/api/rag`、`/api/ai/health` 代理到此服务。
- Qdrant：Docker Compose 运行，仅绑定 `127.0.0.1:6333`，使用独立随机 API Key，数据位于 `data/qdrant`。
- 中文 Embedding：BAAI/bge-small-zh-v1.5，固定 revision `7999e1d3359715c523056ef9478215996d62a620`，本机 CPU 运行，真实向量维度 **512**。
- 模型路径：`data/models/bge-small-zh-v1.5`；文档原件目录：`data/knowledge`，均由私有配置保存为绝对路径。运行时只读取本地模型，不自动下载或执行远端代码。
- Java 与 FastAPI 的 RAG_SERVICE_KEY、AI_SERVICE_KEY 已同步为独立随机密钥；Java 已重启加载，SQL DEBUG 日志已关闭。没有改变用户/Agent 认证边界。

DeepSeek API 配置依据：[官方调用说明](https://api-docs.deepseek.com/)。Embedding 来源：[BAAI 模型卡](https://huggingface.co/BAAI/bge-small-zh-v1.5)。

## 为什么调整模型客户端

DeepSeek 默认开启思考模式；其工具协议要求后续轮次回传 reasoning_content。现有 GraphAgent 保存工具消息和正文，没有保存该字段，所以仅对官方 `api.deepseek.com` 请求显式传入 `thinking.type=disabled`。非流式、流式使用相同选项，其他兼容服务不接收 DeepSeek 专有参数。工具调用、正文流式响应和多轮工具结果回传仍保留。

本次没有实现思考过程展示，也没有把忽略 reasoning_content 描述为支持思考模式。依据：[官方非思考工具调用示例](https://api-docs.deepseek.com/guides/tool_calls/)、[思考模式说明](https://api-docs.deepseek.com/guides/thinking_mode/)。

## 导入的知识库

当前属于 **admin 私人知识库**，遵循既有文档所有权规则，未自动共享给其他用户。

- 名称：服务器管理与 GPU 运维知识库；知识库 ID：**1**。
- 文档：服务器管理与GPU运维手册.md；文档 ID：**1**。
- 状态：**READY**；分块数量：**59**。
- 可编辑源码：[服务器管理手册](knowledge/server-management-handbook.md)。

手册包括架构、数据新鲜度、CPU/内存/根分区、GPU 身份与单位、CUDA OOM、温度、进程与使用登记、Agent 离线、Tailscale 通信、告警生命周期、Incident、权限与备份。示例阈值明确是示例，静态知识不冒充实时监控，不包含登录密码或其他凭据。

导入过程实际经过 Vue 代理 → FastAPI 上传/解析/分块 → 本地中文 Embedding → Qdrant 写入 → Spring Boot/MySQL 状态回写，未直接修改数据库制造 READY 状态。

## 已执行验证

1. 官方 DeepSeek 最小真实对话成功；模型列表验证成功。
2. 真实中文模型加载并生成 512 维向量；Qdrant 持久化索引建立成功。
3. 三条真实中文检索命中对应文档：empty_cache/OOM、告警确认与恢复、根分区与数据盘。检索返回实际章节、chunk_id 和相似度；这些检查不等于通用召回率评测。
4. 用户明确允许将服务器 2 的 GPU 编号、UUID、显存、利用率、采样时间和手册片段发送到官方 DeepSeek 后，执行真实联合流式请求。实际工具为 **list_gpus、search_knowledge**，返回 5 条来源事件中的片段、936 个正文 token 事件和最终 done；无 error 事件。936 是 SSE 文本事件数，不是计费 Token 数。
5. 该单次联合请求实测约 **8.3 秒**，只代表当次环境，不是平均延迟或性能保证。回答区分实时 GPU 采样、静态 OOM 手册建议、尚未查询的历史证据，引用真实文件章节和分块编号。
6. AI Service 回归共 **55 项：54 通过、1 外部 Qdrant 测试因未配置测试变量跳过**。随后单独开启真实 Qdrant 测试变量执行组件集成测试，**10 项全部通过**，包括真实服务读写、幂等、权限范围过滤、删除及旧集合保留；测试使用独立 Collection，不删除已导入文档。

真实导入与联合问答记录保存在 Git 忽略的 `data/local-run/server-kb-import-result.json`、`deepseek-live-stream-result.json`；可能含运维数据，不自动提交。

## 使用与重启

先访问 `http://localhost:5173/#/knowledge`，用 admin 登录，选择知识库测试检索。普通 AI 对话入口为 `http://localhost:5173/#/ai`。

可提问：“服务器 2 两张 GPU 哪张更适合新任务？请查询最新指标，说明采样时间。”或“结合服务器管理手册解释 CUDA OOM，empty_cache 有什么限制？”模型只读查询，不会 SSH、杀进程或预留 GPU。

AI Service 重启命令（仓库根目录，当前已在运行，不要重复启动同一端口）：

```powershell
./agent/ai-service/start-private.ps1
```

Qdrant 重启先把私有配置中的 AIOPS_QDRANT_KEY 注入当前进程，再执行：

```powershell
$ai = Get-Content ./data/local-run/ai-service-secrets.json -Raw | ConvertFrom-Json
$env:AIOPS_QDRANT_KEY = $ai.AIOPS_QDRANT_KEY
docker compose -f compose.rag.yaml up -d
Remove-Item Env:AIOPS_QDRANT_KEY
```

后端继续使用 `mvn spring-boot:run "-Dspring-boot.run.profiles=dev,cluster,operations"`。前端继续使用 `npm run dev`。实际模型配置变更后重启 AI Service；独立 Java 服务密钥变更时两端同步并重启。

## 本轮文件

- 修改 app/llm/client.py：DeepSeek 非思考兼容，其他模型行为保留。
- 新增 tests/test_deepseek.py：真实 SDK + MockTransport 验证聊天/流式选项以及其他服务隔离。
- 新增 start-private.ps1：仅加载本机私有配置，不写明文 Key。
- 新增本记录及知识库手册。此前 Agent 部署文件保持。
- 没有新增数据库迁移、模型执行命令工具或第二套知识库存储。

最初的真实数据联合验证曾被自动审批拒绝；用户随后明确授权上述数据发送范围后，原验证正常执行并成功。本次授权不扩展到其他用户私有知识库或额外服务器数据。
