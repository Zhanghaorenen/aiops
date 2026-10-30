<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { api, errorMessage, type GpuHistorySample, type GpuProcess, type GpuSnapshot } from '../api'
import { formatBytes, formatPercent, formatTime } from '../utils/format'
import { useWorkspaceStore } from '../stores/workspace'

const props = defineProps<{ serverId: number }>()
const store = useWorkspaceStore()
const snapshot = ref<GpuSnapshot | null>(null)
const overview = ref<Record<string, unknown> | null>(null)
const processes = ref<GpuProcess[]>([])
const history = ref<GpuHistorySample[]>([])
const selectedGpu = ref<number | null>(null)
const hours = ref(6)
const loading = ref(false)
const historyLoading = ref(false)
const error = ref('')
const chartElement = ref<HTMLDivElement | null>(null)
const chartError = ref('')
let chart: { setOption: (option: unknown) => void; resize: () => void; dispose: () => void } | null = null
let observer: ResizeObserver | null = null

const gpus = computed(() => {
  const data = snapshot.value
  if (!data) return []
  return data.gpuUuids.map((uuid, index) => ({
    gpuIndex: data.gpuIndices?.[index] ?? index,
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
  }))
})
const statusMessage = computed(() => {
  switch (snapshot.value?.collectionStatus) {
    case 'NO_GPU': return 'Agent 已采样并确认该服务器没有 GPU。'
    case 'UNAVAILABLE': return 'GPU 采集不可用，请检查 NVIDIA 驱动和 NVML。'
    case 'FAILED': return '最近一次 GPU 采集失败。'
    case 'UNREPORTED': return '当前 Agent 尚未上报 GPU 数据，请更新并重启 Agent。'
    case 'UNKNOWN': return 'GPU 采集状态未知，暂不能判断服务器是否有 GPU。'
    default: return ''
  }
})
const statusType = computed(() => snapshot.value?.collectionStatus === 'NO_GPU' ? 'info' : 'warning')
const gpuProcesses = computed(() => selectedGpu.value == null ? processes.value : processes.value.filter(item => item.gpuIndex === selectedGpu.value))

async function loadHistory(signal: AbortSignal) {
  if (selectedGpu.value == null) { history.value = []; return }
  historyLoading.value = true
  const to = new Date()
  try {
    const response = await api.get<GpuHistorySample[]>(`/api/servers/${props.serverId}/gpus/${selectedGpu.value}/history`, {
      signal,
      params: { from: new Date(to.getTime() - hours.value * 3600000).toISOString(), to: to.toISOString(), limit: 1440 },
    })
    if (!signal.aborted) history.value = response.data
  } catch (reason) {
    if (!signal.aborted) error.value = errorMessage(reason)
  } finally {
    if (!signal.aborted) historyLoading.value = false
  }
}

watch([() => props.serverId, hours, () => store.revision, () => store.autoRefresh], (_, __, onCleanup) => {
  const controller = new AbortController()
  let busy = false
  const load = async () => {
    if (busy) return
    busy = true
    loading.value = true
    error.value = ''
    try {
      const [latest, summary, processList] = await Promise.all([
        api.get<GpuSnapshot>(`/api/servers/${props.serverId}/gpus`, { signal: controller.signal }),
        api.get<Record<string, unknown>>(`/api/servers/${props.serverId}/gpus/overview`, { signal: controller.signal }),
        api.get<GpuProcess[]>(`/api/servers/${props.serverId}/gpus/processes`, { signal: controller.signal }),
      ])
      if (controller.signal.aborted) return
      snapshot.value = latest.data
      overview.value = summary.data
      processes.value = processList.data
      if (!latest.data.gpuIndices.includes(selectedGpu.value ?? -1)) selectedGpu.value = latest.data.gpuIndices[0] ?? null
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
  const timer = store.autoRefresh ? window.setInterval(() => void load(), 30000) : undefined
  onCleanup(() => { controller.abort(); if (timer) clearInterval(timer) })
}, { immediate: true })

watch(selectedGpu, (gpuIndex, _, onCleanup) => {
  if (gpuIndex == null) return
  const controller = new AbortController()
  void loadHistory(controller.signal)
  onCleanup(() => controller.abort())
})

watch(history, async (samples, _, onCleanup) => {
  let disposed = false
  onCleanup(() => { disposed = true })
  chart?.dispose()
  observer?.disconnect()
  chart = null
  observer = null
  if (!samples.length) return
  await nextTick()
  try {
    const { init } = await import('../chart')
    if (disposed || !chartElement.value) return
    chart = init(chartElement.value)
    chart.setOption({
      color: ['#087f78', '#4974c5'],
      tooltip: { trigger: 'axis', valueFormatter: (value: number) => `${Number(value).toFixed(1)}%` },
      legend: { top: 0, right: 0 }, grid: { left: 48, right: 16, top: 45, bottom: 35 },
      xAxis: { type: 'time', axisLabel: { hideOverlap: true } },
      yAxis: { type: 'value', min: 0, max: 100, axisLabel: { formatter: '{value}%' } },
      series: [
        { name: 'GPU 利用率', type: 'line', showSymbol: false, data: samples.map(row => [row.collectedAt, row.utilization]) },
        { name: '显存控制器', type: 'line', showSymbol: false, data: samples.map(row => [row.collectedAt, row.memoryUtilization]) },
      ],
    })
    observer = new ResizeObserver(() => chart?.resize())
    observer.observe(chartElement.value)
    chartError.value = ''
  } catch { if (!disposed) chartError.value = 'GPU 历史图表加载失败' }
}, { flush: 'post' })

onBeforeUnmount(() => { observer?.disconnect(); chart?.dispose() })
</script>

<template>
  <section class="gpu-monitor">
    <a-alert v-if="error" type="error" show-icon :message="error" />
    <a-alert v-else-if="statusMessage" :type="statusType" show-icon :message="statusMessage" />
    <a-spin :spinning="loading">
      <template v-if="snapshot && gpus.length">
        <div class="gpu-summary">
          <div><span>GPU 数量</span><strong>{{ overview?.gpuCount ?? gpus.length }}</strong></div>
          <div><span>平均利用率</span><strong>{{ formatPercent(overview?.averageGpuUtilization as number ?? null) }}</strong></div>
          <div><span>总显存</span><strong>{{ formatBytes(overview?.totalGpuMemory as number ?? null) }}</strong></div>
          <div><span>已用显存</span><strong>{{ formatBytes(overview?.usedGpuMemory as number ?? null) }}</strong></div>
        </div>
        <div class="gpu-cards">
          <button v-for="gpu in gpus" :key="gpu.gpuUuid" class="gpu-card" :class="{ selected: selectedGpu === gpu.gpuIndex }" @click="selectedGpu = gpu.gpuIndex">
            <div class="gpu-card-head"><strong>GPU {{ gpu.gpuIndex }} · {{ gpu.name || '未知型号' }}</strong><a-tag>{{ gpu.gpuUuid }}</a-tag></div>
            <div class="gpu-card-grid">
              <span>GPU 利用率</span><strong>{{ formatPercent(gpu.utilization) }}</strong>
              <span>显存</span><strong>{{ formatBytes(gpu.memoryUsed) }} / {{ formatBytes(gpu.memoryTotal) }}</strong>
              <span>温度</span><strong>{{ gpu.temperature == null ? '—' : `${gpu.temperature} °C` }}</strong>
              <span>功耗</span><strong>{{ gpu.powerUsage == null ? '—' : `${gpu.powerUsage} W` }}</strong>
              <span>显存控制器</span><strong>{{ formatPercent(gpu.memoryUtilization) }}</strong>
              <span>风扇 / 时钟</span><strong>{{ gpu.fanSpeed == null ? '—' : `${gpu.fanSpeed}%` }} · {{ gpu.clockSpeed == null ? '—' : `${gpu.clockSpeed} MHz` }}</strong>
            </div>
            <a-progress :percent="gpu.utilization ?? 0" :show-info="false" />
          </button>
        </div>
        <div class="gpu-history-heading">
          <h3>GPU 历史趋势</h3>
          <a-space wrap>
            <a-select v-model:value="selectedGpu" style="min-width: 140px" aria-label="选择 GPU">
              <a-select-option v-for="gpu in gpus" :key="gpu.gpuIndex" :value="gpu.gpuIndex">GPU {{ gpu.gpuIndex }}</a-select-option>
            </a-select>
            <a-segmented v-model:value="hours" :options="[{ label: '1 小时', value: 1 }, { label: '6 小时', value: 6 }, { label: '24 小时', value: 24 }]" />
          </a-space>
        </div>
        <a-spin :spinning="historyLoading"><a-alert v-if="chartError" type="error" :message="chartError" /><div v-if="history.length" ref="chartElement" class="gpu-history-chart" /><a-empty v-else description="该时间段暂无 GPU 历史采样" /></a-spin>
        <h3>GPU 计算进程</h3>
        <a-table row-key="pid" size="small" :data-source="gpuProcesses" :pagination="{ pageSize: 8 }" :scroll="{ x: 700 }" :columns="[
          { title: 'GPU', dataIndex: 'gpuIndex' }, { title: 'PID', dataIndex: 'pid' },
          { title: '进程', dataIndex: 'processName' }, { title: '用户', dataIndex: 'username' },
          { title: '显存', dataIndex: 'gpuMemoryUsage' }, { title: '类型', dataIndex: 'taskType' },
        ]"><template #bodyCell="{ column, record }">{{ column.dataIndex === 'gpuMemoryUsage' ? formatBytes(record.gpuMemoryUsage) : (record[column.dataIndex] ?? '—') }}</template></a-table>
        <p class="gpu-sample-time">采集时间：{{ formatTime(snapshot.collectedAt) }} · 接收时间：{{ formatTime(snapshot.receivedAt) }}</p>
      </template>
      <a-empty v-else-if="snapshot?.collectionStatus === 'NO_GPU'" description="该服务器没有 GPU" />
    </a-spin>
  </section>
</template>

<style scoped>
.gpu-monitor { display: grid; gap: 18px; }
.gpu-summary { display: grid; grid-template-columns: repeat(4, minmax(120px, 1fr)); gap: 12px; }
.gpu-summary > div { display: grid; gap: 8px; padding: 16px; border: 1px solid #e4e9ee; border-radius: 7px; color: #74808d; }
.gpu-summary strong { color: #25323c; font-size: 18px; }
.gpu-cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 14px; }
.gpu-card { display: grid; gap: 14px; text-align: left; border: 1px solid #e4e9ee; border-radius: 8px; background: white; padding: 16px; cursor: pointer; }
.gpu-card.selected { border-color: #087f78; box-shadow: 0 0 0 2px #087f781b; }
.gpu-card-head { display: grid; gap: 8px; }
.gpu-card-head strong { font-size: 14px; }
.gpu-card-head :deep(.ant-tag) { width: fit-content; max-width: 100%; white-space: normal; overflow-wrap: anywhere; }
.gpu-card-grid { display: grid; grid-template-columns: minmax(90px, 1fr) 1fr; gap: 8px 12px; font-size: 12px; }
.gpu-card-grid span { color: #74808d; }
.gpu-card-grid strong { text-align: right; overflow-wrap: anywhere; }
.gpu-history-heading { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; }
.gpu-history-heading h3, .gpu-monitor > h3 { margin: 0; }
.gpu-history-chart { width: 100%; height: 300px; }
.gpu-sample-time { margin: 0; color: #74808d; font-size: 12px; }
@media (max-width: 700px) { .gpu-summary { grid-template-columns: repeat(2, minmax(120px, 1fr)); } }
</style>
