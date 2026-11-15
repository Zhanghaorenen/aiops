<script setup lang="ts">
import { computed,ref,watch } from 'vue'
import { formatTime,formatBytes } from '../utils/format'
import TrendChart from './TrendChart.vue'
const props=defineProps<{report:any}>()
const selected=ref<string>()
watch(()=>props.report,()=>{selected.value=undefined})
const tasks=computed(()=>props.report.activeTasks??props.report.activeRuns??[])
const pairs=computed(()=>(props.report.overlappingPairs??props.report.overlappingRuns??[]).filter((p:any)=>!selected.value||p.runIds.includes(selected.value)))
const task=computed(()=>tasks.value.find((t:any)=>t.id===selected.value))
const intervals=computed(()=>pairs.value.flatMap((p:any)=>p.intervals))
const labels:Record<string,string>={HOST_CPU_PRESSURE:'主机 CPU 等待压力',HOST_IO_PRESSURE:'主机 IO 等待压力',RAM_PRESSURE:'主机内存压力',VRAM_PRESSURE:'显存余量偏低'}
const resources:Record<string,string>={HOST_RAM:'主机内存',HOST_CPU:'主机 CPU',HOST_IO:'磁盘 IO'}
const levels:Record<string,string>={RESOURCE_OVERLAP:'正常共用',RESOURCE_PRESSURE:'发现压力',PERFORMANCE_DEGRADATION:'存在性能下降证据',UNKNOWN:'证据不足'}
const cards=computed(()=>[...new Set<number>((props.report.trends??[]).flatMap((p:any)=>p.gpus.map((g:any)=>g.index)))])
const gpu=ref<number>()
watch(cards,c=>{if(!c.includes(gpu.value!)) gpu.value=c[0]},{immediate:true})
const gpuPoints=computed(()=>(props.report.trends??[]).map((p:any)=>({at:p.at,value:p.gpus.find((g:any)=>g.index===gpu.value)?.utilization??null})))
const ioPoints=computed(()=>(props.report.trends??[]).map((p:any)=>({at:p.at,value:p.ioPsi??null})))
const throughput=computed(()=>Object.entries(props.report.performanceEvidence?.trends??{}).filter(([id])=>!selected.value||id===selected.value).map(([id,points])=>({id,points:(points as any[]).map(p=>({at:p.at,value:p.samplesPerSecond}))})))
function name(id:string) {return props.report.observedRuns?.find((r:any)=>r.id===id)?.displayName??'关联任务'}
const columns=[{title:'任务',key:'task'},{title:'GPU',key:'gpu'},{title:'CPU（单核 %）',key:'cpu'},{title:'显存',key:'memory'}]
function readable(item:string) {
  const parts=item.split('：')
  return parts.length>1?parts.slice(1).join('：'):item.includes('Psi')?'部分主机压力指标未采集':item.endsWith('_UNKNOWN')?'部分资源指标缺失':item
}
</script>
<template>
  <a-card title="资源竞争分析" class="contention-panel">
    <a-row :gutter="16">
      <a-col :span="8"><a-statistic title="当前任务" :value="report.summary?.activeTaskCount??'待确认'" /></a-col>
      <a-col :span="8"><a-statistic title="窗口内重叠组合" :value="report.summary?.overlappingPairCount??pairs.length" /></a-col>
      <a-col :span="8"><a-statistic title="持续压力信号" :value="report.summary?.pressureSignalCount??0" /></a-col>
    </a-row>
    <h3>当前任务</h3>
    <a-alert v-if="!report.window.fresh" type="warning" message="采样已过期，当前任务和资源状态需要重新获取" show-icon />
    <a-empty v-if="!tasks.length" :description="report.window.fresh?'没有可确认的活跃任务，可能存在采集可见性不足':'等待新鲜采样'" />
    <a-table v-else :columns="columns" :data-source="tasks" row-key="id" size="small" :pagination="false" :scroll="{x:520}">
      <template #bodyCell="{column,record}">
        <a-button v-if="column.key==='task'" type="link" @click="selected=selected===record.id?undefined:record.id">{{record.displayName}}</a-button>
        <template v-else-if="column.key==='gpu'">{{record.gpuIndices.join('、')}}</template>
        <template v-else-if="column.key==='cpu'">{{record.resources.cpuPercent?.toFixed(1)??'未知'}}</template>
        <template v-else>{{formatBytes(record.resources.gpuMemoryBytes)}}</template>
      </template>
    </a-table>
    <a-card v-if="task" :title="task.displayName" size="small">
      <p>内存 RSS {{formatBytes(task.resources.rssEstimateBytes)}} · 独占内存 {{formatBytes(task.resources.ussBytes)}}</p>
      <p>磁盘读取 {{formatBytes(task.resources.readBytesPerSecond)}}/s · 写入 {{formatBytes(task.resources.writeBytesPerSecond)}}/s</p>
      <a-button size="small" @click="selected=undefined">查看全部任务组合</a-button>
    </a-card>
    <h3>分析结论</h3>
    <a-alert :message="report.summary?.conclusion??'证据不足'" :type="report.summary?.level==='RESOURCE_OVERLAP'?'success':'info'" show-icon />
    <p v-if="tasks.length===1">当前只观察到一个任务，仍可查看主机和显卡压力。</p>
    <p>分析范围 {{formatTime(report.window.from)}} — {{formatTime(report.window.to)}}；最近采样 {{formatTime(report.window.latestSampleAt)}}</p>
    <a-space wrap><a-tag v-for="(signal,i) in report.pressureSignals??[]" :key="i" color="orange">{{labels[signal.type]??'资源压力'}}{{signal.gpuIndex!=null?' · GPU '+signal.gpuIndex:''}}</a-tag></a-space>
    <p v-if="report.performanceEvidence?.status==='UNKNOWN'" class="muted">{{report.performanceEvidence.reason}}</p>
    <h3>重点任务组合</h3>
    <a-empty v-if="!pairs.length" description="该窗口没有可确认的相关任务重叠组合" />
    <a-card v-for="pair in pairs" :key="pair.runIds.join('|')" size="small" class="pair">
      <strong>{{pair.runIds.map(name).join(' + ')}}</strong> <a-tag>{{levels[pair.level]??'证据不足'}}</a-tag>
      <p>共同使用 {{pair.sharedResources.map((r:string)=>resources[r]??r.replace('GPU_','GPU ')).join('、')||'资源归属待确认'}}</p>
      <p>{{formatTime(pair.window.from)}} — {{formatTime(pair.window.to)}} · 观测重叠 {{pair.observedOverlapSeconds}} 秒</p>
      <p v-for="(c,i) in pair.performanceComparisons??[]" :key="i">{{name(c.taskId)}} · {{c.phase}} 阶段 · {{c.metric==='stepTime'?'每步耗时':'训练吞吐'}}中位数由 {{c.baselineMedian.toFixed(2)}} 变为 {{c.concurrentMedian.toFixed(2)}} {{c.metric==='stepTime'?'秒':'样本/秒'}}（用户上报）</p>
    </a-card>
    <h3>详细证据</h3>
    <p>曲线浅色区域标出已观测的任务并发区间。主机压力和整卡利用率按各自作用域展示。</p>
    <a-select v-if="cards.length" v-model:value="gpu" :options="cards.map(c=>({value:c,label:'GPU '+c}))" style="width:140px" />
    <div class="resource-grid">
      <TrendChart v-if="cards.length" title="整卡 GPU 利用率" unit="%" :points="gpuPoints" :intervals="intervals" />
      <TrendChart title="主机 IO PSI（avg10）" unit="%" :points="ioPoints" :intervals="intervals" />
      <TrendChart v-for="t in throughput" :key="t.id" :title="name(t.id)+' 训练吞吐（用户上报）'" unit="样本/秒" :points="t.points" :intervals="intervals" />
    </div>
    <a-collapse><a-collapse-panel key="missing" header="证据缺口"><p v-for="m in report.missingEvidence" :key="m">{{readable(m)}}</p></a-collapse-panel></a-collapse>
    <p v-for="r in report.recommendations" :key="r">{{r}}</p>
  </a-card>
</template>
<style scoped>
.contention-panel {margin:16px 0;}
h3 {margin-top:24px;}
.pair {margin:12px 0;}
</style>
