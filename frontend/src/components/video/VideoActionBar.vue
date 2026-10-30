<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import type { Count, InteractionVM } from '../../types/ui'
import { formatCount } from '../../api/adapters/format'
import AppIcon from '../common/AppIcon.vue'

/**
 * 播放页操作栏（B 站版式：标题/计数下面一排「点赞 · 投币 · 收藏 · 分享」；
 * 右端是「举报稿件 / 笔记 / ⋮」——本仓只保留 ⋮ 里的「稍后再看」）。
 *
 * <p>**纯展示**：所有动作以事件抛给页面（状态机在 `composables/useVideoInteractions.ts` 与
 * `useWatchLaterAction.ts`）。这样"按钮亮不亮"只由页面状态决定，不会出现两处各自记一份状态。
 *
 * <p>★ 互动态为 `null`（匿名）时按钮是**可点的灰态**：点下去弹登录，而不是禁用 ——
 * B 站也是这么做的（禁用会让人以为功能坏了）。
 *
 * <h2>★ 为什么「稍后再看」在 ⋮ 菜单里，而不是第 5 个平铺按钮</h2>
 * B 站 2026-10 实测：播放页的「稍后再看」在操作栏右端 **⋮ 更多** 菜单里
 * （同一菜单还有「用手机看」）。本仓没有设备联动/二维码域，**不摆死项**；
 * 举报 / 笔记也没有域，所以菜单里只有「稍后再看」一项。这是**有意的版式近似**，不是 GAP。
 */
const props = withDefaults(
  defineProps<{
    stats: { like: Count; coin: Count; favorite: Count }
    interaction: InteractionVM | null
    /** 写操作进行中的那一项（避免连点） */
    busy?: { like: boolean; coin: boolean; favorite: boolean }
    /** 「稍后再看」：本次页面内是否刚加入过（没有状态查询端点，见 watchLaterSession） */
    watchLaterAdded?: boolean
    watchLaterBusy?: boolean
    /** 图文内容不能加稍后再看（后端只收视频）⇒ 不渲染 ⋮ */
    watchLaterAvailable?: boolean
  }>(),
  {
    busy: () => ({ like: false, coin: false, favorite: false }),
    watchLaterAdded: false,
    watchLaterBusy: false,
    watchLaterAvailable: true,
  },
)

const emit = defineEmits<{
  (event: 'like'): void
  (event: 'coin'): void
  (event: 'favorite'): void
  (event: 'share'): void
  (event: 'watch-later'): void
}>()

const liked = computed(() => props.interaction?.liked === true)
const favorited = computed(() => props.interaction?.favorited === true)
/** 已投币数 > 0 ⇒ 图标点亮（B 站口径：投过就是亮的） */
const coined = computed(() => (props.interaction?.coin ?? 0) > 0)

/** ⋮ 菜单：点外部 / Esc 关闭（菜单是浮层，不能让页面滚动时一直挂着） */
const menuOpen = ref(false)
const menuRoot = ref<HTMLElement | null>(null)

function onDocumentPointerDown(event: PointerEvent): void {
  if (!menuOpen.value) return
  const target = event.target as Node | null
  if (target && menuRoot.value?.contains(target)) return
  menuOpen.value = false
}

function onDocumentKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') menuOpen.value = false
}

onMounted(() => {
  document.addEventListener('pointerdown', onDocumentPointerDown)
  document.addEventListener('keydown', onDocumentKeydown)
})

onUnmounted(() => {
  document.removeEventListener('pointerdown', onDocumentPointerDown)
  document.removeEventListener('keydown', onDocumentKeydown)
})

function chooseWatchLater(): void {
  menuOpen.value = false
  emit('watch-later')
}
</script>

<template>
  <div class="action-bar">
    <div class="action-bar__main">
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

    <div v-if="watchLaterAvailable" ref="menuRoot" class="action-bar__side">
      <button
        class="action-more"
        type="button"
        title="更多"
        aria-haspopup="menu"
        :aria-expanded="menuOpen"
        aria-label="更多"
        @click="menuOpen = !menuOpen"
      >
        <AppIcon name="more" :size="20" />
      </button>

      <div v-if="menuOpen" class="action-menu" role="menu" aria-label="更多操作">
        <button
          class="action-menu__item"
          :class="{ 'is-active': watchLaterAdded }"
          type="button"
          role="menuitem"
          :disabled="watchLaterBusy"
          @click="chooseWatchLater"
        >
          <AppIcon name="watch-later" :size="18" />
          <span>{{ watchLaterAdded ? '取消稍后再看' : '稍后再看' }}</span>
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.action-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 6px 0;
  border-bottom: 1px solid var(--line-2);
}

.action-bar__main {
  display: flex;
  align-items: center;
  gap: 34px;
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

/* 右端 ⋮（B 站位置；本仓没有举报 / 笔记，故只剩它一个） */
.action-bar__side {
  position: relative;
  display: flex;
  align-items: center;
}

.action-more {
  display: flex;
  width: 34px;
  height: 40px;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius);
  color: var(--text-2);
}

.action-more:hover,
.action-more[aria-expanded='true'] {
  color: var(--text-1);
  background: var(--bg-1);
}

.action-menu {
  position: absolute;
  right: 0;
  bottom: calc(100% + 8px);
  z-index: 20;
  min-width: 148px;
  padding: 6px;
  border: 1px solid var(--line-1);
  border-radius: var(--radius);
  background: var(--bg-0);
  box-shadow: var(--shadow-2);
}

.action-menu__item {
  display: flex;
  width: 100%;
  height: 34px;
  align-items: center;
  gap: 8px;
  padding: 0 10px;
  border-radius: var(--radius-sm);
  font-size: 13px;
  color: var(--text-1);
  text-align: left;
  white-space: nowrap;
}

.action-menu__item:hover:not(:disabled) {
  background: var(--bg-1);
}

.action-menu__item.is-active {
  color: var(--brand-pink);
}

.action-menu__item:disabled {
  opacity: 0.55;
}
</style>
