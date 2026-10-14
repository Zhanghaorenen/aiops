# API 设计（V0.1）

JSON 使用 UTF-8；时间为 ISO-8601 UTC。用户 API 使用 `Authorization: Bearer <JWT>`；Agent API 使用独立 Agent 凭证。错误响应统一为 `{timestamp,status,error,message}`。未认证 401，权限不足 403，资源不存在 404，参数错误 400。

| 方法与路径 | 身份 | 请求/响应摘要 |
| --- | --- | --- |
| `POST /api/auth/login` | 公开 | `{username,password}` → `{accessToken,tokenType,expiresAt}` |
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

