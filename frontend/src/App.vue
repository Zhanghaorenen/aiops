<script setup lang="ts">
import { computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import { ApiOutlined, DashboardOutlined, DesktopOutlined, ExperimentOutlined, LogoutOutlined, PlusOutlined, ReloadOutlined, SafetyCertificateOutlined, SettingOutlined, UserOutlined } from '@ant-design/icons-vue'
import { api, errorMessage, type Server } from './api'
import { demoServers } from './demo'
import { useWorkspaceStore } from './stores/workspace'
import { formatTime } from './utils/format'
import LoginView from './views/LoginView.vue'

const store = useWorkspaceStore()
const route = useRoute()
const router = useRouter()
const page = computed(() => String(route.meta.section || ''))
const theme = { token: { colorPrimary: '#087f78', borderRadius: 6, fontFamily: '"Segoe UI", "Microsoft YaHei", sans-serif' } }

function onMenuClick({ key }: { key: string | number }) { void router.push(`/${key}`) }

function signOut() {
  store.logout()
  void router.replace('/overview')
}
function onUnauthorized() {
  if (!store.token) return
  signOut()
  message.warning('登录已失效，请重新登录')
}
onMounted(() => window.addEventListener('aiops:unauthorized', onUnauthorized))
onUnmounted(() => window.removeEventListener('aiops:unauthorized', onUnauthorized))

watch(() => store.token, (token, _, onCleanup) => {
  if (!token) return
  const controller = new AbortController()
  onCleanup(() => controller.abort())
  void api.get<{ username: string }>('/api/auth/me', { signal: controller.signal })
    .then(({ data }) => { if (!controller.signal.aborted) store.username = data.username })
    .catch(() => undefined)
}, { immediate: true })

watch([() => store.authenticated, () => store.demo, () => store.token, () => store.autoRefresh, () => store.revision], (_, __, onCleanup) => {
  if (!store.authenticated) return
  const controller = new AbortController()
  let busy = false
  async function load() {
    if (busy) return
    busy = true
    store.loading = true
    try {
      const data = store.demo ? demoServers() : (await api.get<Server[]>('/api/servers', { signal: controller.signal })).data
      if (!controller.signal.aborted) {
        store.servers = data
        store.updatedAt = new Date().toISOString()
        store.error = ''
      }
    } catch (err) {
      if (!controller.signal.aborted) store.error = errorMessage(err)
    } finally {
      busy = false
      if (!controller.signal.aborted) store.loading = false
    }
  }
  void load()
  const timer = store.autoRefresh ? window.setInterval(() => void load(), 30000) : undefined
  onCleanup(() => { controller.abort(); if (timer) clearInterval(timer) })
}, { immediate: true })
</script>

<template>
  <a-config-provider :locale="zhCN" :theme="theme">
    <LoginView v-if="!store.authenticated" />
    <div v-else class="console-shell">
      <aside class="navigation">
        <RouterLink class="brand" to="/overview"><SafetyCertificateOutlined /><span>AIOps<small>运维工作台</small></span></RouterLink>
        <div class="nav-caption">工作空间</div>
        <a-menu :selected-keys="[page]" @click="onMenuClick">
          <a-menu-item key="overview"><template #icon><DashboardOutlined /></template>运行总览</a-menu-item>
          <a-menu-item key="servers"><template #icon><DesktopOutlined /></template>服务器资源</a-menu-item>
          <a-menu-item key="access"><template #icon><ApiOutlined /></template>Agent 接入</a-menu-item>
          <a-menu-item key="settings"><template #icon><SettingOutlined /></template>账户设置</a-menu-item>
        </a-menu>
        <div class="nav-footer"><span class="connection-dot" :class="{ bad: store.error }" />{{ store.demo ? '演示工作空间' : store.error ? '连接异常' : '本地工作空间' }}<small>Agent Platform / V0.1</small></div>
      </aside>
      <div class="workspace">
        <header class="topbar">
          <span class="breadcrumb">工作空间 <span>/</span> {{ route.meta.title }}</span>
          <a-space :size="12" wrap>
            <a-tag v-if="store.demo" color="gold">演示数据</a-tag>
            <span class="user-label"><UserOutlined /> {{ store.username || '管理员' }}</span>
            <a-tooltip title="退出登录"><a-button aria-label="退出登录" @click="signOut"><template #icon><LogoutOutlined /></template></a-button></a-tooltip>
          </a-space>
        </header>
        <main class="content">
          <a-alert v-if="store.demo" class="demo-banner" type="info" show-icon message="演示环境 · 当前为示例数据">
            <template #icon><ExperimentOutlined /></template>
            <template #action><a-button size="small" @click="signOut">登录真实环境</a-button></template>
          </a-alert>
          <div class="page-heading">
            <div><div class="eyebrow">INFRASTRUCTURE / {{ page.toUpperCase() }}</div><h1>{{ route.meta.title }}</h1><p class="muted">{{ route.meta.description }}</p></div>
            <a-space wrap>
              <template v-if="['overview', 'servers'].includes(page)">
                <label class="refresh-control"><a-switch v-model:checked="store.autoRefresh" size="small" />自动刷新</label>
                <a-tooltip title="刷新数据"><a-button :loading="store.loading" aria-label="刷新数据" @click="store.refresh"><template #icon><ReloadOutlined /></template></a-button></a-tooltip>
              </template>
              <a-button v-if="['overview', 'servers'].includes(page)" type="primary" @click="router.push('/access')"><template #icon><PlusOutlined /></template>接入服务器</a-button>
            </a-space>
          </div>
          <a-alert v-if="store.error" type="error" show-icon class="error-banner" message="数据加载失败" :description="store.error"><template #action><a-button @click="store.refresh">重试</a-button></template></a-alert>
          <RouterView />
          <footer class="content-footer"><span>AIOps Agent Platform</span><span>{{ store.updatedAt ? `数据更新于 ${formatTime(store.updatedAt)}` : '等待首次同步' }}{{ store.autoRefresh ? ' · 每 30 秒刷新' : '' }}</span></footer>
        </main>
      </div>
    </div>
  </a-config-provider>
</template>
