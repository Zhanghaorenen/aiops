<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import MetricTrend from './TrendChart.vue'
import { formatBytes, formatTime } from '../utils/format'
import ResourceGauge from './ResourceGauge.vue'
import {
  primaryTemperature,
  temperatureHint,
  freshSample,
} from '../utils/temperature'
const props = defineProps<{
  samples: any[]
  latest: any
  stats?: Record<string, any>
  events?: any[]
  rules?: any[]
  cores?: number | null
}>()
const categoryNames: Record<string, string> = {
  CPU: 'CPU',
  BOARD: '主板',
  STORAGE: '存储',
  OTHER: '其他',
}
const details = computed(() => props.latest?.details ?? {})
const selectedNic = ref(''),
  showSystemMounts = ref(false)
const disks = computed(() =>
  (details.value.disks ?? []).filter(
    (d: any) =>
      showSystemMounts.value || !['squashfs', 'iso9660'].includes(d.fstype),
  ),
)
watch(
  () => details.value.network,
  (items: any[]) => {
    if (!items?.some((n) => n.name === selectedNic.value))
      selectedNic.value =
        items?.find((n) => /^bond/.test(n.name))?.name ??
        items?.find(
          (n) => !/^lo$|^veth|^br-|^docker/.test(n.name) && n.bytesReceived > 0,
        )?.name ??
        items?.[0]?.name ??
        ''
  },
  { immediate: true },
)
const annotations = (metric: string) => {
  const ids = (props.rules ?? [])
    .filter((r) => r.metric === metric)
    .map((r) => r.id)
  return (props.events ?? [])
    .filter((e) => ids.includes(e.rule_id))
    .map((e) => e.first_triggered_at)
}
const trend = (field: string) =>
  props.samples.map((row) => ({
    at: row.collectedAt,
    value: row[field] ?? null,
  }))
const threshold = (metric: string) =>
  (props.rules ?? []).find((r) => r.metric === metric && r.enabled)
    ?.threshold_value
const networkPoints = (field: string) =>
  props.samples.map((row) => {
    const n = row.details?.network?.find(
      (n: any) => n.name === selectedNic.value,
    )
    return {
      at: row.collectedAt,
      value: n?.[field] == null ? null : n[field] / 1024,
    }
  })
</script>
<template>
  <div class="gauge-grid">
    <ResourceGauge
      label="CPU 使用率"
      :value="freshSample(latest?.collectedAt) ? latest?.cpuPercent : null"
      accent="cyan"
      :note="`${cores ?? '—'} 个逻辑核心`"
    />
    <ResourceGauge
      label="内存使用率"
      :value="freshSample(latest?.collectedAt) ? latest?.memoryPercent : null"
      accent="blue"
      :note="
        formatBytes(latest?.memoryUsedBytes) +
        ' / ' +
        formatBytes(latest?.memoryTotalBytes)
      "
    />
    <ResourceGauge
      label="根分区使用率"
      :value="freshSample(latest?.collectedAt) ? latest?.diskPercent : null"
      accent="violet"
      note="其他分区见下方明细"
    />
    <ResourceGauge
      :label="primaryTemperature(details)?.label ?? '主机温度'"
      :value="
        freshSample(latest?.collectedAt)
          ? (primaryTemperature(details)?.value ?? null)
          : null
      "
      unit="°C"
      :max="120"
      accent="amber"
      :note="
        freshSample(latest?.collectedAt)
          ? primaryTemperature(details)
            ? '操作系统传感器的最高读数'
            : temperatureHint(details)
          : '采样缺失或已过期'
      "
    />
  </div>
  <a-card title="服务器温度传感器" class="thermal-panel">
    <template #extra
      ><span class="muted compact">{{
        formatTime(latest?.collectedAt)
      }}</span></template
    >
    <p class="muted compact">
      温度来自操作系统传感器；CPU、主板与硬盘分别标记。阈值由硬件报告，缺失时显示
      —。
    </p>
    <a-table
      v-if="details.temperatures?.length"
      :data-source="details.temperatures"
      :pagination="false"
      size="small"
      :scroll="{ x: 680 }"
      :columns="[
        { title: '传感器', dataIndex: 'sensor' },
        { title: '位置 / 名称', dataIndex: 'label' },
        { title: '类型', dataIndex: 'category' },
        { title: '当前温度', dataIndex: 'currentCelsius' },
        { title: '高温参考', dataIndex: 'highCelsius' },
        { title: '临界温度', dataIndex: 'criticalCelsius' },
      ]"
    >
      <template #bodyCell="{ column, record }"
        ><a-tag v-if="column.dataIndex === 'category'">{{
          categoryNames[record.category]
        }}</a-tag
        ><span
          v-else-if="column.dataIndex.endsWith('Celsius')"
          :class="{
            'thermal-hot':
              record.criticalCelsius != null &&
              record.currentCelsius >= record.criticalCelsius,
          }"
          >{{
            record[column.dataIndex] == null
              ? '—'
              : Number(record[column.dataIndex]).toFixed(1) + ' °C'
          }}</span
        ></template
      >
    </a-table>
    <a-empty v-else :description="temperatureHint(details)" />
  </a-card>

  <div class="resource-grid">
    <MetricTrend
      title="CPU 使用率"
      unit="%"
      :points="trend('cpuPercent')"
      :stats="stats?.cpuPercent"
      :current="latest?.cpuPercent"
      :threshold="threshold('CPU_HIGH')"
      :events="annotations('CPU_HIGH')"
    /><MetricTrend
      title="内存使用率"
      unit="%"
      :points="trend('memoryPercent')"
      :stats="stats?.memoryPercent"
      :current="latest?.memoryPercent"
      :threshold="threshold('MEMORY_HIGH')"
      :events="annotations('MEMORY_HIGH')"
    />
  </div>
  <a-card title="主机指标" class="resource-details"
    ><a-descriptions :column="{ xs: 1, sm: 2, lg: 4 }"
      ><a-descriptions-item label="CPU 核数">{{
        cores ?? '—'
      }}</a-descriptions-item
      ><a-descriptions-item label="系统负载 1 / 5 / 15 分钟">{{
        [details.load1, details.load5, details.load15]
          .map((v) => (v == null ? '—' : Number(v).toFixed(2)))
          .join(' / ')
      }}</a-descriptions-item
      ><a-descriptions-item label="内存总量 / 已用"
        >{{ formatBytes(latest?.memoryTotalBytes) }} /
        {{ formatBytes(latest?.memoryUsedBytes) }}</a-descriptions-item
      ><a-descriptions-item label="可用 / 缓存"
        >{{ formatBytes(details.memoryAvailableBytes) }} /
        {{ formatBytes(details.memoryCachedBytes) }}</a-descriptions-item
      ><a-descriptions-item label="Swap 已用 / 总量"
        >{{ formatBytes(details.swapUsedBytes) }} /
        {{ formatBytes(details.swapTotalBytes) }}</a-descriptions-item
      ><a-descriptions-item label="原始采集时间">{{
        formatTime(latest?.collectedAt)
      }}</a-descriptions-item></a-descriptions
    >
    <a-collapse v-if="details.perCorePercent?.length" ghost
      ><a-collapse-panel
        key="cores"
        :header="`逐核心使用率 · ${details.perCorePercent.length} 核`"
        ><div class="core-grid">
          <a-tooltip
            v-for="(value, index) in details.perCorePercent"
            :key="index"
            :title="`核心 ${index}: ${value}%`"
            ><div
              :style="{
                background: `rgba(8,127,120,${0.1 + Number(value) / 120})`,
              }"
            >
              {{ index }}<b>{{ value }}%</b>
            </div></a-tooltip
          >
        </div></a-collapse-panel
      ></a-collapse
    ></a-card
  ><MetricTrend
    title="根分区使用率"
    unit="%"
    :points="trend('diskPercent')"
    :stats="stats?.diskPercent"
    :current="latest?.diskPercent"
    :threshold="threshold('DISK_HIGH')"
    :events="annotations('DISK_HIGH')"
  /><a-card title="分区与挂载点"
    ><template #extra
      ><a-checkbox v-model:checked="showSystemMounts"
        >显示只读系统分区</a-checkbox
      ></template
    ><a-table
      :data-source="disks"
      row-key="mountpoint"
      size="small"
      :pagination="false"
      :scroll="{ x: 1000 }"
      :columns="[
        { title: '分区', dataIndex: 'device' },
        { title: '挂载点', dataIndex: 'mountpoint' },
        { title: '容量', dataIndex: 'totalBytes' },
        { title: '已用', dataIndex: 'usedBytes' },
        { title: '剩余', dataIndex: 'freeBytes' },
        { title: '使用率', dataIndex: 'percent' },
        { title: '读取 / 秒', dataIndex: 'readBytesPerSecond' },
        { title: '写入 / 秒', dataIndex: 'writeBytesPerSecond' },
      ]"
      ><template #bodyCell="{ column, record }">{{
        column.dataIndex.includes('Bytes')
          ? formatBytes(record[column.dataIndex])
          : column.dataIndex === 'percent'
            ? `${record.percent}%`
            : record[column.dataIndex]
      }}</template></a-table
    >
    <p class="muted">
      旧 Agent 未上报的字段显示暂无数据；网络与磁盘首个速率样本未知。
    </p></a-card
  >
  <div class="section-heading">
    <h3>网络吞吐</h3>
    <a-select
      v-model:value="selectedNic"
      :options="
        (details.network ?? []).map((n: any) => ({
          value: n.name,
          label: n.name,
        }))
      "
      style="min-width: 200px"
    />
  </div>
  <div class="resource-grid">
    <MetricTrend
      :title="'网络接收速率 · ' + selectedNic"
      unit="KiB/s"
      :points="networkPoints('receiveBytesPerSecond')"
    /><MetricTrend
      :title="'网络发送速率 · ' + selectedNic"
      unit="KiB/s"
      :points="networkPoints('sendBytesPerSecond')"
    />
  </div>
  <a-card title="网卡流量"
    ><a-table
      :data-source="details.network ?? []"
      row-key="name"
      :pagination="{ pageSize: 8 }"
      :columns="[
        { title: '网卡', dataIndex: 'name' },
        { title: '累计接收', dataIndex: 'bytesReceived' },
        { title: '累计发送', dataIndex: 'bytesSent' },
        { title: '接收 / 秒', dataIndex: 'receiveBytesPerSecond' },
        { title: '发送 / 秒', dataIndex: 'sendBytesPerSecond' },
      ]"
      ><template #bodyCell="{ column, record }">{{
        column.dataIndex === 'name'
          ? record.name
          : formatBytes(record[column.dataIndex])
      }}</template></a-table
    ></a-card
  >
</template>
