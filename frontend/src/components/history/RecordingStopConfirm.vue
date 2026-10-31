<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import AppIcon from '../common/AppIcon.vue'

/**
 * 「停用历史记录」确认框（B站 版式，`R-25`）。
 *
 * <p>★ 只有**关闭**才弹它，打开不弹（用户 2026-10-31 实测口径）。
 * 文案逐字照抄 B站：「确认要停用历史记录？/ 停用之后浏览记录将不再更新」——
 * 第二句是**必须的**：本仓没有删除能力（GAP-19），说"清除"就是撒谎。
 *
 * <p>**纯展示 + 事件**：`confirm` 抛给页面，落库与状态迁移由页面的开关状态机决定。
 * 点遮罩 = 取消（`@click.self`，同登录 / 投币 / 收藏弹层）。
 */
const emit = defineEmits<{
  (event: 'close'): void
  (event: 'confirm'): void
}>()

const card = ref<HTMLElement | null>(null)

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') emit('close')
}

onMounted(() => {
  document.body.style.overflow = 'hidden'
  window.addEventListener('keydown', onKeydown)
  card.value?.focus()
})

onUnmounted(() => {
  document.body.style.overflow = ''
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <Teleport to="body">
    <div class="dialog-mask" @click.self="emit('close')">
      <div
        ref="card"
        class="rec-confirm"
        role="dialog"
        aria-modal="true"
        aria-labelledby="rec-confirm-title"
        tabindex="-1"
      >
        <button class="rec-confirm__close" type="button" aria-label="关闭" @click="emit('close')">
          <AppIcon name="close" :size="18" />
        </button>
        <h2 id="rec-confirm-title" class="rec-confirm__title">确认要停用历史记录？</h2>
        <p class="rec-confirm__desc">停用之后浏览记录将不再更新</p>
        <footer class="rec-confirm__actions">
          <button class="btn btn-ghost" type="button" @click="emit('close')">取消</button>
          <button class="btn btn-blue" type="button" @click="emit('confirm')">确认</button>
        </footer>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.rec-confirm {
  position: relative;
  width: 340px;
  max-width: 100%;
  padding: 28px 24px 20px;
  border-radius: 8px;
  background: var(--bg-0);
  text-align: center;
  outline: none;
}

.rec-confirm__close {
  position: absolute;
  top: 12px;
  right: 12px;
  display: flex;
  width: 24px;
  height: 24px;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 4px;
  color: var(--text-3);
  background: transparent;
  cursor: pointer;
}

.rec-confirm__close:hover {
  color: var(--text-1);
  background: var(--bg-2);
}

.rec-confirm__title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-1);
}

.rec-confirm__desc {
  margin-top: 10px;
  font-size: 13px;
  line-height: 20px;
  color: var(--text-3);
}

.rec-confirm__actions {
  display: flex;
  gap: 12px;
  margin-top: 24px;
}

.rec-confirm__actions .btn {
  flex: 1;
  height: 36px;
}
</style>
