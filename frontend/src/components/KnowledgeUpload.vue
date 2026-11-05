<script setup lang="ts">
import { ref } from 'vue'
import { aiApi, errorMessage } from '../api'
const props=defineProps<{ kbId:number }>()
const emit=defineEmits<{ uploaded:[] }>()
const busy=ref(false), error=ref('')
async function upload(event:Event) {
 const input=event.target as HTMLInputElement, file=input.files?.[0]
 if(!file)return
 error.value=''
 if(file.size>10*1024*1024 || !/\.(pdf|docx|md|txt)$/i.test(file.name)){error.value='请选择不超过 10 MiB 的 PDF/DOCX/MD/TXT 文件';input.value='';return}
 busy.value=true
 try {const data=new FormData();data.append('file',file);await aiApi.post(`/api/rag/knowledge-bases/${props.kbId}/documents`,data,{timeout:600000});emit('uploaded')}
 catch(e){error.value=errorMessage(e);emit('uploaded')}
 finally{busy.value=false;input.value=''}
}
</script>
<template><a-space direction="vertical"><label>上传运维文档（PDF / DOCX / MD / TXT，最大 10 MiB）<input type="file" accept=".pdf,.docx,.md,.txt" :disabled="busy" @change="upload" /></label><a-spin v-if="busy" tip="正在解析和建立索引…"/><a-alert v-if="error" type="error" :message="error"/></a-space></template>
