<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { KeyOutlined, RobotOutlined, UserOutlined } from '@ant-design/icons-vue'
import { errorMessage } from '../api'
import { useWorkspaceStore } from '../stores/workspace'

const router = useRouter()
const store = useWorkspaceStore()


const form = reactive({ username: '', password: '', reinput_password: '' })
const busy = ref(false)
const dialogVisible = ref(false)
const registrationError = ref('')

// 统一表单禁用状态，避免各处判断不一致
const formDisabled = computed(() => store.demo || busy.value || dialogVisible.value)

const formRules = {
  username: [{ required: true, pattern: /^[a-zA-Z0-9]{3,15}$/, message: '用户名须为 3 至 15 位字母或数字' }],
  password: [
    { required: true, message: '请输入密码' },
    { min: 12, max: 72, message: '密码长度须为 12 至 72 个字符' },
    { validator: validatePasswordBytes },
  ],
  reinput_password: [
    { required: true, message: '请再次输入密码' },
    { validator: validateConfirmation },
  ],
}

function resetForm() {
  form.username = ''
  form.password = ''
  form.reinput_password = ''
  registrationError.value = ''
}

async function validatePasswordBytes(_rule: unknown, value: string): Promise<void> {
  if (value && new TextEncoder().encode(value).length > 72) {
    throw new Error('密码 UTF-8 长度不能超过 72 字节')
  }
}

async function validateConfirmation(_rule: unknown, value: string): Promise<void> {
  if (value && value !== form.password) throw new Error('两次输入的密码不一致')
}

async function register() {
  if (formDisabled.value) return
  busy.value = true
  registrationError.value = ''
  try {
    await store.register({
      username: form.username.trim(),
      password: form.password,
    })
    // 仅成功后清空敏感字段；失败时保留用户输入
    form.password = ''
    form.reinput_password = ''
    dialogVisible.value = true
  } catch (err) {
    registrationError.value = errorMessage(err)
  } finally {
    busy.value = false
  }
}

async function finishRegistration() {
  dialogVisible.value = false
  resetForm()
  store.logout()
  await router.replace('/login')
}

function leaveRegistration() {
  if (store.demo) store.logout()
}
</script>

<template>
  <main class="login-page">
    <section class="login-panel">
      <div class="signup-header"><RobotOutlined /><h1>注册新账户</h1></div>

      <a-alert v-if="registrationError" type="error" show-icon :message="registrationError" class="error-banner" />

      <a-alert
        v-if="store.demo"
        type="info"
        show-icon
        message="演示环境不支持注册，请点击“返回登录”后注册。"
        class="error-banner"
      />

      <a-form
        :model="form"
        :rules="formRules"
        layout="vertical"
        :required-mark="false"
        :disabled="formDisabled"
        @finish="register"
      >
        <a-form-item
          label="用户名"
          name="username"
        >
          <a-input v-model:value="form.username" autocomplete="username" size="large">
            <template #prefix><UserOutlined /></template>
          </a-input>
        </a-form-item>

        <a-form-item
          label="密码"
          name="password"
          extra="密码至少 12 个字符；中文或表情等多字节字符总长不能超过 72 字节。"
        >
          <a-input-password v-model:value="form.password" autocomplete="new-password" size="large">
            <template #prefix><KeyOutlined /></template>
          </a-input-password>
        </a-form-item>

        <a-form-item
          label="确认密码"
          name="reinput_password"
          :dependencies="['password']"
        >
          <a-input-password v-model:value="form.reinput_password" autocomplete="new-password" size="large">
            <template #prefix><KeyOutlined /></template>
          </a-input-password>
        </a-form-item>

        <a-button type="primary" block size="large" html-type="submit" :loading="busy" :disabled="formDisabled">
          注册
        </a-button>
      </a-form>

      <div class="signup-link"><RouterLink to="/login" @click="leaveRegistration">返回登录</RouterLink></div>
    </section>

    <a-modal
      :open="dialogVisible"
      title="注册成功"
      ok-text="前往登录"
      :cancel-button-props="{ style: { display: 'none' } }"
      :closable="false"
      :mask-closable="false"
      :keyboard="false"
      @ok="finishRegistration"
    >
      注册成功，请使用新账户登录。
    </a-modal>
  </main>
</template>

<style scoped>
.signup-header { display: flex; gap: 10px; align-items: center; margin-bottom: 24px; }
.signup-header > .anticon { color: #087f78; font-size: 28px; }
</style>
