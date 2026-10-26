<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { KeyOutlined } from '@ant-design/icons-vue'
import { api, errorMessage } from '../api'
import { useWorkspaceStore } from '../stores/workspace'

const store = useWorkspaceStore()
const router = useRouter()
const form = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' })
const busy = ref(false)
async function validateConfirmation(_: unknown, value: string) {
  if (value && value !== form.newPassword) throw new Error('两次输入的密码不一致')
}
async function changePassword() {
  if (store.demo || busy.value) return
  busy.value = true
  try {
    await api.post('/api/auth/change-password', { currentPassword: form.currentPassword, newPassword: form.newPassword })
    message.success('密码已更新，请重新登录')
    store.logout()
    await router.replace('/overview')
  } catch (err) { message.error(errorMessage(err)) }
  finally { busy.value = false }
}
</script>

<template>
  <div class="settings-content">
    <div class="section-heading"><h2>当前账户</h2><a-tag :color="store.demo ? 'gold' : 'success'">{{ store.demo ? '演示访客' : '已登录' }}</a-tag></div>
    <a-descriptions><a-descriptions-item label="用户名">{{ store.username || '—' }}</a-descriptions-item></a-descriptions>
    <div class="section-heading"><h2>修改密码</h2></div>
    <a-alert v-if="store.demo" type="info" message="演示环境不支持修改账户密码" class="error-banner" />
    <a-form :model="form" layout="vertical" :disabled="store.demo" :required-mark="false" @finish="changePassword">
      <a-form-item label="当前密码" name="currentPassword" :rules="[{ required: true, message: '请输入当前密码' }]"><a-input-password v-model:value="form.currentPassword" autocomplete="current-password" /></a-form-item>
      <a-form-item label="新密码" name="newPassword" :rules="[{ required: true, message: '请输入新密码' }, { min: 12, max: 72, message: '密码长度须为 12 至 72 个字符' }]"><a-input-password v-model:value="form.newPassword" autocomplete="new-password" /></a-form-item>
      <a-form-item label="确认新密码" name="confirmPassword" :rules="[{ required: true, message: '请确认新密码' }, { validator: validateConfirmation }]"><a-input-password v-model:value="form.confirmPassword" autocomplete="new-password" /></a-form-item>
      <a-button type="primary" html-type="submit" :loading="busy"><template #icon><KeyOutlined /></template>更新密码</a-button>
    </a-form>
  </div>
</template>
