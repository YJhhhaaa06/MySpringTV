<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { DynamicCardVM, RelationPageVM, SpaceHeaderVM } from '../types/ui'
import { FEED_PAGE_SIZE, loadFeed } from '../api/feed'
import { loadFollowing } from '../api/relations'
import { loadProfileHeader } from '../api/profile'
import { fetchLikeCount, likeContent, unlikeContent } from '../api/interactions'
import { ApiError, describeError } from '../api/http'
import { formatCount } from '../api/adapters/format'
import { isAuthenticated, restoreSession, sessionState, signOut } from '../auth/session'
import { requireLogin } from '../auth/loginPrompt'
import { showToast } from '../common/toast'
import AppIcon from '../components/common/AppIcon.vue'
import EmptyState from '../components/common/EmptyState.vue'
import Pagination from '../components/common/Pagination.vue'
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
 * - **按作者筛选 / 动态详情页**：没有端点 / 没有独立文档 ⇒ 卡片直接跳内容页。
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

const sessionReady = ref(false)
const loggedIn = computed(() => sessionState.status === 'authenticated')
const me = computed(() => sessionState.user)

const page = computed(() => {
  const value = Number(route.query.page)
  return Number.isInteger(value) && value > 0 ? value : 1
})

const feed = ref<{ items: DynamicCardVM[]; page: number; total: number; totalPages: number } | null>(null)
const loading = ref(false)
const error = ref('')

const profile = ref<SpaceHeaderVM | null>(null)
const following = ref<RelationPageVM | null>(null)

/** 正在请求点赞的卡片（同一张卡不并发两次；其余卡片不受影响） */
const likeBusy = reactive(new Set<number>())

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

watch(page, () => {
  if (isAuthenticated()) void loadFeedPage()
})

async function loadFeedPage(): Promise<void> {
  if (!isAuthenticated()) return
  loading.value = true
  error.value = ''
  try {
    feed.value = await loadFeed(page.value, FEED_PAGE_SIZE)
  } catch (exception) {
    feed.value = null
    error.value = describeError(exception)
  } finally {
    loading.value = false
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
    loadFollowing(userId, 1, PREVIEW_LIMIT),
  ])
  if (profileResult.status === 'fulfilled') profile.value = profileResult.value
  if (followingResult.status === 'fulfilled') following.value = followingResult.value
}

async function toggleLike(card: DynamicCardVM): Promise<void> {
  if (likeBusy.has(card.id)) return
  if (!(await requireLogin())) return
  const target = card.liked !== true
  const beforeLiked = card.liked
  const beforeCount = card.likeCount
  likeBusy.add(card.id)
  // 乐观更新（三态：null = 本面没查，点下去就是 true）
  card.liked = target
  if (typeof beforeCount === 'number') card.likeCount = Math.max(0, beforeCount + (target ? 1 : -1))
  try {
    if (target) await likeContent(card.id)
    else await unlikeContent(card.id)
    // 写后对齐：服务端权威值。对齐失败不回滚（主写已经成功，回滚反而画出旧值）
    try {
      const authoritative = await fetchLikeCount(card.id)
      if (typeof authoritative === 'number' && Number.isFinite(authoritative)) card.likeCount = authoritative
    } catch {
      /* 计数对齐是尽力而为 */
    }
  } catch (exception) {
    card.liked = beforeLiked
    card.likeCount = beforeCount
    if (exception instanceof ApiError && exception.status === 401) {
      signOut()
      showToast('登录已过期，请重新登录', 'error')
      return
    }
    showToast(describeError(exception), 'error')
  } finally {
    likeBusy.delete(card.id)
  }
}

async function signIn(): Promise<void> {
  await requireLogin()
}

function goPage(nextPage: number): void {
  void router.push({ query: nextPage > 1 ? { page: String(nextPage) } : {} })
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
        title="登录后查看关注动态"
        description="动态流只属于账号：登录后按时间倒序显示你关注的 UP 主的投稿与图文（和 B 站一样，这个列表不公开）。"
      >
        <button class="btn btn-blue" type="button" @click="signIn">登录</button>
      </EmptyState>

      <div v-else class="dyn-layout">
        <!-- 左：我的资料卡（真计数；没有「动态」数是因为我们没有「我发过多少条动态」这个口径） -->
        <aside class="dyn-side">
          <section class="dyn-profile">
            <div class="dyn-profile__head">
              <span class="dyn-profile__avatar">{{ me?.initial ?? '?' }}</span>
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
          <header class="dyn-head">
            <h1 class="dyn-head__title">全部动态</h1>
            <span v-if="feed?.total" class="dyn-head__meta">共 {{ feed.total }} 条</span>
          </header>

          <p v-if="loading && !feed" class="dyn-state">加载中…</p>

          <EmptyState v-else-if="error" icon="dynamic" title="动态加载失败" :description="error">
            <button class="btn btn-blue" type="button" @click="loadFeedPage">重试</button>
          </EmptyState>

          <template v-else-if="feed">
            <EmptyState
              v-if="!feed.items.length"
              icon="dynamic"
              title="还没有动态"
              description="关注一些 UP 主之后，他们的投稿与图文会按时间倒序出现在这里。"
            >
              <a class="btn btn-blue" href="/">去首页看看</a>
            </EmptyState>

            <template v-else>
              <div class="dyn-list">
                <DynamicCard
                  v-for="card in feed.items"
                  :key="card.id"
                  :item="card"
                  :busy="likeBusy.has(card.id)"
                  @toggle-like="toggleLike(card)"
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
              <li v-for="user in following.items" :key="user.id">
                <a
                  class="dyn-following__user"
                  :href="`/space/${user.id}`"
                  target="_blank"
                  rel="noopener"
                  :title="`${user.name} 的空间`"
                >
                  <span class="dyn-following__avatar">{{ user.initial }}</span>
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

.dyn-profile__avatar {
  display: flex;
  width: 58px;
  height: 58px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-size: 22px;
  font-weight: 600;
  color: var(--text-inverse);
  background: linear-gradient(135deg, var(--brand-pink) 0%, var(--brand-blue) 100%);
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

.dyn-following__avatar {
  display: flex;
  width: 36px;
  height: 36px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-inverse);
  background: linear-gradient(135deg, var(--brand-pink) 0%, var(--brand-blue) 100%);
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
