<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { api, errorMessage } from '../api'
import { useWorkspaceStore } from '../stores/workspace'
import { formatTime } from '../utils/format'
import { useNotificationEvents } from '../composables/useNotificationEvents'
import IncidentsView from './IncidentsView.vue'
import ResourceGauge from '../components/ResourceGauge.vue'
const route = useRoute(),
  router = useRouter(),
  store = useWorkspaceStore()
const tab = computed({
  get: () => String(route.query.tab ?? 'events'),
  set: value => { void router.replace({query: {...route.query, tab: value}}) },
})
const filter = computed({
  get: () => String(route.query.status ?? 'UNRESOLVED'),
  set: value => { void router.replace({query: {...route.query, status: value}}) },
})
const statusLabels: Record<string,string> = { FIRING:'待确认', ACKNOWLEDGED:'已确认', RESOLVED:'已恢复' }
const severityLabels: Record<string,string> = { CRITICAL:'严重', WARNING:'警告', INFO:'提示' }
const presets = ref<any[]>([]),
  rules = ref<any[]>([]),
  events = ref<any[]>([]),
  busy = ref(false),
  error = ref(''),
  selected = ref('CPU_HIGH'),
  targets = ref<number[]>([]),
  all = ref(false),
  threshold = ref(90),
  drawer = ref<any>(null),
  incidentId = ref<number>(),
  editing = ref<number>(),
  advanced = ref<string[]>([])
const canManage = computed(() => ['ADMIN', 'OPERATOR'].includes(store.role))
const names: Record<string, string> = {
  CPU_HIGH: 'CPU 高占用',
  MEMORY_HIGH: '内存高占用',
  DISK_HIGH: '磁盘空间不足',
  GPU_TEMP_HIGH: 'GPU 温度过高',
  GPU_MEMORY_HIGH: 'GPU 显存紧张',
  SERVER_OFFLINE: '服务器离线',
  GPU_COLLECTION_FAILED: 'GPU 采集失败',
}
const form = reactive({
  name: '',
  serverId: undefined as number | undefined,
  metric: 'CPU_HIGH',
  comparison: 'GE',
  threshold: 90,
  recoveryThreshold: 75,
  durationSeconds: 60,
  freshnessSeconds: 90,
  maxGapSeconds: 45,
  cooldownSeconds: 300,
  severity: 'WARNING',
  enabled: true,
})
function rows(data: any[]) {
  return data.map((r) =>
    Object.fromEntries(Object.entries(r).map(([k, v]) => [k.toLowerCase(), v])),
  )
}
const loaded = ref(false)
const enabledRules = computed(() => rules.value.filter((r) => r.enabled))
const coveredHosts = computed(
  () => new Set(enabledRules.value.map((r) => r.server_id)).size,
)
const unresolved = computed(() =>
  events.value.filter((e) => e.status !== 'RESOLVED'),
)
async function load() {
  if (store.demo || !store.token) return
  const token = store.token
  const [a, r, p] = await Promise.allSettled([
    api.get('/api/alerts'), api.get('/api/alert-rules'), api.get('/api/alert-presets'),
  ])
  if (token !== store.token || store.demo) return
  if (a.status === 'fulfilled') {
    events.value = rows(a.value.data)
    loaded.value = true
  }
  if (r.status === 'fulfilled') rules.value = rows(r.value.data)
  if (p.status === 'fulfilled') presets.value = p.value.data
  const missing = [a, r, p].flatMap((result, i) => result.status === 'rejected' ? [['告警事件', '告警规则', '预设策略'][i]] : [])
  if (missing.length) throw new Error(missing.join('、') + '未能更新')

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
watch(selected, (key) => {
  threshold.value = presets.value.find((p) => p.key === key)?.threshold ?? 90
})
async function apply() {
  await run(async () => {
    const ids = all.value ? store.servers.map((s) => s.id) : targets.value
    const { data } = await api.post('/alert-presets/apply', {
      serverIds: ids,
      presetKey: selected.value,
      threshold: threshold.value,
    })
    message.success(`已添加 ${data.added} 条，跳过已有策略 ${data.skipped} 条`)
    await load()
  })
}
function ruleData(r: any) {
  return {
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
    enabled: r.enabled,
  }
}
async function toggle(r: any) {
  await run(async () => {
    await api.put(`/api/alert-rules/${r.id}`, {
      ...ruleData(r),
      enabled: !r.enabled,
    })
    await load()
  })
}
function edit(r: any) {
  Object.assign(form, ruleData(r))
  editing.value = r.id
  tab.value = 'custom'
}
async function save() {
  await run(async () => {
    await api.request({
      method: editing.value ? 'PUT' : 'POST',
      url: editing.value
        ? `/api/alert-rules/${editing.value}`
        : '/api/alert-rules',
      data: form,
    })
    message.success('规则已保存')
    editing.value = undefined
    await load()
    tab.value = 'presets'
  })
}
let drawerVersion = 0
const linkState = ref<'loading'|'ready'|'error'>('loading')
async function open(r: any) {
  const version = ++drawerVersion
  linkState.value = 'loading'
  drawer.value = r
  incidentId.value = undefined
  try {
    const list = rows((await api.get('/api/incidents')).data)
    if (drawer.value?.id !== r.id || version !== drawerVersion) return
    const linked = list.find(i => Number(i.source_alert_id) === Number(r.id))
    incidentId.value = linked ? Number(linked.id) : undefined
    linkState.value = 'ready'
  } catch {
    if (version === drawerVersion) linkState.value = 'error'
  }
}
watch([() => route.query.event, loaded], ([id, ready]) => {
  if (!id) { drawer.value = null; return }
  if (!ready) return
  const event = events.value.find(e => String(e.id) === id)
  if (event) void open(event)
  else error.value = '告警不存在或当前账户无权查看'
}, {immediate:true})
function selectEvent(id?: number) {
  void router.replace({query:{...route.query, event:id}})
}
const requestKeys = new Map<number, string>()
async function analyze() {
  if (!drawer.value) return
  const id = drawer.value.id
  if (!requestKeys.has(id)) requestKeys.set(id, crypto.randomUUID())
  await run(async () => {
    const { data } = await api.post(
      `/api/alerts/${id}/investigation`,
      {},
      {
        headers: { 'Idempotency-Key': requestKeys.get(id) },
      },
    )
    if (drawer.value?.id === id) {
      incidentId.value = data.incidentId
      await router.push('/incidents/' + data.incidentId)
    }
    requestKeys.delete(id)
  })
}
async function ack(id: number) {
  await run(async () => {
    await api.post(`/api/alerts/${id}/acknowledge`)
    await load()
    if (drawer.value?.id === id)
      drawer.value = events.value.find((e) => e.id === id)
  })
}
const filtered = computed(() =>
  events.value.filter(e => filter.value === 'UNRESOLVED' ? e.status !== 'RESOLVED' : !filter.value || e.status === filter.value),
)
onMounted(() => void run(load))
useNotificationEvents(async () => { if (!busy.value) await run(load) })
const columns = [
  { title: '事件', dataIndex: 'id' },
  { title: '服务器', dataIndex: 'server_id' },
  { title: 'GPU', dataIndex: 'gpu_index' },
  { title: '等级', dataIndex: 'severity' },
  { title: '状态', dataIndex: 'status' },
  { title: '首次触发', dataIndex: 'first_triggered_at' },
  { title: '最近观测', dataIndex: 'last_observed_at' },
  { title: '操作', key: 'action' },
]
</script>
<template>
  <div v-if="tab === 'presets' || tab === 'custom'" class="operations-instruments">
    <ResourceGauge
      label="规则启用率"
      :value="
        loaded && rules.length
          ? (enabledRules.length / rules.length) * 100
          : null
      "
      accent="cyan"
      :note="`${enabledRules.length} / ${rules.length} 条规则启用`"
    />
    <ResourceGauge
      label="服务器覆盖率"
      :value="
        loaded && store.servers.length
          ? (coveredHosts / store.servers.length) * 100
          : null
      "
      accent="blue"
      :note="`${coveredHosts} / ${store.servers.length} 台至少配置一条启用规则`"
    />
    <section class="event-instrument">
      <header>
        <span>活动事件</span><span class="live-pill"><i />15 秒刷新</span>
      </header>
      <strong
        >{{ loaded ? unresolved.length : '—' }}<small>未恢复事件</small></strong
      >
      <div class="event-breakdown">
        <span
          ><i class="event-dot critical" />{{
            unresolved.filter((e) => e.severity === 'CRITICAL').length
          }}
          严重</span
        ><span
          ><i class="event-dot warning" />{{
            unresolved.filter((e) => e.severity === 'WARNING').length
          }}
          警告</span
        ><span
          ><i class="event-dot acknowledged" />{{
            unresolved.filter((e) => e.status === 'ACKNOWLEDGED').length
          }}
          已确认</span
        >
      </div>
    </section>
  </div>
  <a-alert v-if="error" :message="error" type="error" show-icon /><a-tabs
    v-model:active-key="tab"
    ><a-tab-pane key="events" tab="事件中心"
      ><div class="table-toolbar">
        <a-select
          v-model:value="filter"
          style="width: 180px"
          :options="[
            { value: 'UNRESOLVED', label: '未恢复告警' },
            { value: '', label: '全部事件' },
            ...['FIRING', 'ACKNOWLEDGED', 'RESOLVED'].map((value) => ({
              value,
              label: statusLabels[value],
            })),
          ]"
        /><a-button :loading="busy" @click="() => run(load)">刷新事件</a-button>
      </div>
      <a-table
        :columns="columns"
        :data-source="filtered"
        row-key="id"
        :scroll="{ x: 1000 }"
        ><template #bodyCell="{ column, record }"
          ><a-space v-if="column.key === 'action'"
            ><a-button type="link" @click="selectEvent(record.id)"
              >查看详情</a-button
            ><a-button
              v-if="canManage && record.status === 'FIRING'"
              size="small"
              @click="ack(record.id)"
              >确认</a-button
            ></a-space
          ><template
            v-else-if="
              ['first_triggered_at', 'last_observed_at'].includes(
                column.dataIndex,
              )
            "
            >{{ formatTime(record[column.dataIndex]) }}</template
          ><a-tag
            v-else-if="column.dataIndex === 'severity'"
            :color="
              record.severity === 'CRITICAL'
                ? 'red'
                : record.severity === 'WARNING'
                  ? 'orange'
                  : 'cyan'
            "
            >{{ severityLabels[record.severity] || record.severity }}</a-tag
          ><a-tag
            v-else-if="column.dataIndex === 'status'"
            :color="
              record.status === 'RESOLVED'
                ? 'green'
                : record.status === 'FIRING'
                  ? 'red'
                  : 'orange'
            "
            >{{ statusLabels[record.status] || record.status }}</a-tag
          ></template
        ></a-table
      ></a-tab-pane
    ><a-tab-pane key="presets" tab="预设告警策略">
      <div class="preset-grid">
        <button
          v-for="p in presets"
          :key="p.key"
          class="preset-tile"
          :class="{ selected: selected === p.key }"
          @click="selected = p.key"
        >
          <strong>{{ p.name }}</strong
          ><b
            >{{ p.threshold }} <small>{{ p.unit }}</small></b
          >
          <p>{{ p.description }}</p>
        </button>
      </div>
      <a-card v-if="canManage" title="添加到监控范围"
        ><a-space wrap
          ><a-checkbox v-model:checked="all">所有可管理服务器</a-checkbox
          ><a-select
            v-if="!all"
            v-model:value="targets"
            mode="multiple"
            placeholder="选择服务器"
            style="min-width: 280px"
            :options="
              store.servers.map((s) => ({ value: s.id, label: s.name }))
            "
          /><span>触发阈值</span
          ><a-input-number v-model:value="threshold" :min="0.1" /><a-button
            type="primary"
            :loading="busy"
            :disabled="!all && !targets.length"
            @click="apply"
            >添加策略</a-button
          ></a-space
        ></a-card
      ><a-card title="已配置规则" style="margin-top: 18px"
        ><a-table
          :data-source="rules"
          row-key="id"
          :scroll="{ x: 800 }"
          :columns="[
            { title: '名称', dataIndex: 'name' },
            { title: '服务器', dataIndex: 'server_id' },
            { title: '指标', dataIndex: 'metric' },
            { title: '触发 / 恢复', key: 'threshold' },
            { title: '等级', dataIndex: 'severity' },
            { title: '启用', key: 'enabled' },
            { title: '操作', key: 'action' },
          ]"
          ><template #bodyCell="{ column, record }"
            ><template v-if="column.key === 'threshold'"
              >{{ record.threshold_value }} /
              {{ record.recovery_threshold }}</template
            ><template v-else-if="column.dataIndex === 'metric'">{{
              names[record.metric] ?? record.metric
            }}</template
            ><a-switch
              v-else-if="column.key === 'enabled'"
              :checked="!!record.enabled"
              :disabled="!canManage || busy"
              @change="() => toggle(record)"
            /><a-button
              v-else-if="column.key === 'action' && canManage"
              type="link"
              @click="edit(record)"
              >调整阈值</a-button
            ></template
          ></a-table
        ></a-card
      ></a-tab-pane
    ><a-tab-pane key="custom" tab="自定义规则"
      ><a-card
        v-if="canManage"
        :title="editing ? '调整现有规则' : '创建自定义规则'"
        ><a-form layout="vertical" @finish="save"
          ><div class="resource-grid">
            <a-form-item label="规则名称"
              ><a-input
                v-model:value="form.name"
                :maxlength="160" /></a-form-item
            ><a-form-item label="服务器"
              ><a-select
                v-model:value="form.serverId"
                :disabled="!!editing"
                :options="
                  store.servers.map((s) => ({ value: s.id, label: s.name }))
                " /></a-form-item
            ><a-form-item label="指标"
              ><a-select
                v-model:value="form.metric"
                :options="
                  Object.entries(names).map(([value, label]) => ({
                    value,
                    label,
                  }))
                " /></a-form-item
            ><a-form-item label="比较条件"
              ><a-select
                v-model:value="form.comparison"
                :options="[
                  { value: 'GE', label: '大于等于 ≥' },
                  { value: 'GT', label: '大于 >' },
                ]" /></a-form-item
            ><a-form-item label="触发阈值"
              ><a-input-number
                v-model:value="form.threshold"
                :min="0" /></a-form-item
            ><a-form-item label="告警等级"
              ><a-select
                v-model:value="form.severity"
                :options="
                  ['INFO', 'WARNING', 'CRITICAL'].map((value) => ({
                    value,
                    label: value,
                  }))
                "
            /></a-form-item>
          </div>
          <a-collapse v-model:active-key="advanced"
            ><a-collapse-panel
              key="advanced"
              header="高级配置：持续、恢复、冷却与数据有效期"
              ><a-space wrap
                ><a-form-item label="持续秒数"
                  ><a-input-number
                    v-model:value="form.durationSeconds"
                    :min="0"
                    :max="86400" /></a-form-item
                ><a-form-item label="恢复阈值"
                  ><a-input-number
                    v-model:value="form.recoveryThreshold"
                    :min="0" /></a-form-item
                ><a-form-item label="冷却秒数"
                  ><a-input-number
                    v-model:value="form.cooldownSeconds"
                    :min="0" /></a-form-item
                ><a-form-item label="数据有效秒数"
                  ><a-input-number
                    v-model:value="form.freshnessSeconds"
                    :min="15"
                    :max="3600" /></a-form-item
                ><a-form-item label="最大采样间隔"
                  ><a-input-number
                    v-model:value="form.maxGapSeconds"
                    :min="5"
                    :max="
                      3600
                    " /></a-form-item></a-space></a-collapse-panel></a-collapse
          ><a-space style="margin-top: 18px"
            ><a-button
              html-type="submit"
              type="primary"
              :loading="busy"
              :disabled="!form.name || !form.serverId"
              >保存规则</a-button
            ><a-button v-if="editing" @click="editing = undefined"
              >取消编辑</a-button
            ></a-space
          ></a-form
        ></a-card
      ><a-alert
        v-else
        message="当前角色可查看规则，配置需要管理员或运维权限。"
        type="info" /></a-tab-pane
    ><a-tab-pane key="incidents" tab="历史调查报告"
      ><IncidentsView /></a-tab-pane></a-tabs
  ><a-drawer
    :open="!!drawer"
    width="min(640px, 100vw)"
    title="告警详情与故障调查"
    @close="selectEvent()"
    ><template v-if="drawer"
      ><a-descriptions bordered :column="2"
        ><a-descriptions-item label="事件">#{{ drawer.id }}</a-descriptions-item
        ><a-descriptions-item label="服务器">{{
          drawer.server_id
        }}</a-descriptions-item
        ><a-descriptions-item label="状态">{{
          statusLabels[drawer.status] || drawer.status
        }}</a-descriptions-item
        ><a-descriptions-item label="异常时间">{{
          formatTime(drawer.first_triggered_at)
        }}</a-descriptions-item></a-descriptions
      >
      <a-space wrap style="margin: 16px 0">
        <a-button v-if="linkState === 'error'" @click="open(drawer)">重试读取关联调查</a-button>
        <a-button v-else-if="incidentId" type="primary" @click="router.push('/incidents/' + incidentId)">查看调查进度与报告</a-button>
        <a-button v-else type="primary" :loading="busy || linkState === 'loading'" @click="analyze">开始 AI 调查</a-button>
        <RouterLink :to="'/servers/' + drawer.server_id">查看告警主机</RouterLink>
      </a-space>
      <details><summary>原始监控证据</summary><pre class="drawer-evidence">{{ drawer.evidence }}</pre></details>
    </template
  ></a-drawer>
</template>
