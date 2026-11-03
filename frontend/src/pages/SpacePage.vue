<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type {
  DynamicPageVM,
  PublicFolderVM,
  SpaceHeaderVM,
  SpaceWorksVM,
  VideoCardVM,
} from '../types/ui'
import { loadFolderItems, loadPublicFolders, loadSpace } from '../api/profile'
import { followUser, unfollowUser } from '../api/interactions'
import { ApiError, describeError } from '../api/http'
import { formatCount, formatCoinInt } from '../api/adapters/format'
import { applyAvatarUrl, isAuthenticated, restoreSession, sessionState } from '../auth/session'
import { requireLogin } from '../auth/loginPrompt'
import { showToast } from '../common/toast'
import { useCheckIn } from '../composables/useCheckIn'
import { useDynamicActions } from '../composables/useDynamicActions'
import AppIcon from '../components/common/AppIcon.vue'
import EmptyState from '../components/common/EmptyState.vue'
import Pagination from '../components/common/Pagination.vue'
import AvatarSettingsDialog from '../components/common/AvatarSettingsDialog.vue'
import UserAvatar from '../components/common/UserAvatar.vue'
import VideoCard from '../components/common/VideoCard.vue'
import VideoGrid from '../components/home/VideoGrid.vue'
import DynamicCard from '../components/feed/DynamicCard.vue'
import RelationPanel from '../components/space/RelationPanel.vue'

/**
 * UP 主空间（`GET /users/{id}` + 公开收藏夹 + 关注/粉丝列表）。
 *
 * <h2>版式按 B 站 2026 空间页</h2>
 * banner（渐变底）→ 头像 + 昵称 + 关注按钮 → tab（主页 / 动态 / 投稿 / 收藏）→ 内容 + 右侧资料卡。
 * 点头部的「粉丝 / 关注」数字进入**关注 / 粉丝列表**（B 站是 `/{uid}/relation/following`，
 * 2026-10-27 新增；本仓用同一文档里的 query：`?tab=following|followers`）。
 *
 * <h2>★ 三种视图 = 同一份 `contentPage`（`R-31`）</h2>
 * 本仓没有独立动态域（"动态 = 投稿时间线"），所以三个 tab 不是三份数据，而是三种呈现：
 * <ul>
 *   <li><b>主页</b>（缺省）：最新 3 大卡 + 网格预览 6 + 「查看全部投稿」，不分页；</li>
 *   <li><b>动态</b>：全量时间线（图文正文/图集、视频卡、内联评论）+ 分页；</li>
 *   <li><b>投稿</b>：全量网格 + 分页（纯文字动态在这里是文字卡面，GAP-28 原口径）。</li>
 * </ul>
 * 主页与投稿是"预览 vs 全量"，不是"两个名字一套数据"；类型子筛选（视频/图文）另有
 * `GAP-32`，本窗不渲染。
 *
 * <h2>★ 三处降级（都进了 GAP 台账，界面上不假装有）</h2>
 * - **没有 banner 图 / 签名 / 生日 / 地区**：banner 用渐变底，资料卡只放真实计数；
 * - **"代表作"是 UP 自选的，本仓没有这个字段** ⇒ 第一页把**最新 3 条**放大卡，
 *   标题写「最新投稿」而不是「代表作」（标题说谎比少一块更坏）；
 * - **合集和系列 / 最近点赞**没有端点 ⇒ 这两类 tab **不渲染**，而不是摆一个点不动的；
 *   「动态」tab 由 `contentPage` 的时间线视图提供（`R-31`，与 `/dynamic?author=` 同源）。
 *
 * <h2>★ 关注 / 粉丝列表的降级（GAP-25）</h2>
 * `FollowUserVO` 只有 `userId / username / isFollowed / isSelf` —— **没有头像字段、没有签名**
 * （头像由 `RelationPanel` 走 `GET /users/avatars` 批量补，`R-27`）⇒ 名字 + 关注按钮；
 * B 站的「分组 / 最近关注 / 最常访问 / 搜索关键词 / 批量操作」
 * 一个都不渲染（没有对应端点）。两个端点都要登录（B 站同样 `-101`），
 * 匿名进这两个 tab 是「登录后查看」而不是空列表。
 *
 * <p>tab 与翻页都落在 query（`?tab=folders&folder=3&itemPage=2`）：刷新 / 分享能停在原处，
 * 与 B 站同构。两种分页一起用到了：作品页是 **Page**，收藏条目也是 Page，
 * 但**来源不同**（`/users/{id}` 内嵌 vs `/favorite-folders/{id}/items`）。
 * 关注 / 粉丝用 `relPage`（不与作品的 `page` / 夹的 `itemPage` 抢名字）。
 */
const route = useRoute()
const router = useRouter()

const PAGE_SIZE = 12

/**
 * 视图：主页 / 动态 / 投稿 / 收藏（+ 关注/粉丝两个隐藏入口）。
 *
 * <p>★ 后两个不是 B 站**tab 条**上的东西（B 站是点头部「关注 / 粉丝」数字进
 * `/{uid}/relation/following`，tab 条上没有任何一个高亮 —— 见
 * `.screenshots/bilibili-2026-10-26/space-following`）。本仓把入口放在**头部计数**
 * 上（同 B 站），tab 条保持 主页 / 动态 / 投稿 / 收藏；进入关注/粉丝后都不高亮。
 */
type SpaceTab = 'home' | 'dynamic' | 'works' | 'folders' | 'following' | 'followers'

const userId = computed(() => {
  const raw = Number(route.params.id)
  return Number.isInteger(raw) && raw > 0 ? raw : 0
})
const tab = computed<SpaceTab>(() => {
  const raw = route.query.tab
  if (raw === 'dynamic' || raw === 'works' || raw === 'folders' || raw === 'following' || raw === 'followers') {
    return raw
  }
  // 兼容旧链接：works 时代生成的 `/space/{id}?page=2` 没有 tab ⇒ 继续看投稿第 2 页，
  // 不能静默掉回主页（主页不分页，用户会直接丢失第 2 页及之后的上下文）
  return positive(route.query.page) > 1 ? 'works' : 'home'
})
const isRelationTab = computed(() => tab.value === 'following' || tab.value === 'followers')
const worksPage = computed(() => positive(route.query.page))
/**
 * 收藏夹 id：缺省/非法 ⇒ **0**（"没选中任何夹"）。
 * ⚠️ 不能复用 `positive()`（它对页码缺省回 1）：夹 id 缺省回 1 会让"收藏"tab
 * 一进去就去拉 1 号夹的条目，界面还会直接跳到"夹内视图"。
 */
const activeFolderId = computed(() => {
  const value = Number(route.query.folder)
  return Number.isInteger(value) && value > 0 ? value : 0
})
const itemPage = computed(() => positive(route.query.itemPage))

function positive(raw: unknown): number {
  const value = Number(raw)
  return Number.isInteger(value) && value > 0 ? value : 1
}

// ── 作品 ──────────────────────────────────────────────────────────────
const header = ref<SpaceHeaderVM | null>(null)
const works = ref<SpaceWorksVM | null>(null)
/** 同一份 `contentPage` 的时间线视图（`R-31`；与 `works` 同一次请求） */
const timeline = ref<DynamicPageVM | null>(null)
const featured = ref<VideoCardVM[]>([])
const loading = ref(false)
const error = ref('')
const followBusy = ref(false)
const avatarDialogOpen = ref(false)

/** 头像弹层成功后：就地改空间头部 + 登录态（顶栏同一实例立即生效）。 */
function onAvatarUpdated(avatarUrl: string): void {
  if (header.value) header.value = { ...header.value, avatarUrl }
  applyAvatarUrl(avatarUrl)
  avatarDialogOpen.value = false
}

/** 第一页的前 3 条做成大卡（其余进网格），避免同一内容出现两次 */
const gridItems = computed(() => {
  if (!works.value) return []
  // 用**已装载的页**判断，不用 query：主页固定请求第 1 页，`?page=2` 的旧链接也不该把大卡逻辑带偏
  return works.value.page === 1 ? works.value.items.slice(featured.value.length) : works.value.items
})

/** 主页网格预览：第一页去掉 3 张大卡后最多再放 6 张，剩下的去「投稿」tab */
const homePreview = computed(() => gridItems.value.slice(0, 6))

/** 预览之外还有内容才显示「查看全部投稿」（按 total 算，不按本页是否满 12 条） */
const hasMoreWorks = computed(() => {
  if (!works.value) return false
  return works.value.total > featured.value.length + homePreview.value.length
})

/**
 * 空间动态卡的头像：所有条目都是**空间主人**，直接用 `header.avatarUrl`（`R-27` 已解析生效 URL）。
 * 不再为时间线打一次批量头像请求 —— 那会让没打开动态 tab 的页面也白读一趟。
 */

/** 点赞 / 内联评论：与 `/dynamic` 共用同一套动作（`R-31`） */
const {
  likeBusy,
  toggleLike,
  openCommentsId,
  toggleComments,
  onCommentCountChanged,
  closeComments,
} = useDynamicActions()

async function loadWorks(): Promise<void> {
  if (!userId.value) {
    error.value = '地址里的用户 id 不合法'
    header.value = null
    works.value = null
    timeline.value = null
    return
  }
  closeComments() // 换页 / 换人后旧的评论面板不再有意义
  loading.value = true
  error.value = ''
  try {
    // ★ 先等登录态落定：`isSelf` 与 `isFollowed` 都取决于"我是谁"，
    //   抢在 restoreSession 之前装载，会把"看自己的空间"画成"关注自己"（后端会 FOLLOW_SELF）
    await restoreSession()
    // 主页固定第 1 页（它只有 3 + 6 的预览，不分页）；动态 / 投稿才吃 query 页码
    const data = await loadSpace(userId.value, tab.value === 'home' ? 1 : worksPage.value, PAGE_SIZE)
    header.value = data.header
    works.value = data.works
    timeline.value = data.timeline
    featured.value = data.featured
  } catch (exception) {
    header.value = null
    works.value = null
    timeline.value = null
    featured.value = []
    error.value =
      exception instanceof ApiError && exception.status === 404
        ? '这个用户不存在'
        : describeError(exception)
  } finally {
    loading.value = false
  }
}

watch([userId, worksPage], loadWorks, { immediate: true })

/**
 * 登录 / 退出后重读：`isFollowed` 匿名时是 `null`，不重读关注按钮会停在假象上
 * （与播放页 UP 主卡同一个坑）。
 */
watch(
  () => sessionState.status,
  (status, previous) => {
    if ((status === 'authenticated' && previous === 'anonymous') || (status === 'anonymous' && previous === 'authenticated')) {
      void loadWorks()
      if (tab.value === 'folders') void loadFolders(true)
    }
  },
)

async function toggleFollow(): Promise<void> {
  const card = header.value
  if (!card || followBusy.value) return
  if (!(await requireLogin())) return
  if (!isAuthenticated()) return
  followBusy.value = true
  const target = card.followed !== true
  card.followed = target
  if (typeof card.followerCount === 'number') {
    card.followerCount = Math.max(0, card.followerCount + (target ? 1 : -1))
  }
  try {
    if (target) await followUser(card.id)
    else await unfollowUser(card.id)
    showToast(target ? '已关注' : '已取消关注')
    // 写成功后按服务端权威值对齐：登录瞬间可能会并行重读一次资料（读到的是关注前的状态）
    void loadWorks()
  } catch (exception) {
    card.followed = !target
    if (typeof card.followerCount === 'number') {
      card.followerCount = Math.max(0, card.followerCount + (target ? -1 : 1))
    }
    showToast(describeError(exception), 'error')
  } finally {
    followBusy.value = false
  }
}

// ── 硬币 / 签到 ───────────────────────────────────────────────────────
/**
 * 签到卡只在自己的空间渲染（`header.isSelf`）：硬币余额与"今天签没签"都是私密事实，
 * 别人的空间上放一张只有自己看得见的卡是照抄版式、不是照抄语义。
 *
 * ★ 状态来源是 `/users/me` 的 `signedToday`（服务端按上海自然日算），不落 localStorage
 * ⇒ 换号不需要清任何缓存，跨设备 / 多标签页也不会出现"按钮亮着但一点就是已签到"。
 */
const {
  balance: checkInBalance,
  signedToday: checkInSigned,
  busy: checkInBusy,
  submit: submitCheckIn,
} = useCheckIn()

// ── 收藏夹 ────────────────────────────────────────────────────────────
const folders = ref<PublicFolderVM[]>([])
const foldersLoading = ref(false)
const foldersError = ref('')
const foldersLoadedFor = ref(0)

const folderItems = ref<VideoCardVM[]>([])
const folderPage = ref(1)
const folderTotalPages = ref(0)
const itemsLoading = ref(false)
const itemsError = ref('')

async function loadFolders(force = false): Promise<void> {
  if (!userId.value) return
  if (!force && foldersLoadedFor.value === userId.value) return
  foldersLoading.value = true
  foldersError.value = ''
  try {
    folders.value = await loadPublicFolders(userId.value)
    foldersLoadedFor.value = userId.value
  } catch (exception) {
    folders.value = []
    foldersError.value = describeError(exception)
  } finally {
    foldersLoading.value = false
  }
}

async function loadItems(): Promise<void> {
  if (!activeFolderId.value || route.query.tab !== 'folders') return
  itemsLoading.value = true
  itemsError.value = ''
  try {
    const page = await loadFolderItems(activeFolderId.value, itemPage.value, PAGE_SIZE)
    folderItems.value = page.items
    folderPage.value = page.page
    folderTotalPages.value = page.totalPages
  } catch (exception) {
    folderItems.value = []
    itemsError.value = describeError(exception)
  } finally {
    itemsLoading.value = false
  }
}

watch(
  () => [tab.value, activeFolderId.value, itemPage.value, userId.value] as const,
  ([currentTab]) => {
    if (currentTab !== 'folders') return
    void loadFolders()
    void loadItems()
  },
  { immediate: true },
)

const activeFolder = computed(() => folders.value.find((folder) => folder.id === activeFolderId.value) ?? null)

// ── 关注 / 粉丝 ────────────────────────────────────────────────────────
/** 面板只关心"看谁 / 看哪个列表 / 第几页"；装载、竞态与关注动作都在 `RelationPanel` 里 */
const relationPage = computed(() => positive(route.query.relPage))
const relationKind = computed<'following' | 'followers'>(() => (tab.value === 'followers' ? 'followers' : 'following'))

function goRelationPage(page: number): void {
  void router.push({
    name: 'space',
    params: { id: userId.value },
    query: { tab: tab.value, ...(page > 1 ? { relPage: String(page) } : {}) },
  })
}

function setTab(next: SpaceTab): void {
  closeComments() // 离开动态视图时收起评论区
  // 主页是缺省态（URL 不带 tab），其余显式写 tab；切 tab 一律回第 1 页
  void router.push({
    name: 'space',
    params: { id: userId.value },
    query: next === 'home' ? {} : { tab: next },
  })
}

function openFolder(folderId: number): void {
  void router.push({ name: 'space', params: { id: userId.value }, query: { tab: 'folders', folder: String(folderId) } })
}

function backToFolders(): void {
  void router.push({ name: 'space', params: { id: userId.value }, query: { tab: 'folders' } })
}

/**
 * 动态 / 投稿两个视图都有分页：**必须保留 tab**。
 * 只写 `page` 会掉回主页（`works` 时代就有这个坑，`R-31` 把它变成两个 tab 共用）。
 */
function goTabPage(page: number): void {
  void router.push({
    name: 'space',
    params: { id: userId.value },
    query: { tab: tab.value, ...(page > 1 ? { page: String(page) } : {}) },
  })
}

function goItemPage(page: number): void {
  void router.push({
    name: 'space',
    params: { id: userId.value },
    query: { tab: 'folders', folder: String(activeFolderId.value), ...(page > 1 ? { itemPage: String(page) } : {}) },
  })
}
</script>

<template>
  <main class="space-page">
    <div class="space-banner" aria-hidden="true" />

    <div class="page-content space-body">
      <EmptyState v-if="error" icon="user" title="打不开这个空间" :description="error">
        <button class="btn btn-blue" type="button" @click="loadWorks">重试</button>
        <a class="btn btn-ghost" href="/">回首页</a>
      </EmptyState>

      <template v-else>
        <header class="space-header">
          <button
            v-if="header?.isSelf"
            class="space-avatar-btn"
            type="button"
            title="更换头像"
            aria-label="更换头像"
            @click="avatarDialogOpen = true"
          >
            <UserAvatar
              class="space-avatar"
              :avatar-url="header.avatarUrl"
              :user-id="header.id"
              :initial="header.initial"
              :size="84"
            />
            <span class="space-avatar-btn__mask" aria-hidden="true">更换头像</span>
          </button>
          <UserAvatar
            v-else
            class="space-avatar"
            :avatar-url="header?.avatarUrl ?? null"
            :user-id="header?.id ?? null"
            :initial="header?.initial ?? '?'"
            :size="84"
          />
          <div class="space-ident">
            <h1 class="space-name">{{ header?.name ?? '加载中…' }}</h1>
            <p class="space-meta">
              <!-- B 站：头部的「粉丝 / 关注」数字是进入关系列表的入口（tab 条上没有这两项） -->
              <button
                class="space-meta__link"
                type="button"
                title="粉丝列表（需登录）"
                @click="setTab('followers')"
              >粉丝 {{ formatCount(header?.followerCount) }}</button>
              <span class="space-meta__dot">·</span>
              <button
                class="space-meta__link"
                type="button"
                title="关注列表（需登录）"
                @click="setTab('following')"
              >关注 {{ formatCount(header?.followingCount) }}</button>
              <span class="space-meta__dot">·</span>
              <span>投稿 {{ formatCount(header?.worksCount) }}</span>
            </p>
          </div>
          <button
            v-if="header && !header.isSelf"
            class="space-follow"
            :class="{ 'is-followed': header.followed === true }"
            type="button"
            :disabled="followBusy"
            @click="toggleFollow"
          >
            {{ header.followed === true ? '已关注' : '关注' }}
          </button>
        </header>

        <nav class="space-tabs" aria-label="空间分类">
          <button class="space-tab" :class="{ 'is-active': tab === 'home' }" type="button" @click="setTab('home')">
            <AppIcon name="dashboard" :size="16" />
            <span>主页</span>
          </button>
          <button class="space-tab" :class="{ 'is-active': tab === 'dynamic' }" type="button" @click="setTab('dynamic')">
            <AppIcon name="dynamic" :size="16" />
            <span>动态</span>
          </button>
          <button class="space-tab" :class="{ 'is-active': tab === 'works' }" type="button" @click="setTab('works')">
            <AppIcon name="tv" :size="16" />
            <span>投稿</span>
          </button>
          <button class="space-tab" :class="{ 'is-active': tab === 'folders' }" type="button" @click="setTab('folders')">
            <AppIcon name="star" :size="16" />
            <span>收藏</span>
          </button>
        </nav>

        <div v-if="!isRelationTab" class="space-layout">
          <section class="space-main">
            <!-- 主页（R-31）：最新 3 大卡 + 网格预览 6 + 查看全部；不分页 -->
            <template v-if="tab === 'home'">
              <div v-if="loading && !works" class="space-skeleton">
                <div v-for="n in 6" :key="n" class="skeleton space-skeleton__card" />
              </div>
              <p v-else-if="error" class="space-empty space-empty--error">{{ error }}</p>
              <template v-else-if="works">
                <section v-if="featured.length" class="featured">
                  <h2 class="featured__title">最新投稿</h2>
                  <div class="featured__row">
                    <VideoCard v-for="item in featured" :key="item.id" :item="item" />
                  </div>
                </section>

                <p v-if="!works.items.length" class="space-empty">
                  {{ works.total > 0 ? '这一页没有内容，回第 1 页看看' : '这个 UP 主还没有投稿' }}
                </p>
                <template v-else>
                  <VideoGrid v-if="homePreview.length" :items="homePreview" />
                  <button v-if="hasMoreWorks" class="space-more" type="button" @click="setTab('works')">
                    查看全部投稿
                    <AppIcon name="chevron-right" :size="14" />
                  </button>
                </template>
              </template>
            </template>

            <!-- 动态（R-31）：同一份 contentPage 的时间线；与 /dynamic?author= 同源 -->
            <template v-else-if="tab === 'dynamic'">
              <div v-if="loading && !timeline" class="space-skeleton">
                <div v-for="n in 4" :key="n" class="skeleton space-skeleton__card" />
              </div>
              <p v-else-if="error" class="space-empty space-empty--error">{{ error }}</p>
              <template v-else-if="timeline">
                <p v-if="!timeline.items.length" class="space-empty">
                  {{ timeline.total > 0 ? '这一页没有内容，回第 1 页看看' : '这个 UP 主还没有发布过内容' }}
                </p>
                <template v-else>
                  <div class="space-dynamic-list">
                    <DynamicCard
                      v-for="card in timeline.items"
                      :key="card.id"
                      :item="card"
                      :busy="likeBusy.has(card.id)"
                      :avatar-url="header?.avatarUrl ?? null"
                      :comments-open="openCommentsId === card.id"
                      @toggle-like="toggleLike(card)"
                      @toggle-comments="toggleComments(card)"
                      @comment-count-changed="onCommentCountChanged(card, $event)"
                    />
                  </div>
                  <Pagination :page="timeline.page" :total-pages="timeline.totalPages" @update:page="goTabPage" />
                </template>
              </template>
            </template>

            <!-- 投稿（R-31）：全量网格 + 分页；纯文字动态在这里是文字卡面（GAP-28） -->
            <template v-else-if="tab === 'works'">
              <div v-if="loading && !works" class="space-skeleton">
                <div v-for="n in 6" :key="n" class="skeleton space-skeleton__card" />
              </div>
              <p v-else-if="error" class="space-empty space-empty--error">{{ error }}</p>
              <template v-else-if="works">
                <p v-if="!works.items.length" class="space-empty">
                  {{ works.total > 0 ? '这一页没有内容，回第 1 页看看' : '这个 UP 主还没有投稿' }}
                </p>
                <template v-else>
                  <VideoGrid :items="works.items" />
                  <Pagination :page="works.page" :total-pages="works.totalPages" @update:page="goTabPage" />
                </template>
              </template>
            </template>

            <!-- 收藏：公开收藏夹列表 → 某个夹的条目（也是 Page 分页） -->
            <template v-else>
              <button v-if="activeFolderId" class="space-back" type="button" @click="backToFolders">
                <AppIcon name="chevron-left" :size="16" />
                <span>返回收藏夹</span>
              </button>

              <p v-if="foldersLoading || itemsLoading" class="space-empty">加载中…</p>
              <p v-else-if="foldersError || itemsError" class="space-empty space-empty--error">
                {{ foldersError || itemsError }}
              </p>

              <template v-else-if="!activeFolderId">
                <p v-if="!folders.length" class="space-empty">这个 UP 主还没有公开的收藏夹</p>
                <ul v-else class="folder-cards">
                  <li v-for="folder in folders" :key="folder.id">
                    <button class="folder-card" type="button" @click="openFolder(folder.id)">
                      <span class="folder-card__name">{{ folder.name }}</span>
                      <span class="folder-card__count">{{ folder.itemCount }} 个内容</span>
                    </button>
                  </li>
                </ul>
              </template>

              <template v-else>
                <h2 class="space-subtitle">{{ activeFolder?.name ?? '收藏夹' }}</h2>
                <p v-if="!folderItems.length" class="space-empty">这个收藏夹还是空的</p>
                <template v-else>
                  <VideoGrid :items="folderItems" />
                  <Pagination :page="folderPage" :total-pages="folderTotalPages" @update:page="goItemPage" />
                </template>
              </template>
            </template>
          </section>

          <aside class="space-side">
            <section class="side-card">
              <h2 class="side-card__title">个人资料</h2>
              <dl class="side-list">
                <div class="side-list__row">
                  <dt>粉丝数</dt>
                  <dd>{{ formatCount(header?.followerCount) }}</dd>
                </div>
                <div class="side-list__row">
                  <dt>关注数</dt>
                  <dd>{{ formatCount(header?.followingCount) }}</dd>
                </div>
                <div class="side-list__row">
                  <dt>投稿数</dt>
                  <dd>{{ formatCount(header?.worksCount) }}</dd>
                </div>
              </dl>
            </section>

            <!-- 硬币 / 签到：与「个人资料」等宽（同一个 side 列，不另起宽度）。
                 只在看自己时渲染 —— 余额与签到状态是私密事实。 -->
            <section v-if="header?.isSelf" class="side-card">
              <h2 class="side-card__title">硬币</h2>
              <dl class="side-list">
                <div class="side-list__row">
                  <dt>当前硬币</dt>
                  <dd class="checkin__amount">
                    <AppIcon name="coin" :size="16" />
                    {{ formatCoinInt(checkInBalance) }}
                  </dd>
                </div>
              </dl>
              <button
                class="btn checkin__btn"
                :class="checkInSigned ? 'btn-ghost' : 'btn-primary'"
                type="button"
                :disabled="checkInBusy || checkInSigned"
                @click="submitCheckIn"
              >
                {{ checkInSigned ? '今日已签到' : checkInBusy ? '签到中…' : '签到领 1 枚' }}
              </button>
              <!-- 说明"为什么现在不能点"，而不是让用户猜灰按钮是坏了还是签过了 -->
              <p class="checkin__hint">
                {{ checkInSigned ? '明天 0 点后可以再领 1 枚' : '每日签到可得 1 枚硬币' }}
              </p>
            </section>
          </aside>
        </div>

        <!-- 关注 / 粉丝：B 站是 /{uid}/relation/following 一页；本仓用 query 切两个列表。
             装载 / 竞态 / 关注按钮都在面板里（它自己看登录态，深链也能正确装载）。 -->
        <RelationPanel
          v-else
          :user-id="userId"
          :kind="relationKind"
          :page="relationPage"
          :is-self="header?.isSelf === true"
          :following-count="header?.followingCount ?? null"
          :follower-count="header?.followerCount ?? null"
          @update:kind="setTab"
          @update:page="goRelationPage"
          @follow-changed="loadWorks"
        />
      </template>
    </div>

    <AvatarSettingsDialog
      v-if="avatarDialogOpen && header"
      :current-avatar-url="header.avatarUrl"
      :user-id="header.id"
      @close="avatarDialogOpen = false"
      @updated="onAvatarUpdated"
    />
  </main>
</template>

<style scoped>
.space-page {
  padding-top: var(--header-h);
  padding-bottom: 48px;
}

.space-banner {
  height: 180px;
  /* 后端没有 banner 图字段（GAP 台账）⇒ 用低饱和渐变兜底：不编活动图，也不让页面空一块 */
  background:
    radial-gradient(circle at 22% 26%, rgba(255, 255, 255, 0.5) 0%, transparent 58%),
    linear-gradient(118deg, #9ad8f5 0%, #b9c8f2 46%, #f3c6d8 100%);
}

.space-body {
  position: relative;
}

.space-header {
  display: flex;
  align-items: flex-end;
  gap: 16px;
  margin-top: -34px;
  padding-bottom: 16px;
}

.space-avatar {
  border: 3px solid var(--bg-0);
  border-radius: 50%;
  box-shadow: var(--shadow-1);
}

/* 自己的头像才可点（R-27）：hover 出现「更换头像」遮罩；别人的空间保持普通头像 */
.space-avatar-btn {
  position: relative;
  display: block;
  flex: none;
  padding: 0;
  border-radius: 50%;
  line-height: 0;
}

.space-avatar-btn__mask {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: #fff;
  background: rgba(0, 0, 0, 0.5);
  font-size: 12px;
  line-height: 1;
  opacity: 0;
  transition: opacity 0.2s ease;
  pointer-events: none;
}

.space-avatar-btn:hover .space-avatar-btn__mask,
.space-avatar-btn:focus-visible .space-avatar-btn__mask {
  opacity: 1;
}

.space-ident {
  min-width: 0;
  flex: 1;
  padding-bottom: 4px;
}

.space-name {
  font-size: 22px;
  font-weight: 600;
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.space-meta {
  margin-top: 4px;
  font-size: 13px;
  color: var(--text-3);
}

.space-follow {
  flex: none;
  height: 34px;
  margin-bottom: 6px;
  padding: 0 20px;
  border-radius: var(--radius);
  font-size: 14px;
  color: var(--text-inverse);
  background: var(--brand-pink);
}

.space-follow:hover:not(:disabled) {
  background: var(--brand-pink-dark);
}

.space-follow.is-followed {
  color: var(--text-2);
  background: var(--bg-2);
}

.space-follow:disabled {
  opacity: 0.6;
}

.space-tabs {
  display: flex;
  align-items: center;
  gap: 24px;
  border-bottom: 1px solid var(--line-2);
}

.space-tab {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 10px 0 12px;
  font-size: 15px;
  color: var(--text-2);
  border-bottom: 2px solid transparent;
}

.space-tab:hover {
  color: var(--brand-blue);
}

.space-tab.is-active {
  color: var(--brand-pink);
  border-bottom-color: var(--brand-pink);
}

.space-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 28px;
  margin-top: 20px;
  align-items: start;
}

.space-main {
  min-width: 0;
}

.featured__title,
.space-subtitle {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-1);
}

.featured__row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--grid-gap);
  margin: 12px 0 26px;
}

.space-empty {
  padding: 40px 0;
  font-size: 13px;
  color: var(--text-3);
  text-align: center;
}

.space-empty--error {
  color: #f56c6c;
}

/* 主页「查看全部投稿」（R-31） */
.space-more {
  display: flex;
  width: 100%;
  height: 40px;
  margin-top: 18px;
  align-items: center;
  justify-content: center;
  gap: 2px;
  border: 1px solid var(--line-2);
  border-radius: var(--radius);
  background: var(--bg-0);
  font-size: 13px;
  color: var(--text-2);
  cursor: pointer;
}

.space-more:hover {
  color: var(--brand-blue);
  border-color: var(--brand-blue);
}

/* 动态 tab 的卡片流（R-31） */
.space-dynamic-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.space-back {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  margin-bottom: 14px;
  font-size: 13px;
  color: var(--text-2);
}

.space-back:hover {
  color: var(--brand-blue);
}

.folder-cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}

.folder-card {
  display: flex;
  width: 100%;
  height: 96px;
  flex-direction: column;
  justify-content: center;
  gap: 6px;
  padding: 0 16px;
  border: 1px solid var(--line-1);
  border-radius: var(--radius);
  text-align: left;
  background: var(--bg-1);
}

.folder-card:hover {
  border-color: var(--brand-blue);
}

.folder-card__name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.folder-card__count {
  font-size: 12px;
  color: var(--text-3);
}

.space-side {
  position: sticky;
  top: calc(var(--header-h) + 16px);
}

.side-card {
  padding: 16px;
  border-radius: var(--radius);
  background: var(--bg-1);
}

.side-card__title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-1);
}

.side-list {
  margin-top: 10px;
}

.side-list__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 0;
  font-size: 13px;
}

.side-list__row dt {
  color: var(--text-3);
}

.side-list__row dd {
  margin: 0;
  color: var(--text-1);
  font-variant-numeric: tabular-nums;
}

/* 两张侧栏卡之间留出呼吸；宽度由 side 列决定（= 「个人资料」卡同宽） */
.side-card + .side-card {
  margin-top: 12px;
}

.checkin__amount {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-weight: 600;
}

.checkin__amount :deep(svg) {
  color: var(--brand-pink);
}

.checkin__btn {
  width: 100%;
  margin-top: 12px;
}

/*
 * 已签到态的填充色用 `--line-1`（#e3e5e7）而不是 `--bg-1/bg-2`：
 * 卡片底色就是 `--bg-1`，灰阶差两级以内的话按钮会"溶进卡片"，
 * 界面上只剩一行孤零零的灰字 —— 看着像坏了，而不像"已签到"。
 */
.checkin__btn:disabled {
  background: var(--line-1);
  color: var(--text-3);
  cursor: not-allowed;
}

.checkin__hint {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-3);
}

.space-skeleton {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--grid-gap);
}

.space-skeleton__card {
  aspect-ratio: 16 / 12;
}

/* 头部「粉丝 / 关注」计数：按钮而不是链接（它切的是本应用的 tab） */
.space-meta__link {
  padding: 0;
  font-size: 13px;
  color: var(--text-2);
}

.space-meta__link:hover {
  color: var(--brand-blue);
}

.space-meta__dot {
  margin: 0 5px;
  color: var(--text-4);
}

@media (max-width: 1100px) {
  .space-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .space-side {
    position: static;
  }

  .featured__row,
  .folder-cards,
  .space-skeleton {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
