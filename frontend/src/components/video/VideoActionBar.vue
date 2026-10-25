<script setup lang="ts">
import { computed } from 'vue'
import type { Count, InteractionVM } from '../../types/ui'
import { formatCount } from '../../api/adapters/format'
import AppIcon from '../common/AppIcon.vue'

/**
 * 播放页操作栏（B 站版式：标题/计数下面一排「点赞 · 投币 · 收藏 · 分享」）。
 *
 * <p>**纯展示**：所有动作以事件抛给页面（状态机在 `composables/useVideoInteractions.ts`）。
 * 这样"按钮亮不亮"只由 `interaction` 决定，不会出现两处各自记一份状态。
 *
 * <p>★ 互动态为 `null`（匿名）时按钮是**可点的灰态**：点下去弹登录，而不是禁用 ——
 * B 站也是这么做的（禁用会让人以为功能坏了）。
 */
const props = defineProps<{
  stats: { like: Count; coin: Count; favorite: Count }
  interaction: InteractionVM | null
  /** 写操作进行中的那一项（避免连点） */
  busy?: { like: boolean; coin: boolean; favorite: boolean }
}>()

const emit = defineEmits<{
  (event: 'like'): void
  (event: 'coin'): void
  (event: 'favorite'): void
  (event: 'share'): void
}>()

const liked = computed(() => props.interaction?.liked === true)
const favorited = computed(() => props.interaction?.favorited === true)
/** 已投币数 > 0 ⇒ 图标点亮（B 站口径：投过就是亮的） */
const coined = computed(() => (props.interaction?.coin ?? 0) > 0)
</script>

<template>
  <div class="action-bar">
    <button
      class="action"
      :class="{ 'is-active': liked }"
      type="button"
      :disabled="busy?.like"
      :title="liked ? '取消点赞' : '点赞'"
      @click="emit('like')"
    >
      <AppIcon name="thumb" :size="26" />
      <span class="action__count">{{ formatCount(stats.like) }}</span>
    </button>

    <button
      class="action"
      :class="{ 'is-active': coined }"
      type="button"
      :disabled="busy?.coin"
      title="投币"
      @click="emit('coin')"
    >
      <AppIcon name="coin" :size="26" />
      <span class="action__count">{{ formatCount(stats.coin) }}</span>
    </button>

    <button
      class="action"
      :class="{ 'is-active': favorited }"
      type="button"
      :disabled="busy?.favorite"
      :title="favorited ? '取消收藏' : '收藏'"
      @click="emit('favorite')"
    >
      <AppIcon name="star" :size="26" />
      <span class="action__count">{{ formatCount(stats.favorite) }}</span>
    </button>

    <!-- 分享：本仓没有"转发数"接口 ⇒ **不显示数字**（不编 0） -->
    <button class="action" type="button" title="复制链接" @click="emit('share')">
      <AppIcon name="upload" :size="24" />
      <span class="action__label">分享</span>
    </button>
  </div>
</template>

<style scoped>
.action-bar {
  display: flex;
  align-items: center;
  gap: 34px;
  padding: 6px 0;
  border-bottom: 1px solid var(--line-2);
}

.action {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 40px;
  padding: 0 2px;
  border-radius: var(--radius);
  font-size: 13px;
  font-weight: 500;
  color: var(--text-2);
  transition: color 0.15s ease, transform 0.1s ease;
}

.action:hover {
  color: var(--brand-pink);
}

.action:active {
  transform: scale(0.94);
}

.action:disabled {
  opacity: 0.55;
}

.action.is-active {
  color: var(--brand-pink);
}

.action__count {
  font-variant-numeric: tabular-nums;
}

.action__label {
  font-size: 13px;
}
</style>
