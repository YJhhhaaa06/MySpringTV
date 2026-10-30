<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { DynamicCardVM } from '../../types/ui'
import { formatCount } from '../../api/adapters/format'
import AppIcon from '../common/AppIcon.vue'

/**
 * 动态卡（B 站 2026-10 版式，参照 `.screenshots/bilibili-2026-10-26/dynamic-feed-all`）：
 *
 * <pre>
 * [头像] UP 主
 *        刚刚 · 投稿了视频
 * ┌────────────────────────────────────────────┐
 * │ 封面(16:9) │ 标题（两行）                    │
 * │            │ 简介（两行）                    │
 * │            │ ▷ 播放量   💬 评论数             │
 * └────────────────────────────────────────────┘
 *        点赞（有数字时带数字）        评论
 * </pre>
 *
 * <h2>★ 操作栏只有两个按钮，这是**故意**的</h2>
 * B 站还有「转发」：本仓没有转发域（没有表、没有端点）⇒ **不渲染**（有点不动的按钮 = 撒谎）。
 * 「评论」不是独立动作：本仓的评论在播放页，所以它是一个跳到 `/video/{id}#comments` 的链接。
 * 「点赞」走真接口（`PUT|DELETE /contents/{id}/like`），成功后由页面回读 like-count 对齐。
 *
 * <h2>计数口径</h2>
 * - 播放量：**图文不显示**（`play_count` 对图文结构性恒 0，GAP-05）；
 * - 评论数：块内显示（0 也显示，B 站同）；
 * - 点赞数：**> 0 才显示**（B 站 0 赞时只写「点赞」），点过赞后一定显示。
 */
const props = defineProps<{ item: DynamicCardVM; busy?: boolean }>()
defineEmits<{ (event: 'toggle-like'): void }>()

/** 封面 404 / 解码失败也走占位块（dev 数据会撞到），逻辑与 `VideoCard` 一致 */
const coverBroken = ref(false)
watch(
  () => props.item.coverUrl,
  () => {
    coverBroken.value = false
  },
)

const showCover = computed(() => Boolean(props.item.coverUrl) && !coverBroken.value)
const showLikeCount = computed(
  () => props.item.likeCount !== null && (props.item.likeCount > 0 || props.item.liked === true),
)
</script>

<template>
  <article class="dyn-card">
    <header class="dyn-card__head">
      <a
        class="dyn-avatar"
        :href="item.authorHref"
        target="_blank"
        rel="noopener"
        :title="`${item.authorName} 的空间`"
        :aria-label="`${item.authorName} 的空间`"
      >{{ item.authorInitial }}</a>
      <div class="dyn-card__ident">
        <a class="dyn-author" :href="item.authorHref" target="_blank" rel="noopener">{{ item.authorName }}</a>
        <p class="dyn-meta">
          <span>{{ item.timeText }}</span>
          <span class="dyn-meta__dot">·</span>
          <span>{{ item.actionText }}</span>
        </p>
      </div>
    </header>

    <a class="dyn-body" :href="item.href" target="_blank" rel="noopener" :aria-label="item.title">
      <span class="dyn-cover">
        <img
          v-if="showCover"
          class="dyn-cover__img"
          :src="item.coverUrl ?? undefined"
          :alt="item.title"
          loading="lazy"
          decoding="async"
          @error="coverBroken = true"
        />
        <!-- 纯文字图文：封面位换成文字纸片（正文在右侧，避免重复）；视频缺封面仍用 TV 占位 -->
        <span v-else-if="item.isPost" class="dyn-cover__text" aria-hidden="true">
          <span class="dyn-cover__quote">“</span>
          <AppIcon name="article" :size="28" />
        </span>
        <span v-else class="dyn-cover__fallback" aria-hidden="true"><AppIcon name="tv" :size="30" /></span>
        <span v-if="item.isPost" class="dyn-cover__type">图文</span>
        <span v-if="item.durationText" class="dyn-cover__duration">{{ item.durationText }}</span>
      </span>

      <span class="dyn-info">
        <span class="dyn-title">{{ item.title }}</span>
        <span v-if="item.description" class="dyn-desc">{{ item.description }}</span>
        <span class="dyn-stats">
          <span v-if="item.playCountText" class="dyn-stat" :title="`播放量 ${item.playCountText}`">
            <AppIcon name="play" :size="18" />
            <span>{{ item.playCountText }}</span>
          </span>
          <span
            v-if="item.commentCount !== null"
            class="dyn-stat"
            :title="`评论 ${formatCount(item.commentCount)}`"
          >
            <AppIcon name="comment" :size="18" />
            <span>{{ formatCount(item.commentCount) }}</span>
          </span>
        </span>
      </span>
    </a>

    <footer class="dyn-actions">
      <button
        class="dyn-action"
        :class="{ 'is-liked': item.liked === true }"
        type="button"
        :disabled="busy"
        :aria-pressed="item.liked === true"
        @click="$emit('toggle-like')"
      >
        <AppIcon name="thumb" :size="20" />
        <span>{{ item.liked === true ? '已点赞' : '点赞' }}</span>
        <span v-if="showLikeCount" class="dyn-action__count">{{ formatCount(item.likeCount) }}</span>
      </button>
      <a class="dyn-action" :href="`${item.href}#comments`" target="_blank" rel="noopener">
        <AppIcon name="comment" :size="20" />
        <span>评论</span>
      </a>
    </footer>
  </article>
</template>

<style scoped>
.dyn-card {
  padding: 16px;
  border-radius: var(--radius-lg);
  background: var(--bg-0);
  box-shadow: var(--shadow-1);
}

.dyn-card__head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.dyn-avatar {
  display: flex;
  width: 52px;
  height: 52px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-size: 20px;
  font-weight: 600;
  color: var(--text-inverse);
  background: linear-gradient(135deg, var(--brand-pink) 0%, var(--brand-blue) 100%);
}

.dyn-card__ident {
  min-width: 0;
  flex: 1;
}

.dyn-author {
  display: block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-2);
}

.dyn-author:hover {
  color: var(--brand-blue);
}

.dyn-meta {
  margin-top: 3px;
  font-size: 13px;
  color: var(--text-3);
}

.dyn-meta__dot {
  margin: 0 4px;
  color: var(--text-4);
}

/* 内容块：B 站是「浅底 + 1px 描边」的整块，封面在左、信息在右；
 * ★ 左边缩进 = 头像宽 + 间距（B 站把内容块与操作栏对齐到 UP 主名字，不是卡片左缘） */
.dyn-body {
  display: grid;
  grid-template-columns: minmax(0, 40%) minmax(0, 1fr);
  gap: 14px;
  margin-top: 12px;
  margin-left: 64px;
  padding: 10px;
  border: 1px solid var(--line-2);
  border-radius: var(--radius-lg);
  transition: background-color 0.15s ease;
}

.dyn-body:hover {
  background: var(--bg-1);
}

.dyn-cover {
  position: relative;
  display: block;
  aspect-ratio: 16 / 9;
  overflow: hidden;
  border-radius: var(--radius);
  background: var(--bg-2);
}

.dyn-cover__img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.dyn-cover__fallback {
  display: flex;
  width: 100%;
  height: 100%;
  align-items: center;
  justify-content: center;
  color: var(--text-4);
  background: linear-gradient(135deg, #f1f2f3 0%, #e3e5e7 100%);
}

/* 纯文字图文的封面位：与网格卡同一套「文字纸片」语言（正文在右侧，这里只放引号 + 文档图标） */
.dyn-cover__text {
  position: relative;
  display: flex;
  width: 100%;
  height: 100%;
  align-items: center;
  justify-content: center;
  color: var(--brand-blue);
  background:
    radial-gradient(circle at 88% 8%, rgba(0, 174, 236, 0.1), transparent 45%),
    linear-gradient(135deg, #fbfcfd 0%, #eef4f9 100%);
}

.dyn-cover__quote {
  position: absolute;
  top: 2px;
  right: 12px;
  font-family: Georgia, 'Times New Roman', serif;
  font-size: 54px;
  line-height: 1;
  color: var(--brand-blue);
  opacity: 0.16;
}

.dyn-cover__type {
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

.dyn-cover__duration {
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

.dyn-info {
  display: flex;
  min-width: 0;
  flex-direction: column;
}

.dyn-title {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
  font-size: 15px;
  font-weight: 500;
  line-height: 22px;
  color: var(--text-1);
  word-break: break-word;
}

.dyn-desc {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
  margin-top: 6px;
  font-size: 13px;
  line-height: 19px;
  color: var(--text-3);
  word-break: break-word;
}

.dyn-stats {
  display: flex;
  align-items: center;
  gap: 20px;
  margin-top: auto;
  padding-top: 8px;
  font-size: 13px;
  color: var(--text-3);
}

.dyn-stat {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-variant-numeric: tabular-nums;
}

/* 操作栏：B 站是等分三列；本仓没有「转发」⇒ 两列等分（不摆点不动的按钮）。
   缩进与内容块一致，对齐 UP 主名字。 */
.dyn-actions {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin-top: 10px;
  margin-left: 64px;
}

.dyn-action {
  display: flex;
  height: 36px;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border-radius: var(--radius);
  font-size: 14px;
  color: var(--text-2);
  transition: color 0.15s ease, background-color 0.15s ease;
}

.dyn-action:hover {
  color: var(--brand-blue);
  background: var(--bg-1);
}

.dyn-action:disabled {
  cursor: wait;
  opacity: 0.6;
}

.dyn-action.is-liked {
  color: var(--brand-pink);
}

.dyn-action__count {
  font-variant-numeric: tabular-nums;
}

@media (max-width: 760px) {
  .dyn-body {
    grid-template-columns: minmax(0, 1fr);
  }

  .dyn-body,
  .dyn-actions {
    margin-left: 0;
  }
}
</style>
