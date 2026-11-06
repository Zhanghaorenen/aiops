<script setup lang="ts">
import { watch, ref, reactive, onMounted, onUnmounted, computed } from 'vue'
import { api, errorMessage } from '../api'
import { useWorkspaceStore } from '../stores/workspace'
const store = useWorkspaceStore(),
  events = ref<any[]>([]),
  rules = ref<any[]>([]),
  error = ref(''),
  busy = ref(false),
  canManage = computed(() => ['ADMIN', 'OPERATOR'].includes(store.role))
const form = reactive({
  name: '',
  serverId: undefined as number | undefined,
  metric: 'GPU_TEMP_HIGH',
  comparison: 'GE',
  threshold: 85,
  recoveryThreshold: 80,
  durationSeconds: 60,
  freshnessSeconds: 90,
  maxGapSeconds: 45,
  cooldownSeconds: 300,
  severity: 'WARNING',
  enabled: true,
})
const metrics = [
  'CPU_HIGH',
  'MEMORY_HIGH',
  'DISK_HIGH',
  'GPU_TEMP_HIGH',
  'GPU_MEMORY_HIGH',
  'SERVER_OFFLINE',
  'GPU_COLLECTION_FAILED',
]
watch(
  () => form.metric,
  (metric) => {
    const defaults: Record<string, [number, number, number, string]> = {
      CPU_HIGH: [90, 75, 60, 'WARNING'],
      MEMORY_HIGH: [90, 75, 60, 'WARNING'],
      DISK_HIGH: [90, 80, 60, 'WARNING'],
      GPU_TEMP_HIGH: [85, 80, 60, 'WARNING'],
      GPU_MEMORY_HIGH: [95, 85, 60, 'INFO'],
      SERVER_OFFLINE: [90, 30, 30, 'WARNING'],
      GPU_COLLECTION_FAILED: [1, 0, 60, 'WARNING'],
    }
    const [threshold, recovery, duration, severity] = defaults[metric]!
    form.threshold = threshold
    form.recoveryThreshold = recovery
    form.durationSeconds = duration
    form.severity = severity
  },
)

function rows(data: any[]) {
  return data.map((r) =>
    Object.fromEntries(Object.entries(r).map(([k, v]) => [k.toLowerCase(), v])),
  )
}
async function load() {
  if (store.demo) return
  events.value = rows((await api.get('/api/alerts')).data)
  rules.value = rows((await api.get('/api/alert-rules')).data)
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
async function save() {
  await run(async () => {
    await api.post('/api/alert-rules', form)
    await load()
  })
}
async function toggle(r: any) {
  await run(async () => {
    await api.put(`/api/alert-rules/${r.id}`, {
      name: r.name,
      serverId: r.server_id,
      metric: r.metric,
      comparison: r.comparison,
      threshold: r.threshold_value,
      recoveryThreshold: r.recovery_threshold,
      durationSeconds: r.duration_seconds,
      freshnessSeconds: r.freshness_seconds,
      maxGapSeconds: r.max_gap_seconds,
      cooldownSeconds: r.cooldown_seconds,
      severity: r.severity,
      enabled: !r.enabled,
    })
    await load()
  })
}
async function ack(id: number) {
  await run(async () => {
    await api.post(`/api/alerts/${id}/acknowledge`)
    await load()
  })
}
onMounted(() => void run(load))
const timer = setInterval(() => {
  if (!busy.value) void run(load)
}, 15000)
onUnmounted(() => clearInterval(timer))
const columns = [
  { title: '事件', dataIndex: 'id' },
  { title: '服务器', dataIndex: 'server_id' },
  { title: 'GPU', dataIndex: 'gpu_index' },
  { title: '等级', dataIndex: 'severity' },
  { title: '状态', dataIndex: 'status' },
  { title: '触发时间 UTC', dataIndex: 'first_triggered_at' },
  { title: '证据', dataIndex: 'evidence' },
  { title: '操作', key: 'action' },
]
</script>
<template>
  <a-space direction="vertical" :size="16" style="width: 100%"
    ><a-alert v-if="error" type="error" :message="error" /><a-alert
      type="info"
      message="人工确认不等于指标恢复，缺测不会当作 0。高显存表示资源占用，需结合任务核实。"
    /><a-card v-if="canManage" title="新建告警规则"
      ><a-form layout="inline" @finish="save"
        ><a-form-item label="名称"
          ><a-input v-model:value="form.name" :maxlength="160" /></a-form-item
        ><a-form-item label="服务器"
          ><a-select
            v-model:value="form.serverId"
            :options="
              store.servers.map((s) => ({ value: s.id, label: s.name }))
            "
            style="width: 160px" /></a-form-item
        ><a-form-item label="指标"
          ><a-select
            v-model:value="form.metric"
            :options="metrics.map((value) => ({ value, label: value }))"
            style="width: 210px" /></a-form-item
        ><a-form-item label="触发阈值"
          ><a-input-number
            v-model:value="form.threshold"
            :min="0" /></a-form-item
        ><a-form-item label="恢复阈值"
          ><a-input-number
            v-model:value="form.recoveryThreshold"
            :min="0" /></a-form-item
        ><a-form-item label="持续秒数"
          ><a-input-number
            v-model:value="form.durationSeconds"
            :min="0"
            :max="86400" /></a-form-item
        ><a-form-item label="等级"
          ><a-select
            v-model:value="form.severity"
            :options="
              ['INFO', 'WARNING', 'CRITICAL'].map((value) => ({
                value,
                label: value,
              }))
            "
            style="width: 140px" /></a-form-item
        ><a-button
          html-type="submit"
          type="primary"
          :loading="busy"
          :disabled="!form.serverId || !form.name"
          >保存</a-button
        ></a-form
      >
      <p>
        离线阈值单位为秒；采集失败规则使用阈值 1、恢复 0。默认采样有效期 90
        秒、最大间隔 45 秒、冷却 300 秒。
      </p></a-card
    ><a-card title="规则"
      ><a-list :data-source="rules"
        ><template #renderItem="{ item }"
          ><a-list-item
            ><a-space
              >{{ item.name }} · 服务器 {{ item.server_id }} ·
              {{ item.metric }} · {{ item.threshold_value }} / 恢复
              {{ item.recovery_threshold }} · 持续
              {{ item.duration_seconds }} 秒<a-switch
                :checked="item.enabled"
                :disabled="!canManage || busy"
                @change="
                  toggle(item)
                " /></a-space></a-list-item></template></a-list></a-card
    ><a-card title="告警事件"
      ><a-table
        :columns="columns"
        :data-source="events"
        row-key="id"
        :scroll="{ x: 1200 }"
        ><template #bodyCell="{ column, record }"
          ><a-button
            v-if="
              column.key === 'action' && canManage && record.status === 'FIRING'
            "
            :disabled="busy"
            @click="ack(record.id)"
            >确认</a-button
          ></template
        ></a-table
      ></a-card
    ></a-space
  >
</template>
