<script setup lang="ts">
import type { KnowledgeDocument } from '../api'
defineProps<{ documents:KnowledgeDocument[],busy:boolean }>()
defineEmits<{ remove:[id:number] }>()
const columns=[{title:'文件名',dataIndex:'originalFilename'},{title:'索引状态',dataIndex:'status'},{title:'分块数',dataIndex:'chunkCount'},{title:'上传时间（UTC）',dataIndex:'createdAt'},{title:'失败类型',dataIndex:'errorCode'},{title:'操作',key:'action'}]
</script>
<template><a-table :columns="columns" :data-source="documents" row-key="id" :scroll="{x:800}"><template #bodyCell="{column,record}"><template v-if="column.key==='action'"><a-popconfirm title="删除文档及其索引？" @confirm="$emit('remove',record.id)"><a-button danger size="small" :disabled="busy">{{record.status==='DELETING'?'重试删除':'删除'}}</a-button></a-popconfirm></template></template></a-table></template>
