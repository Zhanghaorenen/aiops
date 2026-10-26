<script setup lang="ts">
import { useRouter } from 'vue-router'
const router = useRouter()
</script>

<template><a-empty description="页面不存在"><a-button @click="router.push('/overview')">返回总览</a-button></a-empty></template>
