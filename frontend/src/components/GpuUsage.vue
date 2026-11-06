<script setup lang="ts">
import { ref, reactive, computed, watch, onUnmounted } from "vue";
import { api, errorMessage } from "../api";
import { useWorkspaceStore } from "../stores/workspace";
import { formatTime } from "../utils/format";
const props = defineProps<{ serverId: number }>(),
  store = useWorkspaceStore();
const items = ref<any[]>([]),
  calendarItems = ref<any[]>([]),
  todayItems = ref<any[]>([]),
  gpus = ref<number[]>([]),
  processes = ref<any[] | null>(null),
  sampledAt = ref(""),
  collectionStatus = ref(""),
  clock = ref(Date.now()),
  busy = ref(false),
  error = ref(""),
  mine = ref(false),
  view = ref("day"),
  editing = ref<number>();
function dateText(d: Date) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}
const date = ref(dateText(new Date()));
const form = reactive({
  gpuIndex: undefined as number | undefined,
  date: date.value,
  time: "",
  duration: 2,
  customEnd: "",
  taskName: "",
  purpose: "",
  notes: "",
});
const windowStart = computed(() => {
  const d = new Date(date.value + "T00:00:00");
  if (view.value === "week") d.setDate(d.getDate() - ((d.getDay() + 6) % 7));
  return d.getTime();
});
const windowEnd = computed(() => {
  const d = new Date(windowStart.value);
  d.setDate(d.getDate() + (view.value === "day" ? 1 : 7));
  return d.getTime();
});
const start = computed(() => new Date(`${form.date}T${form.time}`).getTime());
const end = computed(() =>
  form.duration
    ? start.value + form.duration * 3600000
    : new Date(form.customEnd).getTime(),
);
const valid = computed(
  () =>
    form.gpuIndex != null &&
    !!form.taskName.trim() &&
    Number.isFinite(start.value) &&
    Number.isFinite(end.value) &&
    start.value > Date.now() &&
    end.value > start.value &&
    end.value - start.value <= 7 * 86400000,
);
const visible = computed(() =>
  items.value.filter((r) => !mine.value || r.username === store.username),
);
const active = computed(() =>
  calendarItems.value
    .filter((r) => !mine.value || r.username === store.username)
    .filter(
      (r) =>
        ["PLANNED", "RUNNING"].includes(r.status) &&
        Date.parse(r.starts_at) < windowEnd.value &&
        Date.parse(r.expected_end_at) > windowStart.value,
    ),
);
// 过期快照不能证明资源空闲，预约记录与实时采样分别展示。
const fresh = computed(() => {
  const sampled = Date.parse(sampledAt.value);
  return (
    collectionStatus.value === "AVAILABLE" &&
    Number.isFinite(sampled) &&
    clock.value - sampled <= 90000 &&
    sampled - clock.value <= 60000
  );
});
const busyGpu = computed(() =>
  processes.value == null || !fresh.value
    ? null
    : new Set(processes.value.map((p) => p.gpuIndex)).size,
);
const todayCount = computed(() => {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  const next = new Date(d);
  next.setDate(d.getDate() + 1);
  return todayItems.value.filter(
    (r) =>
      r.status !== "CANCELLED" &&
      Date.parse(r.starts_at) < +next &&
      Date.parse(r.expected_end_at) > +d,
  ).length;
});
function normalize(data: any[]) {
  return data.map((r) =>
    Object.fromEntries(Object.entries(r).map(([k, v]) => [k.toLowerCase(), v])),
  );
}
async function load() {
  const day = new Date();
  day.setHours(0, 0, 0, 0);
  const dayEnd = new Date(day);
  dayEnd.setDate(day.getDate() + 1);
  const [usage, snapshot, process, calendar, today] = await Promise.all([
    api.get(`/api/servers/${props.serverId}/gpu-usage`),
    api.get(`/api/servers/${props.serverId}/gpus`),
    api.get(`/api/servers/${props.serverId}/gpus/processes`),
    api.get(`/api/servers/${props.serverId}/gpu-usage`, {
      params: {
        from: new Date(windowStart.value).toISOString(),
        to: new Date(windowEnd.value).toISOString(),
      },
    }),
    api.get(`/api/servers/${props.serverId}/gpu-usage`, {
      params: { from: day.toISOString(), to: dayEnd.toISOString() },
    }),
  ]);
  items.value = normalize(usage.data);
  calendarItems.value = normalize(calendar.data);
  todayItems.value = normalize(today.data);
  gpus.value = snapshot.data.gpuIndices ?? [];
  sampledAt.value = snapshot.data.collectedAt ?? "";
  collectionStatus.value = snapshot.data.collectionStatus ?? "";
  processes.value = process.data;
  if (!gpus.value.includes(form.gpuIndex ?? -1)) form.gpuIndex = gpus.value[0];
}
async function run(fn: () => Promise<void>) {
  busy.value = true;
  error.value = "";
  try {
    await fn();
  } catch (e) {
    error.value = errorMessage(e);
  } finally {
    busy.value = false;
  }
}
async function create() {
  await run(async () => {
    await api.request({
      method: editing.value ? "PUT" : "POST",
      url: editing.value
        ? `/api/servers/${props.serverId}/gpu-usage/${editing.value}`
        : `/api/servers/${props.serverId}/gpu-usage`,
      data: {
        gpuIndex: form.gpuIndex,
        taskName: form.taskName,
        purpose: form.purpose,
        notes: form.notes,
        startsAt: new Date(start.value).toISOString(),
        expectedEndAt: new Date(end.value).toISOString(),
        status: "PLANNED",
      },
    });
    form.taskName = "";
    editing.value = undefined;
    await load();
  });
}
function edit(r: any) {
  const d = new Date(r.starts_at),
    end = new Date(r.expected_end_at);
  form.gpuIndex = r.gpu_index;
  form.taskName = r.task_name;
  form.purpose = r.purpose;
  form.notes = r.notes;
  form.date = dateText(d);
  form.time =
    String(d.getHours()).padStart(2, "0") +
    ":" +
    String(d.getMinutes()).padStart(2, "0");
  form.duration = 0;
  form.customEnd =
    dateText(end) +
    "T" +
    String(end.getHours()).padStart(2, "0") +
    ":" +
    String(end.getMinutes()).padStart(2, "0");
  editing.value = r.id;
  window.scrollTo({ top: 0, behavior: "smooth" });
}
async function status(id: number, value: string) {
  await run(async () => {
    await api.patch(`/api/servers/${props.serverId}/gpu-usage/${id}/status`, {
      status: value,
    });
    await load();
  });
}
function canManage(r: any) {
  return store.role === "ADMIN" || r.username === store.username;
}
function position(r: any) {
  const left = Math.max(Date.parse(r.starts_at), windowStart.value),
    right = Math.min(Date.parse(r.expected_end_at), windowEnd.value);
  return {
    left:
      ((left - windowStart.value) / (windowEnd.value - windowStart.value)) *
        100 +
      "%",
    width: ((right - left) / (windowEnd.value - windowStart.value)) * 100 + "%",
  };
}
function move(days: number) {
  const d = new Date(date.value + "T00:00:00");
  d.setDate(d.getDate() + days);
  date.value = dateText(d);
}
watch(
  [() => props.serverId, date, view],
  () => {
    processes.value = null;
    void run(load);
  },
  { immediate: true },
);
const timer = setInterval(() => {
  clock.value = Date.now();
  if (!busy.value) void run(load);
}, 15000);
onUnmounted(() => clearInterval(timer));
const columns = [
  { title: "GPU", dataIndex: "gpu_index" },
  { title: "使用者", dataIndex: "username" },
  { title: "任务", dataIndex: "task_name" },
  { title: "预约开始", dataIndex: "starts_at" },
  { title: "预约结束", dataIndex: "expected_end_at" },
  { title: "预约状态", dataIndex: "status" },
  { title: "备注", dataIndex: "notes" },
  { title: "操作", key: "action" },
];
</script>
<template>
  <div class="info-hero">
    <div>
      <h2>共享 GPU 预约</h2>
      <p>
        按时间协调资源，后端检查同一 GPU
        的区间冲突。预约状态为人工记录，不等于真实进程状态，也不会强制锁定显存。
      </p>
    </div>
    <a-tag color="cyan">每次最长 7 天</a-tag>
  </div>
  <a-alert v-if="error" :message="error" type="error" show-icon />
  <p class="muted">
    GPU 采样：{{ formatTime(sampledAt) }} ·
    {{
      fresh
        ? "实时进程仅反映本次快照"
        : "采样缺失、过期或采集不可用，暂不能判断资源是否空闲"
    }}
  </p>
  <div class="summary-grid">
    <div class="summary-item">
      <div>GPU 总数</div>
      <strong>{{ gpus.length }}</strong>
    </div>
    <div class="summary-item">
      <div>有计算进程的 GPU</div>
      <strong>{{ busyGpu ?? "—" }}</strong>
    </div>
    <div class="summary-item">
      <div>今日预约</div>
      <strong>{{ todayCount }}</strong>
    </div>
    <div class="summary-item">
      <div>暂无计算进程的 GPU</div>
      <strong>{{
        busyGpu == null ? "—" : Math.max(0, gpus.length - busyGpu)
      }}</strong>
    </div>
  </div>
  <a-card title="快速预约"
    ><a-form layout="vertical" @finish="create"
      ><div class="resource-grid">
        <a-form-item label="GPU"
          ><a-select
            v-model:value="form.gpuIndex"
            :options="
              gpus.map((i) => ({ value: i, label: `GPU ${i}` }))
            " /></a-form-item
        ><a-form-item label="任务名称"
          ><a-input
            v-model:value="form.taskName"
            :maxlength="160"
            placeholder="例如：模型评测 · 不填写敏感参数" /></a-form-item
        ><a-form-item label="日期"
          ><input
            type="date"
            v-model="form.date"
            :min="dateText(new Date())"
            class="ant-input" /></a-form-item
        ><a-form-item label="开始时间（本地）"
          ><input
            type="time"
            v-model="form.time"
            class="ant-input" /></a-form-item
        ><a-form-item label="预约时长"
          ><a-select
            v-model:value="form.duration"
            :options="
              [1, 2, 4, 8]
                .map((n) => ({ value: n, label: n + ' 小时' }))
                .concat([{ value: 0, label: '自定义结束时间' }])
            " /></a-form-item
        ><a-form-item v-if="!form.duration" label="自定义结束时间"
          ><input
            type="datetime-local"
            v-model="form.customEnd"
            class="ant-input" /></a-form-item
        ><a-form-item label="用途"
          ><a-input
            v-model:value="form.purpose"
            :maxlength="1000" /></a-form-item
        ><a-form-item label="使用者备注"
          ><a-input v-model:value="form.notes" :maxlength="2000"
        /></a-form-item>
      </div>
      <a-space wrap
        ><a-button
          type="primary"
          html-type="submit"
          :disabled="!valid || busy"
          :loading="busy"
          >{{ editing ? "保存预约修改" : "确认预约" }}</a-button
        ><a-button v-if="editing" @click="editing = undefined"
          >取消编辑</a-button
        ><span class="muted">{{
          Number.isFinite(start) && Number.isFinite(end)
            ? formatTime(new Date(start).toISOString()) +
              " → " +
              formatTime(new Date(end).toISOString())
            : "请选择完整的预约区间"
        }}</span></a-space
      ></a-form
    ></a-card
  >
  <div class="section-heading">
    <a-space wrap
      ><h2>资源时间轴</h2>
      <a-segmented
        v-model:value="view"
        :options="[
          { label: '日视图', value: 'day' },
          { label: '周视图', value: 'week' },
        ]"
      /><a-button @click="move(view === 'day' ? -1 : -7)">上一页</a-button
      ><input type="date" v-model="date" class="ant-input" /><a-button
        @click="move(view === 'day' ? 1 : 7)"
        >下一页</a-button
      ></a-space
    ><a-checkbox v-model:checked="mine">只看我的预约</a-checkbox>
  </div>
  <div class="reservation-grid">
    <div class="reservation-row">
      <span>本地时间</span>
      <div class="time-ruler">
        <span v-for="i in view === 'day' ? 7 : 8" :key="i">{{
          view === "day"
            ? String((i - 1) * 4).padStart(2, "0") + ":00"
            : dateText(new Date(windowStart + (i - 1) * 86400000))
        }}</span>
      </div>
    </div>
    <div v-for="gpu in gpus" :key="gpu" class="reservation-row">
      <strong>GPU {{ gpu }}</strong>
      <div class="reservation-track">
        <a-tooltip
          v-for="r in active.filter((t) => t.gpu_index === gpu)"
          :key="r.id"
          :title="`${r.username} · ${r.task_name} · ${formatTime(r.starts_at)} 至 ${formatTime(r.expected_end_at)}`"
          ><div
            class="reservation-block"
            :class="{ mine: r.username === store.username }"
            :style="position(r)"
          >
            <strong>{{ r.task_name }}</strong
            >{{ r.username }} · {{ r.status }}
          </div></a-tooltip
        >
      </div>
    </div>
    <a-empty v-if="!gpus.length" description="暂无 GPU 数据" />
  </div>
  <a-card title="预约与历史记录"
    ><a-table
      :data-source="visible"
      :columns="columns"
      row-key="id"
      :scroll="{ x: 1100 }"
      ><template #bodyCell="{ column, record }"
        ><a-space v-if="column.key === 'action' && canManage(record)"
          ><a-button
            v-if="record.status === 'PLANNED'"
            size="small"
            :disabled="busy"
            @click="edit(record)"
            >编辑</a-button
          ><a-button
            v-if="record.status === 'PLANNED'"
            size="small"
            :disabled="busy || Date.parse(record.starts_at) > Date.now()"
            @click="status(record.id, 'RUNNING')"
            >开始使用</a-button
          ><a-button
            v-if="record.status === 'RUNNING'"
            size="small"
            :disabled="busy"
            @click="status(record.id, 'COMPLETED')"
            >结束使用</a-button
          ><a-popconfirm
            v-if="['PLANNED', 'RUNNING'].includes(record.status)"
            title="确认取消这个预约？"
            @confirm="status(record.id, 'CANCELLED')"
            ><a-button size="small" danger :disabled="busy"
              >取消</a-button
            ></a-popconfirm
          ></a-space
        ><template
          v-else-if="
            ['starts_at', 'expected_end_at'].includes(column.dataIndex)
          "
          >{{ formatTime(record[column.dataIndex]) }}</template
        ><a-tag
          v-else-if="column.dataIndex === 'status'"
          :color="
            record.status === 'RUNNING'
              ? 'green'
              : record.status === 'PLANNED'
                ? 'cyan'
                : 'default'
          "
          >{{ record.status }}</a-tag
        ></template
      ></a-table
    >
    <p class="muted">
      人工预约与实际进程分开核实。旧使用登记保留；历史列表显示最近 200
      条；时间轴按所选窗口独立查询。
    </p></a-card
  >
</template>
