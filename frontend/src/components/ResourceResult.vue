<script setup lang="ts">
import { formatTime, formatBytes } from '../utils/format'
defineProps<{ name: string; data: Record<string, any> }>()
</script>
<template>
  <div class="resource-result">
    <template v-if="name === 'estimate_available_capacity'">
      <strong>资源预算</strong>
      <a-tag :color="data.allowed ? 'green' : 'orange'">{{
        data.allowed ? '当前满足' : '暂不建议'
      }}</a-tag>
      <p>
        安全显存
        {{ data.estimatedSafeBudget?.vramMb?.toFixed(0) ?? '未知' }} MiB · RAM
        {{ data.estimatedSafeBudget?.ramMb?.toFixed(0) ?? '未知' }} MiB
      </p>
      <p>{{ formatTime(data.sampledAt) }} · {{ data.confidence }}</p>
      <p
        v-for="risk in [...(data.risks || []), ...(data.missingEvidence || [])]"
        :key="risk"
      >
        {{ risk }}
      </p>
    </template>
    <template v-else-if="name === 'predict_workload_completion'">
      <strong>结束时间预测</strong>
      <p v-if="data.reason">{{ data.reason }}</p>
      <template v-else
        ><p>
          {{ formatTime(data.earliestAt) }} — {{ formatTime(data.latestAt) }}
        </p>
        <p>
          {{ data.method }} · 样本 {{ data.sampleCount }} ·
          {{ data.confidence }}
        </p>
        <p>{{ data.limitations }}</p></template
      >
    </template>
    <template v-else-if="name === 'analyze_active_workload'"
      ><strong>运行任务分析</strong>
      <p>{{ data.displayName }} · {{ formatTime(data.sampledAt) }}</p>
      <p>{{ data.conclusion }}</p>
      <p v-for="risk in data.observations" :key="risk">{{ risk }}</p>
      <p v-for="missing in data.missingEvidence" :key="missing">
        {{ missing }}
      </p></template
    >
    <template v-else-if="name === 'get_workload_profile'"
      ><strong>历史画像</strong>
      <p>
        {{ data.runCount ?? 0 }} 次运行 · {{ data.sampleCount ?? 0 }} 个样本 ·
        {{ data.confidence }}
      </p>
      <p>显存 P95 {{ formatBytes(data.gpuMemoryBytes?.p95) }}</p></template
    >
    <template v-else-if="name === 'search_similar_incidents'"
      ><strong>历史经验来源</strong>
      <p v-for="hit in data.results" :key="hit.case_id">
        {{ hit.title }} · {{ hit.case_id }} · {{ hit.status }} · v{{
          hit.version
        }}
      </p>
      <p v-for="missing in data.missingEvidence" :key="missing">
        {{ missing }}
      </p></template
    >
  </div>
</template>
<style scoped>
.resource-result {
  border: 1px solid var(--edge);
  border-radius: 6px;
  margin-top: 12px;
  padding: 12px;
  white-space: normal;
}
.resource-result p {
  margin: 6px 0;
  font-size: 12px;
}
</style>
