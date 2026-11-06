<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{ text: string }>()
// 按有限 Markdown 结构渲染 Vue 文本节点，不注入原始 HTML。
const blocks = computed(() => {
  const result: { kind: string; text: string; level?: number }[] = []
  let code: string[] | null = null
  for (const line of props.text.split('\n')) {
    if (line.startsWith('```')) {
      if (code) {
        result.push({ kind: 'code', text: code.join('\n') })
        code = null
      } else code = []
      continue
    }
    if (code) {
      code.push(line)
      continue
    }
    const heading = /^(#{1,4})\s+(.+)$/.exec(line)
    if (heading)
      result.push({
        kind: 'heading',
        level: heading[1]!.length,
        text: heading[2]!,
      })
    else if (/^\s*[-*]\s/.test(line))
      result.push({ kind: 'bullet', text: line.replace(/^\s*[-*]\s/, '') })
    else if (line.trim()) result.push({ kind: 'paragraph', text: line })
  }
  if (code) result.push({ kind: 'code', text: code.join('\n') })
  return result
})
</script>
<template>
  <article class="markdown-preview">
    <template v-for="(block, index) in blocks" :key="index"
      ><component :is="'h' + block.level" v-if="block.kind === 'heading'">{{
        block.text
      }}</component>
      <pre v-else-if="block.kind === 'code'">{{ block.text }}</pre>
      <p v-else-if="block.kind === 'bullet'">• {{ block.text }}</p>
      <p v-else>{{ block.text }}</p></template
    ><a-empty v-if="!blocks.length" description="开始填写文档后可在这里预览" />
  </article>
</template>
