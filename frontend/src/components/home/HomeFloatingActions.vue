<script setup lang="ts">
import AppIcon from '../common/AppIcon.vue'

/**
 * 首页右下浮层（`R-29` ②）：**只在内联「换一换」不可见时出现**，两者不同时存在。
 *
 * <p>两个动作刻意不同：`刷新内容` 由页面先回顶再拉（此时用户已经滚得很深，原地换一批
 * 会让页面瞬间变短、被浏览器夹到底部）；`顶部` 只回顶，不刷新。
 *
 * <p>不做 B 站浮层里的「…」：本仓没有对应菜单内容，摆一个点不动的按钮比不摆更糟。
 */
withDefaults(defineProps<{ refreshing?: boolean }>(), { refreshing: false })

defineEmits<{ (event: 'refresh'): void; (event: 'top'): void }>()
</script>

<template>
  <div class="home-float" role="group" aria-label="页面操作">
    <button
      class="home-float__refresh"
      type="button"
      :disabled="refreshing"
      title="刷新内容"
      aria-label="刷新内容"
      @click="$emit('refresh')"
    >
      <AppIcon
        name="refresh"
        :size="20"
        :class="{ 'home-float__icon--spin': refreshing }"
      />
      <span class="home-float__label">刷新内容</span>
    </button>

    <button class="home-float__top" type="button" title="回到顶部" @click="$emit('top')">
      <span class="home-float__top-icon"><AppIcon name="up" :size="18" /></span>
      <span class="home-float__top-label">顶部</span>
    </button>
  </div>
</template>

<style scoped>
.home-float {
  position: fixed;
  top: 50%;
  right: 16px;
  z-index: 30;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 10px;
  transform: translateY(-50%);
}

/* 折叠 = 44 圆钮；悬停 = 蓝底 pill + 文案（B 站实测形态） */
.home-float__refresh {
  display: flex;
  width: 44px;
  height: 44px;
  padding: 0;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border: 1px solid var(--line-1);
  border-radius: 22px;
  color: var(--text-2);
  background: var(--bg-0);
  box-shadow: var(--shadow-1);
  white-space: nowrap;
  transition:
    width 0.18s ease,
    padding 0.18s ease,
    color 0.18s ease,
    background-color 0.18s ease,
    border-color 0.18s ease;
}

.home-float__refresh:hover:not(:disabled),
.home-float__refresh:focus-visible {
  width: 126px;
  padding: 0 14px;
  justify-content: flex-start;
  border-color: var(--brand-blue);
  color: var(--text-inverse);
  background: var(--brand-blue);
}

.home-float__refresh:disabled {
  color: var(--text-4);
}

.home-float__label {
  max-width: 0;
  margin-left: 0;
  overflow: hidden;
  font-size: 13px;
  opacity: 0;
  transition: max-width 0.18s ease, margin-left 0.18s ease, opacity 0.18s ease;
}

.home-float__refresh:hover:not(:disabled) .home-float__label,
.home-float__refresh:focus-visible .home-float__label {
  max-width: 80px;
  margin-left: 8px;
  opacity: 1;
}

.home-float__icon--spin {
  animation: home-float-spin 0.9s linear infinite;
}

@keyframes home-float-spin {
  to {
    transform: rotate(360deg);
  }
}

.home-float__top {
  display: flex;
  padding: 0;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  border: 0;
  color: var(--text-3);
  background: transparent;
  font-size: 12px;
}

.home-float__top-icon {
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

.home-float__top:hover .home-float__top-icon {
  border-color: var(--brand-blue);
  color: var(--brand-blue);
}

.home-float__top-label {
  line-height: 16px;
}

@media (prefers-reduced-motion: reduce) {
  .home-float__refresh,
  .home-float__label,
  .home-float__top-icon {
    transition: none;
  }

  .home-float__icon--spin {
    animation: none;
  }
}

/* 窄屏留出更贴边的手势区；不遮内容列（浮层本来就会压住最后一列边缘，与 B 站一致） */
@media (max-width: 760px) {
  .home-float {
    right: 8px;
  }
}
</style>
