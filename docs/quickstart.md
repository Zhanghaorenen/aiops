# 从新环境运行

## 1. 准备依赖

JDK 17+、Node.js 22.12+（建议与 CI 一致用 24）、Python 3.10+、MySQL 8。以下命令均从仓库根目录开始。

```bash
python -m venv .venv
# Linux / macOS
source .venv/bin/activate
# Windows PowerShell 改用 .\.venv\Scripts\Activate.ps1
python -m pip install -r agent/requirements.txt -r agent/ai-service/requirements.txt
cd frontend
npm ci
cd ..
```

AI 依赖包含本地 Embedding 的运行库，首次安装可能较大。只看前端可以先跳过 Python 与数据库，在登录页进入标明的演示环境；示例模式不能创建真实训练任务。

## 2. 数据库与本机配置

在 MySQL 中创建 `aiops` 数据库，创建专用账号并授予该库的建表和读写权限；账号密码由你自行设置。不要复用生产数据库运行演示。

复制根目录 `.env.example` 为 `.env`，至少填写 `AIOPS_DB_USERNAME`、`AIOPS_DB_PASSWORD`、`AIOPS_JWT_SECRET`、`AIOPS_ADMIN_USERNAME`、`AIOPS_ADMIN_PASSWORD`。生成签名密钥：

```bash
python -c "import base64,secrets; print(base64.b64encode(secrets.token_bytes(32)).decode())"
```

把输出填入本机 `.env` 的 JWT 字段；不要发到 Issue。管理员密码长度为 12–72 字符。已有管理员不会因修改初始化配置而重置密码。

`scripts/run.py` 读取简单的 `KEY=value` 或带引号的值；不执行变量插值，不支持行尾注释。已有终端变量优先于 `.env`。直接使用 Maven 或 uvicorn 时不会自动读取根目录 `.env`，需要自行导出环境变量。

## 3. 三个终端分别启动

保持虚拟环境已激活，在仓库根目录分别执行：

```bash
python scripts/run.py backend
python scripts/run.py ai
python scripts/run.py frontend
```

默认 Java 8080、FastAPI 8000、Vue 5173。可检查 `/api/health` 与 `/api/ai/health`。FastAPI 无模型配置时仍能启动，但不能完成模型问答。服务在各自终端运行，Ctrl+C 停止；此脚本不启动服务器采集程序。

后端使用 `cluster,operations` 配置，Flyway 自动执行 V1–V13。已有数据库升级前先备份；不要修改已执行迁移。生产部署应配置 TLS、反向代理、持久密钥及受限网络。

## 4. 首次管理员引导

1. 先保持 `AIOPS_CLUSTER_BOOTSTRAP_ENABLED=false` 启动后端，由初始化配置创建账号。
2. 正常登录，调用 `GET /api/auth/me` 核对管理员的真实用户 ID，不能假定一定是 1。
3. 将 `.env` 中 `AIOPS_CLUSTER_BOOTSTRAP_USER_ID` 设为该 ID、`AIOPS_CLUSTER_BOOTSTRAP_ENABLED=true`，重启后端一次。
4. 确认工作空间角色为 ADMIN 后关闭该开关。已有成员或已经引导完成的工作空间不会被重复覆盖。公开注册的账号不会自动加入工作空间或提权。

## 5. 接入实际服务器

登录控制台，由 ADMIN 在 Agent 接入页签发一次性注册令牌。服务器仅需安装 `agent/requirements.txt` 并运行采集端：

```bash
cd agent
export AIOPS_BACKEND_URL=https://your-platform.example
export AIOPS_ENROLLMENT_TOKEN=your-one-time-enrollment-token
python -m aiops_agent
```

上面是占位示例，请从正常授权流程获取令牌。持久服务见 [Linux 部署说明](../agent/deploy/linux/README.md)。Agent Credential 和用户 JWT 不能互换；state 文件包含私有凭据，禁止提交。

## 6. 可选模型与 RAG

模型配置填入 `.env` 的 `AIOPS_LLM_BASE_URL`、`AIOPS_LLM_MODEL`、`AIOPS_LLM_API_KEY`，协议需兼容当前客户端。Java 与 FastAPI 的独立服务密钥分别设置为相同的 `AIOPS_AI_SERVICE_KEY`、`AIOPS_RAG_SERVICE_KEY`（每种使用独立的至少 32 字符随机值）。

RAG 需要本地 SentenceTransformer 模型目录及固定版本标识：`AIOPS_EMBEDDING_PATH`、`AIOPS_EMBEDDING_VERSION`。仓库不分发权重，也不会在运行时自动下载。配置 Qdrant 密钥后执行：

```bash
docker compose --env-file .env -f compose.rag.yaml up -d
```

重启相应服务，上传不含敏感数据的自有文档并等待 READY，再做检索测试。检索完成不等于真实模型已经验证；不同模型兼容性需要单独检查。

## 常见问题

- 401：确认用户 JWT 未过期，且未把 Agent Credential 当作用户令牌。
- 403：检查工作空间成员状态和角色；注册成功不代表具有集群访问权限。
- 训练准入拒绝：检查最新采样是否新鲜、RAM/显存安全预留与 Linux 身份映射。采集服务停止时不能承诺资源。
- 温度缺失：操作系统未必提供 CPU 传感器；NVMe 温度按存储类别展示。
- 模型缺失 / Qdrant 不可用：普通资产、监控和规则功能可独立使用；不要用模拟结果冒充真实模型回答。
