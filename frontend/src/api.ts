import axios from 'axios'

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080',
  timeout: 12000,
})

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('aiops.accessToken')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

api.interceptors.response.use((response) => response, (error) => {
  if (error.response?.status === 401) {
    localStorage.removeItem('aiops.accessToken')
    window.dispatchEvent(new Event('aiops:unauthorized'))
  }
  return Promise.reject(error)
})

export interface Server {
  id: number
  name: string
  hostname: string
  ip: string | null
  status: 'ONLINE' | 'OFFLINE' | string
  osName: string | null
  osVersion: string | null
  architecture: string | null
  cpuCores: number | null
  memoryBytes: number | null
  cpuUsage: number | null
  memoryUsage: number | null
  diskUsage: number | null
  lastSeenAt: string | null
  latestMetricAt: string | null
}

export interface MetricSample {
  id: number
  nodeId: number
  cpuPercent: number
  memoryTotalBytes: number
  memoryUsedBytes: number
  memoryPercent: number
  diskTotalBytes: number
  diskUsedBytes: number
  diskPercent: number
  collectedAt: string
  receivedAt: string
}

export interface LoginResponse {
  accessToken: string
  tokenType: string
  expiresAt: string
}

export function errorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    return error.response?.data?.message ?? error.message ?? '请求失败，请稍后重试'
  }
  return error instanceof Error ? error.message : '发生未知错误'
}
