<script setup lang="ts">
import { ref, reactive, watch, onUnmounted } from 'vue'
import { api, errorMessage } from '../api'
const props = defineProps<{ serverId: number }>(),
  items = ref<any[]>([]),
  error = ref(''),
  busy = ref(false)
const form = reactive({
  gpuIndex: 0,
  taskName: '',
  purpose: '',
  startsAt: '',
  expectedEndAt: '',
  notes: '',
  status: 'PLANNED',
})
async function load() {
  items.value = (
    await api.get(`/api/servers/${props.serverId}/gpu-usage`)
  ).data.map((r: any) =>
    Object.fromEntries(Object.entries(r).map(([k, v]) => [k.toLowerCase(), v])),
  )
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
async function create() {
  await run(async () => {
    await api.post(`/api/servers/${props.serverId}/gpu-usage`, {
      ...form,
      startsAt: new Date(form.startsAt).toISOString(),
      expectedEndAt: new Date(form.expectedEndAt).toISOString(),
    })
    form.taskName = ''
    await load()
  })
}
async function status(id: number, value: string) {
  await run(async () => {
    await api.patch(`/api/servers/${props.serverId}/gpu-usage/${id}/status`, {
      status: value,
    })
    await load()
  })
}
watch(
  () => props.serverId,
  () => void run(load),
  { immediate: true },
)
const timer = setInterval(() => {
  if (!busy.value) void run(load)
}, 15000)
onUnmounted(() => clearInterval(timer))
const columns = [
  { title: 'GPU', dataIndex: 'gpu_index' },
  { title: '使用者', dataIndex: 'username' },
  { title: '任务', dataIndex: 'task_name' },
  { title: '用途', dataIndex: 'purpose' },
  { title: '开始 UTC', dataIndex: 'starts_at' },
  { title: '预计结束 UTC', dataIndex: 'expected_end_at' },
  { title: '登记状态', dataIndex: 'status' },
  { title: '核实情况', dataIndex: 'verification_note' },
  { title: '操作', key: 'action' },
]
</script>
<template>
  <a-space direction="vertical" style="width: 100%" :size="12"
    ><a-alert
      type="info"
      message="登记不预留 GPU，也不证明任务实际运行。请结合 GPU 利用率、显存、进程和采样时间核实。"
    /><a-alert v-if="error" type="error" :message="error" /><a-form
      layout="inline"
      @finish="create"
      ><a-form-item label="真实 GPU 编号"
        ><a-input-number v-model:value="form.gpuIndex" :min="0" /></a-form-item
      ><a-form-item label="任务"
        ><a-input v-model:value="form.taskName" :maxlength="160" /></a-form-item
      ><a-form-item label="用途"
        ><a-input v-model:value="form.purpose" :maxlength="1000" /></a-form-item
      ><a-form-item label="开始（本地时间）"
        ><input v-model="form.startsAt" type="datetime-local" /></a-form-item
      ><a-form-item label="预计结束"
        ><input
          v-model="form.expectedEndAt"
          type="datetime-local" /></a-form-item
      ><a-form-item label="备注"
        ><a-input v-model:value="form.notes" :maxlength="2000" /></a-form-item
      ><a-button
        html-type="submit"
        type="primary"
        :loading="busy"
        :disabled="!form.taskName || !form.startsAt || !form.expectedEndAt"
        >登记计划</a-button
      ></a-form
    ><a-table
      :columns="columns"
      :data-source="items"
      row-key="id"
      :scroll="{ x: 1200 }"
      ><template #bodyCell="{ column, record }"
        ><a-space v-if="column.key === 'action'"
          ><a-button
            v-if="record.status === 'PLANNED'"
            @click="status(record.id, 'RUNNING')"
            >标记运行</a-button
          ><a-button
            v-if="record.status === 'RUNNING'"
            @click="status(record.id, 'COMPLETED')"
            >完成</a-button
          ><a-button
            v-if="['PLANNED', 'RUNNING'].includes(record.status)"
            danger
            @click="status(record.id, 'CANCELLED')"
            >取消</a-button
          ></a-space
        ></template
      ></a-table
    ></a-space
  >
</template>
