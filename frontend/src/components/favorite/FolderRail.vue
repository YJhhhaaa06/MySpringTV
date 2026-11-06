<script setup lang="ts">
import type { LibraryFolderVM } from '../../types/ui'
import AppIcon from '../common/AppIcon.vue'

/**
 * 收藏 tab 左栏：真实收藏夹 + self 专属的「稍后再看」特殊项。
 *
 * <p>稍后再看**不是收藏夹**：没有封面、不参与夹数 / “收藏 N”，只是同一套右栏视图的
 * 另一个数据源（`GET /watch-later`）。公开面（看别人 / 匿名）不渲染它。
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
}>()
</script>

<template>
  <aside class="folder-rail" aria-label="收藏夹列表">
    <p class="folder-rail__group">{{ isSelf ? '我创建的收藏夹' : 'TA 的收藏夹' }}</p>

    <!-- 刷新时保留旧列表，别让左栏整块消失 -->
    <p v-if="loading && !folders.length" class="folder-rail__hint">加载中…</p>
    <p v-else-if="error && !folders.length" class="folder-rail__hint folder-rail__hint--error">{{ error }}</p>
    <ul v-else-if="folders.length" class="folder-rail__list">
      <li v-for="folder in folders" :key="folder.id">
        <button
          class="rail-folder"
          :class="{ 'is-active': view === 'folder' && folder.id === activeFolderId }"
          type="button"
          @click="emit('select-folder', folder.id)"
        >
          <span class="rail-folder__name">{{ folder.name }}</span>
          <span v-if="folder.isPrivate" class="rail-folder__badge">私密</span>
          <span class="rail-folder__count">{{ folder.itemCount }}</span>
        </button>
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
        @click="emit('select-later')"
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
  padding: 0 10px;
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
