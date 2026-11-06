<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api, errorMessage, type GpuProcess, type GpuSnapshot } from '../api'
import { formatBytes, formatPercent, formatTime } from '../utils/format'
import MetricTrend from './TrendChart.vue'
import { useWorkspaceStore } from '../stores/workspace'

const props = defineProps<{ serverId: number }>()
const store = useWorkspaceStore()
const snapshot = ref<GpuSnapshot | null>(null)
const overview = ref<Record<string, unknown> | null>(null)
const processes = ref<GpuProcess[]>([])
const history = ref<any[]>([])
const historyStats = ref<Record<string, any>>({}),
  events = ref<any[]>([]),
  rules = ref<any[]>([]),
  search = ref(''),
  userFilter = ref(''),
  taskFilter = ref(''),
  selectedProcess = ref<any>(null)
const points = (field: string, divisor = 1) =>
  history.value.map((r) => ({
    at: r.collectedAt,
    value: r[field] == null ? null : r[field] / divisor,
  }))
const alertTimes = computed(() =>
  events.value
    .filter((e) => e.gpu_index == null || e.gpu_index === selectedGpu.value)
    .map((e) => e.first_triggered_at),
)
const threshold = (metric: string) =>
  rules.value.find((r) => r.metric === metric && r.enabled)?.threshold_value
const selectedGpu = ref<number | null>(null)
const hours = ref(6)
const loading = ref(false)
const historyLoading = ref(false)
const error = ref('')
const clock = ref(Date.now())
const freshnessTimer = window.setInterval(() => {
  clock.value = Date.now()
}, 10000)
const stale = computed(() => {
  const time = Date.parse(snapshot.value?.collectedAt || '')
  return (
    !!snapshot.value &&
    (!Number.isFinite(time) ||
      clock.value - time > 90000 ||
      time - clock.value > 60000)
  )
})

const gpus = computed(() => {
  const data = snapshot.value
  if (!data) return []
  return data.gpuUuids.map((uuid, index) => ({
    gpuIndex: data.gpuIndices[index]!,
    gpuUuid: uuid,
    name: data.gpuNames?.[index] ?? null,
    memoryTotal: data.gpuMemoryTotal?.[index] ?? null,
    memoryUsed: data.gpuMemoryUsed?.[index] ?? null,
    memoryFree: data.gpuMemoryFree?.[index] ?? null,
    utilization: data.utilization?.[index] ?? null,
    memoryUtilization: data.memoryUtilization?.[index] ?? null,
    temperature: data.temperature?.[index] ?? null,
    powerUsage: data.powerUsage?.[index] ?? null,
    fanSpeed: data.fanSpeed?.[index] ?? null,
    clockSpeed: data.clockSpeed?.[index] ?? null,
    powerLimit: data.details?.[index]?.powerLimitW ?? null,
    memoryClock: data.details?.[index]?.memoryClockMhz ?? null,
  }))
})
const statusMessage = computed(() => {
  switch (snapshot.value?.collectionStatus) {
    case 'NO_GPU':
      return 'Agent 已采样并确认该服务器没有 GPU。'
    case 'UNAVAILABLE':
      return 'GPU 采集不可用，请检查 NVIDIA 驱动和 NVML。'
    case 'FAILED':
      return '最近一次 GPU 采集失败。'
    case 'UNREPORTED':
      return '当前 Agent 尚未上报 GPU 数据，请更新并重启 Agent。'
    case 'UNKNOWN':
      return 'GPU 采集状态未知，暂不能判断服务器是否有 GPU。'
    default:
      return ''
  }
})
const statusType = computed(() =>
  snapshot.value?.collectionStatus === 'NO_GPU' ? 'info' : 'warning',
)
const gpuProcesses = computed(() =>
  processes.value.filter(
    (item) =>
      (selectedGpu.value == null || item.gpuIndex === selectedGpu.value) &&
      (!userFilter.value || item.username === userFilter.value) &&
      (!taskFilter.value || item.taskType === taskFilter.value) &&
      (!search.value ||
        [
          item.pid,
          item.processName,
          item.executablePath,
          item.workingDirectory,
        ].some((v) =>
          String(v ?? '')
            .toLowerCase()
            .includes(search.value.toLowerCase()),
        )),
  ),
)

async function loadHistory(signal: AbortSignal) {
  if (selectedGpu.value == null) {
    history.value = []
    return
  }
  historyLoading.value = true
  const to = new Date()
  try {
    const response = await api.get(
      `/api/servers/${props.serverId}/gpus/${selectedGpu.value}/trends`,
      {
        signal,
        params: {
          from: new Date(to.getTime() - hours.value * 3600000).toISOString(),
          to: to.toISOString(),
          limit: 1440,
        },
      },
    )
    if (!signal.aborted) {
      history.value = response.data.samples
      historyStats.value = response.data.stats
    }
  } catch (reason) {
    if (!signal.aborted) error.value = errorMessage(reason)
  } finally {
    if (!signal.aborted) historyLoading.value = false
  }
}

watch(
  [() => props.serverId, hours, () => store.revision, () => store.autoRefresh],
  (_, __, onCleanup) => {
    const controller = new AbortController()
    let busy = false
    const load = async () => {
      if (busy) return
      busy = true
      loading.value = true
      error.value = ''
      try {
        const [latest, summary, processList, alertData, ruleData] =
          await Promise.all([
            api.get<GpuSnapshot>(`/api/servers/${props.serverId}/gpus`, {
              signal: controller.signal,
            }),
            api.get<Record<string, unknown>>(
              `/api/servers/${props.serverId}/gpus/overview`,
              { signal: controller.signal },
            ),
            api.get<GpuProcess[]>(
              `/api/servers/${props.serverId}/gpus/processes`,
              { signal: controller.signal },
            ),
            api.get('/api/alerts', { signal: controller.signal }),
            api.get('/api/alert-rules', { signal: controller.signal }),
          ])
        if (controller.signal.aborted) return
        snapshot.value = latest.data
        overview.value = summary.data
        processes.value = processList.data
        events.value = alertData.data.filter(
          (r: any) => r.server_id === props.serverId,
        )
        rules.value = ruleData.data.filter(
          (r: any) => r.server_id === props.serverId,
        )
        if (!latest.data.gpuIndices.includes(selectedGpu.value ?? -1))
          selectedGpu.value = latest.data.gpuIndices[0] ?? null
        await loadHistory(controller.signal)
      } catch (reason) {
        if (!controller.signal.aborted) {
          snapshot.value = null
          overview.value = null
          processes.value = []
          history.value = []
          error.value = errorMessage(reason)
        }
      } finally {
        busy = false
        if (!controller.signal.aborted) loading.value = false
      }
    }
    void load()
    const timer = store.autoRefresh
      ? window.setInterval(() => void load(), 30000)
      : undefined
    onCleanup(() => {
      controller.abort()
      if (timer) clearInterval(timer)
    })
  },
  { immediate: true },
)

watch(selectedGpu, (gpuIndex, _, onCleanup) => {
  if (gpuIndex == null) return
  const controller = new AbortController()
  void loadHistory(controller.signal)
  onCleanup(() => controller.abort())
})

onBeforeUnmount(() => clearInterval(freshnessTimer))
</script>

<template>
  <a-alert
    v-if="stale"
    type="warning"
    show-icon
    message="GPU 采样已过期或时间不可信。以下为历史快照，不能作为当前资源分配依据。"
    style="margin-bottom: 12px"
  />
  <section class="gpu-monitor">
    <a-alert v-if="error" type="error" show-icon :message="error" />
    <a-alert
      v-else-if="statusMessage"
      :type="statusType"
      show-icon
      :message="statusMessage"
    />
    <a-spin :spinning="loading">
      <template v-if="snapshot && gpus.length">
        <div class="gpu-summary">
          <div>
            <span>GPU 数量</span
            ><strong>{{ overview?.gpuCount ?? gpus.length }}</strong>
          </div>
          <div>
            <span>平均利用率</span
            ><strong>{{
              formatPercent((overview?.averageGpuUtilization as number) ?? null)
            }}</strong>
          </div>
          <div>
            <span>总显存</span
            ><strong>{{
              formatBytes((overview?.totalGpuMemory as number) ?? null)
            }}</strong>
          </div>
          <div>
            <span>已用显存</span
            ><strong>{{
              formatBytes((overview?.usedGpuMemory as number) ?? null)
            }}</strong>
          </div>
        </div>
        <div class="gpu-cards">
          <button
            v-for="gpu in gpus"
            :key="gpu.gpuUuid"
            class="gpu-card"
            :class="{ selected: selectedGpu === gpu.gpuIndex }"
            @click="selectedGpu = gpu.gpuIndex"
          >
            <div class="gpu-card-head">
              <strong
                >GPU {{ gpu.gpuIndex }} · {{ gpu.name || '未知型号' }}</strong
              ><a-tag>{{ gpu.gpuUuid }}</a-tag>
            </div>
            <div class="gpu-card-grid">
              <span>GPU 利用率</span
              ><strong>{{ formatPercent(gpu.utilization) }}</strong>
              <span>显存</span
              ><strong
                >{{ formatBytes(gpu.memoryUsed) }} /
                {{ formatBytes(gpu.memoryTotal) }}</strong
              >
              <span>温度</span
              ><strong>{{
                gpu.temperature == null ? '—' : `${gpu.temperature} °C`
              }}</strong>
              <span>功耗</span
              ><strong>{{
                gpu.powerUsage == null ? '—' : `${gpu.powerUsage} W`
              }}</strong>
              <span>剩余显存 / 容量占用</span
              ><strong
                >{{ formatBytes(gpu.memoryFree) }} ·
                {{
                  gpu.memoryTotal && gpu.memoryUsed != null
                    ? ((gpu.memoryUsed / gpu.memoryTotal) * 100).toFixed(1) +
                      '%'
                    : '—'
                }}</strong
              >
              <span>功耗上限 / 显存频率</span
              ><strong
                >{{ gpu.powerLimit == null ? '—' : gpu.powerLimit + ' W' }} ·
                {{
                  gpu.memoryClock == null ? '—' : gpu.memoryClock + ' MHz'
                }}</strong
              >
              <span>显存控制器</span
              ><strong>{{ formatPercent(gpu.memoryUtilization) }}</strong>
              <span>风扇 / 时钟</span
              ><strong
                >{{ gpu.fanSpeed == null ? '—' : `${gpu.fanSpeed}%` }} ·
                {{
                  gpu.clockSpeed == null ? '—' : `${gpu.clockSpeed} MHz`
                }}</strong
              >
            </div>
            <a-progress :percent="gpu.utilization ?? 0" :show-info="false" />
          </button>
        </div>
        <div class="gpu-history-heading">
          <h3>GPU 历史趋势</h3>
          <a-space wrap>
            <a-select
              v-model:value="selectedGpu"
              style="min-width: 140px"
              aria-label="选择 GPU"
            >
              <a-select-option
                v-for="gpu in gpus"
                :key="gpu.gpuIndex"
                :value="gpu.gpuIndex"
                >GPU {{ gpu.gpuIndex }}</a-select-option
              >
            </a-select>
            <a-segmented
              v-model:value="hours"
              :options="[
                { label: '1 小时', value: 1 },
                { label: '6 小时', value: 6 },
                { label: '24 小时', value: 24 },
                { label: '7 天', value: 168 },
              ]"
            />
          </a-space>
        </div>
        <a-spin :spinning="historyLoading"
          ><div class="resource-grid">
            <MetricTrend
              title="GPU 利用率"
              unit="%"
              :points="points('utilization')"
              :stats="historyStats.utilization"
              :events="alertTimes"
            /><MetricTrend
              title="显存已用"
              unit="GiB"
              :points="points('gpuMemoryUsed', 1073741824)"
              :events="alertTimes"
            /><MetricTrend
              title="GPU 温度"
              unit="°C"
              :points="points('temperature')"
              :stats="historyStats.temperature"
              :threshold="threshold('GPU_TEMP_HIGH')"
              :events="alertTimes"
            /><MetricTrend
              title="GPU 功耗"
              unit="W"
              :points="points('powerUsage')"
              :stats="historyStats.powerUsage"
              :events="alertTimes"
            /></div
        ></a-spin>
        <div class="section-heading">
          <h3>GPU 计算进程</h3>
          <a-space wrap
            ><a-input
              v-model:value="search"
              allow-clear
              placeholder="搜索 PID、名称或路径" /><a-select
              v-model:value="userFilter"
              style="min-width: 120px"
              :options="[
                { value: '', label: '全部用户' },
                ...Array.from(
                  new Set(processes.map((p) => p.username).filter(Boolean)),
                ).map((v) => ({ value: v, label: v })),
              ]" /><a-select
              v-model:value="taskFilter"
              style="min-width: 150px"
              :options="[
                { value: '', label: '全部任务' },
                ...Array.from(
                  new Set(processes.map((p) => p.taskType).filter(Boolean)),
                ).map((v) => ({ value: v, label: v })),
              ]"
          /></a-space>
        </div>
        <a-table
          :row-key="(r: any) => `${r.gpuIndex}:${r.pid}`"
          size="small"
          :data-source="gpuProcesses"
          :pagination="{ pageSize: 8 }"
          :scroll="{ x: 1100 }"
          :columns="[
            { title: 'GPU', dataIndex: 'gpuIndex' },
            { title: 'PID', dataIndex: 'pid' },
            { title: '进程', dataIndex: 'processName' },
            { title: '用户', dataIndex: 'username' },
            { title: '类型', dataIndex: 'taskType' },
            {
              title: 'GPU 显存',
              dataIndex: 'gpuMemoryUsage',
              sorter: (a: any, b: any) =>
                (a.gpuMemoryUsage ?? -1) - (b.gpuMemoryUsage ?? -1),
            },
            { title: 'CPU', dataIndex: 'cpuPercent' },
            { title: '主机内存', dataIndex: 'memoryRssBytes' },
            { title: '线程', dataIndex: 'numThreads' },
            { title: '状态', dataIndex: 'status' },
            { title: '启动时间', dataIndex: 'startedAt' },
            { title: '详情', key: 'action' },
          ]"
          ><template #bodyCell="{ column, record }"
            ><a-button
              v-if="column.key === 'action'"
              type="link"
              @click="selectedProcess = record"
              >查看</a-button
            ><template v-else>{{
              ['gpuMemoryUsage', 'memoryRssBytes'].includes(column.dataIndex)
                ? formatBytes(record[column.dataIndex])
                : column.dataIndex === 'startedAt'
                  ? formatTime(record.startedAt)
                  : (record[column.dataIndex] ?? '—')
            }}</template></template
          ></a-table
        >
        <a-drawer
          :open="!!selectedProcess"
          title="GPU 进程详情"
          width="620"
          @close="selectedProcess = null"
          ><a-alert
            type="info"
            message="只读进程快照；权限不足或进程退出时字段可能缺失。"
          /><a-descriptions
            v-if="selectedProcess"
            bordered
            :column="1"
            style="margin-top: 16px"
            ><a-descriptions-item
              v-for="(label, key) in {
                pid: 'PID',
                processName: '进程名称',
                username: '用户',
                gpuIndex: 'GPU 编号',
                gpuUuid: 'GPU UUID',
                gpuMemoryUsage: 'GPU 显存 / 字节',
                cpuPercent: 'CPU 使用率 / %',
                memoryRssBytes: '主机内存 / 字节',
                numThreads: '线程数',
                startedAt: '启动时间',
                executablePath: '可执行文件',
                workingDirectory: '工作目录',
                status: '状态',
                collectedAt: '采集时间',
              }"
              :key="key"
              :label="label"
              >{{ selectedProcess[key] ?? '暂无数据' }}</a-descriptions-item
            ></a-descriptions
          ></a-drawer
        >
        <p class="gpu-sample-time">
          采集时间：{{ formatTime(snapshot.collectedAt) }} · 接收时间：{{
            formatTime(snapshot.receivedAt)
          }}
        </p>
      </template>
      <a-empty
        v-else-if="snapshot?.collectionStatus === 'NO_GPU'"
        description="该服务器没有 GPU"
      />
    </a-spin>
  </section>
</template>

<style scoped>
.gpu-monitor {
  display: grid;
  gap: 18px;
}
.gpu-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(120px, 1fr));
  gap: 12px;
}
.gpu-summary > div {
  display: grid;
  gap: 8px;
  padding: 16px;
  border: 1px solid var(--edge);
  border-radius: 7px;
  color: var(--muted);
}
.gpu-summary strong {
  color: #dceaf7;
  font-size: 18px;
}
.gpu-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 14px;
}
.gpu-card {
  display: grid;
  gap: 14px;
  text-align: left;
  border: 1px solid var(--edge);
  border-radius: 8px;
  background: var(--surface);
  padding: 16px;
  cursor: pointer;
}
.gpu-card.selected {
  border-color: #27cce3;
  box-shadow: 0 0 0 2px #27cce31b;
}
.gpu-card-head {
  display: grid;
  gap: 8px;
}
.gpu-card-head strong {
  font-size: 14px;
}
.gpu-card-head :deep(.ant-tag) {
  width: fit-content;
  max-width: 100%;
  white-space: normal;
  overflow-wrap: anywhere;
}
.gpu-card-grid {
  display: grid;
  grid-template-columns: minmax(90px, 1fr) 1fr;
  gap: 8px 12px;
  font-size: 12px;
}
.gpu-card-grid span {
  color: var(--muted);
}
.gpu-card-grid strong {
  text-align: right;
  overflow-wrap: anywhere;
}
.gpu-history-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}
.gpu-history-heading h3,
.gpu-monitor > h3 {
  margin: 0;
}
.gpu-history-chart {
  width: 100%;
  height: 300px;
}
.gpu-sample-time {
  margin: 0;
  color: var(--muted);
  font-size: 12px;
}
@media (max-width: 700px) {
  .gpu-summary {
    grid-template-columns: repeat(2, minmax(120px, 1fr));
  }
}
</style>
