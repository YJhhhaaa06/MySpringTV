<script setup lang="ts">
import type { LibraryFolderVM } from '../../types/ui'
import FolderCover from './FolderCover.vue'

/**
 * 主页「收藏」区块的收藏夹卡（B 站：封面 + 条目数 + 夹名）。
 *
 * <p>封面走 `FolderCover` 的三态占位；私密夹只在 self 面出现，左上角打「私密」标记。
 * 点击由父级决定落点（空间内 push `?tab=folders&folder=id`）。
 */
defineProps<{ folder: LibraryFolderVM }>()
const emit = defineEmits<{ (event: 'open', id: number): void }>()
</script>

<template>
  <button class="folder-card" type="button" :title="folder.name" @click="emit('open', folder.id)">
    <span class="folder-card__cover">
      <FolderCover :cover-url="folder.coverUrl" :icon-size="24" />
      <span class="folder-card__count">{{ folder.itemCount }} 个内容</span>
      <span v-if="folder.isPrivate" class="folder-card__private">私密</span>
    </span>
    <span class="folder-card__name">{{ folder.name }}</span>
  </button>
</template>

<style scoped>
.folder-card {
  display: flex;
  width: 100%;
  min-width: 0;
  flex-direction: column;
  gap: 8px;
  padding: 0;
  border: 0;
  background: none;
  text-align: left;
  cursor: pointer;
}

.folder-card__cover {
  position: relative;
  display: block;
}

.folder-card__count {
  position: absolute;
  right: 6px;
  bottom: 6px;
  padding: 0 5px;
  border-radius: 2px;
  font-size: 11px;
  line-height: 18px;
  color: var(--text-inverse);
  background: rgba(0, 0, 0, 0.55);
  font-variant-numeric: tabular-nums;
}

.folder-card__private {
  position: absolute;
  top: 6px;
  left: 6px;
  padding: 0 5px;
  border-radius: 2px;
  font-size: 11px;
  line-height: 18px;
  color: var(--text-inverse);
  background: rgba(0, 0, 0, 0.55);
}

.folder-card__name {
  overflow: hidden;
  font-size: 14px;
  line-height: 20px;
  color: var(--text-1);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.folder-card:hover .folder-card__name {
  color: var(--brand-blue);
}
</style>
