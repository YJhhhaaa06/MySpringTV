<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import AppIcon from './AppIcon.vue'

/**
 * 右下「回到顶部」浮钮（动态页 / 空间页动态 tab 共用）。
 *
 * <p>滚过 `threshold` 才出现；点它平滑回顶，`prefers-reduced-motion` 下直接跳。
 * 固定在视口右下、不参与内容流 —— 页面的滚动容器是 window（不是某个 `overflow` 容器）。
 *
 * <p>`scroll` 监听用 rAF 合帧（一帧最多算一次 rect），且**必须在卸载时移除**：
 * 浮钮是 `v-if` 挂载的（空间页只有动态 tab 有），切 tab 后监听器不能留在 window 上。
 */
const props = withDefaults(defineProps<{ threshold?: number }>(), { threshold: 800 })

const visible = ref(false)
let ticking = false

function onScroll(): void {
  if (ticking) return
  ticking = true
  requestAnimationFrame(() => {
    visible.value = window.scrollY > props.threshold
    ticking = false
  })
}

function backToTop(): void {
  const reduceMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
  window.scrollTo({ top: 0, behavior: reduceMotion ? 'auto' : 'smooth' })
}

onMounted(() => {
  window.addEventListener('scroll', onScroll, { passive: true })
  onScroll()
})

onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll)
  ticking = false
})
</script>

<template>
  <button
    v-if="visible"
    class="back-to-top"
    type="button"
    title="回到顶部"
    aria-label="回到顶部"
    @click="backToTop"
  >
    <span class="back-to-top__icon"><AppIcon name="up" :size="18" /></span>
    <span class="back-to-top__label">顶部</span>
  </button>
</template>

<style scoped>
.back-to-top {
  position: fixed;
  right: 12px;
  bottom: 20px;
  z-index: 30;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 0;
  border: 0;
  background: transparent;
  font-size: 12px;
  color: var(--text-3);
  cursor: pointer;
}

.back-to-top__icon {
  display: flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--line-1);
  border-radius: 50%;
  color: var(--text-2);
  background: var(--bg-0);
  box-shadow: var(--shadow-1);
  transition: color 0.15s ease, border-color 0.15s ease;
}

.back-to-top:hover .back-to-top__icon {
  border-color: var(--brand-blue);
  color: var(--brand-blue);
}

@media (prefers-reduced-motion: reduce) {
  .back-to-top__icon {
    transition: none;
  }
}

@media (max-width: 760px) {
  .back-to-top {
    right: 8px;
  }
}
</style>
