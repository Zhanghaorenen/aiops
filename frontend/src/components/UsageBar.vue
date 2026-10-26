<script setup lang="ts">
import { formatPercent } from '../utils/format'
defineProps<{ value: number | null; stale?: boolean }>()
</script>

<template>
  <a-tooltip :title="stale ? '离线主机的最后一次采样' : undefined">
    <div class="usage" :class="{ stale }"><span>{{ formatPercent(value) }}</span><a-progress :percent="value ?? 0" :show-info="false" :stroke-color="value != null && value >= 80 ? '#d39429' : '#087f78'" /></div>
  </a-tooltip>
</template>
