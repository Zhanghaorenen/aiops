# 注册功能完整审计与测试报告（2026-10-30）

## 结论

注册链路现已实际跑通：Vue 页面注册 → Spring Boot 返回 201 → MySQL 新增 BCrypt 密码摘要 → 返回登录 → 新账户登录工作台。

此前的问题来自多个环节约定不一致。注册最初是要求已有用户授权的账户创建草稿，后来登录页提供公开注册入口，但路由、后端授权、返回结构和前端会话处理没有同步完成。此前验证侧重路由和模拟组件，漏掉了真实表单规则以及运行中后端是否已加载修改，导致问题逐个暴露。

**另有一个实测确认的权限缺口尚未修复：新注册用户登录后能够看到已有服务器。当前查询按 USER 角色授权，尚未按用户/资产归属过滤。该项应作为独立的数据权限任务处理，不能因注册成功就认为用户数据隔离已经完成。**

审计目录：`D:\work\aiops\backend`。真实前端为 `http://localhost:5173`，后端为 `http://127.0.0.1:8080`，数据来自本机 MySQL `aiops`。

## 每类问题及原因

| 表现 | 原因 | 本次结论 |
| --- | --- | --- |
| 点击注册后地址变化，页面仍显示登录 | `/register` 缺少 `meta.public`，`App.vue` 对未登录用户显示登录页 | 前次已修；本次真实浏览器再次验证入口正常 |
| 未登录提交注册返回 401 | Spring Security 原先只放行 login，没有放行 POST register | 前次已改源码；本次确认并落地到运行进程 |
| 改了代码仍返回 401 | 本次开始时 8080 的 Java 进程启动于权限修复之前，仍使用旧类；直接访问和经过 Vite 代理均返回 401 | 已确认项目工作目录和 dev profile，重启目标进程后两条路径均正确返回 400 校验错误 |
| 注册错误提示“登录已失效” | Axios 把注册的 401 作为业务会话过期统一处理，清理状态并发出退出事件 | 前次已修；本次验证登录/注册不携带旧 JWT，注册 401 不触发业务会话退出 |
| 注册成功后出现错误令牌或状态异常 | 后端返回空的 201，旧前端却按 LoginResponse 读取 accessToken，可能保存字符串 undefined | 前次已修；注册只创建账户，不保存令牌；实际页面注册后必须登录才能进入工作台 |
| 返回登录后旧状态没有清干净 | 原注册页使用 aiops_token，与应用实际的 aiops.accessToken 不一致，并未正确清理 Pinia 状态 | 前次已改为 store.logout()；本次真实返回登录及再次登录成功 |
| 7 位密码前端允许提交，后端拒绝 | 本次源码实际仍为 min: 6，但提示和后端要求为至少 12 个字符 | 已复现并修复，页面使用集中 formRules；7/11 位密码在前端直接拦截 |
| 中文或表情密码字符数看似合格，却提交失败 | BCrypt 输入上限按 UTF-8 字节计；前端原来没有字节校验；Java length 对表情按 UTF-16 单元计数，与表单字符数不同 | 新增前端 72 字节校验，注册服务按 Unicode code point 计数；中文和表情边界均补测 |
| 输入无效时不知道哪里不对 | 全局校验异常处理将所有具体错误都替换成“请求参数无效”；瞬时 toast 不便排查 | 后端返回去重后的字段校验消息，不回显密码；注册页保留错误横幅，失败后保留输入并恢复提交 |
| 重复点击可能重复请求 | 原 busy 未在请求开始时置为 true，禁用状态读取为普通布尔值而非响应式计算 | 前次已修；本次验证请求期间禁用、逻辑层重复调用只发送一次、实际只创建一条记录 |
| 演示环境提示返回登录，但回来仍不能注册 | 返回链接仅切换路由，store.demo 没被清除 | 本次真实浏览器复现并修复；返回登录时退出演示，再进注册表单可用 |
| 单元测试通过却仍有表单问题 | 原注册测试替换了 a-form/a-form-item，未执行 Ant Design 的实际校验规则；也未确认旧后端已经重启 | 新增真实 Ant Design 校验器的 16 组边界验证，并执行浏览器和真实 MySQL 链路 |

## 实际测试结果

这些验证层有重叠，不应把数量相加理解为不同的独立业务用例。

| 验证层 | 实际执行结果 | 范围 |
| --- | --- | --- |
| 后端自动化回归 | 73 项通过，失败 0，错误 0，跳过 0 | Spring Security、真实 Service/Mapper、Flyway 与 H2；其中注册专项 16 项 |
| 前端自动化回归 | 14 项通过，失败 0 | 401 分流、空 201、重复提交、失败重试、演示退出；新增真实 Ant Design 校验器检查 |
| 实际表单规则矩阵 | 16 组输入均符合预期 | 空值、用户名边界、7/11/12/72/73 字符、24/25 个汉字、6/12/19 个表情、确认密码 |
| Vue 类型检查及生产构建 | 通过 | npm run build；仍有原有大包体积提示，不影响注册功能 |
| 真实 HTTP 链路 | 25 个检查全部符合预期 | 实际 Vite 代理 → Spring Boot → MySQL，不使用模拟后端 |
| 浏览器真实注册和登录 | 通过 | 页面点击注册、成功弹窗、返回登录、新账户进入运行总览、退出、重复注册、用原密码再次登录 |
| 数据库直接核对 | 通过 | 测试账户 ACTIVE、只创建一行、密码为 BCrypt 摘要；重复注册后 ID 和摘要未改变 |
| 旧会话失效页面行为 | 通过 | 清理测试账户使其会话失效，刷新公开注册页仍可操作，没有被误送回登录或弹出登录过期提示 |
| 演示环境退出 | 通过 | 演示时禁用注册；返回登录退出演示，再次点击注册后表单恢复可用 |

自动化命令：

```powershell
cd D:\work\aiops\backend
mvn -o test
cd frontend
npm test
npm run build
```

自动化回归中的数据库是 H2，不能称作 73 项真实 MySQL 测试。真实 MySQL 证据来自下述 HTTP 检查、真实浏览器以及 JDBC 直接核对。

## 25 个真实 HTTP 检查

- 无 JWT 查询服务器：401；无 JWT 查询当前用户：401；伪造 JWT 查询服务器：401。
- 空注册、过短/过长/含空格/含 SQL 文本的用户名：400。
- 7 位、11 位、73 位密码：400；25 个汉字、6 个表情、19 个表情密码：400。
- 重复用户名：400，明确返回“用户名已存在”；大小写变体在真实 MySQL 唯一性约束下同样返回 400。
- 携带无效旧 JWT 提交重复注册：进入注册校验并返回 400，不被错误拦成 401。
- 使用重复注册时的新密码登录：401；使用初次注册的原密码登录：200。
- 新账户 JWT 查询当前用户：200，身份与注册用户名一致。
- GET 注册接口：401，仅 POST 允许匿名注册。
- 对已存在的测试账户并发发送三个重复注册请求：均为 400。
- 仅注册不带 JWT 查询服务器：仍为 401，不存在隐式自动登录会话。

修复前，8080 直接请求和 5173 代理请求的非法注册均返回 401；重启后，同样的请求均返回 400 和“密码长度须为 12 至 72 个字符”。这证明本次持续 401 的原因包含运行版本未更新，代理目标未配错。

## 浏览器证据

注册成功：

![真实注册成功](C:/Users/xz/.codex/visualizations/2026/09/14/01a09ef0-6ecd-70a3-abf2-21ee2a6acc97/registration-audit-success.png)

重复用户名返回明确错误，表单仍可继续操作：

![重复用户名错误](C:/Users/xz/.codex/visualizations/2026/09/14/01a09ef0-6ecd-70a3-abf2-21ee2a6acc97/registration-audit-duplicate.png)

其他现场图位于同目录：registration-audit-before.png、registration-audit-login-success.png、registration-audit-stale-session.png、registration-audit-demo-recovered.png。

## 本次修改文件

| 文件 | 修改作用 |
| --- | --- |
| frontend/src/views/Register.vue | 集中表单规则、UTF-8 校验、持续错误提示、演示退出 |
| frontend/tests/chat.test.mjs | 使用真正的 Ant Design 校验器；补充失败恢复、重试和演示退出测试 |
| src/main/java/com/aiops/backend/controller/AuthController.java | 注册必填字段使用明确中文校验提示 |
| src/main/java/com/aiops/backend/service/AuthService.java | 注册密码长度按 Unicode 字符计数，保留 UTF-8 72 字节上限 |
| src/main/java/com/aiops/backend/exception/GlobalExceptionHandler.java | 保留具体字段约束错误，避免只有笼统提示 |
| src/test/java/com/aiops/backend/RegistrationTests.java | 新增具体错误、Unicode、ASCII 密码边界及登录验证 |
| frontend/README.md、docs/api-design.md | 补充校验规则、演示退出和验证说明 |
| docs/registration-audit-2026-10-30.md | 本报告 |

没有新增数据库迁移，没有修改原用户密码。其余 GPU、AI Agent 等既有工作区改动保留。

## 测试数据、服务与证据文件

- 测试开始前确认生成的测试用户名不存在，原用户数量为 1。
- 真实页面只创建了一个测试账户，ID 为 2；重复请求没有新增其他行。
- 已删除本次测试账户及其 4 条登录审计记录，原用户数量恢复为 1。清理同时核对了用户名、ID、创建时间和密码摘要，没有删除原用户、服务器或采样。
- 测试凭据及用于对比的密码摘要文件已清理，不写入报告或 Git。
- 8080 已替换为本次验证通过的 dev 后端，保持运行；5173 前端保持可用。
- 非敏感验证日志和 HTTP 结果保存在 Git 忽略的 data/local-run/：registration-audit-tests.log、registration-audit-frontend-tests.log、registration-audit-frontend-build.log、registration-audit-http-results.json。
- 真实 HTTP 脚本属于一次性辅助，复跑需重新生成独立测试账户；已清理的凭据不会保留供重复登录。

## 仍需处理和未覆盖的内容

1. **实测权限缺口：新注册的 USER 能读取原有服务器。** ServerServiceImpl.listServers() 仍读取所有节点，getServerById() 也没有按当前用户校验所有权；数据库存在 owner_user_id 字段并不表示隔离已经生效。本次没有将审计扩展成资产权限重构。
2. 本次并发 HTTP 检查针对已存在账户，未做“多个请求首次同时创建同名账户”的高并发压力测试。
3. 未验证生产网关、TLS、跨机器部署和外部网络故障。没有宣称这些场景通过。
4. 当前密码策略是字符数量与 BCrypt 字节上限，没有要求大小写/数字/符号组合，因此 12 个相同的 ASCII 字符符合当前规则。未实现泄露口令检查、注册限流、邮件验证或验证码。

## 源码阅读建议

先看 Register.vue 的 formRules 和 register()，再看 workspace.ts 的 register() 与 api.ts 的匿名认证分流。后端按 AuthController.register() → AuthService.register() → UserRepository/Mapper → sys_user 阅读。注册只返回空 201，登录才返回 JWT。最后看 RegistrationTests 和前端真实校验器测试，区分业务逻辑测试、表单规则测试与真实浏览器验证。
