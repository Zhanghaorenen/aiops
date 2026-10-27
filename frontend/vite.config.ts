import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],

  server: {
    port: 5173,

    proxy: {
      // AI 聊天请求转发给 FastAPI
      '/api/chat': {
        target: process.env.AIOPS_AI_PROXY_TARGET || 'http://127.0.0.1:8000',
        changeOrigin: true,
      },
      '/api/ai/health': {
        target: process.env.AIOPS_AI_PROXY_TARGET || 'http://127.0.0.1:8000',
        changeOrigin: true,
      },

      // 其他业务请求仍然转发给 Spring Boot
      '/api': {
        target: process.env.AIOPS_PROXY_TARGET
          || 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
})
