<script setup lang="ts">
defineProps<{ label: string; value: string | number; suffix: string; note: string }>()
</script>

<template>
  <div class="summary-item"><div><span class="muted">{{ label }}</span><span class="summary-icon"><slot /></span></div><strong>{{ value }}<small>{{ suffix }}</small></strong><span class="muted compact">{{ note }}</span></div>
</template>
