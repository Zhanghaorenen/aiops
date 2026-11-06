<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { formatTime } from '../utils/format'
const props = defineProps<{
  title: string
  unit: string
  points: { at: string; value: number | null }[]
  current?: number | null
  threshold?: number
  events?: string[]
  stats?: { average?: number; maximum?: number; peakAt?: string }
}>()
const element = ref<HTMLDivElement | null>(null)
const error = ref('')
const valid = computed(() =>
  props.points.filter((p) => p.value != null && Number.isFinite(p.value)),
)
const summary = computed(() => {
  const values = valid.value
  if (!values.length) return null
  const peak = values.reduce((a, b) => (a.value! >= b.value! ? a : b))
  return {
    current: props.current ?? values[values.length - 1]!.value!,
    average:
      props.stats?.average ??
      values.reduce((n, p) => n + p.value!, 0) / values.length,
    maximum: props.stats?.maximum ?? peak.value!,
    peakAt: props.stats?.peakAt ?? peak.at,
  }
})
function value(n: number | undefined) {
  return n == null ? '—' : `${n.toFixed(1)} ${props.unit}`
}
watch(
  () => [props.points, props.threshold, props.events],
  async (_, __, cleanup) => {
    let disposed = false
    let destroy = () => {}
    cleanup(() => {
      disposed = true
      destroy()
    })
    if (!valid.value.length) return
    await nextTick()
    try {
      const { init } = await import('../chart')
      if (disposed || !element.value) return
      const chart = init(element.value)
      chart.setOption({
        color: ['#36d5e9'],
        textStyle: { color: '#91a8c7' },
        tooltip: {
          trigger: 'axis',
          backgroundColor: '#14243b',
          borderColor: '#2b4666',
          textStyle: { color: '#e0edfc' },
        },
        grid: { left: 58, right: 18, top: 24, bottom: 55 },
        xAxis: {
          type: 'time',
          axisLabel: { hideOverlap: true, color: '#8ba2bf' },
          axisLine: { lineStyle: { color: '#2a3e58' } },
        },
        yAxis: {
          type: 'value',
          name: props.unit,
          min: 0,
          max: props.unit === '%' ? 100 : undefined,
          axisLabel: { color: '#8ba2bf' },
          splitLine: { lineStyle: { color: '#22344d', type: 'dashed' } },
        },
        dataZoom: [
          { type: 'inside' },
          {
            type: 'slider',
            height: 14,
            bottom: 8,
            borderColor: '#2a3e58',
            backgroundColor: '#0b172a',
            fillerColor: '#26cbe41c',
            textStyle: { color: '#91a8c7' },
          },
        ],
        series: [
          {
            type: 'line',
            showSymbol: false,
            connectNulls: false,
            data: props.points.map((p) => [p.at, p.value]),
            areaStyle: {
              color: {
                type: 'linear',
                x: 0,
                y: 0,
                x2: 0,
                y2: 1,
                colorStops: [
                  { offset: 0, color: '#36d5e942' },
                  { offset: 1, color: '#36d5e900' },
                ],
              },
            },
            lineStyle: { width: 2 },
            markLine: {
              silent: true,
              symbol: 'none',
              data: [
                ...(props.threshold == null
                  ? []
                  : [
                      {
                        yAxis: props.threshold,
                        name: '阈值',
                        lineStyle: { color: '#d99331', type: 'dashed' },
                      },
                    ]),
                ...(props.events ?? []).map((at) => ({
                  xAxis: at,
                  name: '告警',
                  lineStyle: { color: '#dc6161' },
                })),
              ],
            },
            markPoint: {
              symbolSize: 32,
              label: { formatter: (p: any) => Number(p.value).toFixed(1) },
              data: [{ type: 'max', name: '采样峰值' }],
            },
          },
        ],
      })
      const observer = new ResizeObserver(() => chart.resize())
      observer.observe(element.value)
      destroy = () => {
        observer.disconnect()
        chart.dispose()
      }
    } catch {
      if (!disposed) error.value = '图表暂不可用，请刷新重试'
    }
  },
  { immediate: true, flush: 'post' },
)
</script>
<template>
  <section class="metric-chart">
    <header>
      <h3>{{ title }}</h3>
      <span class="muted">{{ points.length }} 个时间点</span>
    </header>
    <div v-if="summary" class="chart-statistics">
      <span
        >当前样本 <b>{{ value(summary.current) }}</b></span
      ><span
        >{{ stats ? '窗口平均' : '展示点平均' }}
        <b>{{ value(summary.average) }}</b></span
      ><span
        >{{ stats ? '窗口峰值' : '展示点峰值' }}
        <b>{{ value(summary.maximum) }}</b></span
      ><span
        >峰值时间 <b>{{ formatTime(summary.peakAt) }}</b></span
      >
    </div>
    <a-alert v-if="error" :message="error" type="error" />
    <div v-if="valid.length" ref="element" class="resource-chart" />
    <a-empty v-else description="该时间段暂无有效采样" />
  </section>
</template>
