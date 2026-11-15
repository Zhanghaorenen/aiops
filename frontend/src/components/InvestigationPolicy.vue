<script setup lang="ts">
import { reactive,ref,watch } from 'vue'
import { api,errorMessage } from '../api'
const props=defineProps<{serverId:number}>()
const form=reactive({enabled:false,minSeverity:'CRITICAL',cooldownSeconds:900,maxPerDay:3})
const error=ref(''),saved=ref(false)
let version=0
watch(()=>props.serverId,async server=>{
  const current=++version
  try {
    const {data}=await api.get('/api/servers/'+server+'/investigation-policy')
    if(current===version) Object.assign(form,{enabled:!!data.enabled,minSeverity:data.min_severity,cooldownSeconds:data.cooldown_seconds,maxPerDay:data.max_per_day})
  } catch(e) {if(current===version) error.value=errorMessage(e)}
},{immediate:true})
async function save() {
  error.value='';saved.value=false
  try {await api.put('/api/servers/'+props.serverId+'/investigation-policy',form);saved.value=true}
  catch(e) {error.value=errorMessage(e)}
}
</script>
<template>
  <a-collapse style="margin-top:16px"><a-collapse-panel key="policy" header="告警自动调查设置">
    <a-alert v-if="error" type="error" :message="error" />
    <a-form layout="vertical">
      <a-form-item label="自动创建后台调查"><a-switch v-model:checked="form.enabled" /></a-form-item>
      <a-form-item label="告警严重度"><a-select v-model:value="form.minSeverity" :options="[{label:'仅严重告警',value:'CRITICAL'},{label:'警告及严重告警',value:'WARNING'}]" /></a-form-item>
      <a-form-item label="两次自动调查间隔（秒）"><a-input-number v-model:value="form.cooldownSeconds" :min="60" :max="86400" /></a-form-item>
      <a-form-item label="本服务器每天调查上限"><a-input-number v-model:value="form.maxPerDay" :min="1" :max="20" /></a-form-item>
      <a-button @click="save">保存策略</a-button><span v-if="saved"> 已保存</span>
    </a-form>
  </a-collapse-panel></a-collapse>
</template>
