<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { api, errorMessage } from '../api'
import TraceViewer from '../components/TraceViewer.vue'
import InvestigationReport from '../components/InvestigationReport.vue'
import InvestigationPolicy from '../components/InvestigationPolicy.vue'
import { useWorkspaceStore } from '../stores/workspace'
const props = defineProps<{ incidentId?: number; detailOnly?: boolean }>()
const store = useWorkspaceStore(),
  items = ref<any[]>([]),
  detail = ref<any>(null),

  error = ref(''),
  busy = ref(false),
  reviewNote = ref(''),
  canManage = computed(() => ['ADMIN', 'OPERATOR'].includes(store.role))
function row(r: any): any {
  if (Array.isArray(r)) return r.map(row)
  if (r && typeof r === 'object')
    return Object.fromEntries(
      Object.entries(r).map(([k, v]) => [k.toLowerCase(), row(v)]),
    )
  return r
}
let epoch=0,openVersion=0
async function load() {
  if (store.demo || !store.token) return
  const current=epoch
  if(!props.detailOnly) {
    const {data}=await api.get('/api/incidents')
    if(current!==epoch) return
    items.value=row(data)
  }
  if (detail.value) await open(detail.value.id)
}
async function run(fn: () => Promise<void>) {
  const current=epoch
  busy.value = true
  error.value = ''
  try {
    await fn()
  } catch (e) {
    if(current===epoch) error.value = errorMessage(e)
  } finally {
    if(current===epoch) busy.value = false
  }
}
async function open(id: number) {
  const current=epoch,version=++openVersion
  const {data}=await api.get(`/api/incidents/${id}`)
  if(current===epoch && version===openVersion) detail.value=row(data)
}
const stateLabel:Record<string,string>={PENDING:'排队',RUNNING:'调查中',SUCCEEDED:'成功',FAILED:'失败'}
const checkLabel:Record<string,string>={host:'主机指标',gpu:'显卡状态',host_history:'主机历史',gpu_history:'显卡历史',runtime_contention:'资源压力',gpu_processes:'计算进程',workloads:'活跃任务',workload_analysis:'任务资源',training_analysis:'训练性能',knowledge:'知识库',historical_cases:'历史故障经验',status:'在线状态'}
function progress(raw:string) { try { return JSON.parse(raw??'{}') } catch { return {} } }
const traceQuery = ref('')
async function findTrace() {
  const current=epoch
  const {data}=await api.get(`/api/investigation-traces/${encodeURIComponent(traceQuery.value.trim())}`)
  if(current===epoch) await open(data.incidentId)
}
const keys = new Map<number, string>()
async function investigate() {
  const id = detail.value.id
  if (!keys.has(id)) keys.set(id, crypto.randomUUID())
  await run(async () => {
    await api.post(
      `/api/incidents/${id}/investigations`,
      {},
      { headers: { 'Idempotency-Key': keys.get(id) } },
    )
    keys.delete(id)
    await open(id)
  })
}
async function status(value: string) {
  await run(async () => {
    await api.patch(`/api/incidents/${detail.value.id}/status`, {
      status: value,
    })
    await load()
  })
}
function report(raw: string) {
  try {
    return JSON.parse(raw)
  } catch {
    return { missing_evidence: ['报告格式不可读'], evidence: [] }
  }
}
async function review(id: number, helpful: boolean) {
  await run(async () => {
    await api.post(`/api/diagnosis-reports/${id}/review`, {
      helpful,
      note: reviewNote.value,
    })
    await open(detail.value.id)
  })
}
watch(
  [() => props.incidentId,()=>store.token,()=>store.demo],
  ([id]) => {
    epoch++
    detail.value=null
    items.value=[]
    keys.clear()
    error.value=''
    if (id && store.token && !store.demo) void run(() => open(Number(id)))
  },
  { immediate: true,flush:'sync' },
)
onMounted(() => {
  if (!props.detailOnly) void run(load)
})
const timer = setInterval(() => {
  if (!busy.value) void run(load)
}, 5000)
onUnmounted(() => {epoch++;clearInterval(timer)})
const columns = [
  { title: '事件', dataIndex: 'id' },
  { title: '标题', dataIndex: 'title' },
  { title: '服务器', dataIndex: 'server_id' },
  { title: '状态', dataIndex: 'status' },
  { title: '操作', key: 'action' },
]
</script>
<template>
  <a-space direction="vertical" style="width: 100%" :size="16"
    ><a-alert v-if="error" type="error" :message="error" /><a-card
      v-if="!detailOnly"
      title="历史故障调查"
      ><TraceViewer /><a-input-search v-model:value="traceQuery" placeholder="按调查 Trace ID 查找" @search="() => run(findTrace)" />
      <a-table :columns="columns" :data-source="items" row-key="id"
        ><template #bodyCell="{ column, record }"
          ><a-button
            v-if="column.key === 'action'"
            @click="() => run(() => open(record.id))"
            >查看</a-button
          ></template
        ></a-table
      ></a-card
    ><a-card v-if="detail" :title="`#${detail.id} ${detail.title}`"
      ><p>{{ detail.description }}</p>
      <a-space
        ><a-button
          type="primary"
          :loading="busy"
          :disabled="
            detail.runs?.some((r: any) =>
              ['PENDING', 'RUNNING'].includes(r.status),
            )
          "
          @click="investigate"
          >AI 深度调查</a-button
        ><a-select
          v-if="canManage"
          :value="detail.status"
          :options="
            ['OPEN', 'INVESTIGATING', 'RESOLVED'].map((value) => ({
              value,
              label: value,
            }))
          "
          style="width: 180px"
          @change="(v: any) => status(v)" /></a-space
      ><a-list :data-source="detail.runs"
        ><template #renderItem="{ item }"
          ><a-list-item
            ><div>调查 #{{ item.id }} · {{ stateLabel[item.status]??'状态待更新' }}
              <p v-if="item.recovery_attempts">租约中断后已重新领取 {{ item.recovery_attempts }} 次</p>
              <p v-if="item.status==='FAILED'">调查未完成，可能因超时、权限变化或依赖故障。可以重新发起。</p>
              <p v-if="item.progress_json">{{ progress(item.progress_json).stage }} · 已完成 {{ progress(item.progress_json).toolCalls??0 }} 次检查</p>
              <a-space wrap><a-tag v-for="(c,i) in progress(item.progress_json).checks??[]" :key="i">{{ checkLabel[c.tool]??'监控检查' }} · {{ c.status==='FRESH'?'已取得新鲜数据':c.status==='UNAVAILABLE'?'暂不可用':c.status==='FORBIDDEN'?'权限受限':'已记录' }}</a-tag></a-space>
            </div></a-list-item
          ></template
        ></a-list
      ><a-card
        v-for="item in detail.reports"
        :key="item.id"
        :title="`诊断报告 #${item.id}`"
        style="margin-top: 16px"
        ><InvestigationReport :report="report(item.report_json)" />
        <template v-if="canManage"
          ><a-input
            v-model:value="reviewNote"
            placeholder="人工审核意见"
            :maxlength="2000"
          /><a-space
            ><a-button @click="review(item.id, true)">有帮助</a-button
            ><a-button @click="review(item.id, false)">需改进</a-button
            ><span
              >反馈：{{
                item.helpful == null
                  ? '未审核'
                  : item.helpful
                    ? '有帮助'
                    : '需改进'
              }}</span
            ></a-space
          ></template
        ></a-card
      >
      <InvestigationPolicy v-if="store.role==='ADMIN'" :server-id="detail.server_id" />
      <details>
        <summary>事件历史</summary>
        <p v-for="h in detail.history" :key="h.id">
          {{ h.created_at }} · {{ h.action }} · {{ h.detail }}
        </p>
      </details></a-card
    ></a-space
  >
</template>
