<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useWorkspaceStore } from '../stores/workspace'
import GpuMonitor from '../components/GpuMonitor.vue'
import GpuUsage from '../components/GpuUsage.vue'
const route = useRoute(),
  store = useWorkspaceStore(),
  selected = ref<number>()
const reservation = computed(() => route.path === '/reservations')
watch(
  () => store.servers,
  (servers) => {
    if (!servers.some((s) => s.id === selected.value))
      selected.value =
        servers.find((s) => s.status === 'ONLINE')?.id ?? servers[0]?.id
  },
  { immediate: true },
)
</script>
<template>
  <div class="table-toolbar">
    <a-space
      ><span>目标服务器</span
      ><a-select
        v-model:value="selected"
        :options="store.servers.map((s) => ({ value: s.id, label: s.name }))"
        style="min-width: 260px" /></a-space
    ><span class="muted">{{
      reservation
        ? '统一时间预约 · 实际进程独立核实'
        : '真实采样 · 不执行服务器操作'
    }}</span>
  </div>
  <template v-if="selected && !store.demo"
    ><GpuUsage v-if="reservation" :server-id="selected" /><GpuMonitor
      v-else
      :server-id="selected" /></template
  ><a-empty v-else description="请登录并选择已接入服务器" />
</template>
