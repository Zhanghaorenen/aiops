<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useWorkspaceStore } from '../stores/workspace'
import { useServerSelection } from '../composables/useServerSelection'
import GpuMonitor from '../components/GpuMonitor.vue'
import GpuUsage from '../components/GpuUsage.vue'
const route = useRoute(),
  store = useWorkspaceStore()
const {server: selected, unavailable} = useServerSelection()
const reservation = computed(() => route.path === '/reservations')
</script>
<template>
  <div class="table-toolbar">
    <a-space wrap
      ><span>目标服务器</span
      ><a-select
        v-model:value="selected"
        :options="store.servers.map((s) => ({ value: s.id, label: s.name }))"
        style="min-width: 180px"
    />
      <RouterLink v-if="selected" :to="{path:'/training', query:{server:selected}}">查看本机训练任务</RouterLink>
      <RouterLink v-if="selected" :to="'/servers/' + selected">主机详情</RouterLink>
    </a-space>
  </div>
  <template v-if="selected && !store.demo"
    ><GpuUsage v-if="reservation" :key="'usage-' + selected" :server-id="selected" /><GpuMonitor
      v-else
      :key="selected" :server-id="selected" /></template
  ><a-empty v-else :description="unavailable ? '该服务器不存在或当前账户无权查看，请重新选择' : store.demo ? '登录真实环境查看 GPU 数据' : '请选择已接入的服务器'" />
</template>
