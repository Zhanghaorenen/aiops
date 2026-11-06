export const formatTime = (value: string | null | undefined) => {
  if (!value) return '尚未上报'
  // 旧知识库 DTO 的 LocalDateTime 没有时区后缀，但存储口径是 UTC。
  const utc = /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?$/.test(value)
    ? value + 'Z'
    : value
  const time = new Date(utc)
  return Number.isFinite(+time)
    ? time.toLocaleString('zh-CN', { hour12: false })
    : '时间未知'
}

export const formatPercent = (value: number | null) =>
  value == null ? '—' : `${value.toFixed(1)}%`

export function formatBytes(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value) || value < 0) return '—'
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB', 'PiB']
  const index = Math.min(
    value > 0 ? Math.floor(Math.log(value) / Math.log(1024)) : 0,
    units.length - 1,
  )
  return `${(value / 1024 ** index).toFixed(index ? 1 : 0)} ${units[index]}`
}
