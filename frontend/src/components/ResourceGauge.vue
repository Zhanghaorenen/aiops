<script setup lang="ts">
import { computed, useId } from 'vue'
const props = withDefaults(
  defineProps<{
    label: string
    value?: number | null
    unit?: string
    min?: number
    max?: number
    accent?: 'cyan' | 'blue' | 'violet' | 'amber'
    note?: string
    warning?: boolean
  }>(),
  { unit: '%', min: 0, max: 100, accent: 'cyan' },
)
const id = useId()
const known = computed(
  () => typeof props.value === 'number' && Number.isFinite(props.value),
)
const text = computed(() => (known.value ? props.value!.toFixed(1) : '—'))
const span = 2 * Math.PI * 62 * 0.75
const progress = computed(() =>
  known.value
    ? Math.max(
        0,
        Math.min(1, (props.value! - props.min) / (props.max - props.min)),
      )
    : 0,
)
</script>
<template>
  <section
    class="resource-gauge"
    :class="[
      `accent-${accent}`,
      { 'gauge-warning': warning, 'gauge-empty': !known },
    ]"
  >
    <header>
      <span class="gauge-indicator" />{{ label
      }}<span class="gauge-caption">{{ known ? '当前值' : '暂无数据' }}</span>
    </header>
    <div
      class="gauge-dial"
      role="img"
      :aria-label="`${label}：${known ? text + unit : '暂无数据'}`"
    >
      <svg viewBox="0 0 180 156" aria-hidden="true">
        <defs>
          <linearGradient :id="id">
            <stop
              offset="0%"
              stop-color="var(--gauge-color)"
              stop-opacity=".35"
            />
            <stop offset="100%" stop-color="var(--gauge-color)" />
          </linearGradient>
        </defs>
        <circle
          cx="90"
          cy="80"
          r="71"
          class="gauge-ticks"
          :stroke-dasharray="`1.2 ${(2 * Math.PI * 71) / 40 - 1.2}`"
        />
        <circle
          cx="90"
          cy="80"
          r="62"
          class="gauge-track"
          :stroke-dasharray="`${span} ${span / 3}`"
          transform="rotate(135 90 80)"
        />
        <circle
          v-if="known"
          cx="90"
          cy="80"
          r="62"
          class="gauge-fill"
          :stroke="`url(#${id})`"
          :stroke-dasharray="`${span * progress} ${2 * Math.PI * 62}`"
          transform="rotate(135 90 80)"
        />
        <text x="90" y="84" text-anchor="middle" class="gauge-number">
          {{ text }}
        </text>
        <text x="90" y="106" text-anchor="middle" class="gauge-unit">
          {{ unit }}
        </text>
        <text x="39" y="144" text-anchor="middle" class="gauge-scale">
          {{ min }}
        </text>
        <text x="141" y="144" text-anchor="middle" class="gauge-scale">
          {{ max }}
        </text>
      </svg>
    </div>
    <footer>{{ note || '等待有效采样' }}</footer>
  </section>
</template>
