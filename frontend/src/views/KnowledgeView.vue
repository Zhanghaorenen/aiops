<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { message } from 'ant-design-vue'
import {
  api,
  aiApi,
  errorMessage,
  type KnowledgeBase,
  type KnowledgeDocument,
  type RagSearchResult,
} from '../api'
import KnowledgeUpload from '../components/KnowledgeUpload.vue'
import MarkdownPreview from '../components/MarkdownPreview.vue'
import { knowledgeTemplates } from '../utils/knowledgeTemplates'
import { useWorkspaceStore } from '../stores/workspace'
import { formatTime } from '../utils/format'
const workspace = useWorkspaceStore(),
  bases = ref<KnowledgeBase[]>([]),
  documents = ref<KnowledgeDocument[]>([]),
  selected = ref<number>(),
  busy = ref(false),
  error = ref(''),
  tab = ref('documents'),
  query = ref(''),
  results = ref<RagSearchResult[]>([]),
  answer = ref(''),
  editor = ref(false),
  filename = ref('运维文档.md'),
  content = ref(''),
  editingId = ref<number>(),
  original = ref(''),
  name = ref(''),
  description = ref('')
const draftKey = computed(
  () =>
    `aiops.kb-draft.${workspace.username}.${selected.value ?? 'none'}.${editingId.value ?? 'new'}`,
)
async function run(fn: () => Promise<void>) {
  busy.value = true
  error.value = ''
  try {
    await fn()
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    busy.value = false
  }
}
async function load() {
  if (workspace.demo) return
  bases.value = (await api.get('/api/knowledge-bases')).data
  if (!bases.value.some((b) => b.id === selected.value))
    selected.value = bases.value.find((b) => b.status === 'ACTIVE')?.id
  await loadDocs()
}
async function loadDocs() {
  documents.value = selected.value
    ? (await api.get(`/api/knowledge-bases/${selected.value}/documents`)).data
    : []
}
async function create() {
  await run(async () => {
    const { data } = await api.post('/api/knowledge-bases', {
      name: name.value,
      description: description.value,
    })
    selected.value = data.id
    name.value = ''
    await load()
  })
}
function saveDraft() {
  localStorage.setItem(
    draftKey.value,
    JSON.stringify({ filename: filename.value, content: content.value }),
  )
  message.success('草稿已保存在当前浏览器，不会自动上传')
}
function restoreDraft() {
  const saved = localStorage.getItem(draftKey.value)
  if (saved) {
    try {
      const draft = JSON.parse(saved)
      filename.value = draft.filename
      content.value = draft.content
    } catch {
      message.warning('草稿格式不可读')
    }
  }
}
function template(item: (typeof knowledgeTemplates)[number]) {
  editingId.value = undefined
  filename.value = item.title + '.md'
  content.value = item.content
  original.value = ''
  editor.value = true
  restoreDraft()
}
async function edit(document: KnowledgeDocument) {
  await run(async () => {
    const { data } = await aiApi.get(
      `/api/rag/knowledge-bases/${selected.value}/documents/${document.id}/download`,
      { responseType: 'text', transformResponse: [(data) => data] },
    )
    editingId.value = document.id
    filename.value = document.originalFilename
    content.value = data
    original.value = data
    editor.value = true
    restoreDraft()
  })
}
async function uploadEditor() {
  await run(async () => {
    if (editingId.value && content.value === original.value) {
      message.info('文档内容没有变化')
      return
    }
    if (!/^[^/\\]+\.(md|txt)$/i.test(filename.value))
      throw new Error('请输入合法的 Markdown / TXT 文件名')
    const file = new File([content.value], filename.value, {
      type: 'text/markdown',
    })
    if (file.size > 10 * 1024 * 1024) throw new Error('文档超过 10 MiB')
    const data = new FormData()
    data.append('file', file)
    const uploaded = await aiApi.post(
      `/api/rag/knowledge-bases/${selected.value}/documents`,
      data,
      { timeout: 600000 },
    )
    if (uploaded.data.status !== 'READY')
      throw new Error('新文档尚未就绪，旧文档保留')
    if (editingId.value)
      await aiApi.delete(
        `/api/rag/knowledge-bases/${selected.value}/documents/${editingId.value}`,
      )
    localStorage.removeItem(draftKey.value)
    editor.value = false
    message.success('文档已解析并进入知识库')
    await loadDocs()
  })
}
async function reindex(id: number) {
  await run(async () => {
    await aiApi.post(
      `/api/rag/knowledge-bases/${selected.value}/documents/${id}/reindex`,
      {},
      { timeout: 600000 },
    )
    await loadDocs()
    message.success('重新索引完成')
  })
}
async function remove(id: number) {
  await run(async () => {
    await aiApi.delete(
      `/api/rag/knowledge-bases/${selected.value}/documents/${id}`,
    )
    await loadDocs()
  })
}
async function removeBase() {
  if (!selected.value) return
  await run(async () => {
    await aiApi.delete(`/api/rag/knowledge-bases/${selected.value}`, {
      timeout: 600000,
    })
    selected.value = undefined
    await load()
  })
}
async function search(withAnswer: boolean) {
  await run(async () => {
    const { data } = await aiApi.post(
      withAnswer ? '/api/rag/answer' : '/api/rag/search',
      { query: query.value, knowledge_base_ids: [selected.value], top_k: 5 },
      { timeout: 120000 },
    )
    results.value = data.results
    answer.value = data.answer ?? ''
  })
}
function parseStatus(doc: KnowledgeDocument) {
  return doc.status === 'READY'
    ? '完成'
    : doc.status === 'PROCESSING'
      ? '处理中（未细分）'
      : doc.status === 'PENDING'
        ? '等待处理'
        : doc.status === 'FAILED'
          ? '处理未完成'
          : '正在删除'
}
watch(selected, () => {
  results.value = []
  answer.value = ''
  void run(loadDocs)
})
onMounted(() => void run(load))
const timer = setInterval(() => {
  if (!busy.value && selected.value) void loadDocs().catch(() => {})
}, 5000)
onUnmounted(() => clearInterval(timer))
const columns = [
  { title: '文档', dataIndex: 'originalFilename' },
  { title: '状态', dataIndex: 'status' },
  { title: '解析', key: 'parse' },
  { title: '分块', dataIndex: 'chunkCount' },
  { title: 'Embedding', key: 'embedding' },
  { title: '失败原因', dataIndex: 'errorCode' },
  { title: '更新时间', dataIndex: 'updatedAt' },
  { title: '操作', key: 'action' },
]
</script>
<template>
  <div class="info-hero">
    <div>
      <h2>把运维经验变成可检索的依据</h2>
      <p>
        上传服务器环境、操作手册或故障案例，让 AI
        在回答时引用你的资料。先创建知识库，再用模板写一份文档，最后测试检索。私人文档只对当前账号可见。
      </p>
    </div>
    <a-button type="primary" @click="tab = 'templates'">从模板开始</a-button>
  </div>
  <a-alert v-if="error" type="error" :message="error" show-icon /><a-card
    ><a-space wrap
      ><a-select
        v-model:value="selected"
        :options="
          bases.map((b) => ({ value: b.id, label: b.name + ' · ' + b.status }))
        "
        placeholder="选择私人知识库"
        style="min-width: 280px"
      /><a-input
        v-model:value="name"
        placeholder="新知识库名称"
        :maxlength="160"
      /><a-input
        v-model:value="description"
        placeholder="简介（可选）"
        :maxlength="1000"
      /><a-button :loading="busy" :disabled="!name.trim()" @click="create"
        >创建知识库</a-button
      ><a-popconfirm
        v-if="selected"
        title="删除知识库及其原文件和索引？"
        @confirm="removeBase"
        ><a-button danger :disabled="busy">删除知识库</a-button></a-popconfirm
      ></a-space
    ></a-card
  ><a-tabs v-model:active-key="tab" style="margin-top: 18px"
    ><a-tab-pane key="documents" tab="文档管理"
      ><a-card title="上传与索引"
        ><KnowledgeUpload
          v-if="selected"
          :kb-id="selected"
          @uploaded="loadDocs"
        /><a-empty v-else description="请先创建或选择一个知识库" />
        <p class="muted" style="margin-top: 12px">
          READY 表示解析、分块与 Embedding
          已完成；处理中尚无细分进度，不将阶段状态伪装成百分比。
        </p>
        <a-table
          style="margin-top: 18px"
          :columns="columns"
          :data-source="documents"
          row-key="id"
          :scroll="{ x: 1200 }"
          ><template #bodyCell="{ column, record }"
            ><a-tag
              v-if="column.dataIndex === 'status'"
              :color="
                record.status === 'READY'
                  ? 'green'
                  : record.status === 'FAILED'
                    ? 'red'
                    : 'cyan'
              "
              >{{ record.status }}</a-tag
            ><template
              v-else-if="['parse', 'embedding'].includes(column.key)"
              >{{ parseStatus(record) }}</template
            ><template v-else-if="column.dataIndex === 'updatedAt'">{{
              formatTime(record.updatedAt)
            }}</template
            ><a-space v-else-if="column.key === 'action'"
              ><a-button
                v-if="
                  /\.(md|txt)$/i.test(record.originalFilename) &&
                  !['DELETING', 'PROCESSING'].includes(record.status)
                "
                size="small"
                :disabled="busy"
                @click="edit(record)"
                >编辑</a-button
              ><a-button
                v-if="['READY', 'FAILED'].includes(record.status)"
                size="small"
                :disabled="busy"
                @click="reindex(record.id)"
                >重新索引</a-button
              ><a-popconfirm
                title="删除文档和向量索引？"
                @confirm="remove(record.id)"
                ><a-button size="small" danger :disabled="busy"
                  >删除</a-button
                ></a-popconfirm
              ></a-space
            ></template
          ></a-table
        ></a-card
      ></a-tab-pane
    ><a-tab-pane key="templates" tab="模板中心"
      ><p class="muted">
        模板中的占位内容请按实际环境填写。不要上传密码、API Key
        或未经脱敏的命令参数。
      </p>
      <div class="template-grid">
        <button
          v-for="item in knowledgeTemplates"
          :key="item.key"
          class="preset-tile"
          @click="template(item)"
        >
          <strong>{{ item.title }}</strong>
          <p>{{ item.description }}</p>
          <span class="status-pill">打开 Markdown 编辑器 →</span>
        </button>
      </div></a-tab-pane
    ><a-tab-pane key="search" tab="检索与问答测试"
      ><a-card title="验证文档是否真正进入知识库"
        ><a-textarea
          v-model:value="query"
          :rows="3"
          :maxlength="2000"
          placeholder="例如：为什么 empty_cache 不能解决所有 CUDA OOM？"
        /><a-space style="margin: 16px 0"
          ><a-button
            :loading="busy"
            :disabled="!selected || !query.trim()"
            @click="search(false)"
            >仅检索片段</a-button
          ><a-button
            type="primary"
            :loading="busy"
            :disabled="!selected || !query.trim()"
            @click="search(true)"
            >检索并生成 AI 回答</a-button
          ></a-space
        ><a-alert
          v-if="answer"
          type="info"
          message="AI 回答 · 请结合原始引用核对"
        />
        <p v-if="answer" style="white-space: pre-wrap; margin: 16px 0">
          {{ answer }}
        </p>
        <a-empty v-if="!results.length" description="暂无检索结果" /><a-card
          v-for="hit in results"
          :key="hit.chunk_id"
          :title="hit.document_title"
          size="small"
          style="margin-top: 12px"
          ><p class="muted">
            {{ hit.page ? '第 ' + hit.page + ' 页' : '无页码' }} ·
            {{ hit.section ?? '无章节' }} · {{ hit.chunk_id }} · 相似度
            {{ hit.score.toFixed(3) }}
          </p>
          <p style="white-space: pre-wrap; margin-top: 10px">
            {{ hit.content }}
          </p></a-card
        ></a-card
      ></a-tab-pane
    ></a-tabs
  ><a-modal
    :open="editor"
    title="Markdown 文档编辑"
    width="94vw"
    :footer="null"
    @cancel="editor = false"
    ><a-space wrap style="margin-bottom: 16px"
      ><a-input
        v-model:value="filename"
        style="width: 300px"
        placeholder="文件名.md"
      /><a-button @click="saveDraft">保存本机草稿</a-button
      ><a-button @click="restoreDraft">恢复草稿</a-button
      ><a-button
        type="primary"
        :loading="busy"
        :disabled="!selected || !content.trim()"
        @click="uploadEditor"
        >{{ editingId ? '保存新版本并重新入库' : '上传到当前知识库' }}</a-button
      ><span v-if="!selected" class="muted"
        >先关闭编辑器，创建或选择知识库；内容仍保留。</span
      ></a-space
    >
    <div class="editor-grid">
      <a-textarea
        v-model:value="content"
        class="markdown-source"
        :rows="24"
        placeholder="在这里编写 Markdown…"
      /><MarkdownPreview :text="content" />
    </div>
    <p class="muted" style="margin-top: 12px">
      保存草稿仅写当前浏览器。修改已有文档时，新版本 READY 后才清理旧版本。
    </p></a-modal
  >
</template>
