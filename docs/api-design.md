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
