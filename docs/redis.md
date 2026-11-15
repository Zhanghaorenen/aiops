# Redis

Redis 用于监控查询短缓存、Agent 用户级限流和通知刷新提示。MySQL 仍保存监控、告警、通知和会话；Qdrant、训练队列、GPU 准入与预约事务保持原链路。

## 本地启动

在项目根目录将 `.env.example` 复制为 `.env`，填写原有配置。使用 `python -c "import secrets; print(secrets.token_urlsafe(32))"` 生成 Redis 密码，填入 `AIOPS_REDIS_PASSWORD`。密码只保存在忽略的 `.env` 中，不提交。

```powershell
docker compose --env-file .env -f compose.redis.yaml up -d
agent/.venv/Scripts/python.exe -m pip install -r agent/ai-service/requirements.txt
# 以下三个命令分别在终端运行；均读取根目录 .env
python scripts/run.py backend
agent/.venv/Scripts/python.exe scripts/run.py ai
python scripts/run.py frontend
```

Redis 仅绑定 `127.0.0.1:6379`。跨机器部署应使用私有网络，不能直接开放公网；Compose 中 Redis 数据无需持久化，重启时缓存/限流窗口重置，通知由 MySQL 恢复。应用优雅关闭 Redis 客户端、连接池和订阅器。

## 配置和调用链

- Java 和 FastAPI 共享 `AIOPS_REDIS_HOST/PORT/DB/PASSWORD/KEY_PREFIX`，不同环境必须使用不同前缀。Java 复用 Lettuce 连接/池（最多 16）；FastAPI 单应用复用异步池（最多 32）。Java 超时用 `AIOPS_REDIS_TIMEOUT=500ms`，Python 用 `AIOPS_REDIS_TIMEOUT_SECONDS=0.5`。
- 监控：JWT/RBAC → `MonitoringQueryService` / `GpuManagerServiceImpl` → Redis → 未命中查 MySQL。`AIOPS_REDIS_CACHE_TTL=5s`（允许 0–60 秒，不含 0）。服务器最新资源、GPU 列表/总览/单卡共享类型化快照，保留 `collectedAt/receivedAt`；采集事务提交后轮换节点缓存代次，旧 key 自动过期。Redis 断线直接查询 MySQL；写事务、GPU 进程查询、训练准入及预约均绕过缓存。失效期间 Redis 不可用时最多仍可能读取尚未过期的 5 秒缓存，因此仍需按采集时间判断新鲜度。
- 对话：Java `/api/auth/me` 验证身份 → Redis Lua 滑动 60 秒窗口（Redis 服务端时钟）→ Agent。`/api/chat` 与 `/api/chat/stream` 共享额度，默认 `AIOPS_CHAT_RATE_PER_MINUTE=20`。超限在发送 SSE 响应头之前返回 429 和 `Retry-After`。不使用请求体 user_id 或自行解码的 JWT。Redis 故障时采用 **FAIL_CLOSED：503 / Retry-After: 5**，不会静默放行。没有引入额外生成并发锁；取消/超时后没有需要释放的限流锁，已计费请求保留至窗口过期。
- 通知：MySQL 通知写入/标已读、告警更新 → AFTER_COMMIT → Redis Pub/Sub → Java 鉴权 `GET /api/resources/notifications/stream` → 前端刷新原有鉴权 API。消息只有用户路由和刷新类型，不携带通知内容、资源指标或 JWT；每连接 55 秒、心跳 15 秒，每用户每实例最多 4 条连接，重连重新验证 JWT。断线重连主动读取 MySQL（包括未读记录），前端 15 秒轮询始终保留。Pub/Sub 不提供重放、ACK 或可靠存储。

`AIOPS_REDIS_ENABLED=false` 为显式关闭集成的开发配置，**会关闭 Redis 限流**；正常部署保持 true，故障不要通过关闭开关绕过限流。

## 观测与针对性测试

- Java：鉴权 `GET :8080/api/redis/status` 返回健康、命中/未命中/错误、TTL、发布错误及 SSE 连接数。
- FastAPI：鉴权 `GET :8000/api/ai/redis/status` 返回 Redis 健康、允许/限流/故障拒绝次数。统计为各进程累计值，重启清零；限流额度本身跨实例共享。
- 常规回归：`mvn -Dtest=GpuManagerServiceTests,MetricServiceTests,ResourceMemoryTests test`；在 `agent/ai-service` 下执行 `../.venv/Scripts/python.exe -m unittest discover -s tests -p test_chat.py`。
- 真实 Redis 集成：设置 `AIOPS_TEST_REDIS_PORT`、`AIOPS_TEST_REDIS_PASSWORD` 指向专用本机 Redis；运行 `mvn -Dtest=RedisIntegrationTests test`，以及在 `agent/ai-service` 下运行 `../.venv/Scripts/python.exe -m unittest discover -s tests -p test_redis_support.py`。Java 使用 H2 隔离库，测试只清理随机 `aiops:test:*` 命名空间；未提供测试 Redis 时明确跳过集成用例。
- 前端：在 `frontend` 执行 `npm run build`。测试代码中的监控样本和模型 HTTP 替身仅用于测试，不代表真实服务器评测成绩。
