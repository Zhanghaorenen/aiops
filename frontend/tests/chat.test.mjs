import { after, before, test } from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import { AxiosError } from 'axios'
import { createSSRApp, h } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { createPinia, setActivePinia } from 'pinia'
import { createServer as createHttpServer } from 'node:http'
import { routerKey } from 'vue-router'

let vite, chat, apiModule, ChatView, storeModule
const storage = new Map()
const events = new EventTarget()
const testToken = 'frontend-test-placeholder'
const signedIn = () => ({ token: testToken, demo: false })
const defer = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }

before(async () => {
  globalThis.localStorage = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) }
  globalThis.window = {
    addEventListener(type, listener, options) { if (listener) events.addEventListener(type, listener, options) },
    removeEventListener: (...args) => events.removeEventListener(...args),
    dispatchEvent: event => events.dispatchEvent(event),
  }
  // Deliberately separate Java and AI addresses to catch accidental client reuse.
  process.env.VITE_API_BASE_URL = 'http://java.invalid'
  process.env.VITE_AI_BASE_URL = 'http://ai.invalid'
  vite = await createServer({ root: fileURLToPath(new URL('..', import.meta.url)), configFile: false, plugins: [vue()], server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  chat = await vite.ssrLoadModule('/src/composables/useAiChat.ts')
  apiModule = await vite.ssrLoadModule('/src/api.ts')
  ChatView = (await vite.ssrLoadModule('/src/views/AiChatView.vue')).default
  storeModule = await vite.ssrLoadModule('/src/stores/workspace.ts')
})
after(async () => { await vite?.close(); delete globalThis.localStorage; delete globalThis.window; delete process.env.VITE_API_BASE_URL; delete process.env.VITE_AI_BASE_URL })

test('question validation counts Unicode and retains the latest 20 bounded history messages', () => {
  assert.throws(() => chat.createChatRequest('  ', []), /请输入/)
  assert.throws(() => chat.createChatRequest('a'.repeat(4001), []), /4000/)
  assert.equal(chat.characterCount('😀'.repeat(4000)), 4000)
  assert.equal(chat.createChatRequest('😀'.repeat(4000), []).question.length, 8000)
  const history = Array.from({ length: 24 }, (_, index) => ({ role: index % 2 ? 'assistant' : 'user', content: `${index}:short` }))
  const request = chat.createChatRequest(' question ', history)
  assert.equal(request.question, 'question')
  assert.equal(request.history.length, 20)
  assert.ok(request.history[0].content.startsWith('4:'))
  const longHistory = history.map(item => ({ ...item, content: item.content + '😀'.repeat(8100) }))
  const bounded = chat.createChatRequest('😀'.repeat(4000), longHistory)
  assert.ok(bounded.history.length < 20)
  assert.equal(bounded.history[0].role, 'user')
  assert.equal(bounded.history.length % 2, 0)
  assert.equal(chat.characterCount(bounded.history[0].content), 8000)
  assert.ok(chat.characterCount(bounded.question) + bounded.history.reduce((sum, item) => sum + chat.characterCount(item.content), 0) <= 50000)
  assert.ok(longHistory[0].content.length > bounded.history[0].content.length)
})

test('follow-up includes completed user/assistant pairs and excludes the current question', async () => {
  const requests = []
  const session = chat.useAiChat(signedIn, async request => { requests.push(structuredClone(request)); return { answer: 'answer ' + requests.length } })
  session.input.value = 'first'
  await session.send()
  session.input.value = 'follow-up'
  await session.send()
  assert.deepEqual(requests[0], { question: 'first', history: [] })
  assert.deepEqual(requests[1], { question: 'follow-up', history: [{ role: 'user', content: 'first' }, { role: 'assistant', content: 'answer 1' }] })
  assert.equal(session.messages.value.length, 4)
})

test('failure and retry keep the exact context without duplicating a question', async () => {
  const requests = []
  const session = chat.useAiChat(signedIn, async request => { requests.push(structuredClone(request)); if (requests.length === 1) throw new Error('service unavailable'); return { answer: 'recovered' } })
  session.input.value = 'check server'
  await session.send()
  assert.equal(session.error.value, 'service unavailable')
  assert.equal(session.input.value, 'check server')
  assert.equal(session.canRetry.value, true)
  assert.deepEqual(session.messages.value, [])
  await session.send(true)
  assert.deepEqual(requests[0], requests[1])
  assert.equal(session.messages.value.length, 2)
  assert.equal(session.error.value, '')
  assert.equal(session.canRetry.value, false)
})

test('cancel aborts promptly and ignores a late result while a new request is active', async () => {
  const old = defer(), latest = defer()
  let count = 0, oldSignal
  const session = chat.useAiChat(signedIn, (_, signal) => { count++; if (count === 1) { oldSignal = signal; return old.promise } return latest.promise })
  session.input.value = 'old'
  const first = session.send()
  await session.send()
  assert.equal(count, 1)
  session.cancel()
  assert.equal(oldSignal.aborted, true)
  assert.equal(session.sending.value, false)
  assert.equal(session.input.value, 'old')
  session.input.value = 'new'
  const second = session.send()
  old.resolve({ answer: 'stale' })
  await first
  assert.equal(session.sending.value, true)
  assert.deepEqual(session.messages.value, [])
  latest.resolve({ answer: 'fresh' })
  await second
  assert.deepEqual(session.messages.value.map(item => item.content), ['new', 'fresh'])
})

test('reset aborts and clears drafts; late failures cannot contaminate the new session', async () => {
  const pending = defer()
  const session = chat.useAiChat(signedIn, () => pending.promise)
  session.input.value = 'question'
  const sending = session.send()
  session.reset()
  pending.reject(new Error('late failure'))
  await sending
  assert.equal(session.input.value, '')
  assert.equal(session.error.value, '')
  assert.equal(session.pendingQuestion.value, '')
  assert.equal(session.canRetry.value, false)
  assert.deepEqual(session.messages.value, [])
})

test('demo, signed-out and invalid input never invoke transport; changed identity discards the response', async () => {
  let calls = 0
  for (const identity of [{ demo: true, token: testToken }, { demo: false, token: null }]) {
    const session = chat.useAiChat(() => identity, async () => { calls++; return { answer: 'forbidden' } })
    session.input.value = 'test'
    await session.send()
    assert.ok(session.error.value)
  }
  const session = chat.useAiChat(signedIn, async () => { calls++; return { answer: 'no' } })
  session.input.value = 'a'.repeat(4001)
  await session.send()
  assert.equal(calls, 0)
  let identity = signedIn()
  const pending = defer()
  const other = chat.useAiChat(() => identity, () => pending.promise)
  other.input.value = 'test'
  const sending = other.send()
  identity = { token: 'another-test-identity', demo: false }
  pending.resolve({ answer: 'private' })
  await sending
  assert.deepEqual(other.messages.value, [])
  assert.equal(other.pendingQuestion.value, '')
})

test('empty or malformed model answer can be retried and does not enter history', async () => {
  for (const answer of [undefined, '', '   ']) {
    const session = chat.useAiChat(signedIn, async () => ({ answer }))
    session.input.value = 'test'
    await session.send()
    assert.match(session.error.value, /无效回答/)
    assert.equal(session.canRetry.value, true)
    assert.equal(session.messages.value.length, 0)
  }
})

function responseError(status, data, config = {}) {
  return new AxiosError('Request failed', 'ERR_BAD_RESPONSE', config, undefined, { status, data, config, headers: {}, statusText: '' })
}

test('FastAPI detail strings/validation arrays and Spring message are safely extracted', () => {
  assert.equal(apiModule.errorMessage(responseError(502, { detail: '模型不可用' })), '模型不可用')
  assert.equal(apiModule.errorMessage(responseError(400, { message: '业务错误' })), '业务错误')
  assert.equal(apiModule.errorMessage(responseError(422, { detail: [{ loc: ['body', 'history', 0, 'content'], msg: 'Too long', input: 'do not expose' }] })), 'body.history.0.content: Too long')
  for (const status of [401, 403, 422, 502, 503, 504, 508]) assert.ok(apiModule.errorMessage(responseError(status, {})))
  assert.equal(typeof apiModule.errorMessage(responseError(422, { detail: [null, {}, { msg: 4 }], message: {} })), 'string')
})

test('AI uses its own baseURL, sends JWT and AbortSignal, and shares unauthorized handling', async () => {
  const { api, aiApi, sendChat } = apiModule
  assert.equal(api.defaults.baseURL, 'http://java.invalid')
  assert.equal(aiApi.defaults.baseURL, 'http://ai.invalid')
  storage.set('aiops.accessToken', testToken)
  const controller = new AbortController()
  aiApi.defaults.adapter = async config => {
    assert.equal(config.url, '/api/chat')
    assert.equal(config.headers.Authorization, `Bearer ${testToken}`)
    assert.equal(config.signal, controller.signal)
    assert.equal(config.timeout, 120000)
    assert.deepEqual(JSON.parse(config.data), { question: 'test', history: [] })
    return { status: 200, data: { answer: 'ok' }, headers: {}, statusText: 'OK', config }
  }
  assert.equal((await sendChat({ question: 'test', history: [] }, controller.signal)).answer, 'ok')
  let unauthorized = 0
  const listener = () => unauthorized++
  events.addEventListener('aiops:unauthorized', listener)
  aiApi.defaults.adapter = async config => { throw responseError(503, { detail: '请配置模型' }, config) }
  await assert.rejects(sendChat({ question: 'test', history: [] }), error => apiModule.errorMessage(error) === '请配置模型')
  assert.equal(storage.get('aiops.accessToken'), testToken)
  assert.equal(unauthorized, 0)
  aiApi.defaults.adapter = async config => { throw responseError(401, { detail: 'expired' }, config) }
  await assert.rejects(sendChat({ question: 'test', history: [] }))
  assert.equal(storage.has('aiops.accessToken'), false)
  assert.equal(unauthorized, 1)
  storage.set('aiops.accessToken', testToken)
  api.defaults.adapter = async config => { throw responseError(401, { message: 'wrong password' }, config) }
  await assert.rejects(api.post('/api/auth/login', {}))
  assert.equal(storage.get('aiops.accessToken'), testToken)
  assert.equal(unauthorized, 1)
  await assert.rejects(api.post('/api/auth/register', {}))
  assert.equal(storage.has('aiops.accessToken'), false)
  assert.equal(unauthorized, 2)
  events.removeEventListener('aiops:unauthorized', listener)
  storage.clear()
})

test('AI view renders model HTML as escaped text, never active elements', async () => {
  const malicious = '<img src=x onerror="alert(1)"><script>alert(2)</script>'
  const pinia = createPinia()
  setActivePinia(pinia)
  storeModule.useWorkspaceStore().token = testToken
  let state
  const app = createSSRApp({ ...ChatView, setup(props, context) {
    state = ChatView.setup(props, context)
    state.messages.value = [{ role: 'assistant', content: malicious }]
    return state
  } }).use(pinia)
  const container = { setup: (_, { slots }) => () => h('div', slots.default?.()) }
  for (const name of ['a-space', 'a-tooltip', 'a-button', 'a-spin']) app.component(name, container)
  app.component('a-textarea', { render: () => h('textarea') })
  app.component('a-modal', { render: () => null })
  app.component('a-alert', { render: () => null })
  const html = await renderToString(app)
  assert.ok(html.includes('&lt;img'))
  assert.ok(html.includes('&lt;script&gt;'))
  assert.ok(!html.includes('<img'))
  assert.ok(!html.includes('<script>'))
  state.input.value = 'draft'
  state.requestReset('new')
  assert.equal(state.confirmation.value, 'new')
  assert.equal(state.input.value, 'draft')
  assert.equal(state.messages.value.length, 1)
  state.confirmReset()
  assert.equal(state.input.value, '')
  assert.equal(state.messages.value.length, 0)
  state.input.value = 'draft'
  state.requestReset('clear')
  assert.equal(state.confirmation.value, 'clear')
  let prevented = false
  state.handleKeydown({ key: 'Enter', isComposing: true, preventDefault() { prevented = true } })
  assert.equal(prevented, false)
  state.handleKeydown({ key: 'Enter', shiftKey: true, preventDefault() { prevented = true } })
  assert.equal(prevented, false)
})

test('registration draft blocks anonymous/demo submissions and keeps authenticated payload separate', async () => {
  const Register = (await vite.ssrLoadModule('/src/views/Register.vue')).default
  const pinia = createPinia()
  setActivePinia(pinia)
  const store = storeModule.useWorkspaceStore()
  store.token = null
  let state, calls = 0
  apiModule.api.defaults.adapter = async config => {
    calls++
    assert.equal(config.url, '/api/auth/register')
    assert.equal(config.headers.Authorization, `Bearer ${testToken}`)
    assert.deepEqual(JSON.parse(config.data), { username: 'testuser', password: 'mock-password-only' })
    return { status: 201, data: null, config, headers: {}, statusText: 'Created' }
  }
  const app = createSSRApp({ ...Register, setup(props, context) { state = Register.setup(props, context); return state } }).use(pinia)
  app.provide(routerKey, { replace: async () => undefined })
  const container = { setup: (_, { slots }) => () => h('div', slots.default?.()) }
  for (const name of ['a-form', 'a-form-item', 'a-input', 'a-input-password', 'a-button', 'RouterLink']) app.component(name, container)
  app.component('a-modal', { render: () => null })
  app.component('a-alert', { setup: (_, { attrs }) => () => h('div', String(attrs.message)) })
  const html = await renderToString(app)
  assert.ok(html.includes('公开注册暂未开放'))
  await state.register()
  assert.equal(calls, 0)
  store.token = testToken
  store.demo = true
  await state.register()
  assert.equal(calls, 0)
  state.form.password = 'mock-password-only'
  await assert.rejects(state.validateConfirmation(null, 'different'), /不一致/)
  await state.validateConfirmation(null, state.form.password)
  store.demo = false
  storage.set('aiops.accessToken', testToken)
  state.form.username = 'testuser'
  state.form.reinput_password = state.form.password
  await state.register()
  assert.equal(calls, 1)
  assert.equal(state.dialogVisible.value, true)
  assert.equal(state.form.password, '')
  storage.clear()
})

test('Vite sends chat and AI health to FastAPI while business API stays on Java', async () => {
  const requests = []
  const stub = service => createHttpServer((request, response) => {
    let body = ''
    request.on('data', chunk => { body += chunk })
    request.on('end', () => {
      requests.push({ service, path: request.url, authorization: request.headers.authorization, body })
      response.setHeader('Content-Type', 'application/json')
      response.end(JSON.stringify(service === 'ai' && request.url === '/api/ai/health' ? { status: 'ok', service: 'ai-service', llmConfigured: false } : { service }))
    })
  })
  const ai = stub('ai'), java = stub('java')
  const listen = server => new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  let dev
  try {
    await Promise.all([listen(ai), listen(java)])
    process.env.AIOPS_AI_PROXY_TARGET = `http://127.0.0.1:${ai.address().port}`
    process.env.AIOPS_PROXY_TARGET = `http://127.0.0.1:${java.address().port}`
    dev = await createServer({ root: fileURLToPath(new URL('..', import.meta.url)), server: { host: '127.0.0.1', port: 0, hmr: false, open: false } })
    await dev.listen()
    const origin = `http://127.0.0.1:${dev.httpServer.address().port}`
    await fetch(`${origin}/api/chat`, { method: 'POST', headers: { Authorization: `Bearer ${testToken}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ question: 'test', history: [] }) }).then(response => response.json())
    const health = await fetch(`${origin}/api/ai/health`).then(response => response.json())
    await fetch(`${origin}/api/servers`).then(response => response.json())
    assert.equal(health.llmConfigured, false)
    assert.deepEqual(requests.map(({ service, path }) => ({ service, path })), [{ service: 'ai', path: '/api/chat' }, { service: 'ai', path: '/api/ai/health' }, { service: 'java', path: '/api/servers' }])
    assert.equal(requests[0].authorization, `Bearer ${testToken}`)
    assert.deepEqual(JSON.parse(requests[0].body), { question: 'test', history: [] })
  } finally {
    await dev?.close()
    await Promise.all([ai, java].map(server => new Promise(resolve => { server.close(resolve); server.closeAllConnections() })))
    delete process.env.AIOPS_AI_PROXY_TARGET
    delete process.env.AIOPS_PROXY_TARGET
  }
})
