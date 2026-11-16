<script setup lang="ts">
import AgentExecution from '../components/AgentExecution.vue'
import TraceViewer from '../components/TraceViewer.vue'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  RobotOutlined,
  UserOutlined,
  SendOutlined,
  MoreOutlined,
  PlusOutlined,
  StopOutlined,
  ReloadOutlined,
} from '@ant-design/icons-vue'
import { useWorkspaceStore } from '../stores/workspace'
import { useRoute } from 'vue-router'
import {
  characterCount,
  QUESTION_LIMIT,
  useAiChat,
} from '../composables/useAiChat'

import ResourceResult from '../components/ResourceResult.vue'
import { aiApi, chatSessions, errorMessage, type AiSession, type SavedChatMessage } from '../api'
const store = useWorkspaceStore()
const sessions = ref<AiSession[]>([])
const sessionId = ref<string | null>(null)
const loadingHistory = ref(false)
const oldestMessage = ref<number>()
const hasOlder = ref(false)
const renameTitle = ref('')
const renaming = ref(false)
const deleting = ref(false)
let historyVersion = 0

const {
  input,
  messages,
  sending,
  error,
  notice,
  pendingQuestion,
  streamingAnswer,
  toolActivity,
  execution,
  canRetry,
  send: sendTurn,
  cancel,
  reset,
} = useAiChat(() => ({
  demo: store.demo,
  token: store.token,
  sessionId: sessionId.value,
}))
const messageContainer = ref<HTMLElement | null>(null)
const followLatest = ref(true)
const historyOpen = ref(false)
const composer = ref()
function trackScroll() {
  const el = messageContainer.value
  if (el) followLatest.value = el.scrollHeight - el.scrollTop - el.clientHeight < 64
}
function suggest(question: string) {
  input.value = question
  composer.value?.focus()
}
const route = useRoute()
watch(
  () => route?.query.question,
  (question) => {
    if (
      typeof question === 'string' &&
      question.trim() &&
      characterCount(question.trim()) <= QUESTION_LIMIT &&
      !sending.value
    )
      input.value = question.trim()
  },
  { immediate: true },
)

const confirmation = ref<'new' | 'clear' | null>(null)
const unavailable = computed(() => store.demo || !store.token)
const inputLength = computed(() => characterCount(input.value.trim()))
const invalid = computed(() => inputLength.value > QUESTION_LIMIT)

watch(
  [
    () => messages.value.length,
    sending,
    pendingQuestion,
    streamingAnswer,
    toolActivity,
    () => execution.value.length,
    error,
    notice,
  ],
  async () => {
    await nextTick()
    const element = messageContainer.value
    if (element && followLatest.value) element.scrollTop = element.scrollHeight
  },
  { flush: 'post' },
)
watch(
  [() => store.token, () => store.demo],
  () => {
    reset()
    confirmation.value = null
    sessionId.value = null
    sessions.value = []
    historyVersion++
    loadingHistory.value = false
    hasOlder.value = false
    void loadSessions()
  },
  { flush: 'sync' },
)
onBeforeUnmount(cancel)

function handleKeydown(event: KeyboardEvent) {
  if (
    event.key === 'Enter' &&
    !event.shiftKey &&
    !event.isComposing &&
    event.keyCode !== 229
  ) {
    event.preventDefault()
    void send()
  }
}
function requestReset(action: 'new' | 'clear') {
  if (
    messages.value.length ||
    input.value ||
    pendingQuestion.value ||
    sending.value
  )
    confirmation.value = action
  else {
    reset()
    sessionId.value = null
    historyVersion++
    hasOlder.value = false
  }
}
function confirmReset() {
  reset()
  sessionId.value = null
  historyVersion++
  hasOlder.value = false
  confirmation.value = null
}

async function loadSessions() {
  if (store.demo || !store.token) return
  const token = store.token
  try {
    const rows = await chatSessions.list()
    if (token === store.token) sessions.value = rows
  } catch (e) {
    if (token === store.token) error.value = errorMessage(e)
  }
}
function restoreMessage(m: SavedChatMessage) {
  let metadata
  try { metadata = JSON.parse(m.metadata_json || '{}') } catch { /* Older messages can lack metadata. */ }
  return {
    role: m.role, content: m.content, traceId: metadata?.traceId,
    sources: metadata?.sources, resourceResults: metadata?.resourceResults,
    execution: Array.isArray(metadata?.execution) ? metadata.execution : [],
  }
}
async function openSession(id: string) {
  historyOpen.value = false
  followLatest.value = true
  cancel()
  reset()
  sessionId.value = id
  const version = ++historyVersion,
    token = store.token
  loadingHistory.value = true
  try {
    const rows = await chatSessions.messages(id)
    if (version !== historyVersion || token !== store.token) return
    oldestMessage.value = rows[0]?.id
    hasOlder.value = rows.length === 100
    messages.value = rows
      .filter((m) => m.status === 'COMPLETED')
      .map(restoreMessage)
  } catch (e) {
    if (version === historyVersion && token === store.token)
      error.value = errorMessage(e)
  } finally {
    if (version === historyVersion) loadingHistory.value = false
  }
}
async function loadOlder() {
  if (!sessionId.value || !oldestMessage.value) return
  const id = sessionId.value,
    version = historyVersion,
    token = store.token
  try {
    const rows = await chatSessions.messages(id, oldestMessage.value)
    if (version !== historyVersion || token !== store.token) return
    oldestMessage.value = rows[0]?.id
    hasOlder.value = rows.length === 100
    messages.value.unshift(
      ...rows
        .filter((m) => m.status === 'COMPLETED')
        .map(restoreMessage),
    )
  } catch (e) {
    if (version === historyVersion) error.value = errorMessage(e)
  }
}
async function send(retry = false) {
  if (loadingHistory.value || sending.value || unavailable.value || invalid.value || !inputLength.value) return
  followLatest.value = true
  const token = store.token,
    version = historyVersion
  try {
    if (!sessionId.value && !unavailable.value) {
      loadingHistory.value = true
      const session = await chatSessions.create(
        input.value.trim().slice(0, 80) || '新会话',
      )
      if (token !== store.token || version !== historyVersion) return
      sessionId.value = session.id
    }
    await sendTurn(retry)
    await loadSessions()
  } catch (e) {
    if (token === store.token) error.value = errorMessage(e)
  } finally {
    loadingHistory.value = false
  }
}
function beginRename() {
  renameTitle.value =
    sessions.value.find((s) => s.id === sessionId.value)?.title || ''
  renaming.value = true
}
async function renameSession() {
  if (!sessionId.value || !renameTitle.value.trim()) return
  try {
    await chatSessions.update(sessionId.value, renameTitle.value.trim())
    renaming.value = false
    await loadSessions()
  } catch (e) {
    error.value = errorMessage(e)
  }
}
async function deleteSession() {
  if (!sessionId.value) return
  try {
    await chatSessions.remove(sessionId.value)
    deleting.value = false
    reset()
    sessionId.value = null
    await loadSessions()
  } catch (e) {
    error.value = errorMessage(e)
  }
}
async function downloadSource(source: {knowledge_base_id:number; document_id:number; document_title:string}) {
  const token = store.token
  try {
    const {data} = await aiApi.get(`/api/rag/knowledge-bases/${source.knowledge_base_id}/documents/${source.document_id}/download`, {responseType:'blob'})
    if (!token || token !== store.token) return
    const url = URL.createObjectURL(data)
    const link = document.createElement('a')
    link.href = url
    link.download = source.document_title
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (e) {
    if (token === store.token) error.value = errorMessage(e)
  }
}
onMounted(loadSessions)
</script>

<template>
  <div class="chat-layout">
    <aside class="session-sidebar">
      <div class="session-heading">
        <strong class="desktop-title">历史会话</strong>
        <button class="history-toggle" :aria-expanded="historyOpen" @click="historyOpen = !historyOpen">历史会话 {{ historyOpen ? '▴' : '▾' }}</button>
        <a-button size="small" :disabled="unavailable" @click="requestReset('new')"><PlusOutlined /> 新建</a-button>
      </div>
      <nav aria-label="历史会话" :class="{ expanded: historyOpen }">
      <p v-if="!sessions.length" class="empty-history">暂无历史会话</p>
      <button
        v-for="session in sessions"
        :key="session.id"
        class="session-item"
        :class="{ selected: sessionId === session.id }"
        @click="openSession(session.id)"
      >
        {{ session.title }}
      </button>
      </nav>
    </aside>
    <section class="chat-panel" aria-label="AI 对话">
      <header class="chat-header">
        <div class="chat-heading">
          <RobotOutlined /><strong>{{ sessions.find(s => s.id === sessionId)?.title || 'AI 运维助手' }}</strong>
        </div>
        <a-dropdown v-if="sessionId" :trigger="['click']" :disabled="sending">
          <a-button type="text" aria-label="会话操作"><MoreOutlined /></a-button>
          <template #overlay>
            <a-menu>
              <a-menu-item @click="beginRename">重命名会话</a-menu-item>
              <a-menu-item danger @click="deleting = true">删除会话</a-menu-item>
            </a-menu>
          </template>
        </a-dropdown>
      </header>
      <a-alert
        v-if="unavailable"
        type="info"
        show-icon
        :message="
          store.demo
            ? '演示环境不支持真实 AI 查询，请登录后使用'
            : '请先登录后使用 AI 查询'
        "
        class="chat-notice"
      />
      <div
        ref="messageContainer"
        class="chat-messages"
        @scroll.passive="trackScroll"
        role="log"
        aria-label="对话记录"
        aria-live="polite"

      >
        <a-button v-if="hasOlder" @click="loadOlder">加载更早消息</a-button>
        <div v-if="!messages.length && !pendingQuestion" class="chat-welcome">
          <RobotOutlined />
          <h2>有什么需要检查？</h2>
          <a-space wrap>
            <a-button v-for="q in ['查看在线服务器', '检查 GPU 负载', '分析训练任务资源竞争']" :key="q"
              :disabled="unavailable" @click="suggest(q)">{{ q }}</a-button>
          </a-space>
        </div>
        <article
          v-for="(item, index) in messages"
          :key="index"
          class="chat-message"
          :class="item.role"
          :aria-label="item.role === 'user' ? '我的问题' : 'AI 回答'"
        >
          <div class="chat-avatar">
            <UserOutlined v-if="item.role === 'user'" /><RobotOutlined v-else />
          </div>
          <div class="chat-bubble">
            <div>{{ item.content }}</div>
            <AgentExecution v-if="item.execution?.length" :steps="item.execution" state="complete" />
            <ResourceResult
              v-for="(result, i) in item.resourceResults"
              :key="i"
              :name="result.name"
              :data="result.data"
            />
            <TraceViewer v-if="item.traceId" :trace-id="item.traceId" />
            <details v-if="item.sources?.length">
              <summary>检索来源（{{ item.sources.length }}）</summary>
              <div v-for="source in item.sources" :key="source.chunk_id">
                <strong>{{ source.document_title }}</strong> · 文档
                {{ source.document_id }} ·
                {{ source.page ? `第 ${source.page} 页` : '无页码' }} ·
                {{ source.section || '无章节' }}
                <p>{{ source.content }}</p>
                <a-button size="small" @click="downloadSource(source)">下载引用原文</a-button>
              </div>
            </details>
          </div>
        </article>
        <article
          v-if="pendingQuestion"
          class="chat-message user"
          aria-label="本次问题"
        >
          <div class="chat-avatar"><UserOutlined /></div>
          <div class="chat-bubble">{{ pendingQuestion }}</div>
        </article>
        <article
          v-if="sending || (pendingQuestion && execution.length)"
          class="chat-message assistant"
          :aria-label="sending ? 'AI 正在回复' : '查询已中断'"
        >
          <div class="chat-avatar"><RobotOutlined /></div>
          <div class="chat-bubble loading-bubble">
            <AgentExecution v-if="execution.length" :steps="execution" :state="sending ? 'live' : 'interrupted'" />
            <a-spin v-if="sending && !execution.length && !streamingAnswer" size="small" />
            <span v-if="toolActivity && !execution.length" class="tool-activity" role="status">{{
              toolActivity
            }}</span>
            <span v-if="streamingAnswer"
              >{{ streamingAnswer
              }}<span class="stream-cursor" aria-hidden="true">▍</span></span
            >
            <span v-else-if="sending && !toolActivity && !execution.length">正在连接智能体…</span>
          </div>
        </article>
      </div>
      <a-alert
        v-if="error"
        type="error"
        show-icon
        closable
        :message="error"
        class="chat-notice"
        role="alert"
        @close="error = ''"
      />
      <a-alert
        v-if="notice"
        type="info"
        show-icon
        :message="notice"
        class="chat-notice"
        role="status"
      />
      <div class="chat-composer">
        <a-textarea
          ref="composer"
          v-model:value="input"
          :auto-size="{ minRows: 2, maxRows: 5 }"
          :disabled="sending || unavailable"
          :status="invalid ? 'error' : undefined"
          placeholder="输入运维问题"
          aria-label="输入运维问题"
          @keydown="handleKeydown"
        />
        <div class="chat-composer-footer">
          <span
            :class="{ 'invalid-count': invalid }"
            :role="invalid ? 'alert' : undefined"
            >{{ inputLength }} / {{ QUESTION_LIMIT
            }}{{ invalid ? '，问题过长' : '' }}</span
          >
          <a-space wrap>
            <a-button v-if="sending" @click="cancel"
              ><template #icon><StopOutlined /></template>取消查询</a-button
            >
            <template v-else>
              <a-button
                v-if="canRetry"
                :disabled="unavailable"
                @click="send(true)"
                ><template #icon><ReloadOutlined /></template>重试</a-button
              >
              <a-button
                type="primary"
                :disabled="!inputLength || invalid || unavailable"
                @click="send()"
                ><template #icon><SendOutlined /></template>发送</a-button
              >
            </template>
          </a-space>
        </div>
      </div>
    </section>
  </div>
  <a-modal v-model:open="deleting" title="删除此会话？" ok-text="删除" cancel-text="保留" @ok="deleteSession">删除后无法恢复此会话。</a-modal>
  <a-modal v-model:open="renaming" title="重命名会话" @ok="renameSession"
    ><a-input v-model:value="renameTitle" :maxlength="160"
  /></a-modal>
  <a-modal
    :open="confirmation !== null"
    :title="confirmation === 'new' ? '新建会话？' : '清空对话？'"
    :ok-text="confirmation === 'new' ? '新建会话' : '清空'"
    cancel-text="保留"
    @ok="confirmReset"
    @cancel="confirmation = null"
  >
    将关闭当前对话，已保存的历史仍可从侧栏恢复；草稿将被清空{{
      sending ? '，正在进行的查询将被取消' : ''
    }}。
  </a-modal>
</template>

<style scoped>
.chat-layout {
  display: grid;
  grid-template-columns: 190px minmax(0, 1fr);
  gap: 12px;
}
.session-sidebar {
  border: 1px solid var(--edge);
  border-radius: 8px;
  padding: 10px;
  max-height: 760px;
  overflow-y: auto;
}
.session-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.history-toggle { display: none; background: transparent; border: 0; color: inherit; cursor: pointer; }
.empty-history { color: var(--muted); margin-top: 14px; }
.chat-heading { flex: 1; min-width: 0; }
.chat-heading strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chat-bubble > details { margin-top: 6px; }
.session-item {
  display: block;
  width: 100%;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--muted);
  padding: 12px 8px;
  margin-top: 5px;
  text-align: left;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}
.session-item.selected {
  background: #12384b;
  color: #27cce3;
}
@media (max-width: 760px) {
  .desktop-title, .session-sidebar nav:not(.expanded) { display: none; }
  .history-toggle { display: inline-block; }
  .chat-layout {
    grid-template-columns: minmax(0, 1fr);
  }
  .session-sidebar {
    max-height: 150px;
  }
}

.chat-panel {
  display: flex;
  flex-direction: column;
  height: min(760px, calc(100dvh - 260px));
  min-height: 480px;
  border: 1px solid var(--edge);
  border-radius: 8px;
  overflow: hidden;
  background: var(--surface);
}
.chat-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  padding: 16px 24px;
  border-bottom: 1px solid var(--edge);
}
.chat-heading {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
}
.chat-heading > .anticon {
  color: #27cce3;
  font-size: 24px;
}
.chat-messages {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.chat-welcome {
  margin: auto;
  text-align: center;
  color: var(--muted);
}
.chat-welcome > .anticon {
  font-size: 36px;
  color: #27cce3;
  margin-bottom: 16px;
}
.chat-welcome h2 {
  font-size: 17px;
}
.chat-message {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  max-width: 90%;
  min-width: 0;
  flex-shrink: 0;
}
.chat-message.user {
  flex-direction: row-reverse;
  align-self: flex-end;
}
.chat-avatar {
  flex: 0 0 32px;
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: #1d3457;
  color: #75a9f1;
}
.user .chat-avatar {
  background: #1e3048;
  color: #91a8c7;
}
.chat-bubble {
  min-width: 0;
  border-radius: 6px;
  background: #172942;
  padding: 12px 16px;
  font-size: 13px;
  line-height: 1.8;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.user .chat-bubble {
  background: #12384b;
}
.loading-bubble {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  color: inherit;
}
.tool-activity {
  color: #27cce3;
  font-size: 12px;
}
.stream-cursor {
  display: inline-block;
  margin-left: 2px;
  color: #27cce3;
  animation: stream-blink 1s steps(2, start) infinite;
}
@keyframes stream-blink {
  to {
    visibility: hidden;
  }
}
.chat-notice {
  margin: 12px 24px 0;
  flex-shrink: 0;
  overflow-wrap: anywhere;
}
.chat-composer {
  border-top: 1px solid var(--edge);
  padding: 16px 24px;
}
.chat-composer textarea {
  resize: none;
}
.chat-composer-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 12px;
  color: var(--muted);
  font-size: 12px;
}
.invalid-count {
  color: #c54048;
}
@media (max-width: 760px) {
  .chat-panel {
    height: 70dvh;
    min-height: 440px;
  }
  .chat-header,
  .chat-messages,
  .chat-composer {
    padding-left: 14px;
    padding-right: 14px;
  }
  .chat-notice {
    margin-left: 14px;
    margin-right: 14px;
  }
  .chat-message {
    max-width: 100%;
    gap: 8px;
  }
}
</style>
