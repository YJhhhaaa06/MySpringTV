<script setup lang="ts">
import { computed } from 'vue'
import type { Count, InteractionVM } from '../../types/ui'
import { formatCount } from '../../api/adapters/format'
import AppIcon from '../common/AppIcon.vue'

/**
 * 图文详情的悬浮操作栏（B 站 `.side-toolbar`：竖向 58px，按钮 46px）。
 *
 * <h2>为什么没有「投币」</h2>
 * B 站图文可以投币，但本仓 `POST /contents/{id}/coins` 对 `type=2` 直接
 * `400 该内容不支持投币`（coin 域只服务视频）⇒ **不摆一个必然失败的按钮**（GAP 台账）。
 *
 * <h2>为什么没有「转发」/「稍后再看」</h2>
 * 没有转发域（无表 / 无端点）；稍后再看后端只收视频。评论不是独立动作：它是
 * `#comments` 锚点，滚到正文下面的评论区。
 *
 * <p>纯展示 + 事件上抛：点赞 / 收藏的乐观更新与登录闸门在 `useVideoInteractions`。
 */
const props = withDefaults(
  defineProps<{
    stats: { like: Count; favorite: Count; comment: Count }
    interaction: InteractionVM | null
    busy?: { like: boolean; favorite: boolean }
  }>(),
  {
    busy: () => ({ like: false, favorite: false }),
  },
)

const emit = defineEmits<{
  (event: 'like'): void
  (event: 'favorite'): void
  (event: 'share'): void
}>()

const liked = computed(() => props.interaction?.liked === true)
const favorited = computed(() => props.interaction?.favorited === true)
</script>

<template>
  <aside class="opus-rail" aria-label="互动">
    <button
      class="opus-rail__action"
      :class="{ 'is-active': liked }"
      type="button"
      :disabled="busy.like"
      :title="liked ? '取消点赞' : '点赞'"
      :aria-pressed="liked"
      @click="emit('like')"
    >
      <AppIcon name="thumb" :size="22" />
      <span class="opus-rail__count">{{ formatCount(stats.like) }}</span>
    </button>

    <button
      class="opus-rail__action"
      :class="{ 'is-active': favorited }"
      type="button"
      :disabled="busy.favorite"
      :title="favorited ? '取消收藏' : '收藏'"
      :aria-pressed="favorited"
      @click="emit('favorite')"
    >
      <AppIcon name="star" :size="22" />
      <span class="opus-rail__count">{{ formatCount(stats.favorite) }}</span>
    </button>

    <!-- 分享：复制链接；没有转发域 ⇒ 不显示转发数（不编 0） -->
    <button class="opus-rail__action" type="button" title="复制链接" @click="emit('share')">
      <AppIcon name="upload" :size="22" />
      <span class="opus-rail__label">分享</span>
    </button>

    <a class="opus-rail__action" href="#comments" title="查看评论">
      <AppIcon name="comment" :size="22" />
      <span class="opus-rail__count">{{ formatCount(stats.comment) }}</span>
    </a>
  </aside>
</template>

<style scoped>
/*
 * B 站线上 `.side-toolbar` 实测（2026-10-27，浏览器 computed）：
 * 外层 58px、按钮 46px；白底卡片 + 轻阴影，贴着文章列右侧。
 */
.opus-rail {
  position: sticky;
  top: calc(var(--header-h) + 16px);
  display: flex;
  width: 58px;
  flex: none;
  flex-direction: column;
  align-self: flex-start;
  padding: 6px;
  border-radius: var(--radius-lg);
  background: var(--bg-0);
  box-shadow: var(--shadow-1);
}

.opus-rail__action {
  display: flex;
  width: 46px;
  height: 54px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  border-radius: var(--radius);
  font-size: 12px;
  line-height: 16px;
  color: var(--text-2);
  transition: color 0.15s ease, background-color 0.15s ease;
}

.opus-rail__action:hover {
  color: var(--brand-pink);
  background: var(--bg-1);
}

.opus-rail__action:disabled {
  cursor: wait;
  opacity: 0.55;
}

.opus-rail__action.is-active {
  color: var(--brand-pink);
}

.opus-rail__count,
.opus-rail__label {
  font-variant-numeric: tabular-nums;
}

/* 窄屏：文章列与竖栏放不下 ⇒ 变成文章下面的一条横向操作栏（不再是悬浮） */
@media (max-width: 900px) {
  .opus-rail {
    position: static;
    width: auto;
    flex-direction: row;
    flex-wrap: wrap;
    align-self: stretch;
    justify-content: center;
    gap: 8px;
    padding: 6px 10px;
  }

  .opus-rail__action {
    width: auto;
    height: 40px;
    flex-direction: row;
    padding: 0 14px;
    gap: 6px;
  }
}
</style>
