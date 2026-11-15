<script setup lang="ts">
import { computed } from 'vue'
import { formatTime } from '../utils/format'
import TrendChart from './TrendChart.vue'
const props=defineProps<{ report:any }>()
const labels:Record<string,string>={host:'主机资源',status:'服务器状态',gpu:'显卡概况',gpu_history:'显卡历史',host_history:'主机历史',gpu_processes:'计算进程',runtime_contention:'并发资源分析',workloads:'运行任务',training_analysis:'训练性能',workload_analysis:'任务资源',knowledge:'知识库',historical_cases:'历史经验',linked_alerts:'关联告警'}
const states:Record<string,string>={FRESH:'新鲜采样',STALE:'采样过期',UNKNOWN:'信息不足',FORBIDDEN:'权限受限',UNAVAILABLE:'获取失败',REFERENCE:'参考资料'}
const evidence=computed(()=>props.report.evidence??[])
const supported=computed(()=>(props.report.possible_causes??[]).filter((c:any)=>c.supporting_evidence_ids?.some((id:string)=>evidence.value.some((e:any)=>e.id===id && e.dataStatus==='FRESH'))))
function refs(ids:string[]) {
  return (ids??[]).map(id=>{const e=evidence.value.find((v:any)=>v.id===id);return e ? labels[e.sourceTool??e.kind]+'（'+formatTime(e.sampledAt)+'）' : '来源待核实'}).join('、') || '尚无'
}
function metrics(e:any) {
  const data=e.data??{}
  const fields:[string,string,string][]=[['cpuPercent','CPU 使用率','%'],['ioPsiAvg10','IO PSI','%'],['memoryPsiAvg10','内存 PSI','%'],['temperature','GPU 温度','°C'],['utilization','整卡利用率','%'],['powerUsage','GPU 功率','W'],['clockSpeed','GPU 时钟','MHz']]
  return fields.flatMap(([key,label,unit])=>{
    const n=data[key]??data.details?.[key]
    return typeof n==='number'?[label+' '+n.toFixed(1)+' '+unit]:[]
  })
}
function rows(e:any):any[] {
  const data=e.data
  return Array.isArray(data)?data:(Array.isArray(data?.samples)?data.samples:[])
}
function points(e:any,keys:string[]) {
  return rows(e).map((r:any)=>({at:r.collectedAt??r.collected_at??r.sampledAt,
    value:keys.map(k=>r[k]??r.details?.[k]).find(v=>typeof v==='number')??null})).filter((p:any)=>p.at)
}
const charts=computed(()=>evidence.value.flatMap((e:any)=>{
  const metrics=e.sourceTool==='gpu_history'
    ? [['GPU 利用率','%',['utilization','utilizationPercent','utilization_percent']],['GPU 温度','°C',['temperature','temperatureC','temperature_c']],['GPU 时钟','MHz',['clockSpeed','clockSpeedMhz','clock_speed_mhz']],['GPU 功率','W',['powerUsage','powerUsageW','power_usage_w']]]
    : e.sourceTool==='host_history' ? [['主机 CPU','%',['cpuPercent','cpu_percent']],['主机 IO 压力','%',['ioPsiAvg10']]]:[]
  return metrics.map(([title,unit,keys])=>({id:e.id+title,title:String(title),unit:String(unit),points:points(e,keys as string[])})).filter(c=>c.points.some((p:any)=>p.value!=null))
}))
</script>
<template>
  <section class="investigation-report">
    <a-alert :message="supported.length ? supported[0].hypothesis : '证据不足，暂无法形成有监控支持的判断'"
      :description="['BUDGET_EXHAUSTED','TIME_BUDGET'].includes(report.stopReason) ? '已达到调查预算，尚未确认的问题保留在证据缺口中。' : report.collection_errors?.length ? '部分检查未完成，请查看证据缺口。' : '以下结论依据本次可用证据生成。'" type="info" show-icon />
    <h3>候选原因</h3>
    <a-empty v-if="!report.possible_causes?.length" description="尚无足够证据支持候选原因" />
    <a-card v-for="(cause,i) in report.possible_causes" :key="i" size="small" class="cause">
      <strong>{{ cause.hypothesis }}</strong>
      <p>支持证据 · {{ refs(cause.supporting_evidence_ids) }}</p>
      <p>反对证据 · {{ refs(cause.refuting_evidence_ids) }}</p>
      <p class="muted">{{ cause.uncertainty }}</p>
      <p v-if="cause.required_evidence?.length">仍需核实 · {{ cause.required_evidence.join('；') }}</p>
    </a-card>
    <h3>证据详情</h3>
    <a-list :data-source="evidence">
      <template #renderItem="{item}"><a-list-item>
        <div><strong>{{ labels[item.sourceTool??item.kind]??'监控检查' }}</strong>
          <a-tag>{{ states[item.dataStatus]??'历史证据' }}</a-tag>
          <p>{{ formatTime(item.sampledAt) }} · {{ item.metricScope==='WHOLE_GPU'?'整张显卡':item.metricScope==='HOST'?'整台主机':'对应任务或参考资料' }}</p>
          <p v-if="metrics(item).length">{{ metrics(item).join(' · ') }}</p>
          <p v-if="item.dataStatus!=='FRESH'" class="muted">{{ item.limitations?.join('；') }}</p>
          <details><summary>高级诊断数据</summary><pre>{{ JSON.stringify(item.data,null,2) }}</pre></details>
        </div>
      </a-list-item></template>
    </a-list>
    <div class="resource-grid"><TrendChart v-for="chart in charts" :key="chart.id" :title="chart.title" :unit="chart.unit" :points="chart.points" /></div>
    <h3>尚未确认</h3><p v-for="item in report.missing_evidence" :key="item">{{ item }}</p>
    <h3>建议措施</h3><ol><li v-for="item in report.suggested_steps" :key="item">{{ item }}</li></ol>
    <h3>如何验证效果</h3><ol><li v-for="item in report.verification_steps" :key="item">{{ item }}</li></ol>
    <details><summary>知识与历史参考</summary>
      <p v-for="s in report.references" :key="s.chunk_id">{{ s.document_title }} · {{ s.section }} · {{ s.page??'页码未提供' }}</p>
      <p v-for="s in report.historical_cases" :key="s.case_id">{{ s.title }}（已验证的历史经验）</p>
    </details>
    <details><summary>高级调查轨迹</summary><pre>{{ JSON.stringify(report.trace,null,2) }}</pre></details>
  </section>
</template>
<style scoped>
.cause { margin:12px 0; }
pre { max-height:320px; overflow:auto; white-space:pre-wrap; overflow-wrap:anywhere; }
h3 { margin-top:24px; }
</style>
