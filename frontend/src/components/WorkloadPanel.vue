<script setup lang="ts">
import { ref, reactive, watch, onBeforeUnmount, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { api, errorMessage } from '../api'
import { useWorkspaceStore } from '../stores/workspace'
import { formatTime, formatBytes } from '../utils/format'
import {
  resourceSuggestionPatch,
  confidenceText,
  workloadQuestion,
  type ResourceSuggestion,
} from '../utils/resourceSuggestion'
import TrendChart from './TrendChart.vue'
import RuntimeContentionPanel from './RuntimeContentionPanel.vue'
const contention = ref<any>()
const contentionBusy = ref(false)
let contentionController: AbortController | undefined

const props = defineProps<{ serverId?: number; planningEnabled?: boolean }>()
const emit = defineEmits<{ plan: [source: ResourceSuggestion]; presets: [] }>()
const store = useWorkspaceStore(),
  router = useRouter()
const workloads = ref<any[]>([]),
  selected = ref<any>(),
  analysis = ref<any>(),
  capacity = ref<any>()
const error = ref(''),
  busy = ref(false),
  analyzing = ref(false),
  showConfirm = ref(false),
  activeOnly = ref(true)
const confirmation = reactive({
  taskType: 'TRAINING',
  configurationKey: '',
  environmentKey: '',
  jobId: undefined as number | undefined,
})
let loadController: AbortController | undefined,
  analysisController: AbortController | undefined
let epoch = 0,
  loadVersion = 0
const base = () => `/api/servers/${props.serverId}/training`
async function analyzeContention() {
  if (!props.serverId || !store.token || store.demo) return
  contentionController?.abort()
  const controller = new AbortController()
  contentionController = controller
  const current = epoch
  contentionBusy.value = true
  try {
    const { data } = await api.get(base() + '/runtime-contention', { signal: controller.signal, timeout:30000 })
    if (current === epoch && !controller.signal.aborted) contention.value = data
  } catch (e) {
    if (current === epoch && !controller.signal.aborted) error.value = errorMessage(e)
  } finally {
    if (current === epoch && contentionController === controller) contentionBusy.value = false
  }
}
const columns = [
  { title: '任务 / 进程', key: 'task' },
  { title: 'GPU', key: 'gpu' },
  { title: '状态', key: 'state' },
  { title: '显存占用', key: 'vram' },
  { title: 'RAM（RSS 估计）', key: 'ram' },
  { title: 'CPU（单核 %）', key: 'cpu' },
  { title: '最近采样', key: 'sampled' },
  { title: '操作', key: 'actions', fixed: 'right' as const, width: 260 },
]
async function load() {
  const version = ++loadVersion,
    current = epoch
  loadController?.abort()
  const controller = new AbortController()
  loadController = controller
  if (!props.serverId || !store.token || store.demo) return
  busy.value = true
  try {
    const { data } = await api.get(base() + '/workloads', {
      signal: controller.signal,
      params: { active: activeOnly.value },
    })
    if (current === epoch && version === loadVersion) {
      workloads.value = data
      error.value = ''
    }
  } catch (e) {
    if (current === epoch && !controller.signal.aborted)
      error.value = errorMessage(e)
  } finally {
    if (current === epoch && version === loadVersion) busy.value = false
  }
}
async function inspect(row: any) {
  analysisController?.abort()
  const controller = new AbortController()
  analysisController = controller
  const current = epoch
  analyzing.value = true
  selected.value = row
  analysis.value = undefined
  capacity.value = undefined
  error.value = ''
  try {
    const params = row.runId
      ? { runId: row.runId }
      : { snapshotId: row.snapshotId, pid: row.pid }
    const { data } = await api.get(base() + '/workload-analysis', {
      signal: controller.signal,
      params,
    })
    if (current === epoch && !controller.signal.aborted) analysis.value = data
  } catch (e) {
    if (current === epoch && !controller.signal.aborted)
      error.value = errorMessage(e)
  } finally {
    if (current === epoch && analysisController === controller)
      analyzing.value = false
  }
}
async function suggestion(row: any): Promise<ResourceSuggestion | undefined> {
  const current = epoch
  try {
    const { data } = await api.get<ResourceSuggestion>(
      `${base()}/workloads/${row.runId}/resource-suggestion`,
    )
    if (current === epoch) return data
  } catch (e) {
    if (current === epoch) error.value = errorMessage(e)
  }
}
async function plan(row: any) {
  const source = await suggestion(row)
  if (source) emit('plan', source)
}
async function adviseSame() {
  if (!selected.value?.runId) return
  const current = epoch,
    source = await suggestion(selected.value)
  if (!source || current !== epoch) return
  try {
    const fields = resourceSuggestionPatch(source)
    if (!fields.expectedVramMb || !fields.expectedRamMb)
      throw new Error('该运行缺少显存或 RAM 采样，暂不能估算同类任务需求')
    const { data } = await api.get(base() + '/capacity', {
      params: {
        gpuIndex: fields.gpuIndex ?? 0,
        vramMb: fields.expectedVramMb,
        ramMb: fields.expectedRamMb,
        runId: selected.value.runId,
      },
    })
    if (current === epoch)
      capacity.value = { ...data, resourceSuggestion: source }
  } catch (e) {
    if (current === epoch) error.value = errorMessage(e)
  }
}
function askAi(row: any) {
  if (props.serverId)
    void router.push({
      path: '/ai',
      query: { question: workloadQuestion(props.serverId, row) },
    })
}
async function confirmRun() {
  if (!selected.value?.runId) return
  const current = epoch
  try {
    await api.post(
      `${base()}/workloads/${selected.value.runId}/confirm`,
      confirmation,
    )
    if (current === epoch) {
      showConfirm.value = false
      await inspect(selected.value)
      await load()
    }
  } catch (e) {
    if (current === epoch) error.value = errorMessage(e)
  }
}
function points(key: string, divisor = 1) {
  return [...(analysis.value?.samples || [])].reverse().map((s: any) => {
    const value = JSON.parse(s.metrics_json)[key]
    return {
      at: s.sampled_at,
      value: typeof value === 'number' ? value / divisor : null,
    }
  })
}
watch(
  [() => props.serverId, () => store.token, () => store.demo],
  () => {
    epoch++
    contentionController?.abort()
    contention.value = undefined
    contentionBusy.value = false
    loadController?.abort()
    analysisController?.abort()
    workloads.value = []
    selected.value = analysis.value = capacity.value = undefined
    error.value = ''
    analyzing.value = busy.value = showConfirm.value = false
    Object.assign(confirmation, {
      configurationKey: '',
      environmentKey: '',
      jobId: undefined,
    })
    void load()
  },
  { immediate: true, flush: 'sync' },
)
watch(activeOnly, () => void load())
let timer: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  timer = setInterval(() => {
    if (
      store.autoRefresh &&
      !busy.value &&
      !analyzing.value &&
      !store.demo &&
      store.token
    )
      void load()
  }, 30000)
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  contentionController?.abort()
  epoch++
  loadController?.abort()
  analysisController?.abort()
})
</script>
<template>
  <a-card title="已发现的 GPU 任务" class="workload-panel">
    <template #extra
      ><a-space wrap
        ><a-checkbox v-model:checked="activeOnly">仅看运行中</a-checkbox
        ><a-button :disabled="store.demo || !serverId" :loading="contentionBusy" @click="analyzeContention">资源竞争分析</a-button
        ><a-button
          v-if="planningEnabled"
          :disabled="store.demo || !serverId"
          @click="emit('presets')"
          >历史资源填充</a-button
        ><a-button
          :loading="busy"
          :disabled="store.demo || !serverId"
          @click="load"
          >刷新</a-button
        ></a-space
      ></template
    >
    <a-alert
      v-if="error"
      type="error"
      :message="error"
      closable
      @close="error = ''"
    />
    <a-table
      :data-source="workloads"
      :columns="columns"
      row-key="id"
      :scroll="{ x: 1100 }"
      :pagination="{ pageSize: 8 }"
      :loading="busy"
    >
      <template #emptyText>暂无已上报的 GPU 进程，无需先登记任务</template>
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'task'"
          ><strong>{{ record.displayName }}</strong>
          <div class="muted">
            {{ record.run?.linux_username || '共享资源摘要'
            }}<a-tag v-if="record.run && !record.run.confirmed">未归类</a-tag
            ><a-tag v-if="!record.runId">仅当前快照</a-tag>
          </div></template
        >
        <template v-else-if="column.key === 'gpu'">{{
          record.gpuIndices?.join('、') || '未知'
        }}</template>
        <template v-else-if="column.key === 'state'"
          ><a-tag :color="record.fresh ? 'cyan' : 'orange'">{{
            !record.fresh
              ? '采样过期'
              : record.state === 'RUNNING' || record.state === 'OBSERVED'
                ? '已观测运行'
                : record.state === 'MISSING'
                  ? '采样暂缺'
                  : record.state
          }}</a-tag></template
        >
        <template v-else-if="column.key === 'vram'">{{
          formatBytes(record.resources?.gpuMemoryBytes)
        }}</template>
        <template v-else-if="column.key === 'ram'">{{
          formatBytes(record.resources?.rssEstimateBytes)
        }}</template>
        <template v-else-if="column.key === 'cpu'">{{
          record.resources?.cpuPercent?.toFixed(1) ?? '—'
        }}</template>
        <template v-else-if="column.key === 'sampled'">{{
          formatTime(record.sampledAt)
        }}</template>
        <template v-else-if="column.key === 'actions'"
          ><a-space
            ><a-button size="small" type="primary" @click="inspect(record)"
              >分析</a-button
            ><a-button
              v-if="planningEnabled && record.runId && record.privateDetails"
              size="small"
              @click="plan(record)"
              >复用资源</a-button
            ><a-button size="small" @click="askAi(record)"
              >询问 AI</a-button
            ></a-space
          ></template
        >
      </template>
    </a-table>
  </a-card>
  <RuntimeContentionPanel v-if="contention" :report="contention" />
  <a-card
    v-if="selected"
    :title="selected.displayName"
    class="workload-panel"
    :loading="analyzing"
  >
    <template #extra
      ><a-space
        ><a-button
          v-if="selected.runId && selected.privateDetails"
          @click="adviseSame"
          >评估再运行一个同类任务</a-button
        ><a-button
          v-if="selected.run?.user_id && planningEnabled"
          @click="showConfirm = true"
          >标注任务</a-button
        ><a-button @click="selected = undefined">收起</a-button></a-space
      ></template
    >
    <template v-if="analysis">
      <a-alert
        :type="
          !analysis.fresh
            ? 'warning'
            : analysis.observations.length
              ? 'warning'
              : 'info'
        "
        :message="analysis.conclusion"
        show-icon
      />
      <p class="muted">采样于 {{ formatTime(analysis.sampledAt) }}</p>
      <p v-for="risk in analysis.observations" :key="risk">{{ risk }}</p>
      <a-collapse v-if="analysis.missingEvidence.length"
        ><a-collapse-panel
          key="missing"
          :header="`缺失证据（${analysis.missingEvidence.length}）`"
          ><p v-for="missing in analysis.missingEvidence" :key="missing">
            {{ missing }}
          </p></a-collapse-panel
        ></a-collapse
      >
      <p>
        {{
          analysis.prediction?.reason ||
          (analysis.prediction?.earliestAt
            ? `预计结束：${formatTime(analysis.prediction.earliestAt)} — ${formatTime(analysis.prediction.latestAt)}`
            : '尚无可靠结束时间预测')
        }}
      </p>
      <a-descriptions
        v-if="analysis.profile?.sampleCount"
        size="small"
        :column="3"
        ><a-descriptions-item label="匹配运行">{{
          analysis.profile.runCount
        }}</a-descriptions-item
        ><a-descriptions-item label="样本">{{
          analysis.profile.sampleCount
        }}</a-descriptions-item
        ><a-descriptions-item label="可信度">{{
          analysis.profile.confidence
        }}</a-descriptions-item></a-descriptions
      >
      <div v-if="analysis.samples?.length" class="resource-grid">
        <TrendChart
          title="GPU 进程显存"
          unit="MiB"
          :points="points('gpuMemoryBytes', 1048576)"
        /><TrendChart
          title="进程组 RAM（RSS 估计）"
          unit="MiB"
          :points="points('rssEstimateBytes', 1048576)"
        /><TrendChart
          title="进程组 CPU"
          unit="%（单核）"
          :points="points('cpuPercent')"
        /><TrendChart
          title="进程磁盘读取"
          unit="MiB/s"
          :points="points('readBytesPerSecond', 1048576)"
        />
      </div>
      <div v-if="capacity" class="budget-result">
        <a-tag :color="capacity.allowed ? 'green' : 'orange'">{{
          capacity.allowed ? '当前满足所估资源需求' : '暂不满足所估资源需求'
        }}</a-tag
        ><span
          >参考显存
          {{ capacity.resourceSuggestion.suggested.expectedVramMb }} MiB / RAM
          {{ capacity.resourceSuggestion.suggested.expectedRamMb }} MiB ·
          {{ confidenceText(capacity.resourceSuggestion.confidence) }}</span
        >
        <p>{{ capacity.resourceSuggestion.basis }}</p>
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
    </template>
  </a-card>
  <a-modal
    v-model:open="showConfirm"
    title="标注任务（不影响自动采样与分析）"
    @ok="confirmRun"
    ><a-form layout="vertical"
      ><a-form-item label="任务类型"
        ><a-input
          v-model:value="confirmation.taskType"
          :maxlength="80" /></a-form-item
      ><a-form-item label="配置标识"
        ><a-input
          v-model:value="confirmation.configurationKey"
          :maxlength="160" /></a-form-item
      ><a-form-item label="运行环境标识"
        ><a-input
          v-model:value="confirmation.environmentKey"
          :maxlength="160" /></a-form-item
      ><a-form-item label="已关联的训练登记 ID（可选）"
        ><a-input-number
          v-model:value="confirmation.jobId"
          :min="1" /></a-form-item></a-form
  ></a-modal>
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
