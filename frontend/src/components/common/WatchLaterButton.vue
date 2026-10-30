<script setup lang="ts">
import { computed } from 'vue'
import { useWatchLaterAction } from '../../composables/useWatchLaterAction'
import AppIcon from './AppIcon.vue'

/**
 * 卡片封面右上角的「稍后再看」圆钮（B 站 2026-10 版式：hover 出现 + 黑底 tooltip）。
 *
 * <p>**不是**收藏域的状态开关：后端没有"是否已在稍后再看"的查询端点（favorite.md §六 红线），
 * 所以按钮初始只会说「稍后再看」；只有在本页面点过加入之后，才知道它在池里、才能再点取消。
 * 刷新 / 换页即忘 —— 与 B 站实测行为一致（见 `common/watchLaterSession.ts`）。
 *
 * <p>放在封面 `<a>` 的**兄弟节点**（不能塞进链接里：`<a>` 里放 `<button>` 是非法嵌套，
 * 点击也会冒泡成"打开视频"）。
 */
const props = defineProps<{ contentId: number }>()

const { added, busy, toggle } = useWatchLaterAction(computed(() => props.contentId))
const label = computed(() => (added.value ? '取消稍后再看' : '稍后再看'))
</script>

<template>
  <button
    class="watch-later-btn"
    :class="{ 'is-active': added, 'is-busy': busy }"
    type="button"
    :aria-label="label"
    :aria-pressed="added"
    :disabled="busy"
    @click.stop.prevent="toggle"
  >
    <AppIcon name="watch-later" :size="21" />
    <span class="watch-later-btn__tip" aria-hidden="true">{{ label }}</span>
  </button>
</template>

<style scoped>
.watch-later-btn {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 2;
  display: flex;
  width: 40px;
  height: 40px;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius);
  color: var(--text-inverse);
  background: rgba(0, 0, 0, 0.72);
  opacity: 0;
  transform: translateY(-2px);
  transition: opacity 0.15s ease, transform 0.15s ease, background-color 0.15s ease;
}

/* hover / 键盘聚焦卡片时才出现（B 站同）；触屏没有 hover ⇒ 常显 */
.video-card:hover .watch-later-btn,
.video-card:focus-within .watch-later-btn,
.watch-later-btn:focus-visible {
  opacity: 1;
  transform: none;
}

@media (hover: none) {
  .watch-later-btn {
    opacity: 1;
    transform: none;
  }
}

.watch-later-btn:hover {
  background: rgba(0, 0, 0, 0.86);
}

.watch-later-btn.is-active {
  background: var(--brand-pink);
}

.watch-later-btn.is-active:hover {
  background: var(--brand-pink-dark);
}

.watch-later-btn.is-busy {
  cursor: wait;
  opacity: 0.6;
}

.watch-later-btn__tip {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  padding: 5px 10px;
  border-radius: var(--radius-sm);
  font-size: 12px;
  line-height: 16px;
  color: var(--text-inverse);
  background: rgba(0, 0, 0, 0.82);
  white-space: nowrap;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.12s ease;
}

.watch-later-btn:hover .watch-later-btn__tip,
.watch-later-btn:focus-visible .watch-later-btn__tip {
  opacity: 1;
}
</style>
