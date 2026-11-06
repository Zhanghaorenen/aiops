<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message, type TableColumnsType } from 'ant-design-vue'
import { ArrowLeftOutlined, DownloadOutlined } from '@ant-design/icons-vue'
import { api, errorMessage, type MetricSample } from '../api'
import { demoHistory } from '../demo'
import { useWorkspaceStore } from '../stores/workspace'
import { formatBytes, formatPercent, formatTime } from '../utils/format'
import HostResourcePanel from '../components/HostResourcePanel.vue'
import GpuMonitor from '../components/GpuMonitor.vue'
import GpuUsage from '../components/GpuUsage.vue'

const store = useWorkspaceStore()
const route = useRoute()
const router = useRouter()
const server = computed(() =>
  store.servers.find((item) => item.id === Number(route.params.id)),
)
const hours = ref(6)
const samples = ref<MetricSample[]>([])
const loading = ref(false)
const error = ref('')
const tab = ref('host')
const latest = ref<any>(null)
const stats = ref<Record<string, any>>({}),
  events = ref<any[]>([]),
  rules = ref<any[]>([]),
  rawCount = ref(0)
const rawSamples = ref<MetricSample[]>([])
const records = computed(() => [...rawSamples.value].reverse())
const metricKeys = ['cpuUsage', 'memoryUsage', 'diskUsage'] as const
const colors = ['#087f78', '#4974c5', '#c58a28']
const columns: TableColumnsType<MetricSample> = [
  { title: '采集时间', key: 'collectedAt' },
  { title: 'CPU', key: 'cpuPercent' },
  { title: '内存', key: 'memoryPercent' },
  { title: '磁盘', key: 'diskPercent' },
]

watch(
  [
    () => server.value?.id,
    hours,
    () => store.demo,
    () => store.revision,
    () => store.autoRefresh,
  ],
  (_, __, onCleanup) => {
    samples.value = []
    error.value = ''
    if (!server.value) return
    const controller = new AbortController()
    let busy = false
    async function load() {
      if (busy || !server.value) return
      busy = true
      loading.value = true
      const to = new Date()
      try {
        if (store.demo) {
          samples.value = demoHistory(server.value, hours.value)
          latest.value = samples.value.at(-1)
          return
        }
        const [trend, current, alertData, ruleData] = await Promise.all([
          api.get(`/api/servers/${server.value.id}/metrics/trends`, {
            signal: controller.signal,
            params: {
              from: new Date(
                to.getTime() - hours.value * 3600000,
              ).toISOString(),
              to: to.toISOString(),
            },
          }),
          api.get(`/api/servers/${server.value.id}/metrics/latest`, {
            signal: controller.signal,
          }),
          api.get('/api/alerts', { signal: controller.signal }),
          api.get('/api/alert-rules', { signal: controller.signal }),
        ])
        if (!controller.signal.aborted) {
          samples.value = trend.data.samples
          stats.value = trend.data.stats
          rawCount.value = trend.data.rawCount
          latest.value = current.data
          events.value = alertData.data.filter(
            (e: any) => e.server_id === server.value!.id,
          )
          rules.value = ruleData.data.filter(
            (r: any) => r.server_id === server.value!.id,
          )
          error.value = ''
        }
      } catch (err) {
        if (!controller.signal.aborted) error.value = errorMessage(err)
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

watch([tab, () => server.value?.id, hours], (_, __, cleanup) => {
  if (tab.value !== 'samples' || !server.value || store.demo) return
  const controller = new AbortController()
  cleanup(() => controller.abort())
  const to = new Date()
  void api
    .get<MetricSample[]>(`/api/servers/${server.value.id}/metrics`, {
      signal: controller.signal,
      params: {
        from: new Date(+to - hours.value * 3600000).toISOString(),
        to: to.toISOString(),
        limit: 1440,
      },
    })
    .then(({ data }) => {
      if (!controller.signal.aborted) rawSamples.value = data
    })
    .catch((e) => {
      if (!controller.signal.aborted) error.value = errorMessage(e)
    })
})
function exportCsv() {
  if (!samples.value.length || !server.value) return
  const csv =
    '\ufeff时间,CPU(%),内存(%),磁盘(%)\r\n' +
    samples.value
      .map(
        (sample) =>
          `${sample.collectedAt},${sample.cpuPercent},${sample.memoryPercent},${sample.diskPercent}`,
      )
      .join('\r\n')
  const url = URL.createObjectURL(
    new Blob([csv], { type: 'text/csv;charset=utf-8;' }),
  )
  const link = document.createElement('a')
  link.href = url
  link.download = `metrics-${server.value.id}-${hours.value}h.csv`
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
  message.success('指标已导出')
}
</script>

<template>
  <template v-if="server">
    <div class="detail-heading">
      <a-space wrap
        ><a-button @click="router.push('/servers')"
          ><template #icon><ArrowLeftOutlined /></template>返回列表</a-button
        >
        <h2>{{ server.name }}</h2>
        <a-tag :color="server.status === 'ONLINE' ? 'success' : 'default'">{{
          server.status === 'ONLINE' ? '在线' : '离线'
        }}</a-tag></a-space
      ><span class="mono muted">{{ server.ip || 'IP 未上报' }}</span>
    </div>
    <a-alert
      v-if="server.status !== 'ONLINE'"
      type="warning"
      show-icon
      class="error-banner"
      :message="`服务器已离线，以下为历史采样。最后心跳：${formatTime(server.lastSeenAt)}`"
    />
    <div v-if="tab !== 'host'" class="metric-strip">
      <div v-for="(key, index) in metricKeys" :key="key">
        <span class="muted">{{
          ['CPU 使用率', '内存使用率', '磁盘使用率'][index]
        }}</span
        ><strong>{{ formatPercent(server[key]) }}</strong
        ><a-progress
          :percent="server[key] ?? 0"
          :show-info="false"
          :stroke-color="colors[index]"
        /><small class="muted">{{
          index === 0
            ? `${server.cpuCores ?? '—'} 核 · ${server.architecture || '—'}`
            : index === 1
              ? `内存总量 ${formatBytes(server.memoryBytes)}`
              : latest
                ? `${formatBytes(latest.diskUsedBytes)} / ${formatBytes(latest.diskTotalBytes)}`
                : '等待容量数据'
        }}</small>
      </div>
    </div>
    <a-segmented
      v-model:value="tab"
      style="margin: 18px 0"
      :options="[
        { label: '主机资源', value: 'host' },
        { label: 'GPU 监控与进程', value: 'gpu' },
        { label: 'GPU 时间预约', value: 'usage' },
        { label: '原始采样', value: 'samples' },
      ]"
    />
    <template v-if="tab === 'host'">
      <div class="section-heading">
        <h2>资源趋势</h2>
        <a-space wrap
          ><a-segmented
            v-model:value="hours"
            :options="[
              { label: '1 小时', value: 1 },
              { label: '6 小时', value: 6 },
              { label: '24 小时', value: 24 },
              { label: '7 天', value: 168 },
            ]" /><a-tooltip title="导出趋势汇总 CSV"
            ><a-button
              :disabled="!samples.length"
              aria-label="导出趋势汇总 CSV"
              @click="exportCsv"
              ><template #icon
                ><DownloadOutlined /></template></a-button></a-tooltip
        ></a-space>
      </div>
      <a-alert
        v-if="error"
        type="error"
        :message="error"
        class="error-banner"
      />
      <a-spin :spinning="loading"
        ><HostResourcePanel
          :samples="samples"
          :latest="latest"
          :stats="stats"
          :events="events"
          :rules="rules"
          :cores="server.cpuCores"
        />
        <p class="muted compact">
          原始 {{ rawCount }} 条采样 · 按时间桶展示，峰值统计来自原始记录
        </p></a-spin
      >
    </template>
    <a-tabs v-model:active-key="tab" :tab-bar-style="{ display: 'none' }">
      <a-tab-pane key="host" tab="主机信息">
        <a-descriptions bordered :column="{ xs: 1, sm: 2, lg: 3 }">
          <a-descriptions-item label="主机名">{{
            server.hostname
          }}</a-descriptions-item
          ><a-descriptions-item label="操作系统"
            >{{ server.osName || '—' }}
            {{ server.osVersion || '' }}</a-descriptions-item
          ><a-descriptions-item label="架构">{{
            server.architecture || '—'
          }}</a-descriptions-item>
          <a-descriptions-item label="CPU 核心">{{
            server.cpuCores ?? '—'
          }}</a-descriptions-item
          ><a-descriptions-item label="内存总量">{{
            formatBytes(server.memoryBytes)
          }}</a-descriptions-item
          ><a-descriptions-item label="节点 ID">{{
            server.id
          }}</a-descriptions-item>
          <a-descriptions-item label="IP 地址">{{
            server.ip || '—'
          }}</a-descriptions-item
          ><a-descriptions-item label="最后心跳">{{
            formatTime(server.lastSeenAt)
          }}</a-descriptions-item
          ><a-descriptions-item label="最新采样">{{
            formatTime(server.latestMetricAt)
          }}</a-descriptions-item>
        </a-descriptions>
      </a-tab-pane>
      <a-tab-pane key="usage" tab="GPU 时间预约"
        ><GpuUsage
          v-if="server && !store.demo"
          :server-id="server.id" /><a-alert
          v-else
          message="请登录真实环境登记任务"
      /></a-tab-pane>
      <a-tab-pane key="gpu" tab="GPU 监控"
        ><GpuMonitor :server-id="server.id"
      /></a-tab-pane>
      <a-tab-pane key="samples" :tab="`原始采样 (${rawSamples.length})`"
        ><a-table
          row-key="id"
          size="small"
          :columns="columns"
          :data-source="records"
          :scroll="{ x: 600 }"
          :pagination="{ pageSize: 10 }"
          ><template #bodyCell="{ column, record }">{{
            column.key === 'collectedAt'
              ? formatTime(record.collectedAt)
              : formatPercent(record[column.key])
          }}</template></a-table
        ></a-tab-pane
      >
    </a-tabs>
  </template>
  <a-spin v-else :spinning="store.loading"
    ><a-empty
      :description="
        store.error ? '暂时无法加载服务器' : '服务器不存在或尚未接入'
      "
      ><a-button @click="router.push('/servers')">返回列表</a-button></a-empty
    ></a-spin
  >
</template>
