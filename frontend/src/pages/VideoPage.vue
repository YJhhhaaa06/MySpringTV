<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import type { AuthorCardVM, VideoCardVM, VideoDetailVM } from '../types/ui'
import { loadAuthorCard, loadVideoPage } from '../api/endpoints'
import { ApiError, describeError } from '../api/http'
import { formatCount } from '../api/adapters/format'
import { sessionState } from '../auth/session'
import { showToast } from '../common/toast'
import { useVideoInteractions } from '../composables/useVideoInteractions'
import { usePlaybackProgress } from '../composables/usePlaybackProgress'
import { useWatchLaterAction } from '../composables/useWatchLaterAction'
import AppIcon from '../components/common/AppIcon.vue'
import EmptyState from '../components/common/EmptyState.vue'
import VideoCard from '../components/common/VideoCard.vue'
import VideoActionBar from '../components/video/VideoActionBar.vue'
import CoinDialog from '../components/video/CoinDialog.vue'
import CommentSection from '../components/video/CommentSection.vue'
import FavoriteDialog from '../components/video/FavoriteDialog.vue'

/**
 * 播放页：`GET /contents/{id}` +（并行）`GET /users/{id}` + `GET /contents/recommended`。
 *
 * <h2>版式按 B 站 2026 网页版（2026-10-25 实拍对照）</h2>
 * <pre>
 * 标题 → 播放/日期 → 播放器 → 弹幕位（永远占位）→ 操作栏（点赞/投币/收藏/分享）
 *      → 简介 → 评论区
 * 右栏：UP 主卡（关注）→ 相关推荐
 * </pre>
 * 视频区在左、侧栏 350px 固定 —— 与参考实现同构，`:hover` 态与图标朝向照抄。
 *
 * <h2>★ 弹幕是「永远占位」，不是「等后端」</h2>
 * 本仓没有弹幕域（无表 / 无端点 / 无 WS），按专项边界永远不做：只留一条说明，
 * **不摆输入框**（摆一个点不动的输入框比不摆更坏）。
 *
 * <p>互动状态机在 `composables/useVideoInteractions.ts`（登录闸门 / 乐观更新 / 回滚），
 * 本页只负责布局与数据装载。
 */
const route = useRoute()

const id = computed(() => {
  const raw = Number(route.params.id)
  return Number.isInteger(raw) && raw > 0 ? raw : 0
})

const detail = ref<VideoDetailVM | null>(null)
const author = ref<AuthorCardVM | null>(null)
const related = ref<VideoCardVM[]>([])
const loading = ref(true)
const error = ref('')
const videoEl = ref<HTMLVideoElement | null>(null)

const interactions = useVideoInteractions(detail, author)
/** 播放进度：续播定位 + 45s 上报 + 暂停/拖动/播完即时上报 + 退出 keepalive（契约见 player/） */
usePlaybackProgress(videoEl, detail)
/**
 * 稍后再看：**没有状态查询端点**（favorite.md §六 红线）⇒ 初始一律"未加入"；
 * 只有本次页面点过之后才知道它在池里、才能再点取消（刷新 / 换页即忘，与 B 站实测一致）。
 */
const {
  added: watchLaterAdded,
  busy: watchLaterBusy,
  toggle: toggleWatchLater,
} = useWatchLaterAction(computed(() => detail.value?.id ?? 0))
/** 解构是为了模板里能直接用（对象里的 ref 在模板中不会自动解包） */
const {
  busy,
  coinOpen,
  coinError,
  coinInsufficient,
  coinSubmitting,
  favoriteOpen,
  favoriteError,
  folders,
  foldersLoading,
  creatingFolder,
  pendingFolderId,
  refreshState,
  toggleLike,
  openCoin,
  closeCoin,
  giveCoins,
  signInForCoins,
  openFavorite,
  closeFavorite,
  toggleFolder,
  createFolder,
  toggleFollow,
} = interactions

async function load(): Promise<void> {
  if (!id.value) {
    error.value = '地址里的内容 id 不合法'
    loading.value = false
    return
  }
  loading.value = true
  error.value = ''
  try {
    const data = await loadVideoPage(id.value)
    detail.value = data.detail
    author.value = data.author
    related.value = data.related
  } catch (exception) {
    detail.value = null
    author.value = null
    related.value = []
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

/**
 * 登录 / 退出后要重新读「我」的那些维度：
 * 未登录时 `interaction` 整个是 `null`（本面不查），`author.followed` 同理 ⇒
 * 登录成功后不重读，按钮会停在"未点赞/未关注"的假象上。
 */
watch(
  () => sessionState.status,
  (status, previous) => {
    if (status === 'authenticated' && previous === 'anonymous') {
      void refreshState()
      void loadAuthor()
    } else if (status === 'anonymous' && previous === 'authenticated') {
      if (detail.value) detail.value.interaction = null
      if (author.value) author.value.followed = null
    }
  },
)

async function loadAuthor(): Promise<void> {
  const authorId = detail.value?.authorId ?? 0
  if (!authorId) return
  const card = await loadAuthorCard(authorId)
  if (card) author.value = card
}

/** 分享 = 复制当前链接（B 站也是复制，本仓没有"转发数"接口 ⇒ 不显示数字） */
async function share(): Promise<void> {
  const url = window.location.href
  try {
    await navigator.clipboard.writeText(url)
    showToast('链接已复制，发给朋友吧')
  } catch {
    showToast('复制失败，请手动复制地址栏', 'error')
  }
}

/** 自己不能给自己投币 / 关注（后端也会拒，但按钮不该摆出来） */
const isSelf = computed(
  () => sessionState.user !== null && detail.value !== null && sessionState.user.id === detail.value.authorId,
)

/** 评论区自己维护"含楼中楼"的总数（发表 +1 / 删除 -N），详情侧的计数跟着走 */
function onCommentCount(count: number): void {
  if (detail.value) detail.value.stats.comment = count
}
</script>

<template>
  <main class="video-page page-content page-main">
    <div v-if="loading" class="video-loading">
      <div class="skeleton video-loading__line" />
      <div class="skeleton video-loading__player" />
      <div class="skeleton video-loading__line video-loading__line--short" />
    </div>

    <EmptyState v-else-if="error" icon="tv" title="打不开这个页面" :description="error">
      <!-- 429 / 网络异常都是**瞬态**：给重试，别逼用户回首页重新进来（B 站同一位置也有重试） -->
      <button class="btn btn-blue" type="button" @click="load">重试</button>
      <a class="btn btn-ghost" href="/">回首页</a>
    </EmptyState>

    <div v-else-if="detail" class="video-layout">
      <div class="video-main">
        <h1 class="video-title">{{ detail.title }}</h1>

        <p class="video-meta">
          <AppIcon name="play" :size="16" />
          <span class="video-meta__count">{{ formatCount(detail.stats.play) }}</span>
          <span class="video-meta__dot">·</span>
          <span>{{ detail.dateText }}</span>
          <span class="video-meta__dot">·</span>
          <span>{{ detail.isPost ? '图文动态' : '视频' }}</span>
        </p>

        <div class="player">
          <video
            v-if="detail.videoUrl"
            ref="videoEl"
            class="player__video"
            :src="detail.videoUrl"
            :poster="detail.coverUrl ?? undefined"
            controls
            preload="metadata"
            playsinline
          />
          <img
            v-else-if="detail.images.length"
            class="player__image"
            :src="detail.images[0]"
            :alt="detail.title"
          />
          <span v-else class="player__empty">这个内容没有可播放的媒体</span>
        </div>

        <!-- 弹幕：永远占位（§五 边界：无弹幕域 ⇒ 不摆点不动的输入框） -->
        <div class="danmaku-bar" title="本仓没有弹幕域（无表 / 无端点 / 无 WS）">
          <AppIcon name="danmaku" :size="18" />
          <span>弹幕功能未适配：本仓没有弹幕域，按专项边界永远占位</span>
        </div>

        <VideoActionBar
          :stats="detail.stats"
          :interaction="detail.interaction"
          :busy="{ like: busy.like, coin: busy.coin, favorite: busy.favorite }"
          :watch-later-added="watchLaterAdded"
          :watch-later-busy="watchLaterBusy"
          :watch-later-available="!detail.isPost"
          @like="toggleLike"
          @coin="openCoin"
          @favorite="openFavorite"
          @share="share"
          @watch-later="toggleWatchLater"
        />

        <section class="video-desc">
          <p class="video-desc__text">{{ detail.description || '这个内容还没有简介' }}</p>
          <p v-if="detail.durationText" class="video-desc__meta">时长 {{ detail.durationText }}</p>
        </section>

        <section v-if="detail.isPost && detail.images.length > 1" class="image-list">
          <img
            v-for="(image, index) in detail.images.slice(1)"
            :key="image"
            class="image-list__item"
            :src="image"
            :alt="`${detail.title} 图 ${index + 2}`"
            loading="lazy"
          />
        </section>

        <CommentSection
          :content-id="detail.id"
          :comment-enabled="detail.commentEnabled"
          :initial-count="detail.stats.comment"
          @count-changed="onCommentCount"
        />
      </div>

      <aside class="video-side">
        <section v-if="author" class="author-card">
          <a class="author-card__avatar" :href="author.href" :title="`${author.name} 的空间`">
            {{ author.initial }}
          </a>
          <div class="author-card__body">
            <a class="author-card__name" :href="author.href">{{ author.name }}</a>
            <p class="author-card__meta">
              粉丝 {{ formatCount(author.followerCount) }} · 投稿 {{ formatCount(author.worksCount) }}
            </p>
          </div>
          <button
            v-if="!isSelf"
            class="author-card__follow"
            :class="{ 'is-followed': author.followed === true }"
            type="button"
            :disabled="busy.follow"
            @click="toggleFollow"
          >
            {{ author.followed === true ? '已关注' : '关注' }}
          </button>
        </section>

        <section v-if="related.length" class="related">
          <h2 class="related__title">相关推荐</h2>
          <div class="related__list">
            <VideoCard v-for="item in related" :key="item.id" :item="item" layout="row" />
          </div>
        </section>
      </aside>
    </div>

    <CoinDialog
      v-if="coinOpen"
      :balance="sessionState.user?.coin ?? 0"
      :error="coinError"
      :insufficient="coinInsufficient"
      :submitting="coinSubmitting"
      :already-coins="detail?.interaction?.coin ?? 0"
      @close="closeCoin"
      @confirm="giveCoins"
      @checkin="signInForCoins"
    />

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
.video-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 350px;
  gap: 32px;
  align-items: start;
}

.video-title {
  font-size: 20px;
  font-weight: 600;
  line-height: 28px;
  color: var(--text-1);
  word-break: break-word;
}

.video-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 8px 0 14px;
  font-size: 13px;
  color: var(--text-3);
}

.video-meta__count {
  font-variant-numeric: tabular-nums;
}

.video-meta__dot {
  color: var(--text-4);
}

.player {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  aspect-ratio: 16 / 9;
  overflow: hidden;
  border-radius: var(--radius);
  background: #000;
}

.player__video {
  width: 100%;
  height: 100%;
}

.player__image {
  width: 100%;
  height: 100%;
  object-fit: contain;
  background: #000;
}

.player__empty {
  font-size: 13px;
  color: var(--text-3);
}

.danmaku-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  padding: 0 14px;
  height: 38px;
  border-radius: var(--radius);
  font-size: 13px;
  color: var(--text-3);
  background: var(--bg-1);
}

.video-desc {
  margin-top: 16px;
  padding: 12px 14px;
  border-radius: var(--radius);
  background: var(--bg-1);
}

.video-desc__text {
  font-size: 14px;
  line-height: 22px;
  color: var(--text-2);
  white-space: pre-wrap;
  word-break: break-word;
}

.video-desc__meta {
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-3);
}

.image-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 10px;
  margin-top: 16px;
}

.image-list__item {
  width: 100%;
  border-radius: var(--radius-sm);
}

.video-side {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.author-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  border-radius: var(--radius);
  background: var(--bg-1);
}

.author-card__avatar {
  display: flex;
  width: 48px;
  height: 48px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-size: 20px;
  font-weight: 600;
  color: var(--text-inverse);
  background: linear-gradient(135deg, var(--brand-pink) 0%, var(--brand-blue) 100%);
}

.author-card__body {
  min-width: 0;
  flex: 1;
}

.author-card__name {
  display: block;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.author-card__meta {
  margin-top: 2px;
  font-size: 12px;
  color: var(--text-3);
}

.author-card__follow {
  flex: none;
  height: 30px;
  padding: 0 14px;
  border-radius: var(--radius);
  font-size: 13px;
  color: var(--text-inverse);
  background: var(--brand-pink);
}

.author-card__follow:hover:not(:disabled) {
  background: var(--brand-pink-dark);
}

.author-card__follow.is-followed {
  color: var(--text-2);
  background: var(--bg-2);
}

.author-card__follow:disabled {
  opacity: 0.6;
}

.related__title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-1);
}

.related__list {
  display: flex;
  flex-direction: column;
  gap: 14px;
  margin-top: 12px;
}

.video-loading__player {
  margin-top: 16px;
  aspect-ratio: 16 / 9;
  border-radius: var(--radius);
}

.video-loading__line {
  height: 20px;
  margin-top: 16px;
}

.video-loading__line--short {
  width: 40%;
}

@media (max-width: 1100px) {
  .video-layout {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
