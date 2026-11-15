<script setup lang="ts">
import { ref, reactive, watch, onBeforeUnmount } from 'vue'
import { api, aiApi, errorMessage } from '../api'
import { useWorkspaceStore } from '../stores/workspace'
import { formatTime } from '../utils/format'
import { useNotificationEvents } from '../composables/useNotificationEvents'
import LongTermMemoryPanel from '../components/LongTermMemoryPanel.vue'

const store = useWorkspaceStore()
const subscriptions = ref<any[]>([]),
  notifications = ref<any[]>([]),
  cases = ref<any[]>([]),
  searchResults = ref<any[]>([])
const automatic = ref(false),
  error = ref(''),
  server = ref<number>(),
  query = ref('')
const reviewCase = ref<any>(),
  resolution = ref(''),
  share = ref(false),
  reviewOpen = ref(false)
const subscription = reactive({
  gpuIndex: 0,
  requiredVramMb: 10240,
  requiredRamMb: 8192,
  cooldownSeconds: 3600,
  runId: '',
})
const candidate = reactive({
  title: '',
  symptoms: '',
  evidenceJson: '[]',
  candidateCause: '',
  incidentId: undefined as number | undefined,
})
let revision = 0
const caseBase = () => `/api/servers/${server.value}/training/incident-cases`
async function action(fn: () => Promise<void>) {
  const v = revision
  try {
    await fn()
  } catch (e) {
    if (v === revision) error.value = errorMessage(e)
  }
}
async function load() {
  if (!store.token || store.demo) return
  const v = revision
  const [s, n, p] = await Promise.all([
    api.get('/api/resources/subscriptions'),
    api.get('/api/resources/notifications'),
    api.get('/api/ai/memory/settings'),
  ])
  if (v !== revision) return
  subscriptions.value = s.data
  notifications.value = n.data
  automatic.value = p.data.automatic
  if (server.value) {
    const c = await api.get(caseBase())
    if (v === revision) cases.value = c.data
  }
}
async function subscribe() {
  await api.post('/api/resources/subscriptions', {
    ...subscription,
    serverId: server.value,
    runId: subscription.runId || null,
  })
  await load()
}
function openReview(item: any) {
  reviewCase.value = item
  resolution.value = item.resolution || ''
  share.value = item.scope === 'CLUSTER'
  reviewOpen.value = true
}
async function review(status: string) {
  await api.post(`${caseBase()}/${reviewCase.value.id}/review`, {
    status,
    resolution: resolution.value,
    share: share.value,
  })
  reviewOpen.value = false
  await load()
}
async function search() {
  const v = revision
  const { data } = await aiApi.post('/api/rag/incident-cases/search', {
    server_id: server.value,
    query: query.value,
  })
  if (v === revision) searchResults.value = data.results
}
function changeAutomatic(enabled: boolean | number | string) {
  void action(async () => {
    const v = revision
    await api.put('/api/ai/memory/settings', { automatic: !!enabled })
    if (v === revision) automatic.value = !!enabled
  })
}
function cancelSubscription(id: string) {
  void action(async () => {
    await api.delete('/api/resources/subscriptions/' + id)
    await load()
  })
}
function readNotification(id: string) {
  void action(async () => {
    await api.post('/api/resources/notifications/' + id + '/read')
    await load()
  })
}
function deleteCase(id: string) {
  void action(async () => {
    await api.delete(caseBase() + '/' + id)
    await load()
  })
}
async function saveCandidate() {
  await api.post(caseBase(), candidate)
  await load()
}
function payload(row: any) {
  try {
    return JSON.parse(row.content_json)
  } catch {
    return { message: '通知内容不可读' }
  }
}
watch(
  () => store.servers,
  (s) => {
    if (!s.some((n) => n.id === server.value)) server.value = s[0]?.id
  },
  { immediate: true },
)
watch(
  [() => store.token, () => store.demo, server],
  () => {
    revision++
    subscriptions.value = []
    notifications.value = []
    cases.value = []
    searchResults.value = []
    query.value = ''
    automatic.value = false
    error.value = ''
    reviewOpen.value = false
    reviewCase.value = undefined
    resolution.value = ''
    share.value = false
    candidate.title = candidate.symptoms = candidate.candidateCause = ''
    candidate.evidenceJson = '[]'
    subscription.runId = ''
    void action(load)
  },
  { immediate: true, flush: 'sync' },
)
onBeforeUnmount(() => revision++)
useNotificationEvents(load)
</script>
<template>
  <a-alert v-if="store.demo" message="个人记录需要登录后使用" type="info" />
  <a-alert
    v-if="error"
    :message="error"
    type="error"
    closable
    @close="error = ''"
  />
  <a-tabs>
    <a-tab-pane key="memory" tab="私人记忆">
      <a-card>
        <a-space
          ><span>自动积累已授权故障调查的候选经验</span
          ><a-switch
            :checked="automatic"
            :disabled="store.demo"
            @change="changeAutomatic"
        /></a-space>
        <LongTermMemoryPanel />
      </a-card>
    </a-tab-pane>
    <a-tab-pane key="reminders" tab="资源提醒">
      <a-card title="新建订阅"
        ><a-space wrap>
          <a-select
            v-model:value="server"
            style="min-width: 180px"
            :options="
              store.servers.map((s) => ({ value: s.id, label: s.name }))
            "
          />
          <span>GPU</span
          ><a-input-number v-model:value="subscription.gpuIndex" :min="0" />
          <span>显存 MiB</span
          ><a-input-number
            v-model:value="subscription.requiredVramMb"
            :min="0"
          />
          <span>RAM MiB</span
          ><a-input-number
            v-model:value="subscription.requiredRamMb"
            :min="0"
          />
          <span>冷却（秒）</span
          ><a-input-number
            v-model:value="subscription.cooldownSeconds"
            :min="60"
            :max="86400"
          />
          <a-input
            v-model:value="subscription.runId"
            placeholder="运行 ID（可选，用于预计释放提醒）"
            style="width: 280px"
          />
          <a-button :disabled="!server || store.demo" @click="action(subscribe)"
            >订阅</a-button
          >
        </a-space></a-card
      >
      <a-list :data-source="subscriptions"
        ><template #renderItem="{ item }"
          ><a-list-item
            >服务器 {{ item.server_id }} · GPU {{ item.gpu_index }} · 显存
            {{ item.required_vram_mb }} MiB / RAM {{ item.required_ram_mb }} MiB
            · {{ item.status
            }}<a-button
              v-if="item.status === 'ACTIVE'"
              @click="cancelSubscription(item.id)"
              >取消</a-button
            ></a-list-item
          ></template
        ></a-list
      >
      <a-card title="通知历史"
        ><template #extra
          ><a-button @click="action(load)">刷新</a-button></template
        >
        <a-list :data-source="notifications"
          ><template #renderItem="{ item }"
            ><a-list-item
              ><div>
                <a-tag
                  :color="
                    item.kind === 'ACTUALLY_AVAILABLE' ? 'green' : 'orange'
                  "
                  >{{
                    item.kind === 'ACTUALLY_AVAILABLE' ? '实际可用' : '预计可用'
                  }}</a-tag
                ><a-badge
                  v-if="!item.is_read"
                  status="processing"
                  text="未读"
                />
                <p>{{ payload(item).message }}</p>
                <span>{{ formatTime(item.created_at) }}</span>
              </div>
              <a-button v-if="!item.is_read" @click="readNotification(item.id)"
                >标为已读</a-button
              ></a-list-item
            ></template
          ></a-list
        >
      </a-card>
    </a-tab-pane>
    <a-tab-pane key="cases" tab="故障经验">
      <a-space wrap
        ><a-select
          v-model:value="server"
          style="min-width: 180px"
          :options="store.servers.map((s) => ({ value: s.id, label: s.name }))"
        /><a-input
          v-model:value="query"
          placeholder="检索已验证经验"
        /><a-button
          :disabled="!server || !query.trim() || store.demo"
          @click="action(search)"
          >检索</a-button
        ></a-space
      >
      <a-card v-for="hit in searchResults" :key="hit.case_id" :title="hit.title"
        ><p>{{ hit.symptoms }}</p>
        <p>{{ hit.resolution }}</p>
        <span
          >来源 {{ hit.case_id }} · {{ hit.status }} · v{{ hit.version }}</span
        ></a-card
      >
      <a-list :data-source="cases"
        ><template #renderItem="{ item }"
          ><a-list-item
            ><div>
              <strong>{{ item.title }}</strong> <a-tag>{{ item.status }}</a-tag
              ><a-tag>{{ item.scope }}</a-tag>
              <p>{{ item.symptoms }}</p>
              <details v-if="item.candidate_cause">
                <summary>候选分析（待核实）</summary>
                <p>{{ item.candidate_cause }}</p>
              </details>
              <p>{{ item.resolution }}</p>
            </div>
            <a-space
              ><a-button @click="openReview(item)">核实</a-button
              ><a-popconfirm title="删除经验？" @confirm="deleteCase(item.id)"
                ><a-button danger>删除</a-button></a-popconfirm
              ></a-space
            ></a-list-item
          ></template
        ></a-list
      >
      <a-collapse
        ><a-collapse-panel key="new" header="记录候选经验"
          ><a-form layout="vertical">
            <a-form-item label="标题"
              ><a-input v-model:value="candidate.title" :maxlength="160"
            /></a-form-item>
            <a-form-item label="症状（请勿填写私人日志、路径或密钥）"
              ><a-textarea v-model:value="candidate.symptoms" :maxlength="2000"
            /></a-form-item>
            <a-form-item label="证据 ID 数组"
              ><a-input v-model:value="candidate.evidenceJson"
            /></a-form-item>
            <a-form-item label="候选原因"
              ><a-textarea
                v-model:value="candidate.candidateCause"
                :maxlength="2000"
            /></a-form-item>
            <a-button
              :disabled="!server || store.demo || !candidate.title"
              @click="action(saveCandidate)"
              >保存为待验证</a-button
            >
          </a-form></a-collapse-panel
        ></a-collapse
      >
    </a-tab-pane>
  </a-tabs>
  <a-modal
    v-model:open="reviewOpen"
    title="核实故障经验"
    @ok="action(() => review('VERIFIED'))"
    ok-text="确认已验证"
  >
    <p>请依据实际处理结果核实，模型的候选原因不能单独作为证据。</p>
    <a-textarea
      v-model:value="resolution"
      placeholder="实际解决结果与验证过程"
      :maxlength="4000"
    />
    <a-checkbox v-if="store.role === 'ADMIN'" v-model:checked="share"
      >已脱敏，授权共享至当前工作空间</a-checkbox
    >
    <a-space
      ><a-button @click="action(() => review('REJECTED'))">拒绝</a-button
      ><a-button @click="action(() => review('SUPERSEDED'))"
        >标记过时</a-button
      ></a-space
    >
  </a-modal>
</template>
