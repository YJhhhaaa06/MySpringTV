<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { RelationPageVM, SpaceHeaderVM } from '../types/ui'
import { dynamicQuery } from '../api/feed'
import { loadFollowing } from '../api/relations'
import { loadProfileHeader } from '../api/profile'
import { formatCount } from '../api/adapters/format'
import { showToast } from '../common/toast'
import { isAuthenticated, restoreSession, sessionState } from '../auth/session'
import { requireLogin } from '../auth/loginPrompt'
import { useDynamicActions } from '../composables/useDynamicActions'
import { useDynamicFeed } from '../composables/useDynamicFeed'
import { useNewDynamics } from '../composables/useNewDynamics'
import { useUserAvatars } from '../composables/useUserAvatars'
import type { DynamicSource } from '../feed/dynamicPaging'
import AppIcon from '../components/common/AppIcon.vue'
import BackToTop from '../components/common/BackToTop.vue'
import EmptyState from '../components/common/EmptyState.vue'
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
 * <h2>★ 列表口径（`R-32`）：无限滚动，不再翻页</h2>
 * 全部动态走 `/feed`：首屏记下第一条 id 当**快照锚点**，第 2 页起带 `beforeContentId` ⇒
 * 新内容插到头部也不会让 offset 漂（不重复、不漏条），`total` 只算锚点内（有界窗口 ≤300）。
 * 作者模式走 `contentPage`：没有锚点，按 id 去重（作者翻页期间发新内容只会造成重复）。
 * 两种源共用 `feed/listState.ts` 的续页状态机，令牌 = 下一页页码；`page >= totalPages` 才到底。
 * 旧 `?page=` 链接不再读取（落到第 1 页）；作者模式仍保留 `?author=`。
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

/** 离底还有 800px 就开始拉下一页：滚到底时下一页通常已经在路上（与首页同口径） */
const PREFETCH_MARGIN = 800

const sessionReady = ref(false)
const loggedIn = computed(() => sessionState.status === 'authenticated')
const me = computed(() => sessionState.user)

/**
 * 作者模式（`R-31`）：`?author={id}` ⇒ 只看 TA 的动态（`contentPage`）。
 * 非法 / 缺省 = 全部动态（不把脏 query 画成错误页）。
 */
const authorId = computed(() => {
  const value = Number(route.query.author)
  return Number.isInteger(value) && value > 0 ? value : null
})

/** 数据源：全部动态 / 某作者；切源由 `useDynamicFeed` 负责清空。 */
const source = computed<DynamicSource>(() =>
  authorId.value ? { mode: 'author', authorId: authorId.value } : { mode: 'all' },
)

/** 列表行为机：请求 + 续页状态迁移 + 两种源；滚动 / 哨兵留在本页。 */
const feed = useDynamicFeed(() => source.value)
const { state, total, authorHeader, view, errorText } = feed

/**
 * 「有新动态」轮询（`R-32`）：服务端不推送 ⇒ 1 分钟问一次"头部有没有比当前第一条更新"。
 * **只在全部模式**跑（作者模式没有"新动态"语义），列表空 / 已立起横幅时不再问。
 */
const newDynamics = useNewDynamics()
const { hasNew } = newDynamics

const profile = ref<SpaceHeaderVM | null>(null)
const following = ref<RelationPageVM | null>(null)

/**
 * 作者快照面的头像（`R-27`）：动态卡 / 右栏关注列表只拿得到 userId，
 * 由批量端点按页实时补 URL；缺 id 时 UserAvatar 按 userId 回落默认。
 */
const feedAvatarIds = computed(() => state.value.items.map((item) => item.authorId))
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

// ── 无限滚动：哨兵 + 连续补页 ─────────────────────────────────────

const sentinel = ref<HTMLElement | null>(null)
let sentinelObserver: IntersectionObserver | null = null

// 哨兵在登录门之后才渲染（`sessionReady` 切换）⇒ 不能只在 onMounted 里 observe 一次：
// 那一刻 DOM 还没 flush，sentinel 仍是 null（真浏览器抓到的：滚到底永远不加载第二页）。
watch(sentinel, (element) => {
  sentinelObserver?.disconnect()
  if (element) sentinelObserver?.observe(element)
})

/**
 * 内联横幅的可见性：横幅滚出视口时才出右下「有新动态」胶囊（两者互斥，照首页 `R-29` 口径）。
 * 横幅是 `v-if` 渲染的，ref 出现 / 消失都要重新 observe。
 */
const banner = ref<HTMLElement | null>(null)
const bannerVisible = ref(false)
let bannerObserver: IntersectionObserver | null = null

watch(banner, (element) => {
  bannerObserver?.disconnect()
  bannerObserver = null
  if (!element) {
    bannerVisible.value = false
    return
  }
  // 先当可见（横幅刚立起、IO 回调还没来），避免浮层闪一下
  bannerVisible.value = true
  bannerObserver = new IntersectionObserver((entries) => {
    bannerVisible.value = entries[0]?.isIntersecting ?? false
  })
  bannerObserver.observe(element)
})

function inPrefetchRange(): boolean {
  const element = sentinel.value
  // 卸载后元素已脱离文档，rect 恒 0 ⇒ 不加这一句会让 while 继续把整池拉完
  if (!element?.isConnected) return false
  return element.getBoundingClientRect().top <= window.innerHeight + PREFETCH_MARGIN
}

/**
 * 连续补页：短页 / 大视口下哨兵可能仍留在触发区，而 IntersectionObserver 不会因为
 * "元素还在视口里"再报一次。补到哨兵被推出触发区、到底、或失败为止。
 * 死循环由状态机兜住（空页 + 令牌非空 ⇒ error）。
 */
async function loadMore(): Promise<void> {
  while (feed.shouldLoadMore() && inPrefetchRange()) {
    await feed.loadMore()
    await nextTick()
  }
}

/** 首屏 / 刷新 / 重试：整批替换；评论面板随旧卡片一起作废。 */
async function refresh(): Promise<void> {
  closeComments()
  // 切到作者模式时立刻停轮询：不能让"全部动态"的新内容提示挂到 TA 的时间线上
  if (source.value.mode !== 'all') newDynamics.stop()
  const result = await feed.refresh()
  await nextTick()
  if (result.status === 'ok') {
    if (source.value.mode === 'all' && state.value.items.length) {
      // 新列表头 = 下一次轮询的比较基准；同时把「有新动态」横幅收掉
      newDynamics.watchHead(state.value.items[0].id)
    } else {
      newDynamics.stop()
    }
  } else if (result.status === 'failed' && state.value.items.length > 0) {
    // 有旧内容时状态机会保留列表并清掉 error ⇒ 用 toast 说明，别让用户以为刷新成功了
    showToast(result.message, 'error')
  }
  // superseded / busy：什么都不做 —— 胜出的那次刷新会自己接上轮询，这里停会把它的轮询关掉
  if (feed.shouldLoadMore() && inPrefetchRange()) void loadMore()
}

/** 横幅 / 浮层胶囊：先回顶再刷新（列表整批替换，停在深处只会被浏览器夹到底部） */
function onNewClick(): void {
  window.scrollTo({ top: 0, behavior: 'auto' })
  void refresh()
}

/** 标签页回到前台：立刻补一次轮询（不重置已立起的横幅） */
function onVisibilityChange(): void {
  newDynamics.onVisible()
}

/** 行内重试 + 补一次预取：失败时哨兵可能仍在触发区，IO 不会因为"还在视口里"再报 */
async function retryMore(): Promise<void> {
  await feed.retry()
  await nextTick()
  if (feed.shouldLoadMore() && inPrefetchRange()) void loadMore()
}

onMounted(async () => {
  // 先建 observer：哨兵要等 `sessionReady` 之后才渲染，watch(sentinel) 才能在它出现时接上
  sentinelObserver = new IntersectionObserver(
    (entries) => {
      if (entries.some((entry) => entry.isIntersecting)) void loadMore()
    },
    { rootMargin: `0px 0px ${PREFETCH_MARGIN}px 0px` },
  )

  await restoreSession()
  sessionReady.value = true

  document.addEventListener('visibilitychange', onVisibilityChange)
})

onBeforeUnmount(() => {
  sentinelObserver?.disconnect()
  bannerObserver?.disconnect()
  document.removeEventListener('visibilitychange', onVisibilityChange)
  newDynamics.stop()
})

watch(
  () => sessionState.status,
  (status) => {
    if (status === 'authenticated') {
      void refresh()
      void loadSidebars()
      return
    }
    if (status === 'anonymous') {
      feed.reset()
      newDynamics.stop()
      profile.value = null
      following.value = null
    }
  },
  { immediate: true },
)

/** 切作者 = 换数据源：整批清空重来（路由的 scrollBehavior 会顺带回顶）。 */
watch(authorId, () => {
  if (isAuthenticated()) void refresh()
})

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

/** 切到某个作者的动态（`?author=` 保留在 URL 里，可分享 / 刷新不丢） */
function selectAuthor(userId: number): void {
  void router.push({ query: dynamicQuery(userId) })
}

/** 回到全部动态（重置作者筛选） */
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
            <span v-if="total" class="dyn-head__meta">共 {{ total }} 条</span>
            <div class="dyn-head__actions">
              <button v-if="authorId" class="dyn-head__back" type="button" @click="showAll">返回全部</button>
              <button
                class="dyn-head__refresh"
                type="button"
                :disabled="view === 'refreshing' || view === 'initial'"
                title="刷新动态"
                @click="refresh"
              >
                <AppIcon name="refresh" :size="15" :class="{ 'is-spinning': view === 'refreshing' }" />
                <span>刷新</span>
              </button>
            </div>
          </header>

          <!-- 有新动态（R-32）：只属于"全部动态"；服务端不推送 ⇒ 1 分钟轮询；点击 = 回顶 + 重拉第 1 页 -->
          <button
            v-if="loggedIn && !authorId && hasNew"
            ref="banner"
            class="dyn-new"
            type="button"
            :disabled="view === 'refreshing'"
            aria-live="polite"
            @click="onNewClick"
          >
            <AppIcon name="refresh" :size="16" />
            <span>有新动态，点击查看</span>
          </button>

          <!-- 首屏骨架：数据未到（不是"没有动态"）；与首页 / 空间同口径 -->
          <div v-if="view === 'initial'" class="dyn-skeleton">
            <div v-for="n in 3" :key="n" class="skeleton dyn-skeleton__card" />
          </div>

          <EmptyState v-else-if="view === 'error'" icon="dynamic" title="动态加载失败" :description="errorText">
            <button class="btn btn-blue" type="button" @click="refresh">重试</button>
          </EmptyState>

          <template v-else>
            <EmptyState
              v-if="view === 'empty'"
              icon="dynamic"
              :title="authorId ? 'TA 还没有发布过内容' : '还没有动态'"
              :description="
                authorId
                  ? '这个 UP 主的投稿与图文会按时间倒序出现在这里。'
                  : '关注一些 UP 主之后，他们的投稿与图文会按时间倒序出现在这里。'
              "
            >
              <button v-if="authorId" class="btn btn-blue" type="button" @click="showAll">看全部动态</button>
              <a v-else class="btn btn-blue" href="/">去首页看看</a>
            </EmptyState>

            <template v-else>
              <div class="dyn-list">
                <DynamicCard
                  v-for="card in state.items"
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

              <!-- 续页骨架：3 张（动态卡比首页高，10 张会撑太长） -->
              <div v-if="view === 'loadingMore'" class="dyn-skeleton dyn-skeleton--more">
                <div v-for="n in 3" :key="n" class="skeleton dyn-skeleton__card" />
              </div>

              <!-- 行内失败：只影响"下一页"，已加载的内容留着 -->
              <div v-if="view === 'errorMore'" class="dyn-more-error">
                <span>{{ errorText }}</span>
                <button class="dyn-more-error__retry" type="button" @click="retryMore">重试</button>
              </div>

              <!-- 到底 = 会话级（窗口 ≤300），不承诺"全站刷完" -->
              <p v-if="view === 'done'" class="dyn-done">这次的动态到底了</p>
            </template>
          </template>

          <!-- 哨兵恒在：空态 / 错误态下也没有副作用（没有令牌时 shouldLoadMore 恒 false） -->
          <div ref="sentinel" class="dyn-sentinel" aria-hidden="true"></div>
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

    <!-- 横幅滚出视口时才出现的浮层胶囊（与内联横幅互斥；同样只属于"全部动态"） -->
    <button
      v-if="loggedIn && !authorId && hasNew && !bannerVisible"
      class="dyn-new-pill"
      type="button"
      @click="onNewClick"
    >
      <AppIcon name="refresh" :size="16" />
      <span>有新动态</span>
    </button>

    <!-- 回到顶部：滚过一屏才出现（与空间页动态 tab 共用同一组件） -->
    <BackToTop />
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
  align-items: center;
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

.dyn-head__actions {
  display: flex;
  margin-left: auto;
  align-items: center;
  gap: 14px;
}

.dyn-head__back {
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

/* 刷新（`R-32`）：B 站靠服务端推送，本仓没有 ⇒ 这是"平替"的显式入口 */
.dyn-head__refresh {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 0;
  border: 0;
  background: none;
  font-size: 13px;
  color: var(--text-3);
  cursor: pointer;
}

.dyn-head__refresh:hover:not(:disabled) {
  color: var(--brand-blue);
}

.dyn-head__refresh:disabled {
  color: var(--text-4);
  cursor: default;
}

.dyn-head__refresh .is-spinning {
  animation: dyn-spin 0.9s linear infinite;
}

@keyframes dyn-spin {
  to {
    transform: rotate(360deg);
  }
}

/* 有新动态横幅（B 站版式：整行浅蓝） */
.dyn-new {
  display: flex;
  width: 100%;
  margin-bottom: 12px;
  padding: 10px 0;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 0;
  border-radius: var(--radius);
  font-size: 13px;
  color: var(--brand-blue);
  background: var(--brand-blue-bg);
  cursor: pointer;
}

.dyn-new:hover:not(:disabled) {
  filter: brightness(0.98);
}

.dyn-new:disabled {
  opacity: 0.6;
  cursor: default;
}

/* 横幅在视口外时的浮层胶囊（fixed；不与页面内容抢列） */
.dyn-new-pill {
  position: fixed;
  right: 16px;
  bottom: 88px;
  z-index: 30;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 9px 14px;
  border: 1px solid var(--brand-blue);
  border-radius: 999px;
  font-size: 13px;
  color: var(--text-inverse);
  background: var(--brand-blue);
  box-shadow: var(--shadow-1);
  cursor: pointer;
}

.dyn-new-pill:hover {
  filter: brightness(1.04);
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

/* 首屏 / 续页骨架：动态卡比首页高，3 张足够撑住"在加载"的观感 */
.dyn-skeleton {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.dyn-skeleton--more {
  margin-top: 12px;
}

.dyn-skeleton__card {
  height: 168px;
  border-radius: var(--radius-lg);
}

/* 行内失败：只影响"下一页"，已加载的内容留着 */
.dyn-more-error {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 16px 0 4px;
  font-size: 13px;
  color: var(--text-3);
}

.dyn-more-error__retry {
  padding: 0;
  border: 0;
  background: none;
  font-size: 13px;
  color: var(--brand-blue);
  cursor: pointer;
}

.dyn-more-error__retry:hover {
  text-decoration: underline;
}

/* 到底 = 会话级（窗口 ≤300），不承诺"全站刷完" */
.dyn-done {
  padding: 18px 0 4px;
  font-size: 12px;
  color: var(--text-3);
  text-align: center;
}

.dyn-sentinel {
  height: 1px;
}

@media (prefers-reduced-motion: reduce) {
  .dyn-head__refresh .is-spinning {
    animation: none;
  }
}

@media (max-width: 760px) {
  .dyn-new-pill {
    right: 8px;
  }
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
