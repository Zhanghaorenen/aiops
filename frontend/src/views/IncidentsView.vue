<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { api, errorMessage } from '../api'
import { useWorkspaceStore } from '../stores/workspace'
const props = defineProps<{ incidentId?: number; detailOnly?: boolean }>()
const store = useWorkspaceStore(),
  items = ref<any[]>([]),
  detail = ref<any>(null),
  alerts = ref<any[]>([]),
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
async function load() {
  if (store.demo) return
  items.value = row((await api.get('/api/incidents')).data)
  alerts.value = row((await api.get('/api/alerts')).data)
  if (detail.value) await open(detail.value.id)
}
async function run(fn: () => Promise<void>) {
  busy.value = true
  error.value = ''
  try {
    await fn()
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
async function open(id: number) {
  detail.value = row((await api.get(`/api/incidents/${id}`)).data)
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
  () => props.incidentId,
  (id) => {
    if (id) void run(() => open(id))
  },
  { immediate: true },
)
onMounted(() => {
  if (!props.detailOnly) void run(load)
})
const timer = setInterval(() => {
  if (!busy.value) void run(load)
}, 5000)
onUnmounted(() => clearInterval(timer))
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
      ><a-table :columns="columns" :data-source="items" row-key="id"
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
          >AI 只读调查</a-button
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
            >调查 #{{ item.id }} · {{ item.status }} ·
            {{ item.error_code || '' }}</a-list-item
          ></template
        ></a-list
      ><a-card
        v-for="item in detail.reports"
        :key="item.id"
        :title="`诊断报告 #${item.id}`"
        style="margin-top: 16px"
        ><a-alert
          message="根因均为待验证假设，建议未被执行。报告可能包含私有知识库内容，仅发起人可查看。"
          type="info"
        />
        <p>
          {{ report(item.report_json).generated_at }} ·
          {{ report(item.report_json).model }} ·
          {{ report(item.report_json).workflow_version }}
        </p>
        <h3>监控证据和采集时间</h3>
        <details v-for="e in report(item.report_json).evidence" :key="e.id">
          <summary>{{ e.id }} · {{ e.kind }}</summary>
          <pre style="white-space: pre-wrap; overflow-wrap: anywhere">{{
            JSON.stringify(e.data, null, 2)
          }}</pre>
        </details>
        <h3>可能原因</h3>
        <p
          v-for="(cause, i) in report(item.report_json).possible_causes"
          :key="i"
        >
          {{ cause.hypothesis }} · 支持
          {{ cause.supporting_evidence_ids?.join(', ') }} · 反对
          {{ cause.refuting_evidence_ids?.join(', ') }}
        </p>
        <h3>缺失证据</h3>
        <p>{{ report(item.report_json).missing_evidence?.join('；') }}</p>
        <h3>建议步骤</h3>
        <ol>
          <li
            v-for="(step, i) in report(item.report_json).suggested_steps"
            :key="i"
          >
            {{ step }}
          </li>
        </ol>
        <h3>后续验证方法</h3>
        <ul>
          <li
            v-for="step in report(item.report_json).verification_steps ?? [
              '人工处理后，比较同一 GPU 和相同时间窗口的原始指标与恢复阈值，核实告警及任务所有者反馈。',
            ]"
            :key="step"
          >
            {{ step }}
          </li>
        </ul>
        <h3>实际检索来源</h3>
        <p
          v-for="source in report(item.report_json).references"
          :key="source.chunk_id"
        >
          {{ source.document_title }} · 第 {{ source.page ?? '未知' }} 页 ·
          {{ source.section }} · {{ source.chunk_id }}
        </p>
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
      <details>
        <summary>事件历史</summary>
        <p v-for="h in detail.history" :key="h.id">
          {{ h.created_at }} · {{ h.action }} · {{ h.detail }}
        </p>
      </details></a-card
    ></a-space
  >
</template>
