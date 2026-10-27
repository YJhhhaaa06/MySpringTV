<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { VideoCardVM } from '../../types/ui'
import AppIcon from './AppIcon.vue'
import WatchLaterButton from './WatchLaterButton.vue'

const props = withDefaults(
  defineProps<{ item: VideoCardVM; layout?: 'grid' | 'row'; watchLater?: boolean }>(),
  {
    layout: 'grid',
    /** 默认给卡片挂「稍后再看」hover；稍后再看页自己关掉（页面级「移出」已经承担该动作） */
    watchLater: true,
  },
)

/** 封面 404 / 解码失败也走占位块 —— 否则破图比没有封面更难看（dev 数据里会撞到） */
const coverBroken = ref(false)
watch(
  () => props.item.coverUrl,
  () => {
    coverBroken.value = false
  },
)

const showCover = computed(() => Boolean(props.item.coverUrl) && !coverBroken.value)

/** 网格卡底部渐变条：有播放量或时长才渲染（都没有就不摆一条空黑条） */
const showStats = computed(() => Boolean(props.item.playCountText || props.item.durationText))

/**
 * 失效条目（收藏夹 / 稍后再看里内容已被删或下架）**不跳转**：那个链接必然 404，
 * 点进去只会看到错误页。B 站也是灰卡不可点。
 *
 * <p>为什么用 `preventDefault` 而不是把 `<a>` 换成 `<div>`：卡片在索引 / 搜索 / 空间 /
 * 播放页侧栏 / 两个「我的」页里共用，换标签会牵动全部布局与样式；这里只关掉"跳转"这一件事，
 * 链接仍然可聚焦（键盘用户能看到它），视觉由 `.is-invalid` 承担。
 */
function guardInvalid(event: MouseEvent): void {
  if (props.item.invalid) event.preventDefault()
}
</script>

<template>
  <article class="video-card" :class="[`video-card--${layout}`, { 'is-invalid': item.invalid }]">
    <div class="video-card__cover-wrap">
      <a
        class="video-card__cover"
        :href="item.href"
        target="_blank"
        rel="noopener"
        :aria-label="item.title"
        :aria-disabled="item.invalid || undefined"
        @click="guardInvalid"
      >
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
        <span v-if="item.invalid" class="video-card__invalid">已失效</span>

        <!-- 网格卡：B 站封面底部渐变条（播放量在左、总时长在右）。
             弹幕数**永不渲染**（本仓没有弹幕域）；图文不显示计数（GAP-05，adapter 已置 null）。 -->
        <span v-if="showStats && layout === 'grid'" class="video-card__stats">
          <span class="video-card__stats-left">
            <span
              v-if="item.playCountText"
              class="video-card__stat"
              :title="`播放量 ${item.playCountText}`"
            >
              <AppIcon name="play" :size="18" />
              <span class="video-card__stat-text">{{ item.playCountText }}</span>
            </span>
          </span>
          <span v-if="item.durationText" class="video-card__duration">{{ item.durationText }}</span>
        </span>
        <!-- 侧栏小卡（相关推荐，封面仅 104px）：B 站只把时长压在封面右下角，
             播放量放到下方文字区（见 meta），否则渐变条会盖掉大半张封面。 -->
        <span v-else-if="item.durationText" class="video-card__duration-badge">
          {{ item.durationText }}
        </span>
      </a>
      <!-- 图文 / 失效内容不显示：后端「稍后再看」只收视频，摆一个必然 400 的按钮 = 撒谎 -->
      <WatchLaterButton
        v-if="watchLater && !item.isPost && !item.invalid && item.id > 0"
        :content-id="item.id"
      />
    </div>

    <div class="video-card__info">
      <a
        class="video-card__title"
        :href="item.href"
        target="_blank"
        rel="noopener"
        :aria-disabled="item.invalid || undefined"
        @click="guardInvalid"
      >{{ item.title }}</a>
      <p class="video-card__meta">
        <AppIcon name="user" :size="13" />
        <!-- 收藏夹条目（FavoriteItemVO）没有 authorId ⇒ 作者只能渲染成纯文本，不做死链 -->
        <a
          v-if="item.authorHref"
          class="video-card__author"
          :href="item.authorHref"
          :title="`进入 ${item.authorName} 的空间`"
        >
          {{ item.authorName }}
        </a>
        <span v-else class="video-card__author">{{ item.authorName }}</span>
        <template v-if="item.dateText">
          <span class="video-card__dot">·</span>
          <span>{{ item.dateText }}</span>
        </template>
        <!-- 侧栏小卡：播放量跟在作者 / 日期后面（B 站行内位置），不用封面渐变条 -->
        <span
          v-if="layout === 'row' && item.playCountText"
          class="video-card__meta-count"
          :title="`播放量 ${item.playCountText}`"
        >
          <AppIcon name="play" :size="12" />
          {{ item.playCountText }}
        </span>
        <!-- 弹幕数永不渲染（无弹幕域，danmakuText 恒 null） -->
      </p>
    </div>
  </article>
</template>

<style scoped>
.video-card {
  display: block;
  min-width: 0;
}

/* 封面链接 + hover「稍后再看」按钮的定位上下文（按钮不能放进 <a> 里） */
.video-card__cover-wrap {
  position: relative;
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

/* 失效条目：整卡去饱和 + 链接不可点（否则是一个必然 404 的死链） */
.video-card.is-invalid .video-card__cover,
.video-card.is-invalid .video-card__title {
  cursor: default;
}

.video-card.is-invalid .video-card__img,
.video-card.is-invalid .video-card__cover-fallback {
  filter: grayscale(1);
  opacity: 0.82;
}

.video-card.is-invalid .video-card__title {
  color: var(--text-3);
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

/*
 * 封面底部渐变条：B 站 2026-10-27 实测（1600 CSS）
 *   padding 16/8/6、背景 linear-gradient(transparent, rgba(0,0,0,.8))、
 *   字号 13 / 行高 18、图标 18×18、播放量与时长同为白色。
 * 只读展示：不占卡片布局高度（absolute），hover 缩放封面时跟着走。
 */
.video-card__stats {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  display: flex;
  align-items: center;
  padding: 16px 8px 6px;
  border-radius: 0 0 var(--radius) var(--radius);
  font-size: 13px;
  line-height: 18px;
  color: var(--text-inverse);
  background: linear-gradient(rgba(0, 0, 0, 0), rgba(0, 0, 0, 0.8));
}

.video-card__stats-left {
  display: flex;
  flex: 1;
  min-width: 0;
  align-items: center;
}

.video-card__stat {
  display: flex;
  min-width: 0;
  align-items: center;
  margin-right: 12px;
}

.video-card__stat-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

.video-card__stat :deep(.app-icon) {
  margin-right: 2px;
}

.video-card__stats .video-card__duration {
  font-variant-numeric: tabular-nums;
}

/* 侧栏小卡的时长角标：B 站用「压暗背景 + 小字」，不是整条渐变 */
.video-card__duration-badge {
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

/* 已失效（收藏夹里的内容被删）：B 站留灰卡 + 角标，用户才知道自己收藏过什么 */
.video-card__invalid {
  position: absolute;
  top: 6px;
  left: 6px;
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 12px;
  line-height: 16px;
  color: var(--text-inverse);
  background: rgba(0, 0, 0, 0.62);
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

.video-card__meta-count {
  display: inline-flex;
  align-items: center;
  margin-left: auto;
  padding-left: 8px;
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
}

.video-card__meta-count :deep(.app-icon) {
  margin-right: 2px;
}

/* 侧栏紧凑行（相关推荐）：封面在左、标题在右 */
.video-card--row {
  display: grid;
  grid-template-columns: 104px minmax(0, 1fr);
  gap: 8px;
  align-items: start;
}

/* 104px 的封面放不下 40px 圆钮：缩小一档，避免盖住大半张图 */
.video-card--row :deep(.watch-later-btn) {
  top: 4px;
  right: 4px;
  width: 30px;
  height: 30px;
}

.video-card--row :deep(.watch-later-btn svg) {
  width: 16px;
  height: 16px;
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
