import axios, { type AxiosInstance } from 'axios'

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '',
  timeout: 12000,
})

export const aiApi = axios.create({
  baseURL: import.meta.env.VITE_AI_BASE_URL ?? '',
  timeout: 120000,
})

function attachAuthentication(client: AxiosInstance) {
  const isPublicAuth = (url?: string) => url === '/api/auth/login' || url === '/api/auth/register'
  client.interceptors.request.use((config) => {
    if (isPublicAuth(config.url)) {
      config.headers.delete('Authorization')
      return config
    }
    const token = localStorage.getItem('aiops.accessToken')
    if (token) config.headers.Authorization = `Bearer ${token}`
    return config
  })

  client.interceptors.response.use((response) => response, (error) => {
    if (error.response?.status === 401 && !isPublicAuth(error.config?.url)) {
      localStorage.removeItem('aiops.accessToken')
      window.dispatchEvent(new Event('aiops:unauthorized'))
    }
    return Promise.reject(error)
  })
}

attachAuthentication(api)
attachAuthentication(aiApi)

export interface Server {
  /*
   * 服务器信息{
   * @property {number} id - IDb编号
   * @property {string} name - 服务器名称
   * @property {string} hostname - 主机名
   * @property {string | null} ip - IP地址
   * @property {'ONLINE' | 'OFFLINE' | string} status - 在线状态
   * @property {string | null} osName - 操作系统名称
   * @property {string | null} osVersion - 操作系统版本
   * @property {string | null} architecture - 系统架构
   * @property {number | null} cpuCores - CPU核心数
   * @property {number | null} memoryBytes - 内存总量（字节）
   * @property {number | null} cpuUsage - CPU使用率（百分比）
   * @property {number | null} memoryUsage - 内存使用率（百分比）
   * @property {number | null} diskUsage - 磁盘使用率（百分比）
   * @property {string | null} lastSeenAt - 最后在线时间
   * @property {string | null} latestMetricAt - 最新指标时间
   * }
   */
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

/**
 * 指标样本
 * @property {number} id - 指标样本ID
 * @property {number} nodeId - 节点ID
 * @property {number} cpuPercent - CPU使用率（百分比）
 * @property {number} memoryTotalBytes - 内存总量（字节）
 * @property {number} memoryUsedBytes - 内存使用量（字节）
 * @property {number} memoryPercent - 内存使用率（百分比）
 * @property {number} diskTotalBytes - 磁盘总量（字节）
 * @property {number} diskUsedBytes - 磁盘使用量（字节）
 * @property {number} diskPercent - 磁盘使用率（百分比）
 * @property {string} collectedAt - 指标采集时间
 * @property {string} receivedAt - 指标接收时间
 *
 */
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

export interface GpuSnapshot {
  serverId: number
  collectionStatus: 'AVAILABLE' | 'NO_GPU' | 'UNAVAILABLE' | 'FAILED' | 'UNKNOWN' | 'UNREPORTED' | string
  collectedAt: string
  receivedAt: string
  gpuCount: number
  gpuIndices: number[]
  gpuUuids: string[]
  gpuNames: Array<string | null>
  gpuMemoryTotal: Array<number | null>
  gpuMemoryUsed: Array<number | null>
  gpuMemoryFree: Array<number | null>
  utilization: Array<number | null>
  memoryUtilization: Array<number | null>
  temperature: Array<number | null>
  powerUsage: Array<number | null>
  fanSpeed: Array<number | null>
  clockSpeed: Array<number | null>
}

export interface GpuHistorySample {
  serverId: number
  gpuIndex: number
  gpuUuid: string | null
  gpuName: string | null
  gpuMemoryTotal: number | null
  gpuMemoryUsed: number | null
  gpuMemoryFree: number | null
  utilization: number | null
  memoryUtilization: number | null
  temperature: number | null
  powerUsage: number | null
  fanSpeed: number | null
  clockSpeed: number | null
  collectedAt: string
  receivedAt: string
}

export interface GpuProcess {
  serverId: number
  gpuIndex: number
  gpuUuid?: string | null
  pid: number
  processName: string | null
  username: string | null
  gpuMemoryUsage: number | null
  taskType: string | null
  collectedAt?: string
  receivedAt?: string
}

export interface LoginResponse {
  accessToken: string
  tokenType: string
  expiresAt: string
}

export function errorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    if (axios.isCancel(error)) return '请求已取消'
    if (error.code === 'ECONNABORTED' || error.code === 'ETIMEDOUT') return '请求超时，请稍后重试'
    if (!error.response) return '无法连接后端服务，请确认后端已启动后重试'
    const data: unknown = error.response.data
    if (data && typeof data === 'object') {
      const { detail, message } = data as { detail?: unknown; message?: unknown }
      if (typeof detail === 'string' && detail.trim()) return detail
      if (Array.isArray(detail)) {
        const problems = detail.flatMap((item: unknown) => {
          if (!item || typeof item !== 'object') return []
          const { loc, msg } = item as { loc?: unknown; msg?: unknown }
          if (typeof msg !== 'string') return []
          const path = Array.isArray(loc) ? loc.filter(value => typeof value === 'string' || typeof value === 'number').join('.') : ''
          return [path ? `${path}: ${msg}` : msg]
        })
        if (problems.length) return problems.join('；')
      }
      if (typeof message === 'string' && message.trim()) return message
    }
    if (error.response.status === 401 && error.config?.url === '/api/auth/register') {
      return '注册请求未获授权，请确认后端已更新并重启'
    }
    const statusMessages: Record<number, string> = {
      401: '登录已失效，请重新登录', 403: '没有权限执行此查询', 422: '请求内容不符合要求，请检查问题和历史消息',
      502: '模型或平台服务响应异常，请稍后重试', 503: 'AI 服务暂不可用，请稍后重试',
      504: 'AI 查询超时，请稍后重试', 508: '已达到工具查询次数限制，请缩小问题范围后重试',
    }
    return statusMessages[error.response.status] ?? '请求失败，请稍后重试'
  }
  return error instanceof Error ? error.message : '发生未知错误'
}
// AI 对话中的历史消息
export interface ChatHistoryMessage {
  role: 'user' | 'assistant'
  content: string
}

// AI 聊天请求
export interface ChatRequest {
  question: string
  history: ChatHistoryMessage[]
}

// AI 聊天响应
export interface ChatResponse {
  answer: string
}

export type ChatStreamEvent =
  | { type: 'token'; text: string }
  | { type: 'tool_start' | 'tool_end'; name: string; ok?: boolean }
  | { type: 'done' }
  | { type: 'error'; status?: number; message: string }

// 调用中心 AI Service
export async function sendChat(
  request: ChatRequest,
  signal?: AbortSignal
): Promise<ChatResponse> {

  const response = await aiApi.post<ChatResponse>(
    '/api/chat',
    request,
    {
      signal,
      timeout: 120000,
    }
  )

  if (typeof response.data?.answer !== 'string' || !response.data.answer.trim()) {
    throw new Error('AI 服务返回了无效回答，请重试')
  }
  return response.data
}

export async function sendChatStream(
  request: ChatRequest,
  signal?: AbortSignal,
  onEvent?: (event: ChatStreamEvent) => void,
): Promise<ChatResponse> {
  const base = aiApi.defaults.baseURL ?? ''
  const response = await fetch(`${base}/api/chat/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(localStorage.getItem('aiops.accessToken')
        ? { Authorization: `Bearer ${localStorage.getItem('aiops.accessToken')}` }
        : {}),
    },
    body: JSON.stringify(request),
    signal,
  })
  if (!response.ok) {
    if (response.status === 401) {
      localStorage.removeItem('aiops.accessToken')
      window.dispatchEvent(new Event('aiops:unauthorized'))
    }
    let detail = ''
    try {
      const body: unknown = await response.json()
      if (body && typeof body === 'object' && 'detail' in body && typeof body.detail === 'string') detail = body.detail
    } catch { /* Keep the status based fallback below. */ }
    const statusText: Record<number, string> = {
      401: '登录已失效，请重新登录', 403: '没有权限执行此查询', 422: '请求内容不符合要求',
      502: '模型或平台服务响应异常，请稍后重试', 503: 'AI 服务暂不可用，请稍后重试',
      504: 'AI 查询超时，请稍后重试', 508: '已达到工具查询次数限制，请缩小问题范围后重试',
    }
    throw new Error(detail || statusText[response.status] || '请求失败，请稍后重试')
  }
  if (!response.body) throw new Error('AI 服务没有返回流式响应')

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let answer = ''
  let finished = false

  const consumeFrame = (frame: string) => {
    const data = frame.split(/\r?\n/).filter(line => line.startsWith('data:'))
      .map(line => line.slice(5).trimStart()).join('\n')
    if (!data || data === '[DONE]') return
    let event: ChatStreamEvent
    try { event = JSON.parse(data) as ChatStreamEvent }
    catch { throw new Error('AI 服务返回了无效的流式数据') }
    if (event.type === 'error') throw new Error(event.message || 'AI 智能体执行失败')
    if (event.type === 'token') answer += event.text
    if (event.type === 'done') finished = true
    onEvent?.(event)
  }

  try {
    while (true) {
      const { value, done } = await reader.read()
      buffer += decoder.decode(value, { stream: !done })
      const frames = buffer.split(/\r?\n\r?\n/)
      buffer = frames.pop() ?? ''
      for (const frame of frames) consumeFrame(frame)
      if (done) break
    }
    if (buffer.trim()) consumeFrame(buffer)
  } finally {
    reader.releaseLock()
  }

  if (!finished || !answer.trim()) throw new Error('AI 流式响应未正常完成，请重试')
  return { answer }
}
