export const formatTime = (value: string | null) => value
  ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '尚未上报'

export const formatPercent = (value: number | null) => value == null ? '—' : `${value.toFixed(1)}%`

export function formatBytes(value: number | null): string {
  if (value == null) return '—'
  return value >= 1024 ** 3
    ? `${(value / 1024 ** 3).toFixed(1)} GB` : `${(value / 1024 ** 2).toFixed(1)} MB`
}
