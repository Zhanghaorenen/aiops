import type { HostDetails } from '../api'

export function primaryTemperature(details?: HostDetails) {
  if (details?.temperatureStatus !== 'AVAILABLE') return null
  const labels = {
    CPU: 'CPU 最高温度',
    BOARD: '主板最高温度',
    STORAGE: '存储最高温度',
    OTHER: '传感器最高温度',
  }
  for (const category of ['CPU', 'BOARD', 'STORAGE', 'OTHER'] as const) {
    const readings = (details.temperatures ?? []).filter(
      (s) =>
        s.category === category &&
        typeof s.currentCelsius === 'number' &&
        Number.isFinite(s.currentCelsius),
    )
    if (readings.length)
      return {
        category,
        label: labels[category],
        value: Math.max(...readings.map((s) => s.currentCelsius)),
      }
  }
  return null
}

/** 只将明确识别为 CPU 的传感器用于 CPU 温度仪表盘。 */
export function cpuTemperature(details?: HostDetails): number | null {
  if (details?.temperatureStatus !== 'AVAILABLE') return null
  const values = (details.temperatures ?? [])
    .filter(
      (s) =>
        s.category === 'CPU' &&
        typeof s.currentCelsius === 'number' &&
        Number.isFinite(s.currentCelsius),
    )
    .map((s) => s.currentCelsius)
  return values.length ? Math.max(...values) : null
}

export function temperatureHint(details?: HostDetails): string {
  if (details?.temperatureStatus === 'UNSUPPORTED')
    return '系统未提供可读温度传感器'
  if (details?.temperatureStatus === 'UNAVAILABLE')
    return '温度采集失败或传感器不可读'
  if (details?.temperatureStatus === 'AVAILABLE')
    return cpuTemperature(details) == null
      ? '未识别 CPU 温度，其他传感器见明细'
      : 'CPU 传感器最高读数'
  return 'Agent 尚未上报温度'
}

export function freshSample(at?: string | null, now = Date.now()): boolean {
  const time = Date.parse(at ?? '')
  return Number.isFinite(time) && now - time <= 90000 && time - now <= 60000
}
