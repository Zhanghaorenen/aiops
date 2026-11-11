<script setup lang="ts">
import { ref, reactive, watch, onBeforeUnmount, onMounted } from 'vue'
import { api, errorMessage } from '../api'
import { useWorkspaceStore } from '../stores/workspace'
import { formatTime, formatBytes } from '../utils/format'
import TrendChart from './TrendChart.vue'

const props = defineProps<{ serverId?: number }>()
const store = useWorkspaceStore()
const workloads = ref<any[]>([]),
  selected = ref<any>(),
  profile = ref<any>(),
  prediction = ref<any>(),
  capacity = ref<any>()
const error = ref(''),
  busy = ref(false),
  showConfirm = ref(false)
const budget = reactive({ gpuIndex: 0, vramMb: 10240, ramMb: 8192 })
const confirmation = reactive({
  taskType: 'TRAINING',
  configurationKey: '',
  environmentKey: '',
  jobId: undefined as number | undefined,
})
let controller: AbortController | undefined
let revision = 0
const base = () => `/api/servers/${props.serverId}/training`
const columns = [
  { title: '运行标识', key: 'id' },
  { title: '状态', key: 'state' },
  { title: '预计结束', key: 'completion' },
  { title: 'GPU 显存峰值', key: 'vram' },
  { title: 'RAM（RSS 估计）', key: 'ram' },
  { title: '最近采样', key: 'sampled' },
  { title: '', key: 'actions' },
]
async function load() {
  const current = ++revision
  controller?.abort()
  controller = new AbortController()
  if (!props.serverId || !store.token || store.demo) return
  busy.value = true
  try {
    const { data } = await api.get(base() + '/workloads', {
      signal: controller.signal,
      params: { active: false },
    })
    if (current === revision) workloads.value = data
  } catch (e) {
    if (current === revision && !controller.signal.aborted)
      error.value = errorMessage(e)
  } finally {
    if (current === revision) busy.value = false
  }
}
async function inspect(row: any) {
  const current = revision,
    token = store.token,
    server = props.serverId
  try {
    const [details, stats, eta] = await Promise.all([
      api.get(`${base()}/workloads/${row.id}`),
      api.get(`${base()}/workloads/${row.id}/profile`),
      api.get(`${base()}/workloads/${row.id}/prediction`),
    ])
    if (
      current !== revision ||
      token !== store.token ||
      server !== props.serverId
    )
      return
    selected.value = details.data
    profile.value = stats.data
    prediction.value = eta.data
  } catch (e) {
    if (current === revision) error.value = errorMessage(e)
  }
}
async function advise() {
  const current = revision
  try {
    const { data } = await api.get(base() + '/capacity', {
      params: { ...budget, runId: selected.value?.id },
    })
    if (current === revision) capacity.value = data
  } catch (e) {
    if (current === revision) error.value = errorMessage(e)
  }
}
async function confirmRun() {
  const current = revision
  try {
    await api.post(
      `${base()}/workloads/${selected.value.id}/confirm`,
      confirmation,
    )
    if (current === revision) {
      showConfirm.value = false
      await load()
    }
  } catch (e) {
    if (current === revision) error.value = errorMessage(e)
  }
}
function points(key: string) {
  return [...(selected.value?.samples || [])].reverse().map((s: any) => ({
    at: s.sampled_at,
    value: JSON.parse(s.metrics_json)[key] ?? null,
  }))
}
watch(
  [() => props.serverId, () => store.token, () => store.demo],
  () => {
    controller?.abort()
    revision++
    workloads.value = []
    selected.value =
      profile.value =
      prediction.value =
      capacity.value =
        undefined
    error.value = ''
    showConfirm.value = false
    Object.assign(confirmation, {
      configurationKey: '',
      environmentKey: '',
      jobId: undefined,
    })
    void load()
  },
  { immediate: true, flush: 'sync' },
)
let refreshTimer: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  refreshTimer = setInterval(() => {
    if (store.autoRefresh && !busy.value && !store.demo && store.token)
      void load()
  }, 30000)
})
onBeforeUnmount(() => {
  if (refreshTimer) clearInterval(refreshTimer)
  controller?.abort()
  revision++
})
</script>
<template>
  <a-card title="运行记录与资源预算" class="workload-panel">
    <template #extra
      ><a-button
        :loading="busy"
        :disabled="store.demo || !serverId"
        @click="load"
        >刷新</a-button
      ></template
    >
    <a-alert
      v-if="error"
      type="error"
      :message="error"
      closable
      @close="error = ''"
    />
    <a-space wrap>
      <span>GPU</span
      ><a-input-number v-model:value="budget.gpuIndex" :min="0" />
      <span>显存 MiB</span
      ><a-input-number v-model:value="budget.vramMb" :min="0" />
      <span>RAM MiB</span
      ><a-input-number v-model:value="budget.ramMb" :min="0" />
      <a-button :disabled="store.demo || !serverId" @click="advise"
        >检查预算</a-button
      >
    </a-space>
    <div v-if="capacity" class="budget-result">
      <a-tag :color="capacity.allowed ? 'green' : 'orange'">{{
        capacity.allowed ? '当前满足' : '暂不建议'
      }}</a-tag>
      <span
        >安全预算：显存
        {{ capacity.estimatedSafeBudget.vramMb?.toFixed(0) ?? '未知' }} MiB ·
        RAM
        {{ capacity.estimatedSafeBudget.ramMb?.toFixed(0) ?? '未知' }} MiB</span
      >
      <span> · {{ formatTime(capacity.sampledAt) }}</span>
      <p
        v-for="risk in [
          ...capacity.risks,
          ...capacity.missingEvidence,
          ...capacity.warnings,
        ]"
        :key="risk"
      >
        {{ risk }}
      </p>
    </div>
    <a-table
      :data-source="workloads"
      :columns="columns"
      row-key="id"
      :scroll="{ x: 800 }"
      :pagination="{ pageSize: 5 }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'id'"
          >{{ record.id.slice(0, 8) }}
          <a-tag v-if="record.run && !record.run.confirmed"
            >候选任务</a-tag
          ></template
        >
        <template v-else-if="column.key === 'state'"
          >{{ record.state
          }}<a-tag v-if="!record.fresh" color="orange"
            >采样已过期</a-tag
          ></template
        >
        <template v-else-if="column.key === 'completion'">{{
          record.completion?.earliestAt
            ? `${formatTime(record.completion.earliestAt)} — ${formatTime(record.completion.latestAt)}`
            : '证据不足'
        }}</template>
        <template v-else-if="column.key === 'vram'">{{
          formatBytes(record.resources?.gpuMemoryBytes)
        }}</template>
        <template v-else-if="column.key === 'ram'">{{
          formatBytes(record.resources?.rssEstimateBytes)
        }}</template>
        <template v-else-if="column.key === 'sampled'">{{
          formatTime(record.sampledAt)
        }}</template>
        <template v-else-if="column.key === 'actions'"
          ><a-button
            v-if="record.privateDetails"
            size="small"
            @click="inspect(record)"
            >画像与预测</a-button
          ><span v-else>共享资源摘要</span></template
        >
      </template>
    </a-table>
    <div v-if="selected">
      <a-space
        ><strong>{{ selected.id.slice(0, 8) }}</strong
        ><a-button v-if="selected.user_id" @click="showConfirm = true"
          >确认 / 修正归组</a-button
        ></a-space
      >
      <p>
        {{
          prediction?.reason ||
          `${formatTime(prediction?.earliestAt)} — ${formatTime(prediction?.latestAt)}`
        }}
      </p>
      <p v-if="prediction?.method">
        {{ prediction.method }} · 样本 {{ prediction.sampleCount }} ·
        {{ prediction.confidence }}
      </p>
      <p v-if="prediction?.limitations">{{ prediction.limitations }}</p>
      <a-descriptions v-if="profile" size="small" :column="3">
        <a-descriptions-item label="运行次数">{{
          profile.runCount ?? 0
        }}</a-descriptions-item>
        <a-descriptions-item label="采样数">{{
          profile.sampleCount ?? 0
        }}</a-descriptions-item>
        <a-descriptions-item label="可信度">{{
          profile.confidence
        }}</a-descriptions-item>
        <a-descriptions-item label="GPU P95 / 峰值"
          >{{ formatBytes(profile.gpuMemoryBytes?.p95) }} /
          {{ formatBytes(profile.gpuMemoryBytes?.peak) }}</a-descriptions-item
        >
        <a-descriptions-item label="RAM RSS P95（估计）">{{
          formatBytes(profile.rssEstimateBytes?.p95)
        }}</a-descriptions-item>
      </a-descriptions>
      <div class="resource-grid">
        <TrendChart
          title="GPU 进程显存"
          unit="B"
          :points="points('gpuMemoryBytes')"
        /><TrendChart
          title="进程 RSS 估计"
          unit="B"
          :points="points('rssEstimateBytes')"
        />
      </div>
    </div>
  </a-card>
  <a-modal v-model:open="showConfirm" title="确认运行特征" @ok="confirmRun">
    <a-form layout="vertical">
      <a-form-item label="任务类型"
        ><a-input v-model:value="confirmation.taskType" :maxlength="80"
      /></a-form-item>
      <a-form-item label="配置标识（同模型、数据规模和参数使用同一标识）"
        ><a-input
          v-model:value="confirmation.configurationKey"
          :maxlength="160"
      /></a-form-item>
      <a-form-item label="运行环境标识"
        ><a-input v-model:value="confirmation.environmentKey" :maxlength="160"
      /></a-form-item>
      <a-form-item label="已关联的训练登记 ID（可选）"
        ><a-input-number v-model:value="confirmation.jobId" :min="1"
      /></a-form-item>
    </a-form>
  </a-modal>
</template>
<style scoped>
.workload-panel {
  margin: 16px 0;
}
.budget-result {
  margin: 16px 0;
}
.budget-result p {
  margin: 4px 0;
  color: var(--muted);
}
</style>
