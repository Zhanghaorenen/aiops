# 开发说明

## 环境

- Java 17、MySQL 8、Maven Wrapper。
- 前端需要 Node.js 20.19 或更新版本和 npm。
- 后端默认 JDBC URL 指向 `localhost:3306/aiops`。数据库用户名、密码和 JWT 密钥从环境变量提供；不要将真实值提交到仓库。

PowerShell 开发环境示例（按本机账户调整）：

```powershell
$env:AIOPS_DB_USERNAME = "root"
$env:AIOPS_DB_PASSWORD = "123456"
$env:AIOPS_JWT_SECRET = "使用至少32字节的随机开发密钥"
$env:AIOPS_ADMIN_USERNAME = "admin"
$env:AIOPS_ADMIN_PASSWORD = "至少12个字符的管理员密码"
.\mvnw.cmd spring-boot:run
```

`123456` 是用户指定的本机开发密码示例，不是应用内默认值；生产环境须使用独立强密码和密钥管理。

## 当前状态与命令

当前 `pom.xml` 使用 Spring Boot 4.1.1、Java 17、WebMVC、Data JPA、Spring Security、Flyway、MySQL Connector；测试使用 H2。执行 `.\mvnw.cmd test`。

Flyway V1-V4 初始化认证、节点、Agent 注册凭证、主机指标并兼容旧原型表；JPA 使用 `ddl-auto=validate`。旧表数据不会自动转入新领域模型。管理员仅在同时配置 `AIOPS_ADMIN_USERNAME` 与 12 至 72 字符的 `AIOPS_ADMIN_PASSWORD` 时创建。注册令牌由已登录用户签发，有效期 30 分钟且只能使用一次；Agent 密钥和注册令牌只以 SHA-256 摘要存库。Agent 客户端位于 `agent/`，Vue 3 控制台位于 `frontend/`，页面与组件说明见 `frontend/README.md`。

## 前端

```powershell
cd frontend
npm install
npm run dev
```

Vite 默认地址为 `http://localhost:5173`，后端 CORS 默认允许该来源。通过 `AIOPS_WEB_ALLOWED_ORIGIN` 修改部署来源。前端 API 地址可通过 `VITE_API_BASE_URL` 配置。

## Agent

安装 `agent/requirements.txt` 后按 `agent/README.md` 设置后端地址和一次性注册令牌。Agent 凭证落在本机 state 文件中；对外部署时应限制该文件的读取权限并使用 HTTPS。

## 约定

- Controller 负责 HTTP 输入/输出，Service 承担业务规则，Repository 负责持久化。
- 新接口使用 DTO/VO，不直接暴露实体或凭证摘要。
- 数据库时间按 UTC 存储；HTTP 传输 ISO-8601 时间。
- 修改 schema 只通过版本化迁移；高频指标查询必须有时间边界和最大条数。

