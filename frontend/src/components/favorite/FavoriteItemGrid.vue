<script setup lang="ts">
import type { VideoCardVM } from '../../types/ui'
import VideoCard from '../common/VideoCard.vue'
import VideoGrid from '../home/VideoGrid.vue'

/**
 * 收藏夹 / 稍后再看的条目网格。
 *
 * <p>「取消收藏 / 移出」是**页面级**操作，只在 self 面渲染（`canRemove`）；
 * 看别人的公开夹时不出现任何写入口。移出本身由父级做乐观更新 + 失败回滚。
 * 卡片不再叠 hover「稍后再看」—— 本页已经有移出按钮。
 */
defineProps<{
  items: VideoCardVM[]
  removeLabel: string
  canRemove: boolean
  removingId: number | null
}>()

const emit = defineEmits<{ (event: 'remove', item: VideoCardVM): void }>()
</script>

<template>
  <VideoGrid :items="items">
    <template #item="{ item }">
      <div class="favorite-cell">
        <VideoCard :item="item" :watch-later="false" />
        <button
          v-if="canRemove"
          class="favorite-cell__remove"
          type="button"
          :disabled="removingId === item.id"
          @click="emit('remove', item)"
        >
          {{ removingId === item.id ? '处理中…' : removeLabel }}
        </button>
      </div>
    </template>
  </VideoGrid>
</template>

<style scoped>
.favorite-cell {
  position: relative;
}

/* 卡片右上角的移除按钮：hover / 键盘聚焦才出现（B 站也是悬浮操作） */
.favorite-cell__remove {
  position: absolute;
  top: 6px;
  right: 6px;
  z-index: 1;
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  font-size: 12px;
  color: var(--text-inverse);
  background: rgba(0, 0, 0, 0.62);
  opacity: 0;
  transition: opacity 0.15s ease;
}

.favorite-cell:hover .favorite-cell__remove,
.favorite-cell__remove:focus-visible {
  opacity: 1;
}

.favorite-cell__remove:hover {
  background: var(--brand-pink);
}

@media (prefers-reduced-motion: reduce) {
  .favorite-cell__remove {
    transition: none;
  }
}
</style>
