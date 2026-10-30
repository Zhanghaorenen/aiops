import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { api, type LoginResponse, type Server } from '../api'

const TOKEN_KEY = 'aiops.accessToken'
export interface EnrollmentToken { token: string; expiresAt: string }

export const useWorkspaceStore = defineStore('workspace', () => {
  const token = ref<string | null>(localStorage.getItem(TOKEN_KEY))
  const demo = ref(false)
  const username = ref('')
  const servers = ref<Server[]>([])
  const loading = ref(false)
  const error = ref('')
  const updatedAt = ref<string | null>(null)
  const autoRefresh = ref(true)
  const revision = ref(0)
  const enrollment = ref<EnrollmentToken | null>(null)
  const authenticated = computed(() => Boolean(token.value) || demo.value)

  async function login(values: { username: string; password: string }) {
    const { data } = await api.post<LoginResponse>('/api/auth/login', values)
    localStorage.setItem(TOKEN_KEY, data.accessToken)
    token.value = data.accessToken
    demo.value = false
    username.value = values.username
  }

  function startDemo() {
    demo.value = true
    username.value = '演示访客'
  }

  async function register(values: { username: string; password: string }) {
    await api.post<void>('/api/auth/register', values)
  }
  function logout() {
    localStorage.removeItem(TOKEN_KEY)
    token.value = null
    demo.value = false
    username.value = ''
    servers.value = []
    enrollment.value = null
    error.value = ''
    loading.value = false
    updatedAt.value = null
  }

  async function issueEnrollmentToken() {
    const currentToken = token.value
    const { data } = await api.post<EnrollmentToken>('/api/agent/enrollment-tokens')
    if (token.value === currentToken && !demo.value) enrollment.value = data
  }

  function refresh() { revision.value++ }

  return { token, demo, username, servers, loading, error, updatedAt, autoRefresh, revision,
    register, enrollment, authenticated, login, startDemo, logout, issueEnrollmentToken, refresh }
})
