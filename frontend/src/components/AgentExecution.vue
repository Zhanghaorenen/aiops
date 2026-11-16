<script setup lang="ts">
import { computed } from 'vue'
import type { ExecutionStep, Specialist } from '../api'
import { toolLabels } from '../utils/chatToolLabels'

const props = defineProps<{ steps: ExecutionStep[]; state: 'live' | 'complete' | 'interrupted' }>()
const names: Record<Specialist, string> = {
  monitoring: '监控专家', workload: '训练任务专家', knowledge: '知识库专家',
}
const outcomes = { success: '已完成', partial: '证据不完整', failed: '执行失败', skipped: '已跳过' }

function describe(step: ExecutionStep) {
  switch (step.type) {
    case 'planning': return '正在分析问题，制定计划'
    case 'plan':
      if (step.clarification) return '需要补充信息'
      if (!step.tasks.length) return '直接回答'
      return step.tasks.length === 1 ? '已分配任务' :
        step.execution_mode === 'parallel' ? '并行检查' : '按顺序检查'
    case 'agent_start': return names[step.agent] + '开始检查'
    case 'tool_start':
    case 'tool_end': {
      const tool = toolLabels[step.name] || step.name
      const status = step.type === 'tool_start' ? '开始' : step.ok === false ? '失败' : '结束'
      return names[step.agent] + ' · ' + tool + ' · ' + status
    }
    case 'agent_result': return names[step.agent] + ' · ' + outcomes[step.status]
    case 'synthesis': return '正在汇总证据与核对结论'
  }
}
const heading = computed(() => props.state === 'complete' ? '查看协作过程'
  : props.state === 'interrupted' ? '协作已中断' : '实时协作')
</script>

<template>
  <details class="execution" :open="state !== 'complete'">
    <summary>{{ heading }}</summary>
    <ol aria-label="智能体执行记录">
      <li v-for="(step, i) in steps" :key="i"
          :class="{ warning: (step.type === 'agent_result' && step.status !== 'success') ||
                             (step.type === 'tool_end' && step.ok === false) }">
        {{ describe(step) }}
        <ul v-if="step.type === 'plan' && !step.clarification && step.tasks.length">
          <li v-for="(task, j) in step.tasks" :key="j">{{ names[task.agent] }} · {{ task.objective }}</li>
        </ul>
        <small v-if="step.type === 'agent_result'">{{ step.tool_calls }} 次工具调用 · {{ (step.elapsed_ms / 1000).toFixed(1) }} 秒</small>
      </li>
    </ol>
  </details>
</template>

<style scoped>
.execution { min-width: 0; white-space: normal; font-size: 13px; }
summary { cursor: pointer; color: var(--text); font-weight: 600; }
ol { margin: 10px 0 0 5px; padding: 0 0 0 16px; border-left: 1px solid var(--edge); list-style: none; }
ol > li { position: relative; padding: 3px 0; }
ol > li::before { content: ''; position: absolute; left: -20px; top: 12px; width: 6px; height: 6px; background: #59c7cf; border-radius: 50%; }
ol > li.warning::before { background: #e6ae55; }
ul, small { color: var(--muted); }
ul { padding-inline-start: 20px; }
small { margin-left: 6px; }
</style>
