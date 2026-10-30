<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import type { VideoDetailVM } from '../types/ui'
import { loadOpusPage } from '../api/endpoints'
import { ApiError, describeError } from '../api/http'
import { initialOf } from '../api/adapters/format'
import { videoHref } from '../api/adapters/links'
import { sessionState } from '../auth/session'
import { shareCurrentPage } from '../common/share'
import { useVideoInteractions } from '../composables/useVideoInteractions'
import EmptyState from '../components/common/EmptyState.vue'
import CommentSection from '../components/video/CommentSection.vue'
import FavoriteDialog from '../components/video/FavoriteDialog.vue'
import OpusActionRail from '../components/opus/OpusActionRail.vue'
import OpusImageGallery from '../components/opus/OpusImageGallery.vue'

/**
 * 图文详情（B 站 `/opus/{id}` 的对应页；图 2 那种「白底正文列 + 右侧悬浮操作栏 + 评论」）。
 *
 * <h2>★ 所有入口共用这一页</h2>
 * 首页网格 / 轮播、搜索、空间作品、动态卡、收藏夹兜底重定向都落到 `/opus/{id}`
 * （规则唯一出处 = `api/adapters/links.ts#contentHref`）。页面**不区分来源**：
 * 图片区默认九宫格，点图进放大态，两种形态随时可切换（`OpusImageGallery`）。
 *
 * <h2>降级（GAP 台账；不渲染 = 后端没有，不是漏做）</h2>
 * - 没有头像字段（GAP-08）⇒ 昵称首字圆形头像；
 * - 图文没有播放语义（GAP-05）⇒ 不渲染播放量；
 * - 图文不支持投币（coin 域只服务视频）⇒ 操作栏**不渲染硬币按钮**；
 * - 没有「列出点赞人 / 转发人」端点、也没有转发域 ⇒ 不渲染「赞与转发」tab；
 * - 评论没有排序参数（GAP-13）⇒ 复用 `CommentSection`，不摆排序条（时间已由 GAP-12 补回，无需本页处理）；
 * - 没有转发数 ⇒ 分享只做「复制链接」，不显示数字（与播放页同一份 `shareCurrentPage`）。
 *
 * <h2>互动态</h2>
 * 点赞 / 收藏走 `useVideoInteractions`（登录闸门 / 乐观更新 / 回滚 / 权威值对齐）；
 * 匿名时 `interaction` 整个是 `null` ⇒ 按钮可点、点击先登录（不是假装"未点赞"）。
 */
const route = useRoute()

const id = computed(() => {
  const raw = Number(route.params.id)
  return Number.isInteger(raw) && raw > 0 ? raw : 0
})

const detail = ref<VideoDetailVM | null>(null)
const loading = ref(true)
const error = ref('')

const interactions = useVideoInteractions(detail)
const {
  busy,
  favoriteOpen,
  favoriteError,
  folders,
  foldersLoading,
  creatingFolder,
  pendingFolderId,
  refreshState,
  toggleLike,
  openFavorite,
  closeFavorite,
  toggleFolder,
  createFolder,
} = interactions

const authorInitial = computed(() => initialOf(detail.value?.authorName))

async function load(): Promise<void> {
  if (!id.value) {
    error.value = '地址里的内容 id 不合法'
    loading.value = false
    return
  }
  loading.value = true
  error.value = ''
  try {
    const data = await loadOpusPage(id.value)
    /*
     * 反向兜底：`/opus/{videoId}` 不是合法的图文详情（手动改 URL / 旧收藏夹链接）。
     * 视频的 canonical 落点是 `/video/{id}` —— 与播放页对图文的重定向互为镜像。
     */
    if (!data.isPost) {
      window.location.replace(videoHref(id.value))
      return
    }
    detail.value = data
    document.title = `${detail.value.title} - 哔哩哔哩`
  } catch (exception) {
    detail.value = null
    error.value =
      exception instanceof ApiError
        ? exception.status === 404
          ? '内容不存在或已被作者删除'
          : describeError(exception)
        : '加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

watch(id, load, { immediate: true })

/** 登录 / 退出后重读「我」的维度（匿名时 interaction 是 null，不是四个 false） */
watch(
  () => sessionState.status,
  (status, previous) => {
    if (status === 'authenticated' && previous === 'anonymous') {
      void refreshState()
    } else if (status === 'anonymous' && previous === 'authenticated' && detail.value) {
      detail.value.interaction = null
    }
  },
)

/** 评论区自己维护"含楼中楼"总数（发表 +1 / 删除 -N），操作栏的评论数跟着走 */
function onCommentCount(count: number): void {
  if (detail.value) detail.value.stats.comment = count
}
</script>

<template>
  <main class="opus-page">
    <div v-if="loading" class="opus-loading" aria-label="加载中">
      <div class="opus-loading__article">
        <div class="skeleton opus-loading__title" />
        <div class="skeleton opus-loading__author" />
        <div class="skeleton opus-loading__line" />
        <div class="skeleton opus-loading__line" />
        <div class="skeleton opus-loading__line opus-loading__line--short" />
      </div>
    </div>

    <EmptyState v-else-if="error" icon="article" title="打不开这个页面" :description="error">
      <button class="btn btn-blue" type="button" @click="load">重试</button>
      <a class="btn btn-ghost" href="/">回首页</a>
    </EmptyState>

    <div v-else-if="detail" class="opus-layout">
      <article class="opus-article">
        <h1 class="opus-title">{{ detail.title }}</h1>

        <header class="opus-author">
          <a
            class="opus-avatar"
            :href="detail.authorHref"
            target="_blank"
            rel="noopener"
            :title="`${detail.authorName} 的空间`"
            :aria-label="`${detail.authorName} 的空间`"
          >{{ authorInitial }}</a>
          <div class="opus-author__body">
            <a
              class="opus-author__name"
              :href="detail.authorHref"
              target="_blank"
              rel="noopener"
            >{{ detail.authorName }}</a>
            <p class="opus-author__meta">{{ detail.dateText }}</p>
          </div>
        </header>

        <p v-if="detail.description" class="opus-body">{{ detail.description }}</p>

        <OpusImageGallery v-if="detail.images.length" :images="detail.images" :title="detail.title" />

        <div class="opus-comments">
          <CommentSection
            id="comments"
            :content-id="detail.id"
            :comment-enabled="detail.commentEnabled"
            :initial-count="detail.stats.comment"
            @count-changed="onCommentCount"
          />
        </div>
      </article>

      <OpusActionRail
        :stats="{ like: detail.stats.like, favorite: detail.stats.favorite, comment: detail.stats.comment }"
        :interaction="detail.interaction"
        :busy="{ like: busy.like, favorite: busy.favorite }"
        @like="toggleLike"
        @favorite="openFavorite"
        @share="shareCurrentPage"
      />
    </div>

    <FavoriteDialog
      v-if="favoriteOpen"
      :folders="folders"
      :loading="foldersLoading"
      :error="favoriteError"
      :creating="creatingFolder"
      :pending-folder-id="pendingFolderId"
      @close="closeFavorite"
      @toggle="toggleFolder"
      @create="createFolder"
    />
  </main>
</template>

<style scoped>
/*
 * B 站图文详情（2026-10-27 线上 computed + 2560×1398=1707 CSS 实拍）：
 *   白底正文列 708px（内边距 56 ⇒ 正文 596）、右侧悬浮操作栏 58px、
 *   页面底色 #f1f2f3 + 顶部浅蓝主题（B 站是换季插画，本仓用渐变近似，不复制官方素材）。
 */
.opus-page {
  min-height: calc(100vh - var(--header-h));
  /* header 是 fixed；文章列从 header 下缘开始（B 站也是贴着顶栏） */
  padding: var(--header-h) 0 48px;
  background:
    radial-gradient(circle at 18% 12%, rgba(0, 174, 236, 0.12), transparent 38%),
    linear-gradient(180deg, #e8f6fd 0%, #f1f2f3 42%, #f1f2f3 100%);
}

.opus-layout {
  display: flex;
  align-items: flex-start;
  justify-content: center;
  gap: 14px;
  padding-top: 0;
}

.opus-article {
  width: var(--content-w-opus);
  flex: none;
  padding: 32px 56px 48px;
  background: var(--bg-0);
  box-shadow: var(--shadow-1);
}

.opus-title {
  margin: 0 0 16px;
  font-size: 19px;
  font-weight: 500;
  line-height: 32px;
  color: var(--text-1);
  word-break: break-word;
}

.opus-author {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 22px;
}

.opus-avatar {
  display: flex;
  width: 48px;
  height: 48px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-size: 19px;
  font-weight: 600;
  color: var(--text-inverse);
  background: linear-gradient(135deg, var(--brand-pink) 0%, var(--brand-blue) 100%);
}

.opus-author__body {
  min-width: 0;
}

.opus-author__name {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
  font-weight: 500;
  line-height: 22px;
  color: var(--text-2);
}

.opus-author__name:hover {
  color: var(--brand-blue);
}

.opus-author__meta {
  margin-top: 2px;
  font-size: 12px;
  line-height: 18px;
  color: var(--text-3);
}

.opus-body {
  margin-bottom: 20px;
  font-size: 15px;
  line-height: 28px;
  color: var(--text-1);
  white-space: pre-line;
  word-break: break-word;
}

.opus-comments {
  margin-top: 8px;
  padding-top: 20px;
  border-top: 1px solid var(--line-2);
}

/* CommentSection 自带上边距；外层已经有分隔线，别再叠一层 */
.opus-comments :deep(.comments) {
  margin-top: 0;
}

/* ---------- loading / 窄屏 ---------- */
.opus-loading {
  display: flex;
  justify-content: center;
}

.opus-loading__article {
  width: var(--content-w-opus);
  padding: 32px 56px 48px;
  background: var(--bg-0);
  box-shadow: var(--shadow-1);
}

.opus-loading__title {
  width: 62%;
  height: 26px;
}

.opus-loading__author {
  width: 180px;
  height: 20px;
  margin-top: 24px;
}

.opus-loading__line {
  height: 18px;
  margin-top: 14px;
}

.opus-loading__line--short {
  width: 60%;
}

@media (max-width: 900px) {
  .opus-layout {
    flex-direction: column;
    align-items: center;
    gap: 10px;
  }

  .opus-article,
  .opus-loading__article {
    width: min(var(--content-w-opus), calc(100vw - 32px));
    padding: 28px 24px 36px;
  }
}

@media (max-width: 600px) {
  .opus-title {
    font-size: 18px;
    line-height: 28px;
  }

  .opus-body {
    font-size: 14px;
    line-height: 26px;
  }
}
</style>
