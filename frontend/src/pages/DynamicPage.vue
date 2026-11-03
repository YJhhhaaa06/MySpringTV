<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { DynamicCardVM, RelationPageVM, SpaceHeaderVM } from '../types/ui'
import { FEED_PAGE_SIZE, dynamicQuery, loadFeed } from '../api/feed'
import { loadFollowing } from '../api/relations'
import { loadProfileHeader, loadSpace } from '../api/profile'
import { ApiError, describeError } from '../api/http'
import { formatCount } from '../api/adapters/format'
import { isAuthenticated, restoreSession, sessionState } from '../auth/session'
import { requireLogin } from '../auth/loginPrompt'
import { useDynamicActions } from '../composables/useDynamicActions'
import { useUserAvatars } from '../composables/useUserAvatars'
import AppIcon from '../components/common/AppIcon.vue'
import EmptyState from '../components/common/EmptyState.vue'
import Pagination from '../components/common/Pagination.vue'
import UserAvatar from '../components/common/UserAvatar.vue'
import DynamicCard from '../components/feed/DynamicCard.vue'

/**
 * 关注动态流（`/dynamic`，B 站 `t.bilibili.com` 的对应页）。
 *
 * <h2>版式（B 站 2026-10 实拍 2560×1398 = 1707 CSS，`.screenshots/bilibili-2026-10-26/dynamic-feed-all`）</h2>
 * 三栏：**左**我的资料卡（关注 / 粉丝 / 投稿）· **中**动态卡片流 · **右**「我关注的人」。
 * 内容宽 1336（78vw，比首页 1408 窄）、两栏 260 / 320。
 *
 * <h2>★ 这一页的 GAP 台账（不渲染 = 后端没有，不是漏做）</h2>
 * - **发布框**：B 站可以在动态页直接发文字/图片。本仓没有「动态」域（`POST /contents/posts`
 *   是图文**投稿**，不是动态；投稿页按用户拍板暂缓）⇒ 不渲染。见 `architecture/frontend/前端.md` §六；
 * - **tabs「全部 / 视频投稿 / 追番追剧 / 专栏」**：`GET /feed` 只有 `page/pageSize`，没有 `type`
 *   筛选（GAP-23）⇒ 不渲染；「追番追剧」更没有对应域；
 * - **转发**：没有转发域（无表 / 无端点）⇒ 操作栏只有点赞与评论；
 * - **热搜 / 社区中心**（B 站右栏）：没有热搜/运营端点（GAP-24）⇒ 右栏换成**我关注的人**
 *   （真接口 `/users/{me}/following`，顺带把新交付的「关注」列表页接进来）；
 * - **动态详情页**：本仓没有独立的动态文档 ⇒ 卡片直接跳内容页（图文 `/opus`、视频 `/video`）；
 * - **按作者筛选（`R-31`）**：`/dynamic?author={id}` 走 `GET /users/{id}` 的 `contentPage`
 *   （= 该作者的动态时间线，与空间「动态」tab 同源），**不是** `/feed` 加参。
 *
 * <h2>两种模式的 total 口径不同（别当 bug）</h2>
 * 「全部动态」的 `total` = 读侧归并窗口条数（≤300，深翻空页）；
 * 作者模式 = `contentPage.total`（TA 的全部投稿）。页头各自写清「全部动态 / TA 的动态」。
 *
 * <h2>页面级身份态</h2>
 * `/feed` 是类级 `@RequiresLogin` ⇒ 整页都属于"我"，匿名渲染「登录后查看」+ 登录按钮
 * （与历史 / 收藏页同一套口径，见 `architecture/frontend/前端.md` §五）。
 *
 * <h2>点赞：写后对齐回 like-count</h2>
 * 卡片先乐观加减，成功后回读 `GET /contents/{id}/like-count` 覆盖成服务端权威值
 * （一个数字 vs 重拉整页 12 张卡 —— 见 §八「写后对齐」）。
 */
const route = useRoute()
const router = useRouter()

const PREVIEW_LIMIT = 8

/** 右栏只摆 8 个；头像行多取一些（≤24，横向滚动），不在这里做无限加载 */
const FOLLOWING_FETCH = 24

const sessionReady = ref(false)
const loggedIn = computed(() => sessionState.status === 'authenticated')
const me = computed(() => sessionState.user)

const page = computed(() => {
  const value = Number(route.query.page)
  return Number.isInteger(value) && value > 0 ? value : 1
})

/**
 * 作者模式（`R-31`）：`?author={id}` ⇒ 只看 TA 的动态（`contentPage`）。
 * 非法 / 缺省 = 全部动态（不把脏 query 画成错误页）。
 */
const authorId = computed(() => {
  const value = Number(route.query.author)
  return Number.isInteger(value) && value > 0 ? value : null
})

const feed = ref<{ items: DynamicCardVM[]; page: number; total: number; totalPages: number } | null>(null)
const loading = ref(false)
const error = ref('')
/** total > 0 但本页空 = 页码越界（不是"没有动态"；深链 / 旧书签会撞到） */
const outOfRange = computed(
  () => Boolean(feed.value && feed.value.items.length === 0 && feed.value.total > 0),
)

const profile = ref<SpaceHeaderVM | null>(null)
const following = ref<RelationPageVM | null>(null)
/** 作者模式的作者头（同一个 `GET /users/{id}` 顺带返回，不额外请求） */
const authorHeader = ref<SpaceHeaderVM | null>(null)

/**
 * 作者快照面的头像（`R-27`）：动态卡 / 右栏关注列表只拿得到 userId，
 * 由批量端点按页实时补 URL；缺 id 时 UserAvatar 按 userId 回落默认。
 */
const feedAvatarIds = computed(() => (feed.value?.items ?? []).map((item) => item.authorId))
const { avatars: feedAvatars } = useUserAvatars(feedAvatarIds)
const followingAvatarIds = computed(() => (following.value?.items ?? []).map((user) => user.id))
const { avatars: followingAvatars } = useUserAvatars(followingAvatarIds)
/** 右栏只摆前 8 个；头像行用完整的一页（≤24） */
const followingPreview = computed(() => (following.value?.items ?? []).slice(0, PREVIEW_LIMIT))

/**
 * 点赞 + 内联评论（`R-31`）：抽成 composable 与空间动态 tab 共用。
 * 评论是**页面级单值**（同时只开一个），卡片受控显示。
 */
const {
  likeBusy,
  toggleLike,
  openCommentsId,
  toggleComments,
  onCommentCountChanged,
  closeComments,
} = useDynamicActions()

/**
 * 竞态守卫：切作者 / 翻页时只允许**最新一次**请求写 `feed` / `authorHeader`。
 * 没有它，A 的慢响应会在 URL 已经变成 B 之后覆盖列表（页头 B、内容 A）。
 * `loadedAuthorId` 另做一件事：**切作者时先清空**，不让上一位作者的卡片短暂挂在新标题下；
 * 同一作者翻页不清空（避免白屏闪）。
 */
let feedRequestId = 0
let loadedAuthorId: number | null = null

onMounted(async () => {
  await restoreSession()
  sessionReady.value = true
})

watch(
  () => sessionState.status,
  (status) => {
    if (status === 'authenticated') {
      void loadFeedPage()
      void loadSidebars()
      return
    }
    if (status === 'anonymous') {
      feed.value = null
      profile.value = null
      following.value = null
    }
  },
  { immediate: true },
)

watch([page, authorId], () => {
  if (isAuthenticated()) void loadFeedPage()
})

async function loadFeedPage(): Promise<void> {
  if (!isAuthenticated()) return
  const requestId = ++feedRequestId
  closeComments() // 换页 / 重载后旧卡片的评论面板不再有意义
  // 切作者（含从全部切到作者）先清空，别让上一位作者的卡短暂挂在新标题下；同作者翻页不清
  if (authorId.value !== loadedAuthorId) {
    feed.value = null
    authorHeader.value = null
    loadedAuthorId = authorId.value
  }
  loading.value = true
  error.value = ''
  try {
    if (authorId.value) {
      // 作者模式 = 该作者的**全部投稿时间线**（与空间「动态」tab 同源；不是 /feed 加参）
      const data = await loadSpace(authorId.value, page.value, FEED_PAGE_SIZE)
      if (requestId !== feedRequestId) return
      feed.value = data.timeline
      authorHeader.value = data.header
    } else {
      const data = await loadFeed(page.value, FEED_PAGE_SIZE)
      if (requestId !== feedRequestId) return
      feed.value = data
      authorHeader.value = null
    }
  } catch (exception) {
    if (requestId !== feedRequestId) return
    feed.value = null
    authorHeader.value = null
    error.value =
      exception instanceof ApiError && exception.status === 404
        ? '这个用户不存在'
        : describeError(exception)
  } finally {
    if (requestId === feedRequestId) loading.value = false
  }
}

/**
 * 两栏资料：**失败互不阻塞**（右栏挂了不该让左卡空着，反之亦然）。
 * 主列表已经在上面单独装载 —— 侧栏是加分项，不是页面成立的前提。
 */
async function loadSidebars(): Promise<void> {
  const userId = me.value?.id
  if (!userId) return
  const [profileResult, followingResult] = await Promise.allSettled([
    loadProfileHeader(userId),
    loadFollowing(userId, 1, FOLLOWING_FETCH),
  ])
  if (profileResult.status === 'fulfilled') profile.value = profileResult.value
  if (followingResult.status === 'fulfilled') following.value = followingResult.value
}

async function signIn(): Promise<void> {
  await requireLogin()
}

/** 翻页必须**保留 author**：只写 page 会静默掉回「全部动态」（分页口径就坏了） */
function goPage(nextPage: number): void {
  void router.push({ query: dynamicQuery(authorId.value, nextPage) })
}

/** 切到某个作者的动态（重置页码） */
function selectAuthor(userId: number): void {
  void router.push({ query: { author: String(userId) } })
}

/** 回到全部动态（重置作者与页码） */
function showAll(): void {
  void router.push({ query: {} })
}

function followingHref(userId: number): string {
  return `/space/${userId}?tab=following`
}
</script>

<template>
  <main class="dynamic-page">
    <div class="page-content">
      <p v-if="!sessionReady" class="dyn-state">正在恢复登录态…</p>

      <EmptyState
        v-else-if="!loggedIn"
        icon="dynamic"
        :title="authorId ? '登录后查看 TA 的动态' : '登录后查看关注动态'"
        :description="
          authorId
            ? '作者模式沿用动态页的登录门；TA 的空间动态是公开的，不登录也可以直接去空间看。'
            : '动态流只属于账号：登录后按时间倒序显示你关注的 UP 主的投稿与图文（和 B 站一样，这个列表不公开）。'
        "
      >
        <button class="btn btn-blue" type="button" @click="signIn">登录</button>
        <a
          v-if="authorId"
          class="btn btn-ghost"
          :href="`/space/${authorId}?tab=dynamic`"
          target="_blank"
          rel="noopener"
        >去 TA 的空间看动态</a>
      </EmptyState>

      <div v-else class="dyn-layout">
        <!-- 左：我的资料卡（真计数；没有「动态」数是因为我们没有「我发过多少条动态」这个口径） -->
        <aside class="dyn-side">
          <section class="dyn-profile">
            <div class="dyn-profile__head">
              <UserAvatar
                :avatar-url="me?.avatarUrl ?? null"
                :user-id="me?.id ?? null"
                :initial="me?.initial ?? '?'"
                :size="58"
              />
              <div class="dyn-profile__ident">
                <p class="dyn-profile__name">{{ me?.name ?? '加载中…' }}</p>
                <p class="dyn-profile__coin">硬币 {{ me?.coin ?? 0 }}</p>
              </div>
            </div>
            <dl class="dyn-profile__counts">
              <div class="dyn-profile__count">
                <dt>关注</dt>
                <dd>{{ formatCount(profile?.followingCount) }}</dd>
              </div>
              <div class="dyn-profile__count">
                <dt>粉丝</dt>
                <dd>{{ formatCount(profile?.followerCount) }}</dd>
              </div>
              <div class="dyn-profile__count">
                <dt>投稿</dt>
                <dd>{{ formatCount(profile?.worksCount) }}</dd>
              </div>
            </dl>
            <a v-if="me" class="dyn-profile__space" :href="me.spaceHref" target="_blank" rel="noopener">
              进入我的空间
            </a>
          </section>
        </aside>

        <!-- 中：动态流 -->
        <section class="dyn-center">
          <!-- 头像行（R-31）：全部 + 我关注的人（≤24，横向滚动）；点谁看谁 -->
          <nav v-if="following?.items.length" class="dyn-filter" aria-label="按 UP 主筛选动态">
            <button class="dyn-filter__item" :class="{ 'is-active': !authorId }" type="button" @click="showAll">
              <span class="dyn-filter__all"><AppIcon name="dynamic" :size="24" /></span>
              <span class="dyn-filter__name">全部动态</span>
            </button>
            <button
              v-for="user in following.items"
              :key="user.id"
              class="dyn-filter__item"
              :class="{ 'is-active': authorId === user.id }"
              type="button"
              :title="`只看 ${user.name} 的动态`"
              @click="selectAuthor(user.id)"
            >
              <span class="dyn-filter__avatar">
                <UserAvatar
                  :avatar-url="followingAvatars.get(user.id) ?? null"
                  :user-id="user.id"
                  :initial="user.initial"
                  :size="52"
                />
              </span>
              <span class="dyn-filter__name">{{ user.name }}</span>
            </button>
          </nav>

          <header class="dyn-head">
            <h1 class="dyn-head__title">
              {{ authorId ? `${authorHeader?.name ?? 'TA'} 的动态` : '全部动态' }}
            </h1>
            <span v-if="feed?.total" class="dyn-head__meta">共 {{ feed.total }} 条</span>
            <button v-if="authorId" class="dyn-head__back" type="button" @click="showAll">返回全部</button>
          </header>

          <p v-if="loading && !feed" class="dyn-state">加载中…</p>

          <EmptyState v-else-if="error" icon="dynamic" title="动态加载失败" :description="error">
            <button class="btn btn-blue" type="button" @click="loadFeedPage">重试</button>
          </EmptyState>

          <template v-else-if="feed">
            <EmptyState
              v-if="!feed.items.length"
              icon="dynamic"
              :title="outOfRange ? '这一页没有内容' : authorId ? 'TA 还没有发布过内容' : '还没有动态'"
              :description="
                outOfRange
                  ? '页码超出了当前列表的范围（深链或旧书签），回第 1 页继续。'
                  : authorId
                    ? '这个 UP 主的投稿与图文会按时间倒序出现在这里。'
                    : '关注一些 UP 主之后，他们的投稿与图文会按时间倒序出现在这里。'
              "
            >
              <button v-if="outOfRange" class="btn btn-blue" type="button" @click="goPage(1)">回第 1 页</button>
              <button v-else-if="authorId" class="btn btn-blue" type="button" @click="showAll">看全部动态</button>
              <a v-else class="btn btn-blue" href="/">去首页看看</a>
            </EmptyState>

            <template v-else>
              <div class="dyn-list">
                <DynamicCard
                  v-for="card in feed.items"
                  :key="card.id"
                  :item="card"
                  :busy="likeBusy.has(card.id)"
                  :avatar-url="feedAvatars.get(card.authorId) ?? null"
                  :comments-open="openCommentsId === card.id"
                  @toggle-like="toggleLike(card)"
                  @toggle-comments="toggleComments(card)"
                  @comment-count-changed="onCommentCountChanged(card, $event)"
                />
              </div>
              <Pagination :page="feed.page" :total-pages="feed.totalPages" @update:page="goPage" />
            </template>
          </template>
        </section>

        <!-- 右：我关注的人（B 站这里是热搜 + 社区中心；本仓没有那些端点，用真数据填位） -->
        <aside class="dyn-side">
          <section class="dyn-following">
            <header class="dyn-following__head">
              <h2 class="dyn-following__title">我关注的人</h2>
              <a
                v-if="me"
                class="dyn-following__more"
                :href="followingHref(me.id)"
                target="_blank"
                rel="noopener"
              >
                查看全部
                <AppIcon name="chevron-right" :size="14" />
              </a>
            </header>

            <p v-if="!following" class="dyn-following__state">加载中…</p>
            <p v-else-if="!following.items.length" class="dyn-following__state">
              还没有关注任何人。去首页或播放页点「关注」，他们的投稿就会出现在左边。
            </p>
            <ul v-else class="dyn-following__list">
              <li v-for="user in followingPreview" :key="user.id">
                <a
                  class="dyn-following__user"
                  :href="`/space/${user.id}`"
                  target="_blank"
                  rel="noopener"
                  :title="`${user.name} 的空间`"
                >
                  <UserAvatar
                    :avatar-url="followingAvatars.get(user.id) ?? null"
                    :user-id="user.id"
                    :initial="user.initial"
                    :size="36"
                  />
                  <span class="dyn-following__name">{{ user.name }}</span>
                </a>
              </li>
            </ul>
            <p v-if="following?.total" class="dyn-following__total">共关注 {{ following.total }} 人</p>
          </section>
        </aside>
      </div>
    </div>
  </main>
</template>

<style scoped>
.dynamic-page {
  min-height: 100vh;
  padding-top: var(--header-h);
  padding-bottom: 48px;
  /* B 站动态页是浅底 + 白卡；宽度比首页窄（1707 CSS 实测 1336 = 78vw） */
  --content-w: var(--content-w-dynamic);
  background: var(--bg-1);
}

.dyn-layout {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr) 320px;
  gap: 20px;
  align-items: start;
  padding-top: 20px;
}

.dyn-side {
  position: sticky;
  top: calc(var(--header-h) + 20px);
}

.dyn-state {
  padding: 64px 0;
  font-size: 13px;
  color: var(--text-3);
  text-align: center;
}

/* ── 左：资料卡 ─────────────────────────────────────────────── */
.dyn-profile {
  padding: 20px 16px 16px;
  border-radius: var(--radius-lg);
  background: var(--bg-0);
  box-shadow: var(--shadow-1);
}

.dyn-profile__head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.dyn-profile__ident {
  min-width: 0;
}

.dyn-profile__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 17px;
  font-weight: 600;
  color: var(--text-1);
}

.dyn-profile__coin {
  margin-top: 3px;
  font-size: 12px;
  color: var(--text-3);
}

.dyn-profile__counts {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  margin: 16px 0 0;
  padding: 14px 0 4px;
  border-top: 1px solid var(--line-2);
}

.dyn-profile__count {
  text-align: center;
}

.dyn-profile__count dt {
  font-size: 12px;
  color: var(--text-3);
}

.dyn-profile__count dd {
  margin: 4px 0 0;
  font-size: 17px;
  font-weight: 600;
  color: var(--text-1);
  font-variant-numeric: tabular-nums;
}

.dyn-profile__space {
  display: flex;
  height: 32px;
  margin-top: 14px;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius);
  font-size: 13px;
  color: var(--text-2);
  background: var(--bg-1);
}

.dyn-profile__space:hover {
  color: var(--brand-blue);
  background: var(--brand-blue-bg);
}

/* ── 中：动态流 ─────────────────────────────────────────────── */
.dyn-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 4px 4px 14px;
}

.dyn-head__title {
  font-size: 20px;
  font-weight: 600;
  color: var(--text-1);
}

.dyn-head__meta {
  font-size: 12px;
  color: var(--text-3);
}

.dyn-head__back {
  margin-left: auto;
  padding: 0;
  border: 0;
  background: none;
  font-size: 13px;
  color: var(--brand-blue);
  cursor: pointer;
}

.dyn-head__back:hover {
  text-decoration: underline;
}

/* ── 中：头像筛选行（R-31） ─────────────────────────────────── */
.dyn-filter {
  display: flex;
  gap: 10px;
  overflow-x: auto;
  padding: 4px 4px 12px;
  scrollbar-width: none;
}

.dyn-filter::-webkit-scrollbar {
  display: none;
}

.dyn-filter__item {
  display: flex;
  flex: none;
  width: 64px;
  flex-direction: column;
  align-items: center;
  gap: 5px;
  padding: 0;
  border: 0;
  background: none;
  cursor: pointer;
}

.dyn-filter__all,
.dyn-filter__avatar {
  display: flex;
  width: 52px;
  height: 52px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: var(--text-2);
  background: var(--bg-1);
  box-shadow: 0 0 0 2px transparent;
  transition: box-shadow 0.15s ease, color 0.15s ease;
}

.dyn-filter__avatar {
  background: none;
}

.dyn-filter__item:hover .dyn-filter__all,
.dyn-filter__item:hover .dyn-filter__avatar,
.dyn-filter__item.is-active .dyn-filter__all,
.dyn-filter__item.is-active .dyn-filter__avatar {
  box-shadow: 0 0 0 2px var(--brand-blue);
}

.dyn-filter__item.is-active .dyn-filter__all {
  color: var(--brand-blue);
}

.dyn-filter__name {
  max-width: 64px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  line-height: 16px;
  color: var(--text-3);
}

.dyn-filter__item.is-active .dyn-filter__name {
  color: var(--brand-blue);
  font-weight: 600;
}

.dyn-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* ── 右：我关注的人 ─────────────────────────────────────────── */
.dyn-following {
  padding: 16px;
  border-radius: var(--radius-lg);
  background: var(--bg-0);
  box-shadow: var(--shadow-1);
}

.dyn-following__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.dyn-following__title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
}

.dyn-following__more {
  display: inline-flex;
  align-items: center;
  font-size: 12px;
  color: var(--text-3);
}

.dyn-following__more:hover {
  color: var(--brand-blue);
}

.dyn-following__state {
  padding: 18px 0 6px;
  font-size: 12px;
  line-height: 19px;
  color: var(--text-3);
}

.dyn-following__list {
  margin-top: 8px;
}

.dyn-following__user {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 4px;
  border-radius: var(--radius);
}

.dyn-following__user:hover {
  background: var(--bg-1);
}

.dyn-following__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  color: var(--text-2);
}

.dyn-following__total {
  margin-top: 10px;
  padding-top: 12px;
  border-top: 1px solid var(--line-2);
  font-size: 12px;
  color: var(--text-3);
}

@media (max-width: 1240px) {
  .dyn-layout {
    grid-template-columns: 240px minmax(0, 1fr);
  }

  .dyn-layout > .dyn-side:last-child {
    display: none;
  }
}

@media (max-width: 900px) {
  .dyn-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .dyn-layout > .dyn-side {
    position: static;
    display: none;
  }
}
</style>
