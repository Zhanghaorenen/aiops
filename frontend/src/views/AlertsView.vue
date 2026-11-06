<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { api, errorMessage } from '../api'
import { useWorkspaceStore } from '../stores/workspace'
import { formatTime } from '../utils/format'
import IncidentsView from './IncidentsView.vue'
import ResourceGauge from '../components/ResourceGauge.vue'
const route = useRoute(),
  router = useRouter(),
  store = useWorkspaceStore()
const tab = ref(String(route.query.tab ?? 'events')),
  presets = ref<any[]>([]),
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
  advanced = ref<string[]>([]),
  filter = ref('')
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
  if (store.demo) return
  const [a, r, p] = await Promise.all([
    api.get('/api/alerts'),
    api.get('/api/alert-rules'),
    api.get('/api/alert-presets'),
  ])
  events.value = rows(a.data)
  rules.value = rows(r.data)
  presets.value = p.data
  loaded.value = true
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
watch(
  tab,
  (value) => void router.replace({ query: { ...route.query, tab: value } }),
)
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
async function open(r: any) {
  drawer.value = r
  incidentId.value = undefined
  const list = rows((await api.get('/api/incidents')).data)
  const linked = list.find((i) => i.source_alert_id === r.id)
  if (linked) incidentId.value = Number(linked.id)
}
const requestKeys = new Map<number, string>()
async function analyze(retry = false) {
  if (!drawer.value) return
  const id = drawer.value.id
  if (!requestKeys.has(id)) requestKeys.set(id, crypto.randomUUID())
  await run(async () => {
    const { data } = await api.post(
      `/api/alerts/${id}/investigation`,
      {},
      {
        params: { retry },
        headers: { 'Idempotency-Key': requestKeys.get(id) },
      },
    )
    incidentId.value = data.incidentId
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
  events.value.filter((e) => !filter.value || e.status === filter.value),
)
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
  { title: '首次触发', dataIndex: 'first_triggered_at' },
  { title: '最近观测', dataIndex: 'last_observed_at' },
  { title: '操作', key: 'action' },
]
</script>
<template>
  <div class="info-hero">
    <div>
      <span class="eyebrow">OPERATIONS CONTROL</span>
      <h2>监控策略与事件态势</h2>
      <p>
        用预设策略快速接入监控，在告警详情中一键发起只读 AI
        调查。人工确认不等于指标恢复，诊断假设与真实证据分别展示。
      </p>
    </div>
    <a-tag color="cyan"
      >{{
        events.filter((e) => e.status !== 'RESOLVED').length
      }}
      个活动事件</a-tag
    >
  </div>
  <div class="operations-instruments">
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
        <span class="eyebrow">ACTIVE EVENTS</span
        ><span class="live-pill"><i />15 秒刷新</span>
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
      <p>在事件详情发起 AI 分析，查看数据证据与处理建议。</p>
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
            { value: '', label: '全部事件' },
            ...['FIRING', 'ACKNOWLEDGED', 'RESOLVED'].map((value) => ({
              value,
              label: value,
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
            ><a-button type="link" @click="() => run(() => open(record))"
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
            >{{ record.severity }}</a-tag
          ><a-tag
            v-else-if="column.dataIndex === 'status'"
            :color="
              record.status === 'RESOLVED'
                ? 'green'
                : record.status === 'FIRING'
                  ? 'red'
                  : 'orange'
            "
            >{{ record.status }}</a-tag
          ></template
        ></a-table
      ></a-tab-pane
    ><a-tab-pane key="presets" tab="预设告警策略"
      ><p class="muted">
        阈值是可修改的初始建议。同一服务器相同指标不会重复添加；显存占用提示不等于硬件故障。
      </p>
      <div class="preset-grid">
        <button
          v-for="(p, index) in presets"
          :key="p.key"
          class="preset-tile"
          :class="{ selected: selected === p.key }"
          @click="selected = p.key"
        >
          <span class="preset-code"
            >POLICY / 0{{ index + 1
            }}<span>{{
              selected === p.key ? '已选择' : '预设策略'
            }}</span></span
          ><strong>{{ p.name }}</strong
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
    :width="1040"
    title="告警详情与故障调查"
    @close="drawer = null"
    ><template v-if="drawer"
      ><a-descriptions bordered :column="2"
        ><a-descriptions-item label="事件">#{{ drawer.id }}</a-descriptions-item
        ><a-descriptions-item label="服务器">{{
          drawer.server_id
        }}</a-descriptions-item
        ><a-descriptions-item label="状态">{{
          drawer.status
        }}</a-descriptions-item
        ><a-descriptions-item label="异常时间">{{
          formatTime(drawer.first_triggered_at)
        }}</a-descriptions-item></a-descriptions
      >
      <h3>原始监控证据</h3>
      <pre class="drawer-evidence">{{ drawer.evidence }}</pre>
      <a-space style="margin: 16px 0"
        ><a-button type="primary" :loading="busy" @click="analyze(false)"
          >AI 故障分析 / 查看结果</a-button
        ><a-button v-if="incidentId" :loading="busy" @click="analyze(true)"
          >重新调查 / 失败重试</a-button
        ></a-space
      ><IncidentsView
        v-if="incidentId"
        :key="incidentId"
        :incident-id="incidentId"
        detail-only /></template
  ></a-drawer>
</template>
