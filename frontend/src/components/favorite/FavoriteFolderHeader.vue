<script setup lang="ts">
import type { LibraryFolderVM } from '../../types/ui'
import AppIcon from '../common/AppIcon.vue'
import FolderCover from './FolderCover.vue'

/**
 * 收藏浏览器右栏头部：选中夹（封面 + 名称 + 条数 + 私密标记）或稍后再看。
 *
 * <p>本窗封面**只展示**，不提供上传 / 恢复（`R-34` ⑤）；夹 CRUD 与批量操作也不做。
 */
defineProps<{
  folder: LibraryFolderVM | null
  isLater: boolean
  total: number
}>()
</script>

<template>
  <header class="folder-head">
    <template v-if="isLater">
      <span class="folder-head__later-icon" aria-hidden="true">
        <AppIcon name="watch-later" :size="22" />
      </span>
      <div class="folder-head__ident">
        <h2 class="folder-head__title">稍后再看</h2>
        <p class="folder-head__meta">{{ total }} 个内容</p>
      </div>
    </template>

    <template v-else-if="folder">
      <span class="folder-head__cover">
        <FolderCover :cover-url="folder.coverUrl" :icon-size="28" />
      </span>
      <div class="folder-head__ident">
        <h2 class="folder-head__title" :title="folder.name">{{ folder.name }}</h2>
        <p class="folder-head__meta">
          <span v-if="folder.isPrivate" class="folder-head__badge">私密</span>
          <span>{{ total }} 个内容</span>
        </p>
      </div>
    </template>
  </header>
</template>

<style scoped>
.folder-head {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 16px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--line-2);
}

.folder-head__cover {
  width: 160px;
  flex: none;
}

.folder-head__later-icon {
  display: flex;
  width: 48px;
  height: 48px;
  align-items: center;
  justify-content: center;
  flex: none;
  border-radius: 50%;
  color: var(--brand-pink);
  background: #fff0f5;
}

.folder-head__ident {
  min-width: 0;
}

.folder-head__title {
  overflow: hidden;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-1);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.folder-head__meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-3);
}

.folder-head__badge {
  padding: 0 4px;
  border-radius: 2px;
  font-size: 11px;
  line-height: 16px;
  color: var(--text-3);
  background: var(--bg-2);
}
</style>
