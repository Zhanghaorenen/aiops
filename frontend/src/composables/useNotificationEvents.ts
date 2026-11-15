import { onBeforeUnmount, watch } from 'vue'
import { api } from '../api'
import { useWorkspaceStore } from '../stores/workspace'

/** Authenticated SSE invalidates local views. Polling + every reconnect recover from MySQL. */
export function useNotificationEvents(refresh: () => Promise<void>) {
  const store = useWorkspaceStore()
  let controller: AbortController | undefined
  let reconnect: ReturnType<typeof setTimeout> | undefined
  let stopped = false
  let loading = false
  let dirty = false
  let generation = 0
  async function reload() {
    if (stopped || !store.token || store.demo) return
    if (loading) { dirty = true; return }
    loading = true
    try { await refresh() } catch { /* Existing polling is also the outage fallback. */ }
    finally {
      loading = false
      if (dirty) { dirty = false; void reload() }
    }
  }
  function disconnect() {
    generation++
    controller?.abort()
    if (reconnect) clearTimeout(reconnect)
  }
  async function connect() {
    if (stopped || !store.token || store.demo) return
    const version = generation
    const token = store.token
    const abort = new AbortController()
    controller = abort
    // Prevent an unresponsive proxy from leaving a permanently stalled connection.
    const deadline = setTimeout(() => abort.abort(), 65000)
    try {
      const response = await fetch(`${api.defaults.baseURL ?? ''}/api/resources/notifications/stream`, {
        headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
        signal: abort.signal,
      })
      if (response.status === 401) {
        if (token === store.token) window.dispatchEvent(new Event('aiops:unauthorized'))
        return
      }
      if (!response.ok || !response.body) throw new Error('Notification stream unavailable')
      if (version !== generation) return
      void reload() // Includes notifications missed while offline or while subscribing.
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      try {
        while (!abort.signal.aborted) {
          const { done, value } = await reader.read()
          if (done) break
          buffer += decoder.decode(value, { stream: true })
          const frames = buffer.split(/\r?\n\r?\n/)
          buffer = frames.pop() ?? ''
          if (buffer.length > 16384) throw new Error('Invalid notification event')
          for (const frame of frames) {
            if (version !== generation) return
            if (/^event:\s*(refresh|notifications_changed|alerts_changed)\s*$/m.test(frame)) void reload()
          }
        }
      } finally { await reader.cancel().catch(() => {}); reader.releaseLock() }
    } catch { /* Polling remains enabled even when Redis or SSE is unavailable. */ }
    finally {
      clearTimeout(deadline)
      if (!stopped && version === generation && store.token && !store.demo)
        reconnect = setTimeout(() => void connect(), 3000)
    }
  }
  watch([() => store.token, () => store.demo], () => {
    disconnect()
    void connect()
  }, { immediate: true })
  const poll = setInterval(() => void reload(), 15000)
  onBeforeUnmount(() => { stopped = true; disconnect(); clearInterval(poll) })
}
