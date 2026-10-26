<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { Empty } from 'ant-design-vue'
import { ArrowRightOutlined, CheckCircleOutlined, DashboardOutlined, DatabaseOutlined, DesktopOutlined } from '@ant-design/icons-vue'
import { useWorkspaceStore } from '../stores/workspace'
import { formatPercent, formatTime } from '../utils/format'
import SummaryStat from '../components/SummaryStat.vue'
import HostTable from '../components/HostTable.vue'

const store = useWorkspaceStore()
const router = useRouter()
const online = computed(() => store.servers.filter(s => s.status === 'ONLINE'))
function average(key: 'cpuUsage' | 'memoryUsage') {
  const available = online.value.filter(s => s[key] != null)
  return available.length ? (available.reduce((sum, s) => sum + s[key]!, 0) / available.length).toFixed(1) : '—'
}
const ranking = computed(() => online.value.filter(s => s.cpuUsage != null).sort((a, b) => b.cpuUsage! - a.cpuUsage!).slice(0, 5))
const attention = computed(() => store.servers.filter(s => s.status !== 'ONLINE' || [s.cpuUsage, s.memoryUsage, s.diskUsage].some(v => v != null && v >= 80)))
function selectServer(id: number) { void router.push(`/servers/${id}`) }
</script>

<template>
  <div class="summary-grid">
    <SummaryStat label="服务器总量" :value="store.servers.length" suffix="台" :note="`${store.servers.length - online.length} 台离线`"><DesktopOutlined /></SummaryStat>
    <SummaryStat label="在线服务器" :value="online.length" suffix="台" :note="store.servers.length ? `在线率 ${(online.length / store.servers.length * 100).toFixed(0)}%` : '等待 Agent 接入'"><CheckCircleOutlined /></SummaryStat>
    <SummaryStat label="平均 CPU" :value="average('cpuUsage')" suffix="%" note="在线主机最新采样"><DashboardOutlined /></SummaryStat>
    <SummaryStat label="平均内存" :value="average('memoryUsage')" suffix="%" note="在线主机最新采样"><DatabaseOutlined /></SummaryStat>
  </div>
  <div class="section-heading"><h2>资源概况</h2><a-button type="link" @click="router.push('/servers')">全部服务器 <ArrowRightOutlined /></a-button></div>
  <HostTable :servers="store.servers.slice(0, 5)" :loading="store.loading" compact @select="selectServer" @connect="router.push('/access')" />
  <div class="overview-lower">
    <section>
      <div class="section-heading"><h2>CPU 使用率排行</h2><span class="muted compact">在线主机</span></div>
      <button v-for="(server, index) in ranking" :key="server.id" class="rank-row" @click="selectServer(server.id)"><span class="rank-number">0{{ index + 1 }}</span><span>{{ server.name }}</span><a-progress :percent="server.cpuUsage!" :show-info="false" :stroke-color="server.cpuUsage! >= 80 ? '#d39429' : '#087f78'" /><strong>{{ formatPercent(server.cpuUsage) }}</strong></button>
      <a-empty v-if="!ranking.length" :image="Empty.PRESENTED_IMAGE_SIMPLE" description="暂无在线采样" />
    </section>
    <section>
      <div class="section-heading"><h2>需要关注 <a-tag>{{ attention.length }}</a-tag></h2><a-tooltip title="离线主机，或任一资源使用率达到 80%"><span class="muted compact">状态与资源检查</span></a-tooltip></div>
      <button v-for="server in attention" :key="server.id" class="attention-row" @click="selectServer(server.id)"><span class="connection-dot" :class="server.status === 'ONLINE' ? 'warm' : 'bad'" /><span><strong>{{ server.name }}</strong><small>{{ server.status !== 'ONLINE' ? `主机离线 · 最后心跳 ${formatTime(server.lastSeenAt)}` : `资源使用率较高 · CPU ${formatPercent(server.cpuUsage)} / 内存 ${formatPercent(server.memoryUsage)} / 磁盘 ${formatPercent(server.diskUsage)}` }}</small></span><ArrowRightOutlined /></button>
      <a-empty v-if="!attention.length" :image="Empty.PRESENTED_IMAGE_SIMPLE" description="暂无需要关注的主机" />
    </section>
  </div>
</template>
