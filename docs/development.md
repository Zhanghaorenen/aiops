# 开发说明

## 环境

- Java 17、MySQL 8、Maven Wrapper。
- 前端需要 Node.js 20.19 或更新版本和 npm。
- 后端默认 JDBC URL 指向 `localhost:3306/aiops`。数据库用户名、密码和 JWT 密钥从环境变量提供；不要将真实值提交到仓库。

PowerShell 开发环境示例（按本机账户调整）：

```powershell
$env:AIOPS_DB_USERNAME = "root"
$env:AIOPS_ADMIN_USERNAME = "admin"
$initialPassword = Read-Host 'Initial admin password (12-72 characters)' -AsSecureString
$env:AIOPS_ADMIN_PASSWORD = [System.Net.NetworkCredential]::new('', $initialPassword).Password
.\start-local.ps1
```

启动脚本询问实际 MySQL 密码并生成随机开发 JWT 密钥，同时启动 Java、FastAPI 和 Vue；已有管理员不会被覆盖。
先按 `agent/ai-service/README.md` 安装 AI 依赖。生产环境须使用独立强密码和受管理的持久签名密钥。

## 当前状态与命令

单独启动开发后端时，在项目根目录执行：

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```

`application-dev.properties` 导入本机 `data/local-run/dev-secrets.properties`。该文件被 Git 忽略，保存开发数据库密码、初始化管理员密码和随机生成的 JWT 密钥；部署到新机器时需配置这些值，或设置 `AIOPS_DB_PASSWORD`、`AIOPS_JWT_SECRET` 等环境变量。JWT 密钥必须是标准 Base64，解码后至少 32 字节，不能直接填写带连字符的普通文本。已有管理员密码不会因初始化配置而重置。

如果没有本机配置文件，可以按 README 的 PowerShell 示例设置环境变量后再运行上述命令。使用单独的后端启动命令时，不会自动启动 Vue 或 FastAPI。

当前 `pom.xml` 使用 Spring Boot 4.1.1、Java 17、WebMVC、MyBatis-Plus 3.5.18、Spring Security、Flyway、MySQL Connector；测试使用 H2 并运行与生产相同的 Flyway V1–V4。执行 `.\mvnw.cmd test`。

Flyway V1-V4 初始化认证、节点、Agent 注册凭证、主机指标并兼容旧原型表；MyBatis-Plus 负责映射及 SQL 执行，不自动建表或校验全部表结构。旧表数据不会自动转入新领域模型。管理员仅在同时配置 `AIOPS_ADMIN_USERNAME` 与 12 至 72 字符的 `AIOPS_ADMIN_PASSWORD` 时创建。注册令牌由已登录用户签发，有效期 30 分钟且只能使用一次；Agent 密钥和注册令牌只以 SHA-256 摘要存库。Agent 客户端位于 `agent/`，Vue 3 控制台位于 `frontend/`，页面与组件说明见 `frontend/README.md`。

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
- Service 的 `@Transactional` 保留，但对象修改不会自动写入数据库，必须调用 Repository 的保存方法。
- 新实体使用 `@TableName`、`@TableId(type = IdType.AUTO)`；Instant 字段使用 `UtcInstantTypeHandler`，写入 UTC `DATETIME(6)`。
- MyBatis 一级缓存设为 `STATEMENT`，防止同一事务中用户禁用或 Agent 凭证轮换后读取过期结果。

