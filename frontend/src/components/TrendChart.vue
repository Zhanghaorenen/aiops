<script setup lang="ts">
import MetricTrend from './MetricTrend.vue'
import type { MetricSample } from '../api'
// 保留旧 samples/loading 接口，资源页面复用同一入口绘制带单位的独立趋势。
const props = defineProps<{
  samples?: MetricSample[]
  loading?: boolean
  title?: string
  unit?: string
  points?: { at: string; value: number | null }[]
  current?: number | null
  threshold?: number
  events?: string[]
  stats?: { average?: number; maximum?: number; peakAt?: string }
}>()
const legacyMetrics = [
  { key: 'cpuPercent' as const, title: 'CPU 使用率' },
  { key: 'memoryPercent' as const, title: '内存使用率' },
  { key: 'diskPercent' as const, title: '根分区使用率' },
]
</script>
<template>
  <a-spin :spinning="!!loading"
    ><MetricTrend
      v-if="points"
      :title="title ?? '资源趋势'"
      :unit="unit ?? '%'"
      :points="points"
      :current="current"
      :threshold="threshold"
      :events="events"
      :stats="stats" />
    <div v-else class="resource-grid">
      <MetricTrend
        v-for="metric in legacyMetrics"
        :key="metric.key"
        :title="metric.title"
        unit="%"
        :points="
          (props.samples ?? []).map((s) => ({
            at: s.collectedAt,
            value: s[metric.key],
          }))
        "
      /></div
  ></a-spin>
</template>
