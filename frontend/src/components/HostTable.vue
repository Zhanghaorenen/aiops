<script setup lang="ts">
import { computed } from 'vue'
import { Empty, type TableColumnsType } from 'ant-design-vue'
import { ArrowRightOutlined, DesktopOutlined, PlusOutlined } from '@ant-design/icons-vue'
import type { Server } from '../api'
import { formatTime } from '../utils/format'
import UsageBar from './UsageBar.vue'

const props = defineProps<{ servers: Server[]; loading?: boolean; compact?: boolean; filtered?: boolean }>()
const emit = defineEmits<{ select: [id: number]; connect: [] }>()
const columns = computed<TableColumnsType<Server>>(() => [
  { title: '服务器', key: 'name', sorter: (a, b) => a.name.localeCompare(b.name) },
  { title: '状态', key: 'status' },
  { title: 'IP 地址', key: 'ip' },
  { title: 'CPU', key: 'cpuUsage', sorter: (a, b) => (a.cpuUsage ?? -1) - (b.cpuUsage ?? -1) },
  { title: '内存', key: 'memoryUsage', sorter: (a, b) => (a.memoryUsage ?? -1) - (b.memoryUsage ?? -1) },
  ...(!props.compact ? [{ title: '磁盘', key: 'diskUsage', sorter: (a: Server, b: Server) => (a.diskUsage ?? -1) - (b.diskUsage ?? -1) }] : []),
  { title: '最后心跳', key: 'lastSeenAt' },
  ...(!props.compact ? [{ title: '', key: 'action' }] : []),
])
</script>

<template>
  <a-table row-key="id" :columns="columns" :data-source="servers" :loading="loading" :scroll="{ x: compact ? 800 : 1050 }" :pagination="compact ? false : { defaultPageSize: 10, showSizeChanger: true }">
    <template #bodyCell="{ column, record }">
      <button v-if="column.key === 'name'" class="host-link" @click="emit('select', record.id)"><span class="host-symbol"><DesktopOutlined /></span><span><strong>{{ record.name }}</strong><small>{{ record.hostname }}</small></span></button>
      <a-tag v-else-if="column.key === 'status'" :color="record.status === 'ONLINE' ? 'success' : 'default'">{{ record.status === 'ONLINE' ? '在线' : '离线' }}</a-tag>
      <span v-else-if="column.key === 'ip'" class="mono">{{ record.ip || '—' }}</span>
      <UsageBar v-else-if="['cpuUsage', 'memoryUsage', 'diskUsage'].includes(String(column.key))" :value="record[column.key]" :stale="record.status !== 'ONLINE'" />
      <span v-else-if="column.key === 'lastSeenAt'" class="muted compact">{{ formatTime(record.lastSeenAt) }}</span>
      <a-tooltip v-else-if="column.key === 'action'" title="查看详情"><a-button :aria-label="`查看 ${record.name}`" @click="emit('select', record.id)"><template #icon><ArrowRightOutlined /></template></a-button></a-tooltip>
    </template>
    <template #emptyText>
      <a-empty :image="Empty.PRESENTED_IMAGE_SIMPLE" :description="filtered ? '没有匹配的服务器' : '还没有服务器接入'"><a-button v-if="!filtered" type="primary" @click="emit('connect')"><template #icon><PlusOutlined /></template>接入第一台服务器</a-button></a-empty>
    </template>
  </a-table>
</template>
