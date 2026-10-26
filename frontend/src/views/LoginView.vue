<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { ExperimentOutlined, KeyOutlined, SafetyCertificateOutlined, UserOutlined } from '@ant-design/icons-vue'
import { errorMessage } from '../api'
import { useWorkspaceStore } from '../stores/workspace'

const store = useWorkspaceStore()
const router = useRouter()
const form = reactive({ username: '', password: '' })
const busy = ref(false)
async function login() {
  busy.value = true
  try {
    await store.login(form)
    await router.replace('/overview')
  } catch (err) { message.error(errorMessage(err)) }
  finally { busy.value = false; form.password = '' }
}
function enterDemo() { store.startDemo(); void router.replace('/overview') }
</script>

<template>
  <main class="login-page">
    <section class="login-panel">
      <div class="login-brand"><SafetyCertificateOutlined /><span>AIOps<span class="brand-caption">运维平台</span></span></div>
      <h1>登录工作台</h1><p class="muted">连接你的基础设施，查看实时运行状态</p>
      <a-form :model="form" layout="vertical" :required-mark="false" @finish="login">
        <a-form-item label="用户名" name="username" :rules="[{ required: true, message: '请输入用户名' }]"><a-input v-model:value="form.username" autocomplete="username" size="large"><template #prefix><UserOutlined /></template></a-input></a-form-item>
        <a-form-item label="密码" name="password" :rules="[{ required: true, message: '请输入密码' }]"><a-input-password v-model:value="form.password" autocomplete="current-password" size="large"><template #prefix><KeyOutlined /></template></a-input-password></a-form-item>
        <a-button type="primary" block size="large" html-type="submit" :loading="busy">登录</a-button>
      </a-form>
      <div class="login-divider"><span>或</span></div>
      <a-button block size="large" @click="enterDemo"><template #icon><ExperimentOutlined /></template>进入演示环境</a-button>
      <p class="login-note">演示环境使用示例数据，无需连接数据库</p>
    </section>
    <span class="login-footer">AIOps Agent Platform · Infrastructure Operations</span>
  </main>
</template>
