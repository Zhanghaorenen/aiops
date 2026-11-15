<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { api, errorMessage } from '../api'
import { useWorkspaceStore } from '../stores/workspace'
import { formatTime } from '../utils/format'
const store=useWorkspaceStore()
const categories=[{value:'USER_PREFERENCE',label:'用户偏好'},{value:'WORKFLOW',label:'常用流程'},{value:'ENVIRONMENT',label:'稳定环境'},{value:'CONFIRMED_EXPERIENCE',label:'已确认经验'}]
const memories=ref<any[]>([]),sessions=ref<any[]>([]),summary=ref<any>(),automatic=ref(false),rounds=ref(5)
const filter=ref('ALL'),category=ref('USER_PREFERENCE'),content=ref(''),error=ref(''),session=ref<string>(),editing=ref<any>(),busy=ref(false)
let revision=0,controller:AbortController|undefined
const visible=computed(()=>memories.value.filter(m=>filter.value==='ALL'||m.category===filter.value))
async function load() {
  if(!store.token||store.demo)return
  controller?.abort();controller=new AbortController();const signal=controller.signal,v=revision
  try {
    const [m,s,p]=await Promise.all([api.get('/api/ai/memories',{signal}),api.get('/api/ai/sessions',{signal}),api.get('/api/ai/chat-memory/settings',{signal})])
    if(v!==revision||signal.aborted)return
    memories.value=m.data;sessions.value=s.data;automatic.value=p.data.automatic;rounds.value=p.data.rounds
    if(!sessions.value.some(s=>s.id===session.value))session.value=sessions.value[0]?.id
    if(session.value) {
      const {data}=await api.get(`/api/ai/sessions/${session.value}/summary`,{signal})
      if(v===revision&&!signal.aborted)summary.value=data
    }
  } catch(e) { if(v===revision&&!signal.aborted)error.value=errorMessage(e) }
}
async function action(fn:()=>Promise<void>) {
  if(busy.value||!store.token||store.demo)return
  const v=revision;busy.value=true;error.value=''
  try { await fn();if(v===revision)await load() }
  catch(e){if(v===revision)error.value=errorMessage(e)}
  finally{if(v===revision)busy.value=false}
}
function toggle(value:boolean|number|string) {void action(async()=>{await api.put('/api/ai/chat-memory/settings',{automatic:!!value})})}
function save() {const v=revision;void action(async()=>{await api.post('/api/ai/memories',{content:content.value,category:category.value});if(v===revision)content.value=''})}
function update() {const v=revision;void action(async()=>{await api.patch(`/api/ai/memories/${editing.value.id}`,{content:editing.value.content,category:editing.value.category});if(v===revision)editing.value=undefined})}
function remove(id:string) {void action(async()=>{await api.delete(`/api/ai/memories/${id}`)})}
function clear() {void action(async()=>{await api.delete('/api/ai/memories')})}
function retry() {void action(async()=>{await api.post('/api/ai/chat-memory/retry')})}
watch([()=>store.token,()=>store.demo],()=>{
  revision++;controller?.abort();memories.value=[];sessions.value=[];summary.value=undefined;session.value=undefined;editing.value=undefined;content.value='';automatic.value=false;busy.value=false;error.value='';void load()
},{immediate:true,flush:'sync'})
watch(session,()=>{summary.value=undefined;void load()})
const timer=setInterval(()=>{if(!busy.value)void load()},15000)
onBeforeUnmount(()=>{revision++;controller?.abort();clearInterval(timer)})
</script>
<template>
  <a-alert v-if="error" :message="error" type="error" show-icon />
  <a-space wrap class="memory-controls"><span>自动聊天记忆（独立授权）</span><a-switch :checked="automatic" :disabled="store.demo||busy" @change="toggle" /><a-button :disabled="store.demo||busy" @click="retry">重试失败任务</a-button></a-space>
  <p class="muted">默认关闭。开启后只处理新增且已完成的对话，约 {{rounds}} 轮或会话归档时异步生成摘要、提取持久信息。关闭后停止新提取；已有记忆仍可手动管理。实时监控不作为长期事实。</p>
  <a-form layout="vertical" @finish="save"><a-form-item label="保存本人确认的偏好、流程或经验"><a-select v-model:value="category" :options="categories" style="width:180px" /><a-textarea v-model:value="content" :maxlength="4000" :rows="3" /></a-form-item><a-button html-type="submit" :disabled="store.demo||busy||!content.trim()">保存</a-button></a-form>
  <a-space wrap class="memory-controls"><a-select v-model:value="filter" :options="[{value:'ALL',label:'全部类别'},...categories]" style="width:180px"/><a-popconfirm title="清空本人长期记忆和会话摘要？原始聊天记录保留，旧消息不会重新提取。" @confirm="clear"><a-button danger :disabled="store.demo||busy">清空记忆与摘要</a-button></a-popconfirm></a-space>
  <a-list :data-source="visible"><template #renderItem="{item}"><a-list-item>
    <a-list-item-meta :title="item.content"><template #description><a-tag>{{categories.find(c=>c.value===item.category)?.label||item.category}}</a-tag>版本 {{item.version}} · {{formatTime(item.updated_at)}} · {{item.source_session_id?'来自已完成对话':'用户手动确认'}}<a-button v-if="item.source_session_id" type="link" size="small" @click="session=item.source_session_id">来源会话</a-button><span v-if="item.index_status!=='COMPLETED'"> · 语义索引{{item.index_status==='FAILED'?'失败，可重试':'待处理'}}</span></template></a-list-item-meta>
    <a-space><a-button size="small" :disabled="busy" @click="editing={...item}">编辑</a-button><a-popconfirm title="删除这条私人记忆？" @confirm="remove(item.id)"><a-button size="small" danger :disabled="busy">删除</a-button></a-popconfirm></a-space>
  </a-list-item></template></a-list>
  <a-card title="会话摘要" size="small"><a-select v-model:value="session" :options="sessions.map(s=>({value:s.id,label:s.title}))" placeholder="选择自己的会话" style="min-width:240px;max-width:100%"/><p v-if="summary">{{summary.summary||'暂无摘要；未授权、尚未达到触发轮数或任务处理中。'}}</p><a-alert v-if="summary?.processingState==='FAILED'" type="warning" message="摘要任务未完成，请检查服务配置后点击重试失败任务。"/><p v-if="summary?.version" class="muted">版本 {{summary.version}} · {{formatTime(summary.updated_at)}}</p></a-card>
  <a-modal :open="!!editing" title="编辑长期记忆" :confirm-loading="busy" @ok="update" @cancel="editing=undefined"><template v-if="editing"><a-select v-model:value="editing.category" :options="categories" style="width:180px"/><a-textarea v-model:value="editing.content" :maxlength="4000" :rows="5"/></template></a-modal>
</template>
<style scoped>.memory-controls{margin:12px 0}</style>
