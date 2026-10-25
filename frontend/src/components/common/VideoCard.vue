<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { VideoCardVM } from '../../types/ui'
import AppIcon from './AppIcon.vue'

const props = withDefaults(defineProps<{ item: VideoCardVM; layout?: 'grid' | 'row' }>(), {
  layout: 'grid',
})

/** 封面 404 / 解码失败也走占位块 —— 否则破图比没有封面更难看（dev 数据里会撞到） */
const coverBroken = ref(false)
watch(
  () => props.item.coverUrl,
  () => {
    coverBroken.value = false
  },
)

const showCover = computed(() => Boolean(props.item.coverUrl) && !coverBroken.value)
</script>

<template>
  <article class="video-card" :class="`video-card--${layout}`">
    <a class="video-card__cover" :href="item.href" target="_blank" rel="noopener" :aria-label="item.title">
      <img
        v-if="showCover"
        class="video-card__img"
        :src="item.coverUrl ?? undefined"
        :alt="item.title"
        loading="lazy"
        decoding="async"
        @error="coverBroken = true"
      />
      <span v-else class="video-card__cover-fallback" aria-hidden="true">
        <AppIcon name="tv" :size="30" />
      </span>
      <span v-if="item.isPost" class="video-card__type">图文</span>
      <span v-if="item.durationText" class="video-card__duration">{{ item.durationText }}</span>
      <!-- 列表 VO 没有任何计数 ⇒ 播放量角标**不渲染**（不编数字）；
           本仓没有弹幕域 ⇒ 弹幕数**永不渲染** -->
    </a>

    <div class="video-card__info">
      <a class="video-card__title" :href="item.href" target="_blank" rel="noopener">{{ item.title }}</a>
      <p class="video-card__meta">
        <AppIcon name="user" :size="13" />
        <a class="video-card__author" :href="item.authorHref" :title="`进入 ${item.authorName} 的空间（占位页）`">
          {{ item.authorName }}
        </a>
        <template v-if="item.dateText">
          <span class="video-card__dot">·</span>
          <span>{{ item.dateText }}</span>
        </template>
        <span v-if="item.playCountText" class="video-card__count">{{ item.playCountText }}</span>
        <span v-if="item.danmakuText" class="video-card__count">{{ item.danmakuText }}</span>
      </p>
    </div>
  </article>
</template>

<style scoped>
.video-card {
  display: block;
  min-width: 0;
}

.video-card__cover {
  position: relative;
  display: block;
  aspect-ratio: 16 / 9;
  overflow: hidden;
  border-radius: var(--radius);
  background: var(--bg-2);
}

.video-card__img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.3s ease;
}

.video-card:hover .video-card__img {
  transform: scale(1.04);
}

.video-card__cover-fallback {
  display: flex;
  width: 100%;
  height: 100%;
  align-items: center;
  justify-content: center;
  color: var(--text-4);
  background: linear-gradient(135deg, #f1f2f3 0%, #e3e5e7 100%);
}

.video-card__duration {
  position: absolute;
  right: 6px;
  bottom: 6px;
  padding: 1px 4px;
  border-radius: 2px;
  font-size: 12px;
  line-height: 16px;
  color: #fff;
  background: rgba(0, 0, 0, 0.55);
  font-variant-numeric: tabular-nums;
}

.video-card__type {
  position: absolute;
  top: 6px;
  left: 6px;
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 12px;
  line-height: 16px;
  color: var(--text-1);
  background: rgba(255, 255, 255, 0.86);
}

.video-card__info {
  padding-top: 10px;
}

.video-card__title {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
  height: 44px;
  font-size: var(--card-title-size);
  font-weight: 500;
  line-height: 22px;
  color: var(--text-1);
  word-break: break-word;
}

.video-card__title:hover {
  color: var(--brand-blue);
}

.video-card__meta {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 6px;
  font-size: 13px;
  line-height: 18px;
  color: var(--text-3);
  white-space: nowrap;
  overflow: hidden;
}

.video-card__author {
  max-width: 9em;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--text-3);
}

.video-card__author:hover {
  color: var(--brand-blue);
}

.video-card__dot {
  color: var(--text-4);
}

.video-card__count {
  margin-left: auto;
  padding-left: 8px;
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
}

/* 侧栏紧凑行（相关推荐）：封面在左、标题在右 */
.video-card--row {
  display: grid;
  grid-template-columns: 104px minmax(0, 1fr);
  gap: 8px;
  align-items: start;
}

.video-card--row .video-card__cover {
  aspect-ratio: 16 / 10;
}

.video-card--row .video-card__info {
  padding-top: 0;
}

.video-card--row .video-card__title {
  height: 40px;
  font-size: 14px;
  line-height: 20px;
}

.video-card--row .video-card__meta {
  margin-top: 4px;
  font-size: 12px;
}
</style>
