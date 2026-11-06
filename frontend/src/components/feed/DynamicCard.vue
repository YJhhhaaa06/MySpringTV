<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import type { DynamicCardVM } from '../../types/ui'
import { formatCount } from '../../api/adapters/format'
import AppIcon from '../common/AppIcon.vue'
import UserAvatar from '../common/UserAvatar.vue'
import CommentSection from '../video/CommentSection.vue'

/**
 * 动态卡（B 站 2026-10 版式，参照 `.screenshots/bilibili-2026-10-26/dynamic-feed-all`）：
 *
 * <pre>
 * 视频：[头像] UP 主
 *              刚刚 · 投稿了视频
 *      ┌────────────────────────────────────────────┐
 *      │ 封面(16:9) │ 标题（两行）                    │
 *      │            │ 简介（两行）                    │
 *      │            │ ▷ 播放量                        │
 *      └────────────────────────────────────────────┘
 *      点赞                评论（带数字）
 * 图文：[头像] UP 主
 *              刚刚 · 发布了图文 / 发布了动态
 *      标题（可选）
 *      正文（折叠 6 行，可展开）
 *      [图] [图] [图]（≤9，第 9 张 +N）
 *      点赞（有数字时带数字）        评论（可展开评论区）
 * </pre>
 *
 * <h2>★ 操作栏只有两个按钮，这是**故意**的</h2>
 * B 站还有「转发」：本仓没有转发域（没有表、没有端点）⇒ **不渲染**（有点不动的按钮 = 撒谎）。
 * 「评论」自 `R-31` 起是**卡内展开**：挂载 `CommentSection`（懒加载、页面保证同时只开一个），
 * 不再跳 `/video/{id}#comments`；详情页的评论区不动。
 * 「点赞」走真接口（`PUT|DELETE /contents/{id}/like`），成功后由页面回读 like-count 对齐。
 *
 * <h2>计数口径</h2>
 * - 播放量：**图文不显示**（`play_count` 对图文结构性恒 0，GAP-05）；视频在内容块右下；
 * - 评论数：**一律只在操作栏的「评论」按钮旁显示**（0 也显示，B 站同），视频 / 图文同一口径 ——
 *   视频卡曾经把它画在内容块右下，导致「数字离入口很远、点评论还要回头找」，
 *   也把「播放量 / 评论数」两个不同维度的数字并排成一组（B 站内容块右下是播放量 + **弹幕**，
 *   不是评论；本仓没有弹幕域 ⇒ 那里只留播放量，不拿评论数冒充）；
 * - 点赞数：**> 0 才显示**（B 站 0 赞时只写「点赞」），点过赞后一定显示。
 */
const props = defineProps<{
  item: DynamicCardVM
  busy?: boolean
  /** 作者头像 URL（`R-27` 批量端点）；null ⇒ UserAvatar 按 userId 回落 */
  avatarUrl?: string | null
  /** 评论区是否展开（受控：页面保证同时只开一个） */
  commentsOpen?: boolean
}>()
const emit = defineEmits<{
  (event: 'toggle-like'): void
  (event: 'toggle-comments'): void
  (event: 'comment-count-changed', count: number): void
}>()

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

// ── 图文：正文折叠 / 图集（B 站式；视频不走这里） ─────────────────────

const expanded = ref(false)
const canExpand = ref(false)
const textEl = ref<HTMLElement | null>(null)
const brokenImages = reactive(new Set<string>())

const visibleImages = computed(() => props.item.imageUrls.slice(0, 9))
const moreImages = computed(() => Math.max(0, props.item.imageUrls.length - 9))
const imageGridClass = computed(() => {
  const count = visibleImages.value.length
  if (count === 1) return 'is-one'
  return count <= 4 ? 'is-two' : 'is-three'
})

/**
 * ★ 只在**折叠态**量：展开后 `scrollHeight == clientHeight`，
 * 再量会把「收起」按钮误判成"没有溢出"而撤掉。
 */
function measure(): void {
  const element = textEl.value
  if (!element) {
    canExpand.value = false
    return
  }
  canExpand.value = !expanded.value && element.scrollHeight > element.clientHeight + 1
}

function onResize(): void {
  if (!expanded.value) measure()
}

watch(
  () => [props.item.id, props.item.description] as const,
  async () => {
    expanded.value = false
    brokenImages.clear()
    await nextTick()
    measure()
  },
)

onMounted(() => {
  window.addEventListener('resize', onResize)
  void nextTick(measure)
})
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

function toggleExpanded(): void {
  expanded.value = !expanded.value
  if (!expanded.value) void nextTick(measure)
}

function onCommentCountChanged(count: number): void {
  emit('comment-count-changed', count)
}
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
      ><UserAvatar
        :avatar-url="avatarUrl ?? null"
        :user-id="item.authorId"
        :initial="item.authorInitial"
        :size="52"
      /></a>
      <div class="dyn-card__ident">
        <a class="dyn-author" :href="item.authorHref" target="_blank" rel="noopener">{{ item.authorName }}</a>
        <p class="dyn-meta">
          <span>{{ item.timeText }}</span>
          <span class="dyn-meta__dot">·</span>
          <span>{{ item.actionText }}</span>
        </p>
      </div>
    </header>

    <!-- 视频：封面 + 信息（B 站视频动态卡；版式不动） -->
    <a v-if="!item.isPost" class="dyn-body" :href="item.href" target="_blank" rel="noopener" :aria-label="item.title">
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
        <span v-else class="dyn-cover__fallback" aria-hidden="true"><AppIcon name="tv" :size="30" /></span>
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
        </span>
      </span>
    </a>

    <!-- 图文：正文 + 图集（B 站式；太长折叠、展开按钮在链接外，避免嵌套交互元素） -->
    <div v-else class="dyn-post">
      <a class="dyn-post__link" :href="item.href" target="_blank" rel="noopener" :aria-label="item.title">
        <h3 v-if="item.hasTitle" class="dyn-post__title">{{ item.title }}</h3>
        <p
          v-if="item.description"
          ref="textEl"
          class="dyn-post__text"
          :class="{ 'is-clamped': !expanded }"
        >{{ item.description }}</p>
      </a>
      <!-- ★ 展开/收起必须在正文之后、图集之前：放到图集下面会被图片挤到首屏外，
           用户只看到省略号却找不到展开入口（真浏览器验收抓到） -->
      <button
        v-if="canExpand"
        class="dyn-post__toggle"
        type="button"
        :aria-expanded="expanded"
        @click="toggleExpanded"
      >
        {{ expanded ? '收起' : '展开' }}
      </button>
      <a
        v-if="visibleImages.length"
        class="dyn-post__images-link"
        :href="item.href"
        target="_blank"
        rel="noopener"
        :aria-label="`${item.title} 的图片`"
      >
        <div class="dyn-post__images" :class="imageGridClass">
          <span v-for="(url, index) in visibleImages" :key="`${url}-${index}`" class="dyn-post__image">
            <img
              v-if="!brokenImages.has(url)"
              :src="url"
              :alt="`${item.title} 的第 ${index + 1} 张图片`"
              loading="lazy"
              decoding="async"
              @error="brokenImages.add(url)"
            />
            <span v-else class="dyn-post__image-fallback" aria-hidden="true">
              <AppIcon name="article" :size="24" />
            </span>
            <span v-if="index === visibleImages.length - 1 && moreImages > 0" class="dyn-post__more">
              +{{ moreImages }}
            </span>
          </span>
        </div>
      </a>
    </div>

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
      <button
        class="dyn-action"
        :class="{ 'is-open': commentsOpen === true }"
        type="button"
        :aria-expanded="commentsOpen === true"
        @click="$emit('toggle-comments')"
      >
        <AppIcon name="comment" :size="20" />
        <span>评论</span>
        <!-- 评论数紧跟入口（视频 / 图文同一口径）：数字在这里，用户点之前就能看到「有几条」 -->
        <span v-if="item.commentCount !== null" class="dyn-action__count">{{ formatCount(item.commentCount) }}</span>
      </button>
    </footer>

    <!-- 评论内联（`R-31`）：懒加载 —— 只有展开的那张卡才挂载 / 发请求 -->
    <div v-if="commentsOpen" class="dyn-comments">
      <CommentSection
        :content-id="item.id"
        :comment-enabled="item.commentEnabled"
        :initial-count="item.commentCount"
        @count-changed="onCommentCountChanged"
      />
    </div>
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
  display: block;
  flex: none;
  border-radius: 50%;
  line-height: 0;
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

/* ── 图文：正文 + 图集（B 站式；缩进对齐 UP 主名字） ───────────── */
.dyn-post {
  margin: 12px 0 0 64px;
}

.dyn-post__link,
.dyn-post__images-link {
  display: block;
}

.dyn-post__title {
  font-size: 16px;
  font-weight: 600;
  line-height: 24px;
  color: var(--text-1);
  word-break: break-word;
}

.dyn-post__text {
  margin-top: 6px;
  font-size: 15px;
  line-height: 24px;
  color: var(--text-1);
  white-space: pre-wrap;
  word-break: break-word;
}

/* 折叠 6 行；展开后去掉 clamp（`white-space: pre-wrap` 下 line-clamp 仍按行数算） */
.dyn-post__text.is-clamped {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 6;
  overflow: hidden;
}

.dyn-post__toggle {
  margin-top: 2px;
  padding: 0;
  border: 0;
  background: none;
  font-size: 13px;
  line-height: 20px;
  color: var(--brand-blue);
  cursor: pointer;
}

.dyn-post__toggle:hover {
  text-decoration: underline;
}

.dyn-post__images {
  display: grid;
  gap: 6px;
  margin-top: 10px;
}

.dyn-post__images.is-one {
  grid-template-columns: minmax(0, 1fr);
  max-width: 420px;
}

.dyn-post__images.is-two {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.dyn-post__images.is-three {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.dyn-post__image {
  position: relative;
  display: block;
  aspect-ratio: 1;
  overflow: hidden;
  border-radius: var(--radius);
  background: var(--bg-2);
}

.dyn-post__images.is-one .dyn-post__image {
  aspect-ratio: 4 / 3;
}

.dyn-post__image img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.dyn-post__image-fallback {
  display: flex;
  width: 100%;
  height: 100%;
  align-items: center;
  justify-content: center;
  color: var(--text-4);
  background: var(--bg-2);
}

.dyn-post__more {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  font-weight: 600;
  color: #fff;
  background: rgba(0, 0, 0, 0.45);
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
  border: 0;
  border-radius: var(--radius);
  background: transparent;
  font: inherit;
  font-size: 14px;
  color: var(--text-2);
  cursor: pointer;
  text-decoration: none;
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

.dyn-action.is-open {
  color: var(--brand-blue);
  background: var(--brand-blue-bg);
}

.dyn-action__count {
  font-variant-numeric: tabular-nums;
}

/* 评论内联（`R-31`）：只在展开时挂载；分隔线 + 与正文同缩进 */
.dyn-comments {
  margin: 8px 0 0 64px;
  padding-top: 12px;
  border-top: 1px solid var(--line-2);
}

@media (max-width: 760px) {
  .dyn-body {
    grid-template-columns: minmax(0, 1fr);
  }

  .dyn-body,
  .dyn-actions,
  .dyn-post,
  .dyn-comments {
    margin-left: 0;
  }
}
</style>
