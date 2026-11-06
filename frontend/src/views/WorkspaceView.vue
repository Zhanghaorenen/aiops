<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { api, errorMessage } from '../api'
import { useWorkspaceStore } from '../stores/workspace'
const store = useWorkspaceStore(),
  members = ref<any[]>([]),
  error = ref(''),
  busy = ref(false),
  username = ref(''),
  role = ref('VIEWER'),
  roles = ['ADMIN', 'OPERATOR', 'VIEWER']
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
async function load() {
  if (store.demo) return
  store.role = (await api.get('/api/workspace')).data.role
  if (store.role === 'ADMIN')
    members.value = (await api.get('/api/workspace/members')).data.map(
      (r: any) => ({
        userId: r.userId ?? r.USERID ?? r.user_id,
        username: r.username ?? r.USERNAME,
        role: r.role ?? r.ROLE,
        status: r.status ?? r.STATUS,
      }),
    )
}
async function add() {
  await run(async () => {
    await api.post('/api/workspace/members', {
      username: username.value,
      role: role.value,
    })
    username.value = ''
    await load()
  })
}
async function change(id: number, kind: string, value: string) {
  await run(async () => {
    await api.patch(`/api/workspace/members/${id}/${kind}`, { [kind]: value })
    await load()
  })
}
onMounted(() => void run(load))
const columns = [
  { title: '用户', dataIndex: 'username' },
  { title: '角色', key: 'role' },
  { title: '状态', key: 'status' },
  { title: '操作', key: 'action' },
]
</script>
<template>
  <a-space direction="vertical" style="width: 100%" :size="16"
    ><a-alert v-if="error" type="error" :message="error" /><a-card
      title="共享工作空间"
      ><p>
        当前角色：{{ store.role || '尚未加入工作空间' }}。服务器与 GPU
        为共享资源，知识库保持私有。
      </p></a-card
    ><a-card v-if="store.role === 'ADMIN'" title="成员管理"
      ><a-space wrap
        ><a-input
          v-model:value="username"
          placeholder="已注册用户名"
        /><a-select
          v-model:value="role"
          :options="roles.map((value) => ({ value, label: value }))"
          style="width: 130px"
        /><a-button
          type="primary"
          :loading="busy"
          :disabled="!username.trim()"
          @click="add"
          >添加</a-button
        ></a-space
      ><a-table :columns="columns" :data-source="members" row-key="userId"
        ><template #bodyCell="{ column, record }"
          ><a-select
            v-if="column.key === 'role'"
            :value="record.role"
            :options="roles.map((value) => ({ value, label: value }))"
            :disabled="busy"
            style="width: 130px"
            @change="(v: any) => change(record.userId, 'role', v)"
          /><a-tag v-if="column.key === 'status'">{{ record.status }}</a-tag
          ><a-popconfirm
            v-if="column.key === 'action'"
            title="确认改变成员访问资格？"
            @confirm="
              change(
                record.userId,
                'status',
                record.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE',
              )
            "
            ><a-button :disabled="busy" :danger="record.status === 'ACTIVE'">{{
              record.status === 'ACTIVE' ? '禁用 / 移除' : '启用'
            }}</a-button></a-popconfirm
          ></template
        ></a-table
      ></a-card
    ></a-space
  >
</template>
