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
