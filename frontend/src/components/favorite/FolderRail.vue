<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import type { LibraryFolderVM } from '../../types/ui'
import AppIcon from '../common/AppIcon.vue'

/**
 * 收藏 tab 左栏：真实收藏夹 + self 专属的「稍后再看」特殊项。
 *
 * <p>稍后再看**不是收藏夹**：没有封面、不参与夹数 / “收藏 N”，只是同一套右栏视图的
 * 另一个数据源（`GET /watch-later`）。公开面（看别人 / 匿名）不渲染它。
 *
 * <p>`R-35`：self 面每个夹 hover / focus 出 ⋮，菜单只有「编辑信息」。主按钮与 ⋮ 是
 * **两个并列按钮**（不能按钮套按钮）；点外部 / Esc 关闭菜单。
 */
defineProps<{
  folders: LibraryFolderVM[]
  activeFolderId: number
  isSelf: boolean
  loading: boolean
  error: string
  /** null = 计数未取到 / 未登录（渲染成 `-`，不假装是 0） */
  laterTotal: number | null
  view: 'folder' | 'later'
}>()

const emit = defineEmits<{
  (event: 'select-folder', id: number): void
  (event: 'select-later'): void
  (event: 'edit-folder', id: number): void
}>()

const openMenuId = ref<number | null>(null)

function selectFolder(id: number): void {
  openMenuId.value = null
  emit('select-folder', id)
}

function selectLater(): void {
  openMenuId.value = null
  emit('select-later')
}

function menuId(id: number): string {
  return `rail-menu-${id}`
}

function toggleMenu(id: number, event: MouseEvent): void {
  event.stopPropagation()
  const next = openMenuId.value === id ? null : id
  openMenuId.value = next
  if (next !== null) {
    // 键盘 / 读屏：打开后焦点进菜单，而不是留在 ⋮ 上
    void nextTick(() => {
      document.querySelector<HTMLElement>('.rail-menu__item')?.focus()
    })
  }
}

function chooseEdit(id: number): void {
  openMenuId.value = null
  emit('edit-folder', id)
}

function onDocumentPointerDown(event: PointerEvent): void {
  if (openMenuId.value === null) return
  const target = event.target as HTMLElement | null
  if (target?.closest('.rail-menu') || target?.closest('.rail-more')) return
  openMenuId.value = null
}

function onDocumentKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Escape' || openMenuId.value === null) return
  // Esc 后把焦点还给 ⋮，别让菜单移除后焦点落回 body
  const trigger = document.querySelector<HTMLElement>('.rail-more.is-open')
  openMenuId.value = null
  trigger?.focus()
}

onMounted(() => {
  document.addEventListener('pointerdown', onDocumentPointerDown)
  document.addEventListener('keydown', onDocumentKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onDocumentPointerDown)
  document.removeEventListener('keydown', onDocumentKeydown)
})
</script>

<template>
  <aside class="folder-rail" aria-label="收藏夹列表">
    <p class="folder-rail__group">{{ isSelf ? '我创建的收藏夹' : 'TA 的收藏夹' }}</p>

    <!-- 刷新时保留旧列表，别让左栏整块消失 -->
    <p v-if="loading && !folders.length" class="folder-rail__hint">加载中…</p>
    <p v-else-if="error && !folders.length" class="folder-rail__hint folder-rail__hint--error">{{ error }}</p>
    <ul v-else-if="folders.length" class="folder-rail__list">
      <li v-for="folder in folders" :key="folder.id" class="rail-item">
        <button
          class="rail-folder"
          :class="{ 'is-active': view === 'folder' && folder.id === activeFolderId }"
          type="button"
          @click="selectFolder(folder.id)"
        >
          <span class="rail-folder__name">{{ folder.name }}</span>
          <span v-if="folder.isPrivate" class="rail-folder__badge">私密</span>
          <span class="rail-folder__count">{{ folder.itemCount }}</span>
        </button>

        <template v-if="isSelf">
          <button
            class="rail-more"
            :class="{ 'is-open': openMenuId === folder.id }"
            type="button"
            :title="`${folder.name} 操作`"
            aria-haspopup="menu"
            aria-label="收藏夹操作"
            :aria-controls="openMenuId === folder.id ? menuId(folder.id) : undefined"
            :aria-expanded="openMenuId === folder.id"
            @click="toggleMenu(folder.id, $event)"
          >
            <AppIcon name="more" :size="16" />
          </button>
          <div v-if="openMenuId === folder.id" :id="menuId(folder.id)" class="rail-menu" role="menu">
            <button class="rail-menu__item" type="button" role="menuitem" @click="chooseEdit(folder.id)">
              编辑信息
            </button>
          </div>
        </template>
      </li>
    </ul>
    <p v-else class="folder-rail__hint">
      {{ isSelf ? '还没有收藏夹' : '还没有公开的收藏夹' }}
    </p>

    <template v-if="isSelf">
      <span class="folder-rail__divider" aria-hidden="true"></span>
      <button
        class="rail-folder"
        :class="{ 'is-active': view === 'later' }"
        type="button"
        @click="selectLater"
      >
        <span class="rail-folder__name">
          <AppIcon name="watch-later" :size="14" />
          稍后再看
        </span>
        <span class="rail-folder__count">{{ laterTotal === null ? '-' : laterTotal }}</span>
      </button>
    </template>
  </aside>
</template>

<style scoped>
.folder-rail {
  position: sticky;
  top: calc(var(--header-h) + 16px);
  padding: 8px;
  border-radius: var(--radius);
  background: var(--bg-1);
}

.folder-rail__group {
  padding: 8px 10px 6px;
  font-size: 12px;
  color: var(--text-3);
}

.folder-rail__hint {
  padding: 10px 8px;
  font-size: 12px;
  color: var(--text-3);
}

.folder-rail__hint--error {
  color: #f56c6c;
}

.folder-rail__list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.rail-item {
  position: relative;
}

.rail-more {
  position: absolute;
  top: 50%;
  right: 2px;
  display: flex;
  width: 24px;
  height: 24px;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-sm);
  color: var(--text-3);
  opacity: 0;
  pointer-events: none;
  transform: translateY(-50%);
}

.rail-item:hover .rail-more,
.rail-item:focus-within .rail-more,
.rail-more.is-open {
  opacity: 1;
  pointer-events: auto;
}

.rail-more:hover,
.rail-more.is-open {
  color: var(--brand-blue);
  background: var(--bg-2);
}

.rail-menu {
  position: absolute;
  top: 34px;
  right: 0;
  z-index: 10;
  min-width: 104px;
  padding: 4px;
  border: 1px solid var(--line-1);
  border-radius: var(--radius);
  background: var(--bg-0);
  box-shadow: var(--shadow-2);
}

.rail-menu__item {
  display: block;
  width: 100%;
  padding: 7px 10px;
  border-radius: var(--radius-sm);
  font-size: 13px;
  color: var(--text-1);
  text-align: left;
}

.rail-menu__item:hover {
  color: var(--brand-blue);
  background: var(--bg-1);
}

/* 触屏没有可靠的 hover：⋮ 常显，否则编辑入口不可达 */
@media (hover: none) {
  .rail-more {
    opacity: 1;
    pointer-events: auto;
  }
}

.folder-rail__divider {
  display: block;
  height: 1px;
  margin: 8px 4px;
  background: var(--line-1);
}

.rail-folder {
  display: flex;
  width: 100%;
  height: 38px;
  align-items: center;
  gap: 6px;
  padding: 0 30px 0 10px;
  border-radius: var(--radius-sm);
  font-size: 14px;
  color: var(--text-2);
  text-align: left;
}

.rail-folder:hover {
  color: var(--brand-blue);
  background: var(--bg-0);
}

.rail-folder.is-active {
  color: var(--brand-pink);
  background: var(--bg-0);
  font-weight: 600;
}

.rail-folder__name {
  display: inline-flex;
  min-width: 0;
  align-items: center;
  gap: 4px;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rail-folder__badge {
  flex: none;
  padding: 0 4px;
  border-radius: 2px;
  font-size: 11px;
  line-height: 16px;
  color: var(--text-3);
  background: var(--bg-2);
}

.rail-folder__count {
  flex: none;
  font-size: 12px;
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
}

@media (max-width: 900px) {
  .folder-rail {
    position: static;
  }
}
</style>
