import { computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useWorkspaceStore } from '../stores/workspace'

export function useServerSelection() {
  const store = useWorkspaceStore()
  const route = useRoute()
  const router = useRouter()
  const requested = computed(() => route?.query.server)
  const server = computed({
    get() {
      if (requested.value !== undefined)
        return store.servers.find(s => String(s.id) === requested.value)?.id
      return (store.servers.find(s => s.status === 'ONLINE') ?? store.servers[0])?.id
    },
    set(id: number | undefined) {
      if (id !== undefined) void router.replace({ query: { ...route.query, server: String(id) } })
    },
  })
  watch(server, id => {
    if (route && requested.value === undefined && id !== undefined) server.value = id
  }, { immediate: true })
  const unavailable = computed(() => requested.value !== undefined && !server.value && !store.loading && !!store.updatedAt)
  return { server, unavailable }
}
