<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Empty, message } from 'ant-design-vue'
import { CopyOutlined, KeyOutlined } from '@ant-design/icons-vue'
import { errorMessage } from '../api'
import { useWorkspaceStore } from '../stores/workspace'
import { formatTime } from '../utils/format'

const store = useWorkspaceStore()
const router = useRouter()
const os = ref('Windows')
const backend = ref(import.meta.env.VITE_AGENT_BACKEND_URL || import.meta.env.VITE_API_BASE_URL || `http://${location.hostname}:8080`)
const busy = ref(false)
const confirm = ref(false)
const now = ref(Date.now())
const timer = window.setInterval(() => { now.value = Date.now() }, 1000)
onUnmounted(() => clearInterval(timer))
const expired = computed(() => Boolean(store.enrollment && new Date(store.enrollment.expiresAt).getTime() <= now.value))
const command = computed(() => {
  const token = store.enrollment?.token || '<注册令牌>'
  const psBackend = backend.value.replace(/'/g, "''")
  const shBackend = backend.value.replace(/'/g, "'\"'\"'")
  return os.value === 'Windows'
    ? `cd agent\npy -m venv .venv\n.\\.venv\\Scripts\\python.exe -m pip install -r requirements.txt\n$env:AIOPS_BACKEND_URL = '${psBackend}'\n$env:AIOPS_ENROLLMENT_TOKEN = '${token}'\n.\\.venv\\Scripts\\python.exe -m aiops_agent`
    : `cd agent\npython3 -m venv .venv\n.venv/bin/python -m pip install -r requirements.txt\nexport AIOPS_BACKEND_URL='${shBackend}'\nexport AIOPS_ENROLLMENT_TOKEN='${token}'\n.venv/bin/python -m aiops_agent`
})
async function copy(value: string) {
  try { await navigator.clipboard.writeText(value); message.success('已复制') }
  catch { message.error('复制失败，请手动选择文本复制') }
}
async function createToken() {
  if (store.demo || busy.value) return
  confirm.value = false
  busy.value = true
  try { await store.issueEnrollmentToken(); message.success('已签发注册令牌') }
  catch (err) { message.error(errorMessage(err)) }
  finally { busy.value = false }
}
const columns = [{ title: '已接入节点', key: 'name' }, { title: '状态', key: 'status' }, { title: '最后心跳', key: 'lastSeenAt' }]
</script>

<template>
  <a-alert v-if="store.demo" type="warning" show-icon class="error-banner" message="签发令牌需要登录真实环境。下方可预览接入步骤与命令。" />
  <section class="access-step"><div class="step-marker">01</div><div class="step-body"><h2>签发注册令牌</h2><p class="muted">每台服务器使用一个令牌，有效期 30 分钟，成功注册后即失效。</p><a-button type="primary" :loading="busy" :disabled="store.demo" @click="store.enrollment ? confirm = true : createToken()"><template #icon><KeyOutlined /></template>{{ store.enrollment ? '签发新令牌' : '生成注册令牌' }}</a-button>
    <div v-if="store.enrollment" class="token-block"><a-textarea :value="store.enrollment.token" readonly auto-size aria-label="注册令牌" /><a-space wrap><a-button @click="copy(store.enrollment.token)"><template #icon><CopyOutlined /></template>复制令牌</a-button><a-tag :color="expired ? 'error' : 'success'">{{ expired ? '已过期' : '已签发' }}</a-tag><span class="muted compact">过期时间 {{ formatTime(store.enrollment.expiresAt) }}</span></a-space></div>
  </div></section>
  <section class="access-step"><div class="step-marker">02</div><div class="step-body"><h2>在目标服务器启动 Agent</h2><div class="command-toolbar"><a-segmented v-model:value="os" :options="['Windows', 'Linux / macOS']" /><a-tooltip title="复制启动命令"><a-button aria-label="复制启动命令" @click="copy(command)"><template #icon><CopyOutlined /></template></a-button></a-tooltip></div><a-form layout="vertical"><a-form-item label="后端地址"><a-input v-model:value="backend" placeholder="http://服务器IP:8080" /></a-form-item></a-form><pre class="command-block">{{ command }}</pre><p class="muted compact">从项目根目录执行。目标主机需要 Python 3.10+，且能够访问上方后端地址。</p></div></section>
  <section class="access-step"><div class="step-marker">03</div><div class="step-body"><h2>确认节点在线</h2><p class="muted">注册成功后，节点会自动出现在服务器列表中。Agent 默认每 15 秒采集一次，工作台每 30 秒同步。</p>
    <a-table v-if="store.servers.length" row-key="id" size="small" :columns="columns" :data-source="store.servers" :scroll="{ x: 450 }" :pagination="{ pageSize: 5, hideOnSinglePage: true }"><template #bodyCell="{ column, record }"><a-button v-if="column.key === 'name'" type="link" @click="router.push(`/servers/${record.id}`)">{{ record.name }}</a-button><a-tag v-else-if="column.key === 'status'" :color="record.status === 'ONLINE' ? 'success' : 'default'">{{ record.status === 'ONLINE' ? '在线' : '离线' }}</a-tag><span v-else>{{ formatTime(record.lastSeenAt) }}</span></template></a-table>
    <a-empty v-else :image="Empty.PRESENTED_IMAGE_SIMPLE" description="等待第一个 Agent 上线" />
  </div></section>
  <a-modal v-model:open="confirm" title="签发新的注册令牌？" ok-text="签发新令牌" cancel-text="取消" @ok="createToken">当前令牌将不再显示，请确认已经保存。已签发的令牌在过期或被使用前仍然有效。</a-modal>
</template>
