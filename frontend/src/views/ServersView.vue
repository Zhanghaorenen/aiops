<script setup lang="ts">
import { computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { SearchOutlined } from '@ant-design/icons-vue'
import { useWorkspaceStore } from '../stores/workspace'
import HostTable from '../components/HostTable.vue'

const store = useWorkspaceStore()
const router = useRouter()
const route = useRoute()
const search = computed({
  get: () => String(route.query.search ?? ''),
  set: value => { void router.replace({query:{...route.query, search:value || undefined}}) },
})
const status = computed({
  get: () => String(route.query.status ?? 'ALL'),
  set: value => { void router.replace({query:{...route.query, status:value === 'ALL' ? undefined : value}}) },
})
const filtered = computed(() => store.servers.filter(server =>
  (status.value === 'ALL' || server.status === status.value) &&
  `${server.name} ${server.hostname} ${server.ip || ''} ${server.osName || ''}`.toLowerCase().includes(search.value.trim().toLowerCase())
))
</script>

<template>
  <div class="table-toolbar">
    <a-space wrap>
      <a-input v-model:value="search" placeholder="搜索名称、IP、操作系统" allow-clear class="search-input" aria-label="搜索服务器"><template #prefix><SearchOutlined /></template></a-input>
      <a-select v-model:value="status" aria-label="服务器状态" :options="[{ value: 'ALL', label: '全部状态' }, { value: 'ONLINE', label: '在线' }, { value: 'OFFLINE', label: '离线' }]" />
    </a-space>
    <span class="muted compact">共 {{ filtered.length }} 台服务器</span>
  </div>
  <HostTable :servers="filtered" :loading="store.loading" :filtered="store.servers.length > 0" @select="id => router.push({path:'/servers/' + id, query:route.query})" @connect="router.push('/access')" />
</template>
