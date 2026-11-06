import { createRouter, createWebHashHistory } from 'vue-router'
import LoginView from '../views/LoginView.vue'

// Keep bookmarks from the previous console working.
if (/^#[^/]/.test(location.hash)) {
  history.replaceState(
    null,
    '',
    `${location.pathname}${location.search}#/${location.hash.slice(1)}`,
  )
}

export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/overview' },
    {
      path: '/login',
      component: LoginView,
      meta: { title: '登录', public: true },
    },
    {
      path: '/register',
      component: () => import('../views/Register.vue'),
      meta: { title: '注册新账户', public: true },
    },
    {
      path: '/overview',
      component: () => import('../views/OverviewView.vue'),
      meta: {
        title: '运行总览',
        section: 'overview',
        description: '基础设施状态与资源使用情况',
      },
    },
    {
      path: '/servers',
      component: () => import('../views/ServersView.vue'),
      meta: {
        title: '服务器资源',
        section: 'servers',
        description: '主机资产、运行状态与历史指标',
      },
    },
    {
      path: '/servers/:id(\\d+)',
      component: () => import('../views/ServerDetailView.vue'),
      meta: {
        title: '服务器详情',
        section: 'servers',
        description: '主机资产、运行状态与历史指标',
      },
    },
    {
      path: '/gpu',
      component: () => import('../views/ResourceView.vue'),
      meta: {
        title: 'GPU 监控与进程',
        section: 'gpu',
        description: '设备指标、进程详情和独立历史趋势',
      },
    },
    {
      path: '/reservations',
      component: () => import('../views/ResourceView.vue'),
      meta: {
        title: 'GPU 资源预约',
        section: 'reservations',
        description: '共享工作空间时间预约 · 不强制分配硬件资源',
      },
    },
    {
      path: '/ai',
      component: () => import('../views/AiChatView.vue'),
      meta: {
        title: 'AI 智能运维',
        section: 'ai',
        description: '服务器查询与只读运维诊断',
      },
    },
    {
      path: '/knowledge',
      component: () => import('../views/KnowledgeView.vue'),
      meta: {
        title: '运维知识库',
        section: 'knowledge',
        description: '私人文档与知识检索',
      },
    },
    {
      path: '/workspace',
      component: () => import('../views/WorkspaceView.vue'),
      meta: { title: '共享工作空间', section: 'workspace' },
    },
    {
      path: '/alerts',
      component: () => import('../views/AlertsView.vue'),
      meta: { title: '运维中心', section: 'alerts' },
    },
    { path: '/incidents', redirect: '/alerts?tab=incidents' },
    {
      path: '/access',
      component: () => import('../views/AccessView.vue'),
      meta: {
        title: 'Agent 接入',
        section: 'access',
        description: '注册节点并连接 Python Agent',
      },
    },
    {
      path: '/settings',
      component: () => import('../views/AccountView.vue'),
      meta: {
        title: '账户设置',
        section: 'settings',
        description: '管理当前账户与登录凭证',
      },
    },
    {
      path: '/:pathMatch(.*)*',
      component: () => import('../views/NotFoundView.vue'),
      meta: { title: '页面不存在', section: '' },
    },
  ],
  scrollBehavior: () => ({ top: 0 }),
})
