<script setup lang="ts">
import { ref,onMounted,onUnmounted } from 'vue'
import { api,aiApi,errorMessage,type KnowledgeBase,type KnowledgeDocument,type RagSearchResult } from '../api'
import KnowledgeUpload from '../components/KnowledgeUpload.vue'
import KnowledgeDocumentTable from '../components/KnowledgeDocumentTable.vue'
import { useWorkspaceStore } from '../stores/workspace'
const workspace=useWorkspaceStore()
const bases=ref<KnowledgeBase[]>([]),selected=ref<number>(),documents=ref<KnowledgeDocument[]>([]),results=ref<RagSearchResult[]>([])
const name=ref(''),description=ref(''),query=ref(''),error=ref(''),busy=ref(false)
async function run(fn:()=>Promise<void>){if(workspace.demo){error.value='请登录真实账户后管理知识库';return}busy.value=true;error.value='';try{await fn()}catch(e){error.value=errorMessage(e)}finally{busy.value=false}}
async function load(){bases.value=(await api.get<KnowledgeBase[]>('/api/knowledge-bases')).data;if(!bases.value.some(k=>k.id===selected.value))selected.value=bases.value[0]?.id;await loadDocs()}
async function loadDocs(){results.value=[];documents.value=selected.value?(await api.get<KnowledgeDocument[]>(`/api/knowledge-bases/${selected.value}/documents`)).data:[]}
async function create(){await run(async()=>{if(!name.value.trim())throw new Error('请输入知识库名称');const {data}=await api.post<KnowledgeBase>('/api/knowledge-bases',{name:name.value,description:description.value});selected.value=data.id;name.value='';description.value='';await load()})}
async function edit(){const kb=bases.value.find(k=>k.id===selected.value);if(!kb)return;const value=window.prompt('知识库名称',kb.name);if(!value)return;const desc=window.prompt('简介',kb.description||'');if(desc===null)return;await run(async()=>{await api.put(`/api/knowledge-bases/${kb.id}`,{name:value,description:desc});await load()})}
async function removeBase(){if(!selected.value)return;await run(async()=>{await aiApi.delete(`/api/rag/knowledge-bases/${selected.value}`,{timeout:600000});selected.value=undefined;await load()})}
async function removeDoc(id:number){await run(async()=>{await aiApi.delete(`/api/rag/knowledge-bases/${selected.value}/documents/${id}`);await loadDocs()});await refreshDocs()}
async function search(){await run(async()=>{results.value=(await aiApi.post<{results:RagSearchResult[]}>('/api/rag/search',{query:query.value,knowledge_base_ids:[selected.value],top_k:5})).data.results})}
async function refreshDocs(){if(selected.value)try{documents.value=(await api.get<KnowledgeDocument[]>(`/api/knowledge-bases/${selected.value}/documents`)).data}catch{}}
onMounted(()=>void run(load))
const timer=window.setInterval(()=>{if(!busy.value&&!workspace.demo)void refreshDocs()},5000)
onUnmounted(()=>clearInterval(timer))
</script>
<template><a-space direction="vertical" style="width:100%" :size="20">
<a-alert v-if="error" type="error" :message="error" show-icon/>
<a-card title="私人运维知识库"><a-space wrap><a-input v-model:value="name" placeholder="新知识库名称" :maxlength="160"/><a-input v-model:value="description" placeholder="简介" :maxlength="1000"/><a-button type="primary" :loading="busy" @click="create">创建知识库</a-button></a-space></a-card>
<a-card title="文档与索引"><a-space wrap style="margin-bottom:20px"><a-select v-model:value="selected" style="min-width:240px" placeholder="选择知识库" :options="bases.map(k=>({value:k.id,label:`${k.name} (${k.status})`}))" @change="()=>run(loadDocs)"/><a-button :disabled="!selected||busy" @click="edit">编辑</a-button><a-popconfirm title="删除知识库及所有文档和索引？" @confirm="removeBase"><a-button danger :disabled="!selected||busy">删除 / 重试清理</a-button></a-popconfirm><a-button :disabled="busy" @click="()=>run(load)">刷新</a-button></a-space>
<KnowledgeUpload v-if="selected && bases.find(k=>k.id===selected)?.status==='ACTIVE'" :key="selected" :kb-id="selected" @uploaded="refreshDocs"/>
<KnowledgeDocumentTable :documents="documents" :busy="busy" @remove="removeDoc"/></a-card>
<a-card title="测试知识检索"><a-input-group style="width:100%"><a-input v-model:value="query" placeholder="例如：GPU 显存高、利用率低怎么办？" :maxlength="2000" @press-enter="search"/><a-button type="primary" :loading="busy" :disabled="!selected||!query.trim()" @click="search">检索</a-button></a-input-group><a-empty v-if="!results.length" description="暂无检索结果"/><a-card v-for="hit in results" :key="hit.chunk_id" :title="hit.document_title" size="small" style="margin-top:12px"><p>文档 {{hit.document_id}} · {{hit.page ? `第 ${hit.page} 页` : '无页码'}} · {{hit.section||'无章节'}} · 相似度 {{hit.score.toFixed(3)}}</p><p style="white-space:pre-wrap">{{hit.content}}</p></a-card></a-card>
</a-space></template>
