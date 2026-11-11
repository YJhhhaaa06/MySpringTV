<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { lockBodyOverflow, unlockBodyOverflow } from '../../common/modalOverflow'
import AppIcon from '../common/AppIcon.vue'

/**
 * 删除收藏夹的确认框（`R-36`）。
 *
 * <p>文案按用户拍板**只有标题**：不解释"删夹不等于下架视频"，也不写夹内条数
 * （`itemCount` 是快照，跨标签页可能过期）。确认后由父级**先关闭弹窗再发请求**，
 * 失败用 toast，不在弹窗里内联。
 *
 * <p>点遮罩 / Esc / 右上角关闭 = 取消。
 */
defineProps<{ folderName: string }>()

const emit = defineEmits<{
  (event: 'close'): void
  (event: 'confirm'): void
}>()

const card = ref<HTMLElement | null>(null)

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') emit('close')
}

onMounted(() => {
  lockBodyOverflow()
  window.addEventListener('keydown', onKeydown)
  card.value?.focus()
})

onUnmounted(() => {
  unlockBodyOverflow()
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <Teleport to="body">
    <div class="dialog-mask" @click.self="emit('close')">
      <div
        ref="card"
        class="folder-delete"
        role="dialog"
        aria-modal="true"
        aria-labelledby="folder-delete-title"
        tabindex="-1"
      >
        <button class="folder-delete__close" type="button" aria-label="关闭" @click="emit('close')">
          <AppIcon name="close" :size="18" />
        </button>
        <h2 id="folder-delete-title" class="folder-delete__title">
          确认要删除收藏夹「{{ folderName }}」？
        </h2>
        <footer class="folder-delete__actions">
          <button class="btn btn-ghost" type="button" @click="emit('close')">取消</button>
          <button class="btn btn-blue" type="button" @click="emit('confirm')">删除</button>
        </footer>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.folder-delete {
  position: relative;
  width: 340px;
  max-width: 100%;
  padding: 28px 24px 20px;
  border-radius: var(--radius-lg);
  background: var(--bg-0);
  box-shadow: var(--shadow-2);
  text-align: center;
  outline: none;
}

.folder-delete__close {
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

.folder-delete__close:hover {
  color: var(--text-1);
  background: var(--bg-2);
}

.folder-delete__title {
  overflow-wrap: anywhere;
  font-size: 16px;
  font-weight: 600;
  line-height: 24px;
  color: var(--text-1);
}

.folder-delete__actions {
  display: flex;
  gap: 12px;
  margin-top: 24px;
}

.folder-delete__actions .btn {
  flex: 1;
  height: 36px;
}
</style>
