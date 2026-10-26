import { useEffect, useRef } from 'react'
import { Empty, Spin } from 'antd'
import type { MetricSample } from './api'

export default function TrendChart({ samples, loading }: { samples: MetricSample[]; loading: boolean }) {
  const element = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!samples.length || !element.current) return
    let disposed = false
    let cleanup = () => {}
    void import('./chart').then(({ init }) => {
      if (disposed || !element.current) return
      const chart = init(element.current)
      chart.setOption({
        color: ['#087f78', '#4974c5', '#c58a28'],
        tooltip: { trigger: 'axis', valueFormatter: (v: number) => `${Number(v).toFixed(1)}%` },
        legend: { top: 0, right: 0 },
        grid: { left: 48, right: 16, top: 45, bottom: 35 },
        xAxis: { type: 'time', axisLine: { lineStyle: { color: '#d5dce3' } }, axisLabel: { color: '#76818b', hideOverlap: true } },
        yAxis: { type: 'value', min: 0, max: 100, axisLabel: { formatter: '{value}%', color: '#76818b' }, splitLine: { lineStyle: { color: '#edf0f3' } } },
        series: [['CPU', 'cpuPercent'], ['内存', 'memoryPercent'], ['磁盘', 'diskPercent']].map(([name, key]) => ({
          name, type: 'line', showSymbol: false, smooth: false, lineStyle: { width: 2 },
          data: samples.map(s => [s.collectedAt, s[key as 'cpuPercent']]),
        })),
      })
      const observer = new ResizeObserver(() => chart.resize())
      observer.observe(element.current)
      cleanup = () => { observer.disconnect(); chart.dispose() }
    })
    return () => { disposed = true; cleanup() }
  }, [samples])
  return <Spin spinning={loading}>{samples.length ? <div className="trend-chart" ref={element} /> : <div className="chart-empty"><Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该时间段暂无采样" /></div>}</Spin>
}
