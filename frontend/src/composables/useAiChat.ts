import { computed, ref } from 'vue'
import { errorMessage, sendChatStream, type ChatHistoryMessage, type ChatRequest, type ChatResponse, type ChatStreamEvent } from '../api'

export const QUESTION_LIMIT = 4000
export const HISTORY_LIMIT = 20
export const CONTENT_LIMIT = 8000
export const TOTAL_LIMIT = 50000
export const characterCount = (value: string) => Array.from(value).length

export function createChatRequest(question: string, messages: ChatHistoryMessage[]): ChatRequest {
  const trimmed = question.trim()
  if (!trimmed) throw new Error('请输入运维问题')
  if (characterCount(trimmed) > QUESTION_LIMIT) throw new Error(`问题不能超过 ${QUESTION_LIMIT} 个字符`)
  const history = messages.slice(-HISTORY_LIMIT).map(item => ({
    role: item.role,
    content: Array.from(item.content.trim()).slice(0, CONTENT_LIMIT).join(''),
  }))
  let total = characterCount(trimmed) + history.reduce((sum, item) => sum + characterCount(item.content), 0)
  while (total > TOTAL_LIMIT && history.length) {
    // Drop complete oldest turns so retained context always starts with a user.
    const removed = history.splice(0, 2)
    total -= removed.reduce((sum, item) => sum + characterCount(item.content), 0)
  }
  return { question: trimmed, history }
}

// Only completed turns enter history. Request identity isolates canceled or replaced sessions.
export function useAiChat(
  access: () => { demo: boolean; token: string | null },
  transport: (request: ChatRequest, signal?: AbortSignal, onEvent?: (event: ChatStreamEvent) => void) => Promise<ChatResponse> = sendChatStream,
) {
  const input = ref('')
  const messages = ref<ChatHistoryMessage[]>([])
  const sending = ref(false)
  const error = ref('')
  const notice = ref('')
  const pendingQuestion = ref('')
  const streamingAnswer = ref('')
  const toolActivity = ref('')
  let active: AbortController | null = null
  let failedRequest: ChatRequest | null = null
  const canRetry = computed(() => !sending.value && !!failedRequest && input.value.trim() === failedRequest.question)

  function cancel() {
    if (!active) return
    const request = active
    active = null
    sending.value = false
    request.abort()
    streamingAnswer.value = ''
    toolActivity.value = ''
    input.value = pendingQuestion.value
    notice.value = '已取消本次查询'
  }

  function reset() {
    cancel()
    messages.value = []
    input.value = ''
    error.value = ''
    notice.value = ''
    pendingQuestion.value = ''
    streamingAnswer.value = ''
    toolActivity.value = ''
    failedRequest = null
  }

  async function send(retry = false) {
    if (sending.value) return
    const identity = access()
    if (identity.demo || !identity.token) {
      error.value = identity.demo ? '演示环境不支持真实 AI 查询，请登录后使用' : '请先登录后使用 AI 查询'
      return
    }
    let request: ChatRequest
    try { request = retry && canRetry.value && failedRequest ? failedRequest : createChatRequest(input.value, messages.value) }
    catch (err) { error.value = errorMessage(err); return }
    const controller = new AbortController()
    active = controller
    sending.value = true
    error.value = ''
    notice.value = ''
    failedRequest = request
    pendingQuestion.value = request.question
    streamingAnswer.value = ''
    toolActivity.value = ''
    input.value = ''
    try {
      const response = await transport(request, controller.signal, (event) => {
        if (active !== controller || controller.signal.aborted) return
        if (event.type === 'token') {
          streamingAnswer.value += event.text
          toolActivity.value = ''
        } else if (event.type === 'tool_start') {
          const labels: Record<string, string> = {
            list_servers: '查询服务器列表', get_server_status: '查询服务器状态',
            get_server_resource_usage: '查询主机指标', list_gpus: '查询 GPU 列表',
            get_gpu_status: '查询 GPU 状态', get_gpu_overview: '汇总 GPU 指标',
            get_gpu_processes: '查询 GPU 进程', get_gpu_history: '查询 GPU 历史',
            analyze_gpu_health: '分析 GPU 健康度', compare_gpu_load: '比较 GPU 负载',
            recommend_gpu_placement: '生成 GPU 调度建议',
          }
          toolActivity.value = `${labels[event.name] ?? '执行只读查询'}…`
        } else if (event.type === 'tool_end') {
          toolActivity.value = event.ok === false ? '查询未成功，正在整理结果…' : '数据已取得，正在分析…'
        }
      })
      if (active !== controller || controller.signal.aborted) return
      const current = access()
      if (current.demo || current.token !== identity.token) { reset(); return }
      if (typeof response.answer !== 'string' || !response.answer.trim()) throw new Error('AI 服务返回了无效回答，请重试')
      messages.value.push({ role: 'user', content: request.question }, { role: 'assistant', content: response.answer })
      pendingQuestion.value = ''
      failedRequest = null
      streamingAnswer.value = ''
      toolActivity.value = ''
    } catch (err) {
      if (active !== controller) return
      input.value = request.question
      streamingAnswer.value = ''
      toolActivity.value = ''
      if (!controller.signal.aborted) error.value = errorMessage(err)
    } finally {
      if (active === controller) { active = null; sending.value = false }
    }
  }

  return { input, messages, sending, error, notice, pendingQuestion, streamingAnswer, toolActivity, canRetry, send, cancel, reset }
}
