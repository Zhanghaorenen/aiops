import type { MetricSample, Server } from './api'

export function demoServers(): Server[] {
  return [
    ['prod-api-01', '10.20.1.11', 'ONLINE', 32.6, 48.2, 41.8, 'Ubuntu', '24.04 LTS', 8, 32],
    ['prod-worker-02', '10.20.1.12', 'ONLINE', 86.4, 78.5, 63.1, 'Ubuntu', '24.04 LTS', 16, 64],
    ['mysql-primary', '10.20.2.10', 'ONLINE', 24.1, 67.3, 72.6, 'Rocky Linux', '9.4', 16, 64],
    ['gpu-inference-01', '10.20.3.21', 'ONLINE', 51.7, 58.9, 36.2, 'Ubuntu', '22.04 LTS', 32, 128],
    ['staging-win-01', '10.20.4.15', 'OFFLINE', 12.4, 33.6, 28.4, 'Windows', 'Server 2022', 8, 16],
  ].map((row, i) => ({
    id: i + 1, name: String(row[0]), hostname: String(row[0]), ip: String(row[1]), status: String(row[2]),
    cpuUsage: Number(row[3]), memoryUsage: Number(row[4]), diskUsage: Number(row[5]),
    osName: String(row[6]), osVersion: String(row[7]), cpuCores: Number(row[8]),
    memoryBytes: Number(row[9]) * 1024 ** 3, architecture: 'x86_64',
    lastSeenAt: new Date(Date.now() - (i === 4 ? 7200000 : 12000)).toISOString(),
    latestMetricAt: new Date(Date.now() - (i === 4 ? 7200000 : 12000)).toISOString(),
  }))
}

export function demoHistory(server: Server, hours: number): MetricSample[] {
  const end = Date.now() - (server.status === 'OFFLINE' ? 7200000 : 15000)
  return Array.from({ length: 120 }, (_, i) => {
    const wave = Math.sin(i / 7 + server.id) * 8 + Math.cos(i / 3) * 3
    const memory = Math.max(0, Math.min(100, (server.memoryUsage ?? 0) + wave / 3))
    const disk = Math.max(0, (server.diskUsage ?? 0) - (120 - i) / 100)
    return {
      id: i, nodeId: server.id, cpuPercent: Math.max(0, Math.min(100, (server.cpuUsage ?? 0) + wave)),
      memoryTotalBytes: server.memoryBytes!, memoryUsedBytes: server.memoryBytes! * memory / 100,
      memoryPercent: memory, diskTotalBytes: 512 * 1024 ** 3,
      diskUsedBytes: 512 * 1024 ** 3 * disk / 100, diskPercent: disk,
      collectedAt: new Date(end - (119 - i) * hours * 3600000 / 120).toISOString(),
      receivedAt: new Date(end - (119 - i) * hours * 3600000 / 120).toISOString(),
    }
  })
}
