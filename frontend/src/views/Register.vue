<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { KeyOutlined, RobotOutlined, UserOutlined } from '@ant-design/icons-vue'
import { api, errorMessage } from '../api'
import { useWorkspaceStore } from '../stores/workspace'

const router = useRouter()
const store = useWorkspaceStore()
const unavailable = computed(() => !store.token || store.demo)
const form = reactive({ username: '', password: '', reinput_password: '' })
const busy = ref(false)
const dialogVisible = ref(false)
async function validateConfirmation(_: unknown, value: string) {
  if (value && value !== form.password) throw new Error('两次输入的密码不一致')
}
async function register() {
  if (busy.value || unavailable.value) return
  busy.value = true
  try {
    await api.post('/api/auth/register', { username: form.username, password: form.password })
    dialogVisible.value = true
  } catch (err) { message.error(errorMessage(err)) }
  finally { busy.value = false; form.password = ''; form.reinput_password = '' }
}
function finishRegistration() { dialogVisible.value = false; void router.replace('/login') }
</script>

<template>
  <main class="login-page">
    <section class="login-panel">
      <div class="signup-header"><RobotOutlined /><h1>注册新账户</h1></div>
      <a-alert v-if="unavailable" type="info" show-icon message="公开注册暂未开放，请联系平台管理员。账户登记需要已有用户登录授权。" class="error-banner" />
      <a-form :model="form" layout="vertical" :required-mark="false" :disabled="unavailable || busy || dialogVisible" @finish="register">
        <a-form-item label="用户名" name="username" :rules="[{ required: true, pattern: /^[a-zA-Z0-9]{3,15}$/, message: '用户名须为 3 至 15 位字母或数字' }]"><a-input v-model:value="form.username" autocomplete="username" size="large"><template #prefix><UserOutlined /></template></a-input></a-form-item>
        <a-form-item label="密码" name="password" :rules="[{ required: true, message: '请输入密码' }, { min: 12, max: 72, message: '密码长度须为 12 至 72 个字符' }]"><a-input-password v-model:value="form.password" autocomplete="new-password" size="large"><template #prefix><KeyOutlined /></template></a-input-password></a-form-item>
        <a-form-item label="确认密码" name="reinput_password" :dependencies="['password']" :rules="[{ required: true, message: '请再次输入密码' }, { validator: validateConfirmation }]"><a-input-password v-model:value="form.reinput_password" autocomplete="new-password" size="large"><template #prefix><KeyOutlined /></template></a-input-password></a-form-item>
        <a-button type="primary" block size="large" html-type="submit" :loading="busy" :disabled="unavailable">注册</a-button>
      </a-form>
      <div class="signup-link"><RouterLink to="/login">返回登录</RouterLink></div>
    </section>
    <a-modal :open="dialogVisible" title="注册成功" ok-text="前往登录" :cancel-button-props="{ style: { display: 'none' } }" :closable="false" :mask-closable="false" :keyboard="false" @ok="finishRegistration">注册成功，请使用新账户登录。</a-modal>
  </main>
</template>

<style scoped>
.signup-header { display: flex; gap: 10px; align-items: center; margin-bottom: 24px; }
.signup-header > .anticon { color: #087f78; font-size: 28px; }
</style>
