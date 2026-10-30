<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { RobotOutlined, UserOutlined, SendOutlined, DeleteOutlined, PlusOutlined, StopOutlined, ReloadOutlined } from '@ant-design/icons-vue'
import { useWorkspaceStore } from '../stores/workspace'
import { characterCount, QUESTION_LIMIT, useAiChat } from '../composables/useAiChat'

const store = useWorkspaceStore()
const { input, messages, sending, error, notice, pendingQuestion, streamingAnswer, toolActivity, canRetry, send, cancel, reset } = useAiChat(() => ({ demo: store.demo, token: store.token }))
const messageContainer = ref<HTMLElement | null>(null)
const confirmation = ref<'new' | 'clear' | null>(null)
const unavailable = computed(() => store.demo || !store.token)
const inputLength = computed(() => characterCount(input.value.trim()))
const invalid = computed(() => inputLength.value > QUESTION_LIMIT)

watch([() => messages.value.length, sending, pendingQuestion, streamingAnswer, toolActivity, error, notice], async () => {
  await nextTick()
  const element = messageContainer.value
  if (element) element.scrollTop = element.scrollHeight
}, { flush: 'post' })
watch([() => store.token, () => store.demo], () => { reset(); confirmation.value = null })
onBeforeUnmount(cancel)

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
    event.preventDefault()
    void send()
  }
}
function requestReset(action: 'new' | 'clear') {
  if (messages.value.length || input.value || pendingQuestion.value || sending.value) confirmation.value = action
  else reset()
}
function confirmReset() { reset(); confirmation.value = null }
</script>

<template>
  <section class="chat-panel" aria-label="AI 智能运维对话">
    <header class="chat-header">
      <div class="chat-heading"><RobotOutlined /><strong>AI 智能运维助手</strong></div>
      <a-space>
        <a-tooltip title="新建会话"><a-button aria-label="新建会话" @click="requestReset('new')"><template #icon><PlusOutlined /></template></a-button></a-tooltip>
        <a-tooltip title="清空对话"><a-button aria-label="清空对话" :disabled="!messages.length && !input && !pendingQuestion" @click="requestReset('clear')"><template #icon><DeleteOutlined /></template></a-button></a-tooltip>
      </a-space>
    </header>
    <a-alert v-if="unavailable" type="info" show-icon :message="store.demo ? '演示环境不支持真实 AI 查询，请登录后使用' : '请先登录后使用 AI 查询'" class="chat-notice" />
    <div ref="messageContainer" class="chat-messages" role="log" aria-label="对话记录" aria-live="polite" :aria-busy="sending">
      <div v-if="!messages.length && !pendingQuestion" class="chat-welcome"><RobotOutlined /><h2>有什么需要检查？</h2></div>
      <article v-for="(item, index) in messages" :key="index" class="chat-message" :class="item.role" :aria-label="item.role === 'user' ? '我的问题' : 'AI 回答'">
        <div class="chat-avatar"><UserOutlined v-if="item.role === 'user'" /><RobotOutlined v-else /></div>
        <div class="chat-bubble">{{ item.content }}</div>
      </article>
      <article v-if="pendingQuestion" class="chat-message user" aria-label="本次问题"><div class="chat-avatar"><UserOutlined /></div><div class="chat-bubble">{{ pendingQuestion }}</div></article>
      <article v-if="sending" class="chat-message assistant" aria-label="AI 正在回复">
        <div class="chat-avatar"><RobotOutlined /></div>
        <div class="chat-bubble loading-bubble">
          <a-spin v-if="!streamingAnswer" size="small" />
          <span v-if="toolActivity" class="tool-activity" role="status">{{ toolActivity }}</span>
          <span v-if="streamingAnswer">{{ streamingAnswer }}<span class="stream-cursor" aria-hidden="true">▍</span></span>
          <span v-else-if="!toolActivity">正在连接智能体…</span>
        </div>
      </article>
    </div>
    <a-alert v-if="error" type="error" show-icon closable :message="error" class="chat-notice" role="alert" @close="error = ''" />
    <a-alert v-if="notice" type="info" show-icon :message="notice" class="chat-notice" role="status" />
    <div class="chat-composer">
      <a-textarea v-model:value="input" :rows="3" :disabled="sending || unavailable" :status="invalid ? 'error' : undefined" placeholder="输入运维问题" aria-label="输入运维问题" @keydown="handleKeydown" />
      <div class="chat-composer-footer">
        <span :class="{ 'invalid-count': invalid }" :role="invalid ? 'alert' : undefined">{{ inputLength }} / {{ QUESTION_LIMIT }}{{ invalid ? '，问题过长' : '' }}</span>
        <a-space wrap>
          <a-button v-if="sending" @click="cancel"><template #icon><StopOutlined /></template>取消查询</a-button>
          <template v-else>
            <a-button v-if="canRetry" :disabled="unavailable" @click="send(true)"><template #icon><ReloadOutlined /></template>重试</a-button>
            <a-button type="primary" :disabled="!inputLength || invalid || unavailable" @click="send()"><template #icon><SendOutlined /></template>发送</a-button>
          </template>
        </a-space>
      </div>
    </div>
  </section>
  <a-modal :open="confirmation !== null" :title="confirmation === 'new' ? '新建会话？' : '清空对话？'" :ok-text="confirmation === 'new' ? '新建会话' : '清空'" cancel-text="保留" @ok="confirmReset" @cancel="confirmation = null">
    当前对话和草稿将被清空{{ sending ? '，正在进行的查询将被取消' : '' }}。
  </a-modal>
</template>

<style scoped>
.chat-panel { display: flex; flex-direction: column; height: min(760px, calc(100dvh - 260px)); min-height: 480px; border: 1px solid #e4e9ee; border-radius: 8px; overflow: hidden; background: white; }
.chat-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; padding: 16px 24px; border-bottom: 1px solid #edf0f2; }
.chat-heading { display: flex; align-items: center; gap: 10px; font-size: 14px; }
.chat-heading > .anticon { color: #087f78; font-size: 24px; }
.chat-messages { flex: 1; min-height: 0; overflow-y: auto; padding: 24px; display: flex; flex-direction: column; gap: 20px; }
.chat-welcome { margin: auto; text-align: center; color: #74808d; }
.chat-welcome > .anticon { font-size: 36px; color: #087f78; margin-bottom: 16px; }
.chat-welcome h2 { font-size: 17px; }
.chat-message { display: flex; align-items: flex-start; gap: 12px; max-width: 90%; min-width: 0; flex-shrink: 0; }
.chat-message.user { flex-direction: row-reverse; align-self: flex-end; }
.chat-avatar { flex: 0 0 32px; width: 32px; height: 32px; display: grid; place-items: center; border-radius: 50%; background: #edf2fb; color: #4974c5; }
.user .chat-avatar { background: #edf1f5; color: #60727f; }
.chat-bubble { min-width: 0; border-radius: 6px; background: #f3f6f8; padding: 12px 16px; font-size: 13px; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; }
.user .chat-bubble { background: #e7f4f0; }
.loading-bubble { display: flex; align-items: center; gap: 10px; color: #647783; }
.tool-activity { color: #087f78; font-size: 12px; }
.stream-cursor { display: inline-block; margin-left: 2px; color: #087f78; animation: stream-blink 1s steps(2, start) infinite; }
@keyframes stream-blink { to { visibility: hidden; } }
.chat-notice { margin: 12px 24px 0; flex-shrink: 0; overflow-wrap: anywhere; }
.chat-composer { border-top: 1px solid #edf0f2; padding: 16px 24px; }
.chat-composer textarea { resize: none; }
.chat-composer-footer { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; margin-top: 12px; color: #74808d; font-size: 12px; }
.invalid-count { color: #c54048; }
@media (max-width: 760px) {
  .chat-panel { height: 70dvh; min-height: 440px; }
  .chat-header, .chat-messages, .chat-composer { padding-left: 14px; padding-right: 14px; }
  .chat-notice { margin-left: 14px; margin-right: 14px; }
  .chat-message { max-width: 100%; gap: 8px; }
}
</style>
