<script setup lang="ts">
import { onMounted, onUnmounted, ref, computed } from 'vue'
import AppIcon from '../common/AppIcon.vue'

/**
 * 删除历史的确认框（B站 版式，`R-26`）。
 *
 * <h2>两种形态，一套壳</h2>
 * - `selected`：管理态点「删除」⇒「确认要删除选中的历史记录？/ 记录删除后将不可恢复」；
 * - `all`：工具栏点「清空历史」⇒「确认要清空所有历史记录？/ 记录清空后将不可恢复」。
 *
 * <p>★ 确认框的归属是**入口**，不是条数：管理态哪怕只选了一条也照样确认（B站 实测口径），
 * 而**卡片上那个垃圾桶是直删**（不确认）—— 摆一个"每个入口都要确认"的统一规则，
 * 反而会让 B站 用户在这里多点一次。
 *
 * <p>文案第二句「记录删除后将不可恢复」不是修辞：`R-26` 之后删除是**硬删**，
 * 服务端不保留副本（播放量仍在账本里、不会掉，但历史行本身回不来）。
 *
 * <p>点遮罩 = 取消（`@click.self`，同登录 / 投币 / 收藏 / 记录开关的弹层）。
 */
const props = defineProps<{ mode: 'selected' | 'all' }>()

const emit = defineEmits<{
  (event: 'close'): void
  (event: 'confirm'): void
}>()

/** 用 computed 而不是 setup 时求值：同一实例上切 mode 时文案要跟着变（不依赖"一定会重建"）。 */
const title = computed(() =>
  props.mode === 'all' ? '确认要清空所有历史记录？' : '确认要删除选中的历史记录？',
)
const desc = computed(() =>
  props.mode === 'all' ? '记录清空后将不可恢复' : '记录删除后将不可恢复',
)

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
        class="del-confirm"
        role="dialog"
        aria-modal="true"
        aria-labelledby="del-confirm-title"
        tabindex="-1"
      >
        <button class="del-confirm__close" type="button" aria-label="关闭" @click="emit('close')">
          <AppIcon name="close" :size="18" />
        </button>
        <h2 id="del-confirm-title" class="del-confirm__title">{{ title }}</h2>
        <p class="del-confirm__desc">{{ desc }}</p>
        <footer class="del-confirm__actions">
          <button class="btn btn-ghost" type="button" @click="emit('close')">取消</button>
          <button class="btn btn-blue" type="button" @click="emit('confirm')">确认</button>
        </footer>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.del-confirm {
  position: relative;
  width: 340px;
  max-width: 100%;
  padding: 28px 24px 20px;
  border-radius: 8px;
  background: var(--bg-0);
  text-align: center;
  outline: none;
}

.del-confirm__close {
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

.del-confirm__close:hover {
  color: var(--text-1);
  background: var(--bg-2);
}

.del-confirm__title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-1);
}

.del-confirm__desc {
  margin-top: 10px;
  font-size: 13px;
  line-height: 20px;
  color: var(--text-3);
}

.del-confirm__actions {
  display: flex;
  gap: 12px;
  margin-top: 24px;
}

.del-confirm__actions .btn {
  flex: 1;
  height: 36px;
}
</style>
