# API

用户接口携带 JWT，范围受当前工作空间角色限制；Agent 使用独立 Credential。时间使用 ISO-8601；401 表示身份失效，403 表示权限不足。

| 路径 | 用途 |
|---|---|
| POST /api/auth/register、/login | 注册与登录 |
| GET /api/auth/me | 当前用户 |
| POST /api/auth/change-password | 修改密码 |
| POST /api/agent/enrollment-tokens | ADMIN 签发一次性令牌 |
| POST /api/agent/enroll | 注册 Agent |
| POST /api/agent/heartbeat、/metrics | 心跳与采样 |
| GET /api/servers | 授权服务器 |
| GET /api/servers/{id}/metrics/latest | 最新主机采样 |
| GET /api/servers/{id}/metrics、/trends | 主机历史与分桶趋势 |
| GET /api/servers/{id}/gpus | GPU 快照 |
| GET /api/servers/{id}/gpus/{gpuId}/latest | 单卡最新状态 |
| GET /api/servers/{id}/gpus/processes | GPU 进程 |
| GET /api/servers/{id}/gpus/{gpuId}/history、/trends | GPU 历史与趋势 |
| /api/servers/{id}/gpu-usage | 时间预约 |
| /api/servers/{id}/training/* | 训练任务与准入 |
| /api/alerts、/api/alert-rules、/api/alert-presets | 告警与策略 |
| POST /api/alerts/{id}/investigation | 创建或复用调查 |
| /api/knowledge-bases | 知识库元数据 |
| POST /api/chat/stream | FastAPI 流式对话 |
| /api/rag/* | FastAPI 文档索引与检索 |

时间范围和条数由各接口校验。字段见 Controller 和请求类型；训练接口及单位见 [训练任务](training.md)，管理员引导见 [快速开始](quickstart.md)。内部调查与索引更新同时校验服务密钥和用户身份。


## 会话、个人记录与资源预测

- `GET/POST /api/ai/sessions`，`PATCH/DELETE /api/ai/sessions/{id}`：本人的会话。
- `GET /api/ai/sessions/{id}/messages?before={messageId}&limit=50`：按消息 ID 向前分页。
- 原有 `/api/chat` 和 `/api/chat/stream` 可添加 `session_id`、UUID `request_id`。此时不能提交 `history`，历史由 Spring Boot 校验所有权后加载。无会话 ID 的旧调用保持兼容。
- `POST /api/ai/sessions/{id}/turns` / `POST /api/ai/sessions/{id}/turns/{requestId}/finish`：FastAPI 使用原用户 JWT 领取、完成生成租约。完成时必须携带服务端签发的 lease；相同会话并发返回 409，成功重复请求重放原回答。中断问题不作为成功上下文，失败回写不可达时由十分钟租约恢复。
- `GET/POST /api/ai/memories`、`DELETE /api/ai/memories/{id}`；`GET/PUT /api/ai/memory/settings`。
- `GET/POST /api/resources/subscriptions`、`DELETE /api/resources/subscriptions/{id}`；`GET /api/resources/notifications`、`POST /api/resources/notifications/{id}/read`。
- 运行、画像与预算接口见[训练任务](training.md)；案例接口见[知识库](knowledge-base.md)。

资源通知区分 EXPECTED_AVAILABLE（预测）与 ACTUALLY_AVAILABLE（新鲜监控与准入检查满足条件），按订阅、通知种类去重与冷却。通知不会启动、暂停或抢占任何进程。

Flyway V14 新增会话、消息、私人记忆、工作负载运行/采样/画像/预测、故障案例、订阅与通知表；扩展现有 Linux 身份映射的可选 UID。旧迁移及训练历史保持不变。

资源分析工具：`get_active_workloads`、`get_workload_profile`、`estimate_available_capacity`、`predict_workload_completion`、`search_similar_incidents`；私人记忆仅通过 `get_private_memories` 按需读取。所有工具沿用原用户 JWT，不提供进程控制能力。
