<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { Empty } from 'ant-design-vue'
import type { MetricSample } from '../api'

const props = defineProps<{ samples: MetricSample[]; loading: boolean }>()
const element = ref<HTMLDivElement | null>(null)
const chartError = ref('')

watch(() => props.samples, async (samples, _, onCleanup) => {
  let disposed = false
  let cleanup = () => {}
  onCleanup(() => { disposed = true; cleanup() })
  if (!samples.length) return
  chartError.value = ''
  await nextTick()
  try {
    const { init } = await import('../chart')
    if (disposed || !element.value) return
    const chart = init(element.value)
    const series: Array<[string, 'cpuPercent' | 'memoryPercent' | 'diskPercent']> = [
      ['CPU', 'cpuPercent'], ['内存', 'memoryPercent'], ['磁盘', 'diskPercent'],
    ]
    chart.setOption({
      color: ['#087f78', '#4974c5', '#c58a28'],
      tooltip: { trigger: 'axis', valueFormatter: (value: number) => `${Number(value).toFixed(1)}%` },
      legend: { top: 0, right: 0 },
      grid: { left: 48, right: 16, top: 45, bottom: 35 },
      xAxis: { type: 'time', axisLine: { lineStyle: { color: '#d5dce3' } }, axisLabel: { color: '#76818b', hideOverlap: true } },
      yAxis: { type: 'value', min: 0, max: 100, axisLabel: { formatter: '{value}%', color: '#76818b' }, splitLine: { lineStyle: { color: '#edf0f3' } } },
      series: series.map(([name, key]) => ({ name, type: 'line', showSymbol: false, smooth: false, lineStyle: { width: 2 }, data: samples.map(sample => [sample.collectedAt, sample[key]]) })),
    })
    const observer = new ResizeObserver(() => chart.resize())
    observer.observe(element.value)
    cleanup = () => { observer.disconnect(); chart.dispose() }
  } catch {
    if (!disposed) chartError.value = '图表加载失败，请刷新页面重试'
  }
}, { immediate: true, flush: 'post' })
</script>

<template>
  <a-spin :spinning="loading">
    <a-alert v-if="chartError" type="error" :message="chartError" />
    <div v-if="samples.length" ref="element" class="trend-chart" />
    <div v-else class="chart-empty"><a-empty :image="Empty.PRESENTED_IMAGE_SIMPLE" description="该时间段暂无采样" /></div>
  </a-spin>
</template>
