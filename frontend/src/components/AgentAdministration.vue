<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { api, errorMessage } from '../api'
const agents = ref<any[]>([]),
  tokens = ref<any[]>([]),
  busy = ref(false),
  error = ref('')
function rows(data: any[]) {
  return data.map((r) =>
    Object.fromEntries(Object.entries(r).map(([k, v]) => [k.toLowerCase(), v])),
  )
}
async function load() {
  agents.value = rows((await api.get('/api/managed-agents')).data)
  tokens.value = rows((await api.get('/api/agent/enrollment-tokens')).data)
}
async function run(fn: () => Promise<void>) {
  busy.value = true
  error.value = ''
  try {
    await fn()
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
async function disable(id: number) {
  await run(async () => {
    await api.post(`/api/managed-agents/${id}/disable`)
    await load()
  })
}
async function revoke(id: number) {
  await run(async () => {
    await api.delete(`/api/agent/enrollment-tokens/${id}`)
    await load()
  })
}
function tokenStatus(t: any) {
  if (t.revoked_at) return '已撤销'
  if (t.used_at) return '已使用'
  const end = Date.parse(t.expires_at)
  return Number.isFinite(end)
    ? end < Date.now()
      ? '已过期'
      : '未使用'
    : '请核对有效期'
}
onMounted(() => void run(load))
const timer = setInterval(() => {
  if (!busy.value) void run(load)
}, 30000)
onUnmounted(() => clearInterval(timer))
const agentColumns = [
  { title: '节点', dataIndex: 'display_name' },
  { title: 'Agent ID', dataIndex: 'id' },
  { title: '版本', dataIndex: 'version' },
  { title: '状态', dataIndex: 'status' },
  { title: '最后心跳 UTC', dataIndex: 'last_heartbeat_at' },
  { title: '操作', key: 'action' },
]
const tokenColumns = [
  { title: '令牌记录 ID', dataIndex: 'id' },
  { title: '签发人 ID', dataIndex: 'issued_by_user_id' },
  { title: '有效期 UTC', dataIndex: 'expires_at' },
  { title: '状态', key: 'status' },
  { title: '操作', key: 'action' },
]
</script>
<template>
  <a-space direction="vertical" style="width: 100%" :size="16"
    ><a-alert v-if="error" type="error" :message="error" /><a-card
      title="Agent 管理"
      ><a-alert
        type="info"
        message="停用只撤销平台上报资格，不停止服务器上的进程。重新接入需要管理员签发新令牌。"
      /><a-table
        :columns="agentColumns"
        :data-source="agents"
        row-key="id"
        :scroll="{ x: 850 }"
        ><template #bodyCell="{ column, record }"
          ><a-popconfirm
            v-if="column.key === 'action' && record.status !== 'DISABLED'"
            title="确认停用该 Agent 的平台凭据？"
            @confirm="disable(record.id)"
            ><a-button danger :disabled="busy">停用</a-button></a-popconfirm
          ></template
        ></a-table
      ></a-card
    ><a-card title="注册令牌状态"
      ><p>列表只展示记录和状态，不重复返回令牌明文。</p>
      <a-table :columns="tokenColumns" :data-source="tokens" row-key="id"
        ><template #bodyCell="{ column, record }"
          ><a-tag v-if="column.key === 'status'">{{
            tokenStatus(record)
          }}</a-tag
          ><a-popconfirm
            v-if="
              column.key === 'action' && !record.used_at && !record.revoked_at
            "
            title="撤销后不能再使用此令牌注册，是否继续？"
            @confirm="revoke(record.id)"
            ><a-button danger :disabled="busy">撤销</a-button></a-popconfirm
          ></template
        ></a-table
      ></a-card
    ></a-space
  >
</template>
