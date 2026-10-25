<script setup lang="ts">
import { computed } from 'vue'
import AppIcon from './AppIcon.vue'

const props = defineProps<{ page: number; totalPages: number }>()
const emit = defineEmits<{ (event: 'update:page', page: number): void }>()

/** 页码窗口：首末页 + 当前页 ±1，中间用省略号。B 站搜索页就是这种"两端固定"的分页。 */
const items = computed<Array<number | 'gap'>>(() => {
  const total = Math.max(1, props.totalPages)
  if (total <= 7) return Array.from({ length: total }, (_, index) => index + 1)
  const wanted = new Set<number>([1, total, props.page - 1, props.page, props.page + 1])
  const sorted = [...wanted].filter((page) => page >= 1 && page <= total).sort((a, b) => a - b)
  const result: Array<number | 'gap'> = []
  let previous = 0
  for (const page of sorted) {
    if (previous > 0 && page - previous > 1) result.push('gap')
    result.push(page)
    previous = page
  }
  return result
})

function go(page: number): void {
  if (page < 1 || page > props.totalPages || page === props.page) return
  emit('update:page', page)
}
</script>

<template>
  <nav v-if="totalPages > 1" class="pagination" aria-label="分页">
    <button class="pagination__item" :disabled="page <= 1" @click="go(page - 1)">
      <AppIcon name="chevron-left" :size="16" />
      <span>上一页</span>
    </button>
    <template v-for="(item, index) in items" :key="`${item}-${index}`">
      <span v-if="item === 'gap'" class="pagination__gap">…</span>
      <button
        v-else
        class="pagination__item"
        :class="{ 'is-current': item === page }"
        :aria-current="item === page ? 'page' : undefined"
        @click="go(item)"
      >
        {{ item }}
      </button>
    </template>
    <button class="pagination__item" :disabled="page >= totalPages" @click="go(page + 1)">
      <span>下一页</span>
      <AppIcon name="chevron-right" :size="16" />
    </button>
  </nav>
</template>

<style scoped>
.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-top: 32px;
  flex-wrap: wrap;
}

.pagination__item {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  min-width: 34px;
  height: 34px;
  padding: 0 10px;
  border-radius: var(--radius);
  font-size: 14px;
  color: var(--text-2);
  background: var(--bg-1);
  transition: background-color 0.15s ease, color 0.15s ease;
}

.pagination__item:hover:not(:disabled) {
  background: var(--brand-blue-bg);
  color: var(--brand-blue);
}

.pagination__item:disabled {
  color: var(--text-4);
  background: transparent;
}

.pagination__item.is-current {
  color: var(--text-inverse);
  background: var(--brand-blue);
}

.pagination__gap {
  color: var(--text-3);
}
</style>
