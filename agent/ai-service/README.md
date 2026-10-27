# 中心 AI Service（阶段 A）

本目录部署在中心服务器，连接 Spring Boot 和用户提供的 OpenAI 兼容模型服务。
被管理机器只需要 `agent/aiops_agent` 与 `agent/requirements.txt`；不要在被管理机器安装此 AI 服务或大模型。

## 安装与启动

在此目录使用 Python 3.10+：

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
$env:AIOPS_BACKEND_URL = 'http://127.0.0.1:8080'
$env:AIOPS_LLM_BASE_URL = Read-Host 'OpenAI-compatible base URL, including /v1 when required'
$env:AIOPS_LLM_MODEL = Read-Host 'Model name'
$modelKey = Read-Host 'Model API key' -AsSecureString
$env:AIOPS_LLM_API_KEY = [System.Net.NetworkCredential]::new('', $modelKey).Password
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

没有设置模型变量时，服务仍可启动，健康检查返回 `llmConfigured: false`；已认证的聊天请求返回 503 配置提示。
支持无需认证的本地兼容服务，但调用方仍须显式设置非空 `AIOPS_LLM_API_KEY` 占位值，生产密钥由用户提供。
`.env.example` 是变量参考，程序不会自动加载 `.env`。变量变化后重启 AI 服务。

仓库根目录的 `start-local.ps1` 同时启动 Java、AI、Vue，选择空闲端口，设置代理并检查三个服务是否启动。
默认优先使用本目录 `.venv`；开发时也可复用 `agent/.venv`，或传入 `-PythonExe` 指定解释器。
MySQL 和真实模型需要事先由用户部署。脚本不会下载或在被管理机器启动大模型。

## API 和认证

- `GET /api/ai/health`：进程健康与模型变量是否完整，不代表已成功访问真实模型。
- `POST /api/chat`：`Authorization: Bearer <用户JWT>`，输入 `question` 和可选 `history`，返回 `{answer}`。

```json
{
  "question": "服务器 1 现在的 CPU 和内存如何？",
  "history": [
    {"role": "user", "content": "有哪些服务器？"},
    {"role": "assistant", "content": "上一轮的真实回答"}
  ]
}
```

问题最多 4000 字符；历史最多 20 条，每条最多 8000 字符，总上下文最多 50000 字符。
只允许 `user`、`assistant` 历史角色，客户端不能传入 `system` 或 `tool`。
FastAPI 不保存 JWT 密钥，而是把当前请求的用户 JWT 交给 Java `/api/auth/me` 验证；所有 Tool 请求继续携带同一 JWT。
用户 A、B 的客户端按请求创建和释放，不共享凭证。返回值中的模型文本仍是不可信内容，Vue 使用文本插值展示。

仅注册三个只读 Tool：

| Tool | 实际 Spring Boot API | 返回 |
| --- | --- | --- |
| `list_servers` | `GET /api/servers` | 资产列表和最新状态 |
| `get_server_status` | `GET /api/servers/{server_id}` | 资产、在线状态、心跳 |
| `get_server_resource_usage` | `GET /api/servers/{server_id}/metrics/latest` | CPU/内存/磁盘和采集时间 |

历史辅助方法保留，已改为真实的 `/api/servers/{id}/metrics?from&to&limit`，本阶段不注册第四个 Tool。
旧 GPU/集群方法没有可用平台接口时明确报未实现，不读取中心服务器的 GPU 来冒充目标服务器数据。
GPU 采集代码继续保留在轻量 Agent 内，GPU 上报和数据库闭环留在阶段 B。

错误：401 缺失/失效 JWT，403 无权限，422 请求格式不合法，502 上游响应失败，503 模型未配置，504 超时，508 工具循环超限。
工具最多 4 轮、每轮最多 3 次调用，模型请求超时 30 秒、SDK 重试最多 1 次，聊天执行总期限 90 秒。
参数错误、无采样和后端临时故障会以工具错误结果交给模型；401/403 会立即终止。

## 实际测试命令

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
.\.venv\Scripts\python.exe -m compileall -q app tests
```

单元测试使用 HTTPX `MockTransport` 模拟后端和兼容模型 HTTP 响应，实际经过 OpenAI SDK、FastAPI 路由和参数校验。
这验证协议、工具执行和认证边界，不能证明真实模型的语义判断能力。

三个服务与 MySQL 启动后，可执行显式真实数据烟测：

```powershell
$env:AIOPS_VERIFY_USERNAME = Read-Host 'Existing username'
$verifyPassword = Read-Host 'Existing password' -AsSecureString
$env:AIOPS_VERIFY_PASSWORD = [System.Net.NetworkCredential]::new('', $verifyPassword).Password
.\.venv\Scripts\python.exe tests/live_smoke.py --backend-url http://localhost:8080 --ai-url http://localhost:8000 --enroll-local
Remove-Item Env:AIOPS_VERIFY_PASSWORD
```

也可使用 `AIOPS_USER_JWT`，无需再次输入账号密码。
`--enroll-local` 会为运行测试的这台真实机器注册 Agent，并向现有 MySQL 写入一条真实采样；不是假节点或模拟监控数据。
Agent 凭证存于被 Git 忽略的 `agent/.aiops-agent/stage-a-state.json`，不得公开；不传该选项则只读取已接入节点。
烟测使用真实 Java/MySQL、真实采样和模拟模型 HTTP；它会明确输出 `MOCK MODEL`，不会替代真实模型验收。

完整源码阅读顺序见仓库 `docs/stage-a-source-guide.md`。
