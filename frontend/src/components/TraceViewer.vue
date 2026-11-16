<script setup lang="ts">
import { ref, watch } from 'vue'
import { api, errorMessage } from '../api'
import { useWorkspaceStore } from '../stores/workspace'
const workspace=useWorkspaceStore()
const props=defineProps<{traceId?:string}>()
const input=ref(props.traceId ?? ''), timelines=ref<any[]>([]), error=ref(''), loading=ref(false)
let version=0
watch(()=>workspace.token,()=>{version++;timelines.value=[];error.value='';loading.value=false})
async function find(){
  const seq=++version
  timelines.value=[];error.value=''
  loading.value=false
  const id=input.value.trim()
  if(!/^[0-9a-f]{32}$/.test(id)){error.value='请输入 32 位 Trace ID';return}
  loading.value=true
  try{
    const {data}=await api.get('/api/ai/traces/'+id)
    if(seq===version)timelines.value=data
  }catch(e){if(seq===version)error.value=errorMessage(e)}
  finally{if(seq===version)loading.value=false}
}
</script>
<template>
  <details @toggle="(e:Event) => {if(props.traceId && (e.target as HTMLDetailsElement).open) void find()}"><summary>{{ props.traceId ? '执行追踪 · '+props.traceId : '查询对话或调查执行追踪' }}</summary>
    <a-input-search v-if="!props.traceId" v-model:value="input" placeholder="Trace ID" :loading="loading" @search="find" />
    <p v-if="error">{{ error }}</p>
    <section v-for="t in timelines" :key="t.executionId">
      <h4>{{ t.incidentId ? '调查 #'+t.incidentId : 'Agent 对话' }} · {{ new Date(t.startedAt*1000).toLocaleString() }} · {{ t.durationMs }} ms</h4>
      <ol><li v-for="(s,i) in t.spans" :key="i">+{{ s.startMs }} ms · {{ s.name }} · {{ s.durationMs }} ms · {{ s.error || s.status }}
        <span v-if="s.model"> · {{ s.model }} · 输入 {{ s.inputTokens ?? '未知' }} / 输出 {{ s.outputTokens ?? '未知' }} tokens</span>
      </li></ol>
    </section>
  </details>
</template>
