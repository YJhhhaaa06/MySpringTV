<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import type { FavoriteFolderVM } from '../../types/ui'
import AppIcon from '../common/AppIcon.vue'

/**
 * 收藏弹层（B 站版式：收藏夹列表 + 勾选即存 + 新建收藏夹）。
 *
 * <h2>为什么"勾选即存"而不是"整页确认"</h2>
 * 后端没有"批量设置收藏夹"的端点，只有 `POST /favorites`（加进一个夹）与
 * `DELETE /favorites`（从一个夹移除）⇒ 一次勾选 = 一次写。硬做成"确认后一起提交"
 * 会让部分失败无法回滚（用户以为全存上了）。所以照抄 B 站：**改一个存一个**。
 *
 * <p>★ 新用户的 `GET /favorite-folders` 是**空数组**——默认收藏夹由后端在首次收藏时创建，
 * 前端不预置假夹名。空态里给"新建收藏夹"入口。
 */
const props = defineProps<{
  folders: FavoriteFolderVM[]
  loading: boolean
  error: string
  creating: boolean
  /** 正在提交的那一项（避免连点） */
  pendingFolderId: number | null
}>()

const emit = defineEmits<{
  (event: 'close'): void
  (event: 'toggle', folderId: number): void
  (event: 'create', name: string): void
}>()

const newName = ref('')
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

function submitCreate(): void {
  const name = newName.value.trim()
  if (!name || props.creating) return
  emit('create', name)
  newName.value = ''
}
</script>

<template>
  <Teleport to="body">
    <div class="dialog-mask" @click.self="emit('close')">
      <div
        ref="card"
        class="folder-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="folder-title"
        tabindex="-1"
      >
        <header class="folder-dialog__head">
          <h2 id="folder-title" class="folder-dialog__title">收藏到</h2>
          <button class="folder-dialog__close" type="button" aria-label="关闭" @click="emit('close')">
            <AppIcon name="close" :size="18" />
          </button>
        </header>

        <p v-if="loading" class="folder-dialog__hint">加载收藏夹…</p>

        <ul v-else-if="folders.length" class="folder-list">
          <li v-for="folder in folders" :key="folder.id">
            <button
              class="folder-item"
              :class="{ 'is-checked': folder.contains }"
              type="button"
              :disabled="pendingFolderId === folder.id"
              @click="emit('toggle', folder.id)"
            >
              <span class="folder-item__check" aria-hidden="true">
                <AppIcon v-if="folder.contains" name="star" :size="14" />
              </span>
              <span class="folder-item__name">
                {{ folder.name }}
                <span v-if="folder.isDefault" class="folder-item__tag">默认</span>
                <span v-if="folder.isPrivate" class="folder-item__tag">私密</span>
              </span>
              <span class="folder-item__count">{{ folder.itemCount }}</span>
            </button>
          </li>
        </ul>

        <p v-else-if="!error" class="folder-dialog__hint">
          还没有收藏夹。第一次收藏时后端会创建「默认收藏夹」，也可以现在新建一个。
        </p>

        <p v-if="error" class="folder-dialog__error" role="alert">{{ error }}</p>

        <form class="folder-create" @submit.prevent="submitCreate">
          <input
            v-model="newName"
            class="folder-create__input"
            type="text"
            maxlength="20"
            placeholder="新建收藏夹"
            aria-label="新建收藏夹名称"
          />
          <button class="btn btn-ghost folder-create__submit" type="submit" :disabled="creating">
            {{ creating ? '创建中…' : '新建' }}
          </button>
        </form>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.folder-dialog {
  width: 100%;
  max-width: 420px;
  padding: 20px 22px 18px;
  border-radius: var(--radius-lg);
  background: var(--bg-0);
  box-shadow: var(--shadow-2);
  outline: none;
}

.folder-dialog__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.folder-dialog__title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-1);
}

.folder-dialog__close {
  display: flex;
  width: 28px;
  height: 28px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: var(--text-3);
}

.folder-dialog__close:hover {
  color: var(--text-1);
  background: var(--bg-1);
}

.folder-dialog__hint {
  margin-top: 14px;
  font-size: 13px;
  line-height: 20px;
  color: var(--text-3);
}

.folder-list {
  display: flex;
  max-height: 300px;
  flex-direction: column;
  gap: 2px;
  margin-top: 12px;
  overflow-y: auto;
}

.folder-item {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 10px;
  padding: 9px 8px;
  border-radius: var(--radius);
  font-size: 14px;
  color: var(--text-1);
  text-align: left;
}

.folder-item:hover {
  background: var(--bg-1);
}

.folder-item__check {
  display: flex;
  width: 18px;
  height: 18px;
  flex: none;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--line-1);
  border-radius: var(--radius-sm);
  color: var(--brand-pink);
  background: var(--bg-0);
}

.folder-item.is-checked .folder-item__check {
  border-color: var(--brand-pink);
  background: #fff5f8;
}

.folder-item__name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.folder-item__tag {
  margin-left: 6px;
  padding: 1px 5px;
  border-radius: var(--radius-sm);
  font-size: 11px;
  color: var(--text-3);
  background: var(--bg-1);
}

.folder-item__count {
  flex: none;
  font-size: 12px;
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
}

.folder-dialog__error {
  margin-top: 12px;
  font-size: 13px;
  color: #f56c6c;
}

.folder-create {
  display: flex;
  gap: 8px;
  margin-top: 16px;
}

.folder-create__input {
  flex: 1;
  height: 34px;
  padding: 0 10px;
  border: 1px solid var(--line-1);
  border-radius: var(--radius);
  font-size: 13px;
  color: var(--text-1);
  background: var(--bg-0);
  outline: none;
}

.folder-create__input:focus {
  border-color: var(--brand-blue);
}

.folder-create__submit {
  flex: none;
}
</style>
