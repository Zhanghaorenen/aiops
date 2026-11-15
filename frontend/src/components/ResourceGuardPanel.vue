<script setup lang="ts">
import { ref, watch, onUnmounted } from 'vue'
import { Modal, message } from 'ant-design-vue'
import { api, errorMessage } from '../api'
import { useWorkspaceStore } from '../stores/workspace'
import { formatTime } from '../utils/format'
const props = defineProps<{ serverId: number }>()
const store = useWorkspaceStore()
const busy=ref(false),error=ref(''),state=ref<any>(null),events=ref<any[]>([]),uids=ref('')
const policy=ref<any>({ mode:'DISABLED',businessUids:[],minAvailableMb:1024,minAvailablePercent:5,memoryPsiThreshold:10,sustainSeconds:10,cooldownSeconds:60,maxActionsPerHour:3,graceSeconds:5,minRssMb:256 })
let generation=0
async function load(reset=false) {
  if(store.demo)return
  const id=props.serverId,gen=++generation
  try {
    const data=(await api.get(`/api/servers/${id}/resource-guard`)).data
    if(gen!==generation||id!==props.serverId)return
    state.value=data;error.value=''
    events.value=data.events.map((e:any)=>{let v:any={};try{v=JSON.parse(e.event_json)}catch{};return {...v,receivedAt:e.received_at}})
    if(reset){policy.value=data.policy;uids.value=data.policy.businessUids.join(',')}
  }catch(e){if(gen===generation)error.value=errorMessage(e)}
}
async function save(){
  const ids=uids.value.split(',').filter(v=>v.trim()).map(Number)
  if(ids.some(n=>!Number.isInteger(n)||n<1000)||ids.length>32){message.error('业务UID必须是大于等于1000的整数，最多32个');return}
  const body={...policy.value,businessUids:ids},id=props.serverId
  if(body.mode==='AUTO'&&!ids.length){message.error('请填写业务账号UID');return}
  Modal.confirm({title:'保存资源保护策略',content:body.mode==='AUTO'?`将允许自动结束业务UID ${ids.join(', ')} 范围内的高内存进程，可能丢失未保存的训练进度。仅采集端已独立启用时生效。`:'保存关闭或仅观察策略。',
    async onOk(){busy.value=true;try{await api.put(`/api/servers/${id}/resource-guard`,body);message.success('策略已保存，等待采集端同步');await load(true)}catch(e){error.value=errorMessage(e)}finally{busy.value=false}}})
}
async function check(){busy.value=true;try{await api.post(`/api/servers/${props.serverId}/resource-guard/check`);message.success('检查请求已受理，不代表已结束进程');await load()}catch(e){error.value=errorMessage(e)}finally{busy.value=false}}
watch(()=>props.serverId,()=>{generation++;state.value=null;events.value=[];error.value='';void load(true)},{immediate:true})
const timer=setInterval(()=>void load(),10000)
onUnmounted(()=>{generation++;clearInterval(timer)})
</script>
<template>
  <a-card title="内存资源保护" style="margin:16px 0">
    <a-alert v-if="store.demo" type="info" message="演示模式不提供进程处置" />
    <template v-else>
      <a-alert v-if="error" type="error" :message="error" />
      <a-alert type="warning" show-icon message="自动处置会结束业务进程，训练进度可能丢失。系统、平台服务与保护进程始终排除。" />
      <p>采集端状态：{{ state?.agentOnline ? state?.agentStatus?.state : '保护模块未连接或同步已过期' }} · 上次上报的本地执行开关：{{ state?.agentStatus?.armed ? '已启用' : '未启用' }} · 最后同步：{{ formatTime(state?.lastSeenAt) }}</p>
      <p v-if="state?.agentStatus?.lastCheckRequest">最近检查结果：{{ state.agentStatus.lastCheckOutcome || '已接收，等待本地评估' }}；该状态不等于已终止进程，请以处置审计为准。</p>
      <p>采集端允许的业务UID：{{ state?.agentStatus?.localBusinessUids?.join(', ') || '未配置' }}。策略仅在与本地范围的交集中生效，超过5分钟未同步会停止自动处置。</p>
      <a-form layout="inline" :disabled="store.role !== 'ADMIN' || busy">
        <a-form-item label="保护模式"><a-select v-model:value="policy.mode" style="width:150px" :options="[{label:'关闭',value:'DISABLED'},{label:'仅观察',value:'OBSERVE'},{label:'自动处置业务进程',value:'AUTO'}]" /></a-form-item>
        <a-form-item label="业务账号UID"><a-input v-model:value="uids" placeholder="多个UID用逗号分隔" /></a-form-item>
        <a-form-item label="可用内存低于 MiB"><a-input-number v-model:value="policy.minAvailableMb" :min="256" :max="65536" /></a-form-item>
        <a-form-item label="或低于总量 %"><a-input-number v-model:value="policy.minAvailablePercent" :min="1" :max="15" /></a-form-item>
        <a-form-item label="内存PSI阈值 %"><a-input-number v-model:value="policy.memoryPsiThreshold" :min="1" :max="100" /></a-form-item>
        <a-form-item label="持续秒数"><a-input-number v-model:value="policy.sustainSeconds" :min="4" :max="300" /></a-form-item>
        <a-form-item label="最小目标RSS MiB"><a-input-number v-model:value="policy.minRssMb" :min="128" :max="65536" /></a-form-item>
        <a-form-item label="冷却秒数"><a-input-number v-model:value="policy.cooldownSeconds" :min="30" :max="3600" /></a-form-item>
        <a-form-item label="每小时最多处置"><a-input-number v-model:value="policy.maxActionsPerHour" :min="1" :max="10" /></a-form-item>
        <a-form-item label="退出等待秒数"><a-input-number v-model:value="policy.graceSeconds" :min="1" :max="30" /></a-form-item>
        <a-form-item><a-button type="primary" @click="save">保存策略</a-button></a-form-item>
        <a-form-item><a-button @click="check">请求保护检查</a-button></a-form-item>
      </a-form>
      <p>触发条件为低可用内存持续达到阈值，且内存PSI偏高；没有PSI时只在极低内存下评估。每轮最多处置一个高RSS业务进程，先请求退出，必要时强制结束；不会仅因CPU或显存占用高而操作。</p>
      <a-table size="small" :data-source="events" row-key="id" :pagination="{pageSize:5}" :columns="[{title:'状态',dataIndex:'status'},{title:'目标PID',key:'pid'},{title:'业务UID',key:'uid'},{title:'记录时间',key:'at'}]">
        <template #bodyCell="{column,record}"><template v-if="column.key==='pid'">{{record.details?.target?.pid || '—'}}</template><template v-else-if="column.key==='uid'">{{record.details?.target?.uid ?? '—'}}</template><template v-else-if="column.key==='at'">{{record.at ? new Date(record.at*1000).toLocaleString() : record.receivedAt}}</template></template>
      </a-table>
    </template>
  </a-card>
</template>
