<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Empty } from 'ant-design-vue'
import {
  ArrowRightOutlined,
  CheckCircleOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  DesktopOutlined,
} from '@ant-design/icons-vue'
import { useWorkspaceStore } from '../stores/workspace'
import { formatPercent, formatTime } from '../utils/format'
import SummaryStat from '../components/SummaryStat.vue'
import HostTable from '../components/HostTable.vue'
import MetricTrend from '../components/TrendChart.vue'
import { api } from '../api'
import ResourceGauge from '../components/ResourceGauge.vue'
import { primaryTemperature, freshSample } from '../utils/temperature'

const store = useWorkspaceStore()
const router = useRouter()
const online = computed(() =>
  store.servers.filter((s) => s.status === 'ONLINE'),
)
function average(key: 'cpuUsage' | 'memoryUsage') {
  const available = online.value.filter((s) => s[key] != null)
  return available.length
    ? (
        available.reduce((sum, s) => sum + s[key]!, 0) / available.length
      ).toFixed(1)
    : '—'
}
const ranking = computed(() =>
  online.value
    .filter((s) => s.cpuUsage != null)
    .sort((a, b) => b.cpuUsage! - a.cpuUsage!)
    .slice(0, 5),
)
const attention = computed(() =>
  store.servers.filter(
    (s) =>
      s.status !== 'ONLINE' ||
      [s.cpuUsage, s.memoryUsage, s.diskUsage].some(
        (v) => v != null && v >= 80,
      ),
  ),
)
function selectServer(id: number) {
  void router.push(`/servers/${id}`)
}
const heatLabel = ref('主机温度'),
  hostHeat = ref<number | null>(null),
  heatHosts = ref(0)
const gpuTotal = ref<number | null>(null),
  gpuLoad = ref<number | null>(null),
  alertCount = ref<number | null>(null),
  clusterCpu = ref<{ at: string; value: number | null }[]>([]),
  clusterMemory = ref<{ at: string; value: number | null }[]>([])
watch(
  () => store.updatedAt,
  (_, __, cleanup) => {
    if (store.demo || !store.token) return
    const controller = new AbortController()
    cleanup(() => controller.abort())
    void (async () => {
      const to = new Date(),
        from = new Date(+to - 3600000).toISOString()
      const summaries = await Promise.allSettled(
        store.servers.map((s) =>
          api.get(`/api/servers/${s.id}/gpus/overview`, {
            signal: controller.signal,
          }),
        ),
      )
      const values = summaries
        .filter(
          (r): r is PromiseFulfilledResult<any> => r.status === 'fulfilled',
        )
        .map((r) => r.value.data)
      if (controller.signal.aborted) return
      gpuTotal.value = values.length
        ? values.reduce((n, r) => n + (r.gpuCount ?? 0), 0)
        : null
      const fresh = values.filter(
        (r) =>
          Date.now() - Date.parse(r.collectedAt) <= 90000 &&
          r.averageGpuUtilization != null,
      )
      gpuLoad.value = fresh.length
        ? fresh.reduce((n, r) => n + r.averageGpuUtilization, 0) / fresh.length
        : null
      try {
        const { data } = await api.get('/api/alerts', {
          signal: controller.signal,
        })
        if (!controller.signal.aborted)
          alertCount.value = data.filter(
            (e: any) => e.status !== 'RESOLVED',
          ).length
      } catch {
        if (!controller.signal.aborted) alertCount.value = null
      }
      const temperatures = await Promise.allSettled(
        store.servers.map((s) =>
          api.get(`/api/servers/${s.id}/metrics/latest`, {
            signal: controller.signal,
          }),
        ),
      )
      if (controller.signal.aborted) return
      const measured = temperatures.flatMap((r) => {
        if (r.status !== 'fulfilled' || !freshSample(r.value.data.collectedAt))
          return []
        const reading = primaryTemperature(r.value.data.details)
        return reading == null ? [] : [reading]
      })
      const priorities = { CPU: 0, BOARD: 1, STORAGE: 2, OTHER: 3 }
      measured.sort((a, b) => priorities[a.category] - priorities[b.category])
      const category = measured[0]?.category
      const matching = measured.filter((r) => r.category === category)
      hostHeat.value = matching.length
        ? Math.max(...matching.map((r) => r.value))
        : null
      heatLabel.value = measured[0]?.label ?? '主机温度'
      heatHosts.value = matching.length
      const trends = await Promise.allSettled(
        store.servers.map((s) =>
          api.get(`/api/servers/${s.id}/metrics/trends`, {
            params: { from, to: to.toISOString() },
            signal: controller.signal,
          }),
        ),
      )
      const buckets = new Map<number, any[]>()
      for (const item of trends) {
        if (item.status === 'fulfilled')
          for (const row of item.value.data.samples) {
            const time =
              Math.floor(Date.parse(row.collectedAt) / 300000) * 300000
            if (!buckets.has(time)) buckets.set(time, [])
            buckets.get(time)!.push(row)
          }
      }
      function points(field: string) {
        return [...buckets.entries()]
          .sort((a, b) => a[0] - b[0])
          .map(([t, rows]) => {
            const valid = rows.filter((r) => r[field] != null)
            return {
              at: new Date(t).toISOString(),
              value: valid.length
                ? valid.reduce((sum, r) => sum + r[field], 0) / valid.length
                : null,
            }
          })
      }
      if (!controller.signal.aborted) {
        clusterCpu.value = points('cpuPercent')
        clusterMemory.value = points('memoryPercent')
      }
    })().catch(() => {})
  },
  { immediate: true },
)
</script>

<template>
  <div class="telemetry-hero">
    <div>
      <span class="eyebrow">CLUSTER TELEMETRY</span>
      <h2>资源运行态势</h2>
      <p>主机、GPU 与温度传感器，汇集于同一工作台。</p>
    </div>
    <a-space wrap
      ><span class="live-pill"
        ><i />{{ store.autoRefresh ? '自动刷新 · 30 秒' : '手动刷新' }}</span
      ><a-button @click="router.push('/reservations')"
        >GPU 预约日历 <ArrowRightOutlined /></a-button
    ></a-space>
  </div>
  <div class="summary-grid">
    <SummaryStat
      label="服务器总量"
      :value="store.servers.length"
      suffix="台"
      :note="`${store.servers.length - online.length} 台离线`"
      ><DesktopOutlined
    /></SummaryStat>
    <SummaryStat
      label="在线服务器"
      :value="online.length"
      suffix="台"
      note="根据 Agent 心跳判断"
      ><CheckCircleOutlined
    /></SummaryStat>
    <SummaryStat
      label="已读取 GPU"
      :value="gpuTotal ?? '—'"
      suffix="张"
      note="授权服务器上报的设备"
      ><DatabaseOutlined
    /></SummaryStat>
    <SummaryStat
      label="未恢复告警"
      :value="alertCount ?? '—'"
      suffix="项"
      note="包含已确认、未恢复事件"
      ><DashboardOutlined
    /></SummaryStat>
  </div>
  <div class="gauge-grid">
    <ResourceGauge
      label="CPU 平均使用率"
      :value="average('cpuUsage') === '—' ? null : Number(average('cpuUsage'))"
      accent="cyan"
      note="在线主机最新采样"
    />
    <ResourceGauge
      label="内存平均使用率"
      :value="
        average('memoryUsage') === '—' ? null : Number(average('memoryUsage'))
      "
      accent="blue"
      note="在线主机最新采样"
    />
    <ResourceGauge
      label="GPU 平均利用率"
      :value="gpuLoad"
      accent="violet"
      note="有新鲜快照的主机"
    />
    <ResourceGauge
      :label="heatLabel"
      :value="hostHeat"
      unit="°C"
      :max="120"
      accent="amber"
      :note="
        heatHosts
          ? `${heatHosts} 台主机的同类传感器最高读数`
          : '暂无可读的主机温度传感器'
      "
    />
  </div>
  <div class="resource-grid">
    <MetricTrend
      title="近一小时 CPU · 有采样主机均值"
      unit="%"
      :points="clusterCpu"
    /><MetricTrend
      title="近一小时内存 · 有采样主机均值"
      unit="%"
      :points="clusterMemory"
    />
  </div>
  <div class="section-heading">
    <h2>资源概况</h2>
    <a-button type="link" @click="router.push('/servers')"
      >全部服务器 <ArrowRightOutlined
    /></a-button>
  </div>
  <HostTable
    :servers="store.servers.slice(0, 5)"
    :loading="store.loading"
    compact
    @select="selectServer"
    @connect="router.push('/access')"
  />
  <div class="overview-lower">
    <section>
      <div class="section-heading">
        <h2>CPU 使用率排行</h2>
        <span class="muted compact">在线主机</span>
      </div>
      <button
        v-for="(server, index) in ranking"
        :key="server.id"
        class="rank-row"
        @click="selectServer(server.id)"
      >
        <span class="rank-number">0{{ index + 1 }}</span
        ><span>{{ server.name }}</span
        ><a-progress
          :percent="server.cpuUsage!"
          :show-info="false"
          :stroke-color="server.cpuUsage! >= 80 ? '#d39429' : '#087f78'"
        /><strong>{{ formatPercent(server.cpuUsage) }}</strong>
      </button>
      <a-empty
        v-if="!ranking.length"
        :image="Empty.PRESENTED_IMAGE_SIMPLE"
        description="暂无在线采样"
      />
    </section>
    <section>
      <div class="section-heading">
        <h2>
          需要关注 <a-tag>{{ attention.length }}</a-tag>
        </h2>
        <a-tooltip title="离线主机，或任一资源使用率达到 80%"
          ><span class="muted compact">状态与资源检查</span></a-tooltip
        >
      </div>
      <button
        v-for="server in attention"
        :key="server.id"
        class="attention-row"
        @click="selectServer(server.id)"
      >
        <span
          class="connection-dot"
          :class="server.status === 'ONLINE' ? 'warm' : 'bad'"
        /><span
          ><strong>{{ server.name }}</strong
          ><small>{{
            server.status !== 'ONLINE'
              ? `主机离线 · 最后心跳 ${formatTime(server.lastSeenAt)}`
              : `资源使用率较高 · CPU ${formatPercent(server.cpuUsage)} / 内存 ${formatPercent(server.memoryUsage)} / 磁盘 ${formatPercent(server.diskUsage)}`
          }}</small></span
        ><ArrowRightOutlined />
      </button>
      <a-empty
        v-if="!attention.length"
        :image="Empty.PRESENTED_IMAGE_SIMPLE"
        description="暂无需要关注的主机"
      />
    </section>
  </div>
</template>
