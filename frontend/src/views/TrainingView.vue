<script setup lang="ts">
import {
  computed,
  ref,
  reactive,
  watch,
  onUnmounted,
  onMounted,
  nextTick,
} from 'vue'
import { api, errorMessage } from '../api'
import { useWorkspaceStore } from '../stores/workspace'
import { formatTime } from '../utils/format'
import TrendChart from '../components/TrendChart.vue'
import WorkloadPanel from '../components/WorkloadPanel.vue'
import {
  resourceSuggestionPatch,
  confidenceText,
  type ResourceSuggestion,
} from '../utils/resourceSuggestion'
const store = useWorkspaceStore(),
  server = ref<number>(),
  items = ref<any[]>([]),
  detail = ref<any>(),
  report = ref<any>(),
  error = ref(''),
  busy = ref(false),
  editing = ref<number>(),
  processes = ref<any[]>([])
const form = reactive({
  taskName: '',
  description: '',
  gpuIndex: 0,
  mode: 'SHARED',
  start: '',
  hours: 2,
  expectedVramMb: undefined as number | undefined,
  expectedRamMb: undefined as number | undefined,
  expectedCpuCores: undefined as number | undefined,
  expectedDiskReadMbps: null,
  expectedDiskWriteMbps: null,
  expectedNetworkMbps: null,
  dataset: '',
  runConfiguration: '',
  taskType: 'TRAINING',
  queueIfUnavailable: true,
})
const policy = reactive<any>({
    ramReserveMb: 2048,
    vramReserveMb: 512,
    psiThreshold: 10,
    diskReadMbps: null,
    diskWriteMbps: null,
    networkMbps: null,
  }),
  identity = reactive({ userId: undefined, linuxUsername: '' })
const base = () => `/api/servers/${server.value}/training`
const canPlan = computed(
  () => ['ADMIN', 'OPERATOR'].includes(store.role) && !store.demo,
)
const showPlanner = ref(false),
  showPresets = ref(false),
  presetsLoading = ref(false),
  planNotice = ref('')
const presets = ref<ResourceSuggestion[]>([]),
  plannerElement = ref<HTMLElement | null>(null)
let pageEpoch = 0
function openPlanner() {
  showPlanner.value = true
  if (!form.start)
    form.start = new Date(
      Date.now() + 120000 - new Date().getTimezoneOffset() * 60000,
    )
      .toISOString()
      .slice(0, 16)
  void nextTick(() =>
    plannerElement.value?.scrollIntoView({
      behavior: 'smooth',
      block: 'start',
    }),
  )
}
async function openPresets() {
  if (!server.value || store.demo) return
  const current = pageEpoch
  showPresets.value = true
  presetsLoading.value = true
  try {
    const { data } = await api.get<ResourceSuggestion[]>(
      base() + '/resource-presets',
    )
    if (current === pageEpoch) presets.value = data
  } catch (e) {
    if (current === pageEpoch) error.value = errorMessage(e)
  } finally {
    if (current === pageEpoch) presetsLoading.value = false
  }
}
function applySuggestion(source: ResourceSuggestion) {
  try {
    Object.assign(form, resourceSuggestionPatch(source))
    if (!form.taskName) form.taskName = source.label.slice(0, 160)
    if (source.taskType) form.taskType = source.taskType
    planNotice.value = `${source.source === 'CONFIRMED_PROFILE' ? '历史画像' : '单次观测参考'} · ${source.sampleCount} 个样本 · ${source.basis}`
    if (source.missingEvidence.length)
      planNotice.value += '；缺少依据的字段已留空'
    showPresets.value = false
    openPlanner()
  } catch (e) {
    error.value = errorMessage(e)
  }
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
async function load() {
  if (!server.value || store.demo) return
  const current = pageEpoch,
    target = server.value
  const [jobRows, processRows] = await Promise.all([
    api.get(`/api/servers/${target}/training/jobs`),
    api.get(`/api/servers/${target}/gpus/processes`),
  ])
  if (current !== pageEpoch) return
  items.value = jobRows.data
  processes.value = processRows.data
  if (detail.value) {
    const { data } = await api.get(
      `/api/servers/${target}/training/jobs/${detail.value.id}`,
    )
    if (current === pageEpoch) detail.value = data
  }
}
watch(
  () => store.servers,
  (s) => {
    if (!s.some((x) => x.id === server.value))
      server.value = s.find((x) => x.status === 'ONLINE')?.id ?? s[0]?.id
  },
  { immediate: true },
)
watch(server, () => {
  pageEpoch++
  items.value = []
  processes.value = []
  presets.value = []
  showPlanner.value = showPresets.value = false
  planNotice.value = ''
  detail.value = undefined
  report.value = undefined
  editing.value = undefined
  void run(load)
})
let timer: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  timer = setInterval(() => {
    if (!busy.value && server.value) void run(load)
  }, 15000)
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
})
function request() {
  const { start, hours, ...payload } = form
  const startsAt = new Date(start).toISOString()
  return {
    ...payload,
    startsAt,
    expectedEndAt: new Date(
      Date.parse(startsAt) + hours * 3600000,
    ).toISOString(),
  }
}
async function save() {
  await run(async () => {
    detail.value = (
      await api.request({
        method: editing.value ? 'PUT' : 'POST',
        url: base() + '/jobs' + (editing.value ? '/' + editing.value : ''),
        data: request(),
      })
    ).data
    editing.value = undefined
    showPlanner.value = false
    await load()
  })
}
async function select(row: any) {
  await run(async () => {
    detail.value = (await api.get(base() + '/jobs/' + row.id)).data
    report.value = undefined
  })
}
async function action(state: string) {
  await run(async () => {
    detail.value = (
      await api.post(base() + '/jobs/' + detail.value.id + '/state', { state })
    ).data
    await load()
  })
}
async function recheck() {
  await run(async () => {
    detail.value = (
      await api.post(base() + '/jobs/' + detail.value.id + '/recheck')
    ).data
    await load()
  })
}
async function link(p: any) {
  await run(async () => {
    detail.value = (
      await api.post(base() + '/jobs/' + detail.value.id + '/process', {
        pid: p.pid,
        startedAt: p.startedAt,
      })
    ).data
    await load()
  })
}
function edit() {
  const r = detail.value.request
  Object.assign(form, r, {
    start: new Date(
      Date.parse(r.startsAt) - new Date().getTimezoneOffset() * 60000,
    )
      .toISOString()
      .slice(0, 16),
    hours: (Date.parse(r.expectedEndAt) - Date.parse(r.startsAt)) / 3600000,
  })
  editing.value = detail.value.id
  openPlanner()
}
const policyLabels: Record<string, string> = {
  ramReserveMb: 'RAM 安全预留 / MiB',
  vramReserveMb: '显存安全预留 / MiB',
  psiThreshold: 'PSI 风险阈值 / %',
  diskReadMbps: '磁盘读能力 / MB/s',
  diskWriteMbps: '磁盘写能力 / MB/s',
  networkMbps: '网络能力 / MB/s',
}
function points(field: string, divisor = 1) {
  return (report.value?.processHistory ?? [])
    .slice()
    .reverse()
    .map((p: any) => ({
      at: p.collected_at,
      value:
        (p.details?.[field] ?? p[field]) == null
          ? null
          : (p.details?.[field] ?? p[field]) / divisor,
    }))
}
async function analyze() {
  await run(async () => {
    report.value = (
      await api.get(base() + '/jobs/' + detail.value.id + '/analysis')
    ).data
  })
}
async function saveIdentity() {
  await run(async () => {
    await api.put(base() + '/identity', identity)
  })
}
async function loadPolicy() {
  await run(async () => {
    const p = (await api.get(base() + '/policy')).data
    Object.assign(policy, {
      ramReserveMb: p.ram_reserve_mb,
      vramReserveMb: p.vram_reserve_mb,
      psiThreshold: p.psi_threshold,
      diskReadMbps: p.disk_read_mbps,
      diskWriteMbps: p.disk_write_mbps,
      networkMbps: p.network_mbps,
    })
  })
}
async function savePolicy() {
  await run(async () => {
    await api.put(base() + '/policy', policy)
  })
}
const latestProgress = computed(() => {
  try {
    return JSON.parse(detail.value?.progress?.[0]?.payload_json ?? '{}')
  } catch {
    return {}
  }
})
function processTree() {
  const p = report.value?.processHistory?.[0]
  if (!p) return []
  const nodes = new Map<number, any>()
  for (const c of p.details?.processTree ?? [])
    nodes.set(c.pid, {
      ...c,
      key: String(c.pid) + ':' + c.startedAt,
      title: c.pid + ' · ' + c.name,
      children: [],
    })
  const root = {
    key: 'root',
    title: String(p.pid) + ' · ' + (p.process_name ?? '根进程'),
    children: [] as any[],
  }
  for (const n of nodes.values()) {
    const parent = nodes.get(n.parentPid)
    if (parent && parent !== n) parent.children.push(n)
    else root.children.push(n)
  }
  return [root]
}
</script>
<template>
  <div class="info-hero">
    <span>目标服务器</span>
    <a-select
      v-model:value="server"
      style="min-width: 240px"
      :options="store.servers.map((s) => ({ value: s.id, label: s.name }))"
    />
    <a-button
      v-if="canPlan"
      type="primary"
      :disabled="!server"
      @click="openPlanner"
      >规划新任务</a-button
    >
  </div>
  <a-alert v-if="error" :message="error" type="error" show-icon />
  <a-alert v-if="store.demo" message="训练任务需要登录真实环境" type="info" />
  <WorkloadPanel
    :server-id="server"
    :planning-enabled="canPlan"
    @plan="applySuggestion"
    @presets="openPresets"
  />
  <div ref="plannerElement">
    <a-card
      v-if="canPlan && showPlanner"
      :title="editing ? '编辑资源计划' : '规划新任务'"
    >
      <template #extra
        ><a-space
          ><a-button @click="openPresets">按历史画像填充</a-button
          ><a-button @click="showPlanner = false">收起</a-button></a-space
        ></template
      >
      <a-alert v-if="planNotice" :message="planNotice" type="info" show-icon />
      <a-form layout="vertical" @finish="save"
        ><div class="resource-grid">
          <a-form-item label="任务名称" required
            ><a-input v-model:value="form.taskName" :maxlength="160" required
          /></a-form-item>
          <a-form-item label="GPU 编号 / 使用模式"
            ><a-space
              ><a-input-number
                v-model:value="form.gpuIndex"
                :min="0" /><a-select
                v-model:value="form.mode"
                :options="
                  ['SHARED', 'EXCLUSIVE'].map((value) => ({ value }))
                " /></a-space
          ></a-form-item>
          <a-form-item label="预约开始时间" required
            ><input
              type="datetime-local"
              class="ant-input"
              v-model="form.start"
              required /></a-form-item
          ><a-form-item label="时长（小时，最多 168）"
            ><a-input-number v-model:value="form.hours" :min="0.1" :max="168"
          /></a-form-item>
          <a-form-item label="显存峰值（MiB）" required
            ><a-input-number
              placeholder="选择历史记录或填写"
              v-model:value="form.expectedVramMb"
              :min="1" /></a-form-item
          ><a-form-item label="系统 RAM 峰值（MiB）" required
            ><a-input-number v-model:value="form.expectedRamMb" :min="1"
          /></a-form-item>
          <a-form-item label="CPU 核心"
            ><a-input-number
              v-model:value="form.expectedCpuCores"
              :min="0.1" /></a-form-item
          ><a-form-item label="数据集"
            ><a-input v-model:value="form.dataset" :maxlength="1000"
          /></a-form-item>
          <a-form-item
            v-for="[key, label] in [
              ['expectedDiskReadMbps', '磁盘读 MB/s'],
              ['expectedDiskWriteMbps', '磁盘写 MB/s'],
              ['expectedNetworkMbps', '网络 MB/s'],
            ]"
            :key="key"
            :label="label + '（可选）'"
            ><a-input-number v-model:value="(form as any)[key]" :min="0.1"
          /></a-form-item>
          <a-form-item label="任务类型"
            ><a-input v-model:value="form.taskType" :maxlength="80"
          /></a-form-item>
          <a-form-item label="任务描述"
            ><a-textarea
              v-model:value="form.description"
              :maxlength="1000" /></a-form-item
          ><a-form-item label="训练脚本 / 配置说明（仅登记，请勿填写密钥）"
            ><a-textarea
              v-model:value="form.runConfiguration"
              :maxlength="2000"
          /></a-form-item>
        </div>
        <a-space
          ><a-checkbox v-model:checked="form.queueIfUnavailable"
            >资源不足时排队</a-checkbox
          ><a-button
            type="primary"
            html-type="submit"
            :loading="busy"
            :disabled="!server"
            >提交资源检查</a-button
          ><a-button v-if="editing" @click="editing = undefined"
            >取消编辑</a-button
          ></a-space
        ></a-form
      >
    </a-card>
  </div>
  <a-collapse style="margin-top: 18px"
    ><a-collapse-panel key="plans" header="资源计划"
      ><a-card title="已登记的资源计划" style="margin-top: 18px"
        ><a-table
          :data-source="items"
          row-key="id"
          :scroll="{ x: 850 }"
          :columns="[
            { title: '任务', dataIndex: 'task_name' },
            { title: '用户 ID', dataIndex: 'user_id' },
            { title: 'GPU', dataIndex: 'gpu_index' },
            { title: '模式', dataIndex: 'mode' },
            { title: '状态', dataIndex: 'state' },
            { title: '开始', dataIndex: 'starts_at' },
            { title: '详情', key: 'view' },
          ]"
          ><template #bodyCell="{ column, record }"
            ><a-button
              v-if="column.key === 'view' && record.privateDetails !== false"
              type="link"
              @click="select(record)"
              >查看</a-button
            ><template v-else-if="column.dataIndex === 'starts_at'">{{
              formatTime(record.starts_at)
            }}</template
            ><a-tag v-else-if="column.dataIndex === 'state'">{{
              record.state
            }}</a-tag></template
          ></a-table
        ></a-card
      >
    </a-collapse-panel></a-collapse
  >
  <a-card
    v-if="detail"
    :title="detail.task_name + ' · ' + detail.state"
    style="margin-top: 18px"
  >
    <a-space
      v-if="store.role === 'ADMIN' || detail.username === store.username"
      wrap
    >
      <a-button
        v-if="
          ['QUEUED', 'REJECTED', 'RESERVED', 'PENDING'].includes(detail.state)
        "
        @click="recheck"
        :disabled="busy"
        >重新准入</a-button
      ><a-button
        v-if="
          ['QUEUED', 'REJECTED', 'RESERVED', 'PENDING'].includes(detail.state)
        "
        @click="edit"
        >编辑</a-button
      ><a-button
        v-if="detail.state === 'RESERVED'"
        @click="action('STARTING')"
        :disabled="busy"
        >启动前检查</a-button
      ><a-button
        v-if="['RUNNING', 'EXITED_UNKNOWN'].includes(detail.state)"
        @click="action('SUCCEEDED')"
        :disabled="busy"
        >标记成功</a-button
      ><a-button
        v-if="['RUNNING', 'EXITED_UNKNOWN'].includes(detail.state)"
        @click="action('FAILED')"
        :disabled="busy"
        >标记失败</a-button
      ><a-button
        v-if="
          !['SUCCEEDED', 'FAILED', 'CANCELLED', 'EXITED_UNKNOWN'].includes(
            detail.state,
          )
        "
        danger
        @click="action('CANCELLED')"
        :disabled="busy"
        >取消登记</a-button
      >
    </a-space>
    <p class="muted" style="margin: 12px 0">
      取消登记不会终止系统进程。启动检查通过后，请自行启动训练并关联最新快照中的
      PID、启动时间和 Linux 身份。
    </p>
    <a-space v-if="detail.state === 'STARTING'" wrap
      ><a-button
        v-for="p in processes.filter(
          (p) => p.gpuIndex === detail.gpu_index && p.startedAt,
        )"
        :key="p.pid"
        @click="link(p)"
        :disabled="busy"
        >关联 {{ p.username }} / PID {{ p.pid }}</a-button
      ></a-space
    >
    <a-alert
      :type="detail.admission?.allowed ? 'success' : 'warning'"
      :message="detail.admission?.allowed ? '资源检查通过' : '资源检查尚未通过'"
      :description="detail.admission?.reasons?.join('；')"
    />
    <a-descriptions style="margin-top: 16px" :column="2"
      ><a-descriptions-item label="准入基准 RAM / MiB">{{
        detail.admission?.availableRamMb?.toFixed(0)
      }}</a-descriptions-item
      ><a-descriptions-item label="准入基准显存 / MiB">{{
        detail.admission?.availableVramMb?.toFixed(0)
      }}</a-descriptions-item
      ><a-descriptions-item label="待启动 RAM 承诺 / MiB">{{
        detail.admission?.pendingRamMb
      }}</a-descriptions-item
      ><a-descriptions-item label="待启动显存承诺 / MiB">{{
        detail.admission?.pendingVramMb
      }}</a-descriptions-item></a-descriptions
    >
    <a-button @click="analyze" :disabled="busy">分析性能瓶颈</a-button>
    <a-alert
      v-for="warning in detail.admission?.warnings ?? []"
      :key="warning"
      type="warning"
      :message="warning"
      style="margin-top: 8px"
    />
    <p
      v-if="
        detail.admission?.evidence?.basis === 'FUTURE_CAPACITY_RECHECK_AT_START'
      "
      class="muted"
    >
      当前为远期容量承诺，启动前将按实时可用资源再次检查。
    </p>
    <a-descriptions
      title="训练进度（用户 / SDK 上报）"
      :column="3"
      style="margin-top: 16px"
      ><a-descriptions-item
        v-for="(label, key) in {
          epoch: 'Epoch',
          step: 'Step',
          stepTime: 'Step Time / 秒',
          samplesPerSecond: 'Samples / 秒',
          phase: '训练阶段',
          loss: 'Loss',
        }"
        :key="key"
        :label="label"
        >{{ latestProgress[key] ?? '未上报' }}</a-descriptions-item
      ></a-descriptions
    >
    <a-table
      v-if="detail.admission?.gpuUsers?.length"
      size="small"
      :data-source="detail.admission.gpuUsers"
      :pagination="false"
      :columns="[
        { title: '当前 GPU 用户', dataIndex: 'username' },
        { title: 'PID', dataIndex: 'pid' },
        { title: '显存 / 字节', dataIndex: 'gpu_memory_usage_bytes' },
      ]"
    />
    <a-collapse style="margin-top: 16px"
      ><a-collapse-panel
        key="admission"
        header="完整准入报告 / 安全预留 / 竞争任务"
      >
        <pre class="drawer-evidence">{{
          JSON.stringify(detail.admission, null, 2)
        }}</pre></a-collapse-panel
      ><a-collapse-panel key="history" header="任务事件与训练进度">
        <pre class="drawer-evidence">{{
          JSON.stringify(
            { history: detail.history, progress: detail.progress },
            null,
            2,
          )
        }}</pre>
      </a-collapse-panel></a-collapse
    >
    <div v-if="report">
      <a-tree :tree-data="processTree()" default-expand-all /><a-alert
        v-if="report.processHistory?.[0]?.details?.processTreeTruncated"
        type="info"
        message="进程树超过采集上限，仅显示前 128 个子进程"
      />
      <div class="resource-grid">
        <TrendChart
          title="关联根进程 CPU"
          unit="%（单核）"
          :points="points('cpuPercent')"
        /><TrendChart
          title="关联根进程 RAM"
          unit="MiB"
          :points="points('memoryRssBytes', 1048576)"
        /><TrendChart
          title="进程 GPU 显存"
          unit="MiB"
          :points="points('gpu_memory_usage_bytes', 1048576)"
        /><TrendChart
          title="进程磁盘读速率"
          unit="MiB/s"
          :points="points('readBytesPerSecond', 1048576)"
        />
      </div>
      <a-alert
        v-for="r in report.observations"
        :key="r"
        :message="r"
        type="warning"
        style="margin-top: 12px"
      />
      <p class="muted">{{ report.conclusion }}</p>
      <pre class="drawer-evidence">{{ JSON.stringify(report, null, 2) }}</pre>
    </div>
  </a-card>
  <a-collapse v-if="store.role === 'ADMIN'" style="margin-top: 18px"
    ><a-collapse-panel key="admin" header="管理员：资源策略与 Linux 身份绑定"
      ><a-space wrap
        ><a-input-number
          v-model:value="identity.userId"
          placeholder="平台用户 ID"
        /><a-input
          v-model:value="identity.linuxUsername"
          placeholder="Linux 用户名"
        /><a-button @click="saveIdentity">保存身份绑定</a-button></a-space
      >
      <div class="resource-grid">
        <a-form-item
          v-for="key in Object.keys(policy)"
          :key="key"
          :label="policyLabels[key]"
          ><a-input-number v-model:value="policy[key]" :min="0"
        /></a-form-item>
      </div>
      <a-space
        ><a-button @click="loadPolicy">读取策略</a-button
        ><a-button @click="savePolicy">保存策略</a-button></a-space
      ></a-collapse-panel
    ></a-collapse
  >
  <a-modal
    v-model:open="showPresets"
    title="选择历史资源"
    :footer="null"
    width="820px"
  >
    <a-alert
      v-if="!presetsLoading && !presets.length"
      type="info"
      message="暂无可复用的个人运行记录。已运行任务仍可直接分析；管理员绑定 Linux 身份后可积累个人画像。"
    />
    <a-table
      :data-source="presets"
      :loading="presetsLoading"
      row-key="id"
      :pagination="{ pageSize: 6 }"
      :scroll="{ x: 700 }"
      :columns="[
        { title: '来源', key: 'source' },
        { title: '显存 / MiB', key: 'vram' },
        { title: 'RAM / MiB', key: 'ram' },
        { title: '运行 / 采样', key: 'count' },
        { title: '', key: 'apply' },
      ]"
    >
      <template #bodyCell="{ column, record }"
        ><template v-if="column.key === 'source'"
          ><strong>{{ record.label }}</strong>
          <div>
            {{
              record.source === 'CONFIRMED_PROFILE'
                ? '已确认画像'
                : '单次运行参考'
            }}
            · {{ confidenceText(record.confidence) }}
          </div></template
        ><template v-else-if="column.key === 'vram'">{{
          record.suggested.expectedVramMb ?? '未知'
        }}</template
        ><template v-else-if="column.key === 'ram'">{{
          record.suggested.expectedRamMb ?? '未知'
        }}</template
        ><template v-else-if="column.key === 'count'"
          >{{ record.runCount }} / {{ record.sampleCount }}</template
        ><template v-else-if="column.key === 'apply'"
          ><a-button
            type="primary"
            :disabled="record.multipleGpus"
            @click="applySuggestion(record)"
            >{{
              record.multipleGpus ? '多卡记录，仅供参考' : '使用此资源'
            }}</a-button
          ></template
        ></template
      >
    </a-table>
  </a-modal>
</template>
