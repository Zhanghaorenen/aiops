# API 设计（V0.1）

JSON 使用 UTF-8；时间为 ISO-8601 UTC。用户 API 使用 `Authorization: Bearer <JWT>`；Agent API 使用独立 Agent 凭证。错误响应统一为 `{timestamp,status,error,message}`。未认证 401，权限不足 403，资源不存在 404，参数错误 400。

| 方法与路径 | 身份 | 请求/响应摘要 |
| --- | --- | --- |
| `POST /api/auth/login` | 公开 | `{username,password}` → `{accessToken,tokenType,expiresAt}` |
| `POST /api/auth/register` | 公开（仅 POST） | `{username,password}` → 空响应 201；创建账户后需调用 login 获取 JWT |
| `GET /api/auth/me` | 用户 JWT | 当前用户 id、用户名、状态 |
| `POST /api/auth/change-password` | 用户 JWT | `{currentPassword,newPassword}` → 204 |
| `POST /api/agent/enrollment-tokens` | 用户 JWT | → 30 分钟有效的注册令牌（仅一次返回） |
| `POST /api/agent/enroll` | 注册令牌 | 节点/Agent 元信息 → Agent 凭证（仅一次返回） |
| `POST /api/agent/heartbeat` | Agent 凭证 | `{agentVersion}` → 204 |
| `POST /api/agent/metrics` | Agent 凭证 | CPU、内存、磁盘容量/使用量、采集时间 → 202 |
| `GET /api/servers` | 用户 JWT | 节点列表及最新指标 |
| `GET /api/servers/{id}` | 用户 JWT | 节点资产及最新指标 |
| `GET /api/servers/{id}/metrics/latest` | 用户 JWT | 最新主机采样 |
| `GET /api/servers/{id}/metrics?from=&to=&limit=` | 用户 JWT | 时间范围采样，限制最大返回数量 |

原型 `/api/agents` 管理 CRUD、`/api/metrics` 写入和 `/api/dashboard/summary` 目前统一要求用户 JWT；Agent 新写入使用独立 `/api/agent/*` 凭证。

注册用户名为 3 至 15 位字母或数字，密码为 12 至 72 字符且 UTF-8 编码不超过 72 字节。
注册密码字符数按 Unicode code point 计算，前后端保持一致；字段校验失败会返回具体约束消息。
重复用户名或不合法输入返回 400 与 `message`；注册不签发令牌，不会自动建立登录会话。
前端登录/注册请求不携带旧 JWT，其 401 不触发已登录业务会话失效事件；权限规则更新需重启后端。

## 阶段 A AI 接口

FastAPI 提供 `POST /api/chat`，使用现有用户 JWT，输入 `{question,history:[{role,content}]}`，输出 `{answer}`。
FastAPI先调用Java `/api/auth/me`验证JWT，再把同一JWT用于三个只读Tool；不另存JWT签名密钥。
`GET /api/ai/health` 返回 `{status,service,llmConfigured}`，可匿名访问，不代表已完成真实模型连接。
Vite把 `/api/chat` 和 `/api/ai/health` 代理到FastAPI，其他 `/api` 保持Java目标。
错误码401/403/422/502/503/504/508，响应详情在`detail`字段；完整约束见 `../agent/ai-service/README.md`。

原型历史 `/api/metrics/agents/{agentId}/history` 返回最多200条倒序采样；details返回`{agent,latestMetric}`，overview返回`{agentId,hostname,status,latestMetric}`。
集群overview/details尚未实现，明确返回501。正式节点历史API与数据库来源保持原样。

