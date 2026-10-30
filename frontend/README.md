# AIOps Vue 前端

技术栈：Vue 3、Vue Router 4、Pinia、Ant Design Vue 4、TypeScript、Vite、ECharts。
页面使用 `.vue` 单文件组件与 `<script setup lang="ts">`，不需要 JSX。

## 启动和检查

在当前 `frontend` 目录执行：

```powershell
npm ci
npm run dev
```

访问终端显示的本地地址，默认是 `http://localhost:5173`。登录页的“进入演示环境”可以直接体验示例主机，不需要后端或数据库。真实环境仍使用原管理员账号和 MySQL 中的数据。

```powershell
npm run typecheck
npm run build
npm test
```

`typecheck` 检查 TypeScript 和 Vue 模板；`build` 先检查类型，再输出到 `dist/`。
仓库根目录的 `start-local.ps1` 仍可同时启动 Java 后端和 Vue 前端，并自动选择空闲端口。

## 修改页面的位置

| 文件 | 用途 |
| --- | --- |
| `src/main.ts` | 创建 Vue 应用、注册路由、Pinia 和组件库 |
| `src/App.vue` | 侧边栏、顶部栏、自动刷新、登录失效处理 |
| `src/router/index.ts` | 路由与页面标题，使用 Hash 模式 |
| `src/stores/workspace.ts` | 登录状态、服务器列表、刷新设置和注册令牌 |
| `src/views/LoginView.vue` | 管理员登录与演示入口 |
| `src/views/OverviewView.vue` | 统计、资源排行、需要关注的主机 |
| `src/views/ServersView.vue` | 搜索、状态筛选和列表 |
| `src/views/ServerDetailView.vue` | 主机详情、时间范围、采样表和 CSV 导出 |
| `src/views/AccessView.vue` | 注册令牌和 Windows/Linux 接入命令 |
| `src/views/AccountView.vue` | 当前账户和修改密码 |
| `src/views/AiChatView.vue` | AI 聊天、取消查询、确认清空和文本显示 |
| `src/composables/useAiChat.ts` | 问答状态、上下文限制、重试和请求隔离 |
| `src/views/Register.vue` | 保留的账户登记草稿；没有公开注册入口，需要用户 JWT |
| `src/components/HostTable.vue` | 总览和资源页面共用的服务器表格 |
| `src/components/TrendChart.vue` | ECharts 曲线和图表生命周期 |
| `src/api.ts` | Axios 实例、JWT 请求头、接口类型和错误信息 |
| `src/demo.ts` | 独立演示数据，不写入数据库 |
| `src/console.css` | 整体样式和移动端布局 |

在 `.vue` 文件中，`<script setup>` 放数据和函数，`<template>` 放页面结构。
`ref()` 声明响应式数据，`computed()` 声明计算值，`v-model` 绑定输入框，`@click` 绑定点击事件。
列表页的 `search` 和 `filtered` 是一个完整的小例子，可从 `ServersView.vue` 开始阅读。
添加页面时，在 `src/views/` 新建文件，再到 `src/router/index.ts` 配置路由；导航菜单在 `App.vue`。

## 后端地址

前端请求 `/api/*`，默认由 Vite 代理到 `http://localhost:8080`。
如果后端使用其他端口，可在启动前设置：

```powershell
$env:AIOPS_PROXY_TARGET = 'http://localhost:8081'
$env:VITE_AGENT_BACKEND_URL = 'http://localhost:8081'
npm run dev
```

`VITE_AGENT_BACKEND_URL` 控制接入向导生成的 Agent 地址。
生产部署需要将 `/api` 反向代理到后端；也可以设置 `VITE_API_BASE_URL`，并配置后端允许的 CORS 来源。
环境变量变更后需要重启 Vite，生产构建则需要重新构建。

Hash 路由如 `#/servers/1` 支持直接打开和刷新，也兼容之前的 `#servers/1` 书签。
真实环境中的历史查询沿用后端每次最多 1440 条的限制，页面会显示实际返回的采样时间范围。

## AI 聊天联调

中心服务器需单独启动 Spring Boot 和 FastAPI，随后在本目录启动前端。前端开发代理配置：

```powershell
$env:AIOPS_PROXY_TARGET = 'http://127.0.0.1:8080'
$env:AIOPS_AI_PROXY_TARGET = 'http://127.0.0.1:8000'
npm run dev
```

`/api/chat` 和 `/api/ai/health` 优先转发给 FastAPI，其他 `/api` 请求仍转发给 Java。
生产环境需要配置相同的反向代理顺序。也可设置 `VITE_AI_BASE_URL` 为 AI 服务根地址，
例如 `https://ai.example.com`，并在 AI 服务配置允许的 CORS 来源。
该变量默认留空，表示同源；它独立于 `VITE_API_BASE_URL`，不会随业务地址改变。
不要把 `/api/chat` 本身写入 `VITE_AI_BASE_URL`。

登录真实账户后打开 `#/ai`。请求携带现有用户 JWT：

```json
{"question":"查看 1 号服务器的状态","history":[{"role":"user","content":"列出服务器"},{"role":"assistant","content":"上一轮回答"}]}
```

接口为 `POST /api/chat`，响应为 `{"answer":"回答文本"}`。前端按 Unicode 字符计数：
问题去除首尾空白后为 1 至 4000 字符；最多携带最近 20 条已完成消息；
历史每条内容截取前 8000 字符。问题与历史总计超过 50000 字符时，移除最旧的完整问答轮次。
本轮提问单独放在 `question`，失败和取消的轮次不写入历史。
页面中仍显示完整回答，上下文不包含工具执行消息。

401 沿用业务认证处理，清除本地令牌并返回登录；403、422、502、503、504、508
显示 FastAPI `detail`；422 校验数组只显示字段路径和错误描述。无模型配置时的 503
提示会原样显示，不会误判为认证失败。模型回答使用文本插值，不执行 HTML。
演示环境禁用输入和发送，逻辑层也会拦截请求。

新建会话和清空均会在存在对话或草稿时确认；确认会取消当前请求并清空页面内容。
取消查询会中断客户端等待、恢复输入供重试，并忽略迟到响应；服务端是否立即终止模型计算由服务端决定。
会话保存在当前页面内存中，离开聊天页或刷新浏览器后不持久化。

登录页的注册入口跳转到公开的 `#/register`，无需已有 JWT。
`POST /api/auth/register` 返回空的 201，仅创建账户；前端不会从注册响应读取或保存登录令牌。
成功确认后清理当前会话并返回登录。用户名为 3 至 15 位字母或数字，密码为 12 至 72 字符，
后端另校验 BCrypt 的 UTF-8 长度上限 72 字节。演示环境禁用注册。
页面表单规则集中在 `Register.vue` 的 `formRules`，前端同时校验 UTF-8 字节长度。
演示模式下点击“返回登录”会退出演示，之后可以正常注册。提交失败时保留具体错误提示和输入，允许重试。
完整注册审计与真实链路证据见 `../docs/registration-audit-2026-10-30.md`。
登录和注册请求不携带旧 JWT，返回 401 时不触发业务接口的登录失效处理；
其余业务 API 的 JWT 校验保持生效。更新注册权限配置后需要重启 Spring Boot。

## 聊天源码阅读顺序

1. `src/router/index.ts` 与 `src/App.vue`：`/ai` 路由和导航如何加载聊天组件，401 事件如何退出登录。
2. `src/views/AiChatView.vue`：`input` 绑定输入框，`send()` 发送，`cancel()` 取消；`watch` 和 `nextTick` 在消息变化后滚动到底部。Enter 发送会避开中文输入法组合状态。
3. `src/composables/useAiChat.ts`：`createChatRequest(question, messages)` 返回受限的 `{question, history}`；`useAiChat(access, transport)` 返回响应式消息、输入、错误及操作函数。默认 `transport` 是下一步的 `sendChat`，测试可替换成模拟函数。
4. `src/api.ts`：`sendChat(request, signal)` 通过独立 `aiApi` 发送并返回 `{answer}`；两个 Axios 实例共用 JWT 拦截器。`errorMessage(error)` 将 Java 与 FastAPI 错误转成字符串。
5. `vite.config.ts`：请求如何按路径进入两个不同的后端；生产反向代理需要采用相同顺序。
6. `tests/chat.test.mjs`：使用 Node 测试、Vue SSR 和模拟 Axios 验证状态与 HTML 转义；代理测试启动临时 HTTP 桩并实际检查转发路径，不使用真实用户凭证。

`ref`/`computed` 管理 Vue 状态，组合函数负责可测试的状态逻辑；`AbortController` 与请求身份检查一起处理取消竞态。
FastAPI 的 Pydantic 校验错误可通过 `detail` 数组返回；JWT 授权仍由 Spring Boot/FastAPI 校验。
此改动不涉及数据库迁移、Agent 采集或模型工具实现。真实 LLM 的回答和数据库权限需要由三服务联调验收。
