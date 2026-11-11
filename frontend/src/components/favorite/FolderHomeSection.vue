<script setup lang="ts">
import type { LibraryFolderVM } from '../../types/ui'
import AppIcon from '../common/AppIcon.vue'
import FolderCard from './FolderCard.vue'

/**
 * 空间「主页」tab 的收藏区块（`R-34` ⑥）。
 *
 * <p>只展示夹卡（封面 + 条目数 + 名称），不承担任何管理动作；点击进收藏 tab 的对应夹。
 * self 面由父级传入含私密夹的列表（夹卡自带「私密」标记）；公开面只有公开夹。
 * 无夹时不渲染整块；夹列表失败只在块内联重试，**不拖垮主页的投稿内容**。
 */
defineProps<{
  folders: LibraryFolderVM[]
  /** 夹总数：用于「收藏 N」；`folders` 可以是截断后的前 10 个 */
  total: number
  loading: boolean
  error: string
}>()

const emit = defineEmits<{
  (event: 'open-folder', id: number): void
  (event: 'more'): void
  (event: 'retry'): void
}>()
</script>

<template>
  <section v-if="loading && !folders.length" class="home-folders">
    <header class="home-folders__head">
      <h2 class="home-folders__title">收藏</h2>
    </header>
    <div class="home-folders__grid">
      <div v-for="n in 5" :key="n" class="skeleton home-folders__skeleton" />
    </div>
  </section>

  <section v-else-if="error && !folders.length" class="home-folders">
    <header class="home-folders__head">
      <h2 class="home-folders__title">收藏</h2>
    </header>
    <p class="home-folders__error">
      收藏夹加载失败
      <button class="home-folders__retry" type="button" @click="emit('retry')">重试</button>
    </p>
  </section>

  <section v-else-if="folders.length" class="home-folders">
    <header class="home-folders__head">
      <h2 class="home-folders__title">收藏</h2>
      <span class="home-folders__count">{{ total }}</span>
      <button class="home-folders__more" type="button" @click="emit('more')">
        查看更多
        <AppIcon name="chevron-right" :size="12" />
      </button>
    </header>
    <div class="home-folders__grid">
      <FolderCard
        v-for="folder in folders"
        :key="folder.id"
        :folder="folder"
        @open="emit('open-folder', $event)"
      />
    </div>
  </section>
</template>

<style scoped>
.home-folders {
  margin-top: 28px;
}

.home-folders__head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.home-folders__title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-1);
}

.home-folders__count {
  font-size: 12px;
  color: var(--text-3);
}

.home-folders__more {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  margin-left: auto;
  padding: 3px 10px;
  border: 1px solid var(--line-2);
  border-radius: 14px;
  font-size: 12px;
  color: var(--text-2);
  background: var(--bg-0);
}

.home-folders__more:hover {
  color: var(--brand-blue);
  border-color: var(--brand-blue);
}

.home-folders__grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 20px 16px;
  margin-top: 12px;
}

.home-folders__skeleton {
  aspect-ratio: 16 / 9;
  border-radius: var(--radius-sm);
}

.home-folders__error {
  padding: 16px 0 4px;
  font-size: 13px;
  color: var(--text-3);
}

.home-folders__retry {
  padding: 0 0 0 4px;
  border: 0;
  background: none;
  font-size: 13px;
  color: var(--brand-blue);
  cursor: pointer;
}

.home-folders__retry:hover {
  text-decoration: underline;
}

@media (max-width: 1200px) {
  .home-folders__grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}

@media (max-width: 900px) {
  .home-folders__grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .home-folders__grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
