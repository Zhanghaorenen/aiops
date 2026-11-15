<script setup lang="ts">
import { computed } from 'vue'
import { formatTime, formatBytes } from '../utils/format'
import TrendChart from './TrendChart.vue'
const props = defineProps<{ report: any }>()
const labels: Record<string,string> = { HOST_CPU_PRESSURE:'主机 CPU 压力', HOST_IO_PRESSURE:'主机 IO 压力', RAM_PRESSURE:'RAM 压力', LOW_GPU_UTILIZATION:'整卡 GPU 利用率低', HIGH_GPU_LOAD:'整卡 GPU 高负载', VRAM_PRESSURE:'显存余量偏低' }
const status = computed(() => ({UNKNOWN:'证据不足 / 当前状态未知',OBSERVED_PRESSURE:'窗口内观测到持续风险信号',NO_OVERLAP_OBSERVED:'窗口内未观测到任务重叠',NO_PRESSURE_OBSERVED:'窗口内未观测到持续压力'} as Record<string,string>)[props.report.status] ?? props.report.status)
const columns = [{title:'任务',dataIndex:'displayName'}, {title:'GPU',key:'gpu'}, {title:'CPU（单核 %）',key:'cpu'}, {title:'RSS / USS',key:'ram'}, {title:'读 / 写速率',key:'io'}, {title:'身份',dataIndex:'identityStatus'}]
function points(key:string) { return (props.report.evidence ?? []).map((p:any) => ({at:p.collectedAt,value:p.host?.[key] ?? null})) }
function name(id:string) { return props.report.observedRuns?.find((r:any)=>r.id===id)?.displayName ?? id }
</script>
<template>
  <a-card title="运行态资源竞争分析" class="contention-panel">
    <a-alert :message="status" :type="report.status === 'NO_PRESSURE_OBSERVED' ? 'info' : 'warning'" show-icon />
    <p>{{ formatTime(report.window.from) }} — {{ formatTime(report.window.to) }} · {{ report.sampleCount }} 个快照</p>
    <p class="muted">最近采样 {{ formatTime(report.window.latestSampleAt) }}；主机指标最多向前对齐 30 秒。风险信号来自窗口内至少三个连续采样，不代表已确认根因。</p>
    <a-table :columns="columns" :data-source="report.activeRuns" row-key="id" size="small" :scroll="{x:950}">
      <template #bodyCell="{column,record}">
        <template v-if="column.key==='gpu'">{{ record.gpuIndices.join('、') }}</template>
        <template v-else-if="column.key==='cpu'">{{ record.resources.cpuPercent?.toFixed(1) ?? '未知' }}</template>
        <template v-else-if="column.key==='ram'">{{ formatBytes(record.resources.rssEstimateBytes) }} / {{ formatBytes(record.resources.ussBytes) }}</template>
        <template v-else-if="column.key==='io'">{{ formatBytes(record.resources.readBytesPerSecond) }}/s / {{ formatBytes(record.resources.writeBytesPerSecond) }}/s</template>
      </template>
    </a-table>
    <p v-if="!report.activeRuns.length">没有可确认的新鲜活跃进程；不将缺测解释为任务已退出。</p>
    <a-space wrap><a-tag v-for="(risk,i) in report.possibleBottlenecks" :key="i" color="orange">{{ labels[risk.type] || risk.type }}{{ risk.gpuIndex != null ? ` · GPU ${risk.gpuIndex}` : '' }}</a-tag></a-space>
    <div v-if="report.evidence.length" class="resource-grid">
      <TrendChart title="主机 IO PSI（avg10）" unit="%" :points="points('ioPsiAvg10')" />
      <TrendChart title="主机内存 PSI（avg10）" unit="%" :points="points('memoryPsiAvg10')" />
      <TrendChart title="主机 CPU PSI（avg10）" unit="%" :points="points('cpuPsiAvg10')" />
    </div>
    <a-collapse>
      <a-collapse-panel key="pairs" :header="`重叠候选组合（${report.overlappingRuns.length}）`">
        <div v-for="pair in report.overlappingRuns" :key="pair.runIds.join('|')" class="pair">
          <strong>{{ pair.runIds.map(name).join(' + ') }}</strong>
          <p>{{ formatTime(pair.window.from) }} — {{ formatTime(pair.window.to) }}；{{ pair.alignedSampleCount }} 个共现快照 / {{ pair.observedOverlapSeconds }} 秒观测跨度；{{ pair.status }}</p>
          <p>共同使用的资源：{{ pair.sharedResources.join('、') || '未知' }}</p>
          <p>{{ pair.conclusion }}</p>
          <a-tag v-for="(risk,i) in pair.possibleBottlenecks" :key="i">{{ labels[risk.type] || risk.type }}</a-tag>
        </div>
      </a-collapse-panel>
      <a-collapse-panel key="missing" :header="`缺失证据（${report.missingEvidence.length}）`"><p v-for="item in report.missingEvidence" :key="item">{{ item }}</p></a-collapse-panel>
      <a-collapse-panel key="evidence" header="对齐采样证据（最多120点）"><pre>{{ JSON.stringify(report.evidence, null, 2) }}</pre></a-collapse-panel>
    </a-collapse>
    <p v-for="item in report.recommendations" :key="item">{{ item }}</p>
    <p class="muted">未登记进程显示为候选任务；RSS 含共享页，USS 不含共享页；整卡 GPU 利用率不归因于单个进程。本分析不会操作训练任务。</p>
  </a-card>
</template>
<style scoped>
.contention-panel { margin:16px 0; }
.pair { border-bottom:1px solid var(--border); padding:8px 0; }
pre { max-height:360px; overflow:auto; white-space:pre-wrap; }
</style>
