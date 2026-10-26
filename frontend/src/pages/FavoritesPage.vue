<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { MyFolderVM, VideoCardVM } from '../types/ui'
import {
  loadMyFolderItems,
  loadMyFolders,
  loadWatchLater,
  removeFavoriteItems,
  removeWatchLater,
} from '../api/favorites'
import { ApiError, describeError } from '../api/http'
import { restoreSession, sessionState } from '../auth/session'
import { requireLogin } from '../auth/loginPrompt'
import { showToast } from '../common/toast'
import AppIcon from '../components/common/AppIcon.vue'
import EmptyState from '../components/common/EmptyState.vue'
import Pagination from '../components/common/Pagination.vue'
import VideoCard from '../components/common/VideoCard.vue'
import VideoGrid from '../components/home/VideoGrid.vue'

/**
 * 收藏（`/favorites`）：**我的**收藏夹 + 稍后再看。
 *
 * <h2>版式按 B 站「我的收藏」</h2>
 * 左侧夹列表（名字 + 条目数 + 私密标记）→ 右侧当前夹的卡片网格 + Page 分页；
 * 「稍后再看」同一页、走 query `?tab=later`（与夹切换并列在左栏）。
 *
 * <h2>★ 页面级身份态（与动作级闸门不矛盾）</h2>
 * 这两个页面**整个内容都属于"我"**：未登录时请求必然 401，没有"匿名可见的一部分"。
 * 所以这里先等 `restoreSession()` 落定：
 * - 匿名 ⇒ 渲染「登录后查看」+ 一个登录按钮（**不自动弹框**，B 站也是卡片态）；
 * - 点登录 ⇒ `requireLogin()`（动作级闸门）→ 登录成功后同一个 watcher 自动装载。
 * 对比：播放页的点赞/收藏是**动作级**（页面匿名可看，点了才拦）。
 *
 * <h2>★ 写后对齐</h2>
 * 「取消收藏 / 移出稍后再看」先乐观移除（含夹计数 -1），成功后**静默重读**当前页
 * （权威 total / 条目数和别的标签页的改动都会对齐）；失败则回滚快照并提示。
 *
 * <h2>缺口（GAP 台账，不假装有）</h2>
 * - `FavoriteItemVO` / `WatchLaterItemVO` 没有 authorId / 时长 / 发布时间 ⇒ 作者纯文本、无时长（GAP-16）；
 * - 失效条目保留占位（「已失效」角标 + 仍可移出 —— 后端按 contentId 定位，不校验内容存在性）。
 * - 批量管理（B 站的多选 + 批量取消）本窗口不做：只做单条移除。
 */
const route = useRoute()
const router = useRouter()

const PAGE_SIZE = 12

const sessionReady = ref(false)
const loggedIn = computed(() => sessionState.status === 'authenticated')
const mode = computed<'folder' | 'later'>(() => (route.query.tab === 'later' ? 'later' : 'folder'))
/** 夹 id 缺省是 **0**（"没选中"）——不能像页码那样缺省回 1，否则会去拉 1 号夹 */
const activeFolderId = computed(() => positive(route.query.folder))
const page = computed(() => Math.max(1, positive(route.query.page)))

function positive(raw: unknown): number {
  const value = Number(raw)
  return Number.isInteger(value) && value > 0 ? value : 0
}

const folders = ref<MyFolderVM[]>([])
const foldersLoading = ref(false)
const foldersError = ref('')

const items = ref<VideoCardVM[]>([])
const total = ref(0)
const totalPages = ref(0)
const laterTotal = ref(0)
const itemsLoading = ref(false)
const itemsError = ref('')
const removing = ref<number | null>(null)

const activeFolder = computed(() => folders.value.find((folder) => folder.id === activeFolderId.value) ?? null)
const panelTitle = computed(() => (mode.value === 'later' ? '稍后再看' : activeFolder.value?.name ?? '收藏夹'))
const removeLabel = computed(() => (mode.value === 'later' ? '移出' : '取消收藏'))

onMounted(async () => {
  await restoreSession()
  sessionReady.value = true
})

/** 登录 / 退出：进 → 重读夹；退 → 清空（不留下上一账号的夹名） */
watch(
  () => sessionState.status,
  (status) => {
    if (status === 'authenticated') {
      void loadFolders()
      return
    }
    if (status === 'anonymous') {
      folders.value = []
      items.value = []
      total.value = 0
      totalPages.value = 0
      laterTotal.value = 0
    }
  },
  { immediate: true },
)

/** 应用内 tab / 夹 / 翻页都只改 query（刷新与分享能停在原处，与 B 站同构） */
watch(
  [mode, activeFolderId, page, () => sessionState.status],
  ([currentMode, folderId, currentPage, status]) => {
    if (status !== 'authenticated') return
    if (currentMode === 'later') {
      void loadLater(currentPage)
      return
    }
    if (folderId > 0) void loadFolderItems(folderId, currentPage)
  },
  { immediate: true },
)

async function loadFolders(): Promise<void> {
  foldersLoading.value = true
  foldersError.value = ''
  try {
    folders.value = await loadMyFolders()
    // 侧栏的「稍后再看」条目数不在夹列表里 ⇒ 单独取一次 total（pageSize=1，只拿计数）
    void loadLaterTotal()
    // 没有选中夹时落到默认夹（B 站进收藏页直接打开默认收藏夹）；夹列表为空则什么都不做
    if (mode.value === 'folder' && activeFolderId.value === 0) {
      const preferred = folders.value.find((folder) => folder.isDefault) ?? folders.value[0]
      if (preferred) await router.replace({ query: { folder: String(preferred.id) } })
    }
  } catch (exception) {
    folders.value = []
    foldersError.value = describeError(exception)
  } finally {
    foldersLoading.value = false
  }
}

/**
 * 侧栏「稍后再看」的条目数。
 *
 * <p>为什么不从夹列表里拿：`GET /favorite-folders` 只覆盖收藏夹，稍后再看是**另一张表**。
 * 不取的话侧栏会显示 0，用户会以为池子是空的 —— 比"少一行"更糟。
 * 失败不阻塞页面：计数是装饰，列表能不能打开才是主路径。
 */
async function loadLaterTotal(): Promise<void> {
  try {
    const result = await loadWatchLater(1, 1)
    laterTotal.value = result.total
  } catch {
    // 静默：计数失败不影响收藏夹主路径
  }
}

async function loadFolderItems(folderId: number, currentPage: number): Promise<void> {
  itemsLoading.value = true
  itemsError.value = ''
  try {
    const result = await loadMyFolderItems(folderId, currentPage, PAGE_SIZE)
    items.value = result.items
    total.value = result.total
    totalPages.value = result.totalPages
    // 夹列表里的条目数是快照；以列表端点回来的 total 对齐（它在同一事务口径下更准）
    const folder = folders.value.find((entry) => entry.id === folderId)
    if (folder) folder.itemCount = result.total
  } catch (exception) {
    items.value = []
    itemsError.value =
      exception instanceof ApiError && exception.status === 403
        ? '这个收藏夹不是你的'
        : describeError(exception)
  } finally {
    itemsLoading.value = false
  }
}

async function loadLater(currentPage: number): Promise<void> {
  itemsLoading.value = true
  itemsError.value = ''
  try {
    const result = await loadWatchLater(currentPage, PAGE_SIZE)
    items.value = result.items
    total.value = result.total
    totalPages.value = result.totalPages
    laterTotal.value = result.total
  } catch (exception) {
    items.value = []
    itemsError.value = describeError(exception)
  } finally {
    itemsLoading.value = false
  }
}

/** 登录按钮：动作级闸门（成功后 watcher 自动装载，不需要在这里手动 reload） */
async function signIn(): Promise<void> {
  await requireLogin()
}

function selectFolder(folderId: number): void {
  void router.push({ query: { folder: String(folderId) } })
}

function selectLater(): void {
  void router.push({ query: { tab: 'later' } })
}

function goPage(nextPage: number): void {
  const query = mode.value === 'later'
    ? { tab: 'later', ...(nextPage > 1 ? { page: String(nextPage) } : {}) }
    : { folder: String(activeFolderId.value), ...(nextPage > 1 ? { page: String(nextPage) } : {}) }
  void router.push({ query })
}

/**
 * 单条移除：乐观 → 失败回滚 → 成功后静默对齐。
 *
 * <p>为什么成功后还要重读：夹计数 / total 是**别的标签页也能改**的共享事实，
 * 只靠本地 -1 迟早漂移（与播放页「写后对齐」同一口径）。
 */
async function removeItem(item: VideoCardVM): Promise<void> {
  if (removing.value !== null) return
  const contentId = item.id
  if (!contentId) return
  const folder = activeFolder.value
  const snapshot = {
    items: items.value,
    total: total.value,
    totalPages: totalPages.value,
    laterTotal: laterTotal.value,
    folderCount: folder?.itemCount ?? 0,
  }

  removing.value = contentId
  items.value = items.value.filter((entry) => entry.id !== contentId)
  total.value = Math.max(0, total.value - 1)
  if (mode.value === 'later') laterTotal.value = Math.max(0, laterTotal.value - 1)
  else if (folder) folder.itemCount = Math.max(0, folder.itemCount - 1)

  try {
    if (mode.value === 'later') await removeWatchLater([contentId])
    else await removeFavoriteItems(activeFolderId.value, [contentId])
    showToast(mode.value === 'later' ? '已移出稍后再看' : '已取消收藏')
    await refreshSilently()
  } catch (exception) {
    items.value = snapshot.items
    total.value = snapshot.total
    totalPages.value = snapshot.totalPages
    laterTotal.value = snapshot.laterTotal
    if (folder) folder.itemCount = snapshot.folderCount
    showToast(describeError(exception), 'error')
  } finally {
    removing.value = null
  }
}

/** 静默重读当前页；这一页被删空时退到最后一个有内容的页 */
async function refreshSilently(): Promise<void> {
  try {
    const result = mode.value === 'later'
      ? await loadWatchLater(page.value, PAGE_SIZE)
      : await loadMyFolderItems(activeFolderId.value, page.value, PAGE_SIZE)
    if (result.items.length === 0 && result.total > 0 && page.value > 1) {
      goPage(Math.max(1, result.totalPages))
      return
    }
    items.value = result.items
    total.value = result.total
    totalPages.value = result.totalPages
    if (mode.value === 'later') laterTotal.value = result.total
    const folder = activeFolder.value
    if (folder && mode.value === 'folder') folder.itemCount = result.total
  } catch {
    // 静默失败：乐观结果已经呈现，下次进入页面会重建（不弹第二个错误提示）
  }
}
</script>

<template>
  <main class="library-page page-main">
    <div class="page-content">
      <header class="library-head">
        <span class="library-head__icon"><AppIcon name="star" :size="22" /></span>
        <h1 class="library-head__title">收藏夹</h1>
        <span v-if="loggedIn && folders.length" class="library-head__meta">
          {{ folders.length }} 个收藏夹 · 共 {{ folders.reduce((sum, folder) => sum + folder.itemCount, 0) }} 个内容
        </span>
      </header>

      <p v-if="!sessionReady" class="library-loading">正在恢复登录态…</p>

      <EmptyState
        v-else-if="!loggedIn"
        icon="star"
        title="登录后查看你的收藏"
        description="收藏夹与稍后再看都属于账号：登录后自动同步（本仓用 Bearer token，不用 Cookie）。"
      >
        <button class="btn btn-primary" type="button" @click="signIn">立即登录</button>
      </EmptyState>

      <div v-else class="library-layout">
        <aside class="library-side" aria-label="收藏夹列表">
          <p v-if="foldersLoading" class="library-side__hint">加载中…</p>
          <p v-else-if="foldersError" class="library-side__hint library-side__hint--error">{{ foldersError }}</p>
          <ul v-else class="library-side__list">
            <li v-for="folder in folders" :key="folder.id">
              <button
                class="side-folder"
                :class="{ 'is-active': mode === 'folder' && folder.id === activeFolderId }"
                type="button"
                @click="selectFolder(folder.id)"
              >
                <span class="side-folder__name">{{ folder.name }}</span>
                <span v-if="folder.isPrivate" class="side-folder__badge">私密</span>
                <span class="side-folder__count">{{ folder.itemCount }}</span>
              </button>
            </li>
            <li>
              <button
                class="side-folder side-folder--later"
                :class="{ 'is-active': mode === 'later' }"
                type="button"
                @click="selectLater"
              >
                <span class="side-folder__name"><AppIcon name="clock" :size="14" /> 稍后再看</span>
                <span class="side-folder__count">{{ laterTotal }}</span>
              </button>
            </li>
          </ul>
        </aside>

        <section class="library-main">
          <header class="library-main__head">
            <h2 class="library-main__title">{{ panelTitle }}</h2>
            <span class="library-main__count">{{ total }} 个内容</span>
          </header>

          <p v-if="itemsLoading && !items.length" class="library-loading">加载中…</p>

          <EmptyState v-else-if="itemsError" icon="star" title="打不开这个列表" :description="itemsError">
            <button class="btn btn-blue" type="button" @click="refreshSilently">重试</button>
          </EmptyState>

          <EmptyState
            v-else-if="!items.length"
            icon="star"
            :title="mode === 'later' ? '稍后再看是空的' : '这个收藏夹还是空的'"
            :description="mode === 'later' ? '在播放页点「稍后再看」就会出现在这里。' : '在播放页点收藏，选一个夹存进去。'"
          >
            <a class="btn btn-ghost" href="/">去首页看看</a>
          </EmptyState>

          <template v-else>
            <VideoGrid :items="items">
              <template #item="{ item }">
                <div class="library-cell">
                  <VideoCard :item="item" />
                  <button
                    class="library-cell__remove"
                    type="button"
                    :disabled="removing === item.id"
                    @click="removeItem(item)"
                  >
                    {{ removing === item.id ? '处理中…' : removeLabel }}
                  </button>
                </div>
              </template>
            </VideoGrid>
            <Pagination :page="page" :total-pages="totalPages" @update:page="goPage" />
          </template>
        </section>
      </div>
    </div>
  </main>
</template>

<style scoped>
.library-page {
  min-height: 100vh;
}

.library-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--line-2);
}

.library-head__icon {
  display: flex;
  width: 34px;
  height: 34px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: var(--brand-pink);
  background: #fff0f5;
}

.library-head__title {
  font-size: 20px;
  font-weight: 600;
  color: var(--text-1);
}

.library-head__meta {
  margin-left: 4px;
  font-size: 12px;
  color: var(--text-3);
}

.library-loading {
  padding: 48px 0;
  font-size: 13px;
  color: var(--text-3);
  text-align: center;
}

.library-layout {
  display: grid;
  grid-template-columns: 216px minmax(0, 1fr);
  gap: 24px;
  margin-top: 18px;
  align-items: start;
}

.library-side {
  position: sticky;
  top: calc(var(--header-h) + 16px);
  padding: 8px;
  border-radius: var(--radius);
  background: var(--bg-1);
}

.library-side__hint {
  padding: 10px 8px;
  font-size: 12px;
  color: var(--text-3);
}

.library-side__hint--error {
  color: #f56c6c;
}

.side-folder {
  display: flex;
  width: 100%;
  height: 38px;
  align-items: center;
  gap: 6px;
  padding: 0 10px;
  border-radius: var(--radius-sm);
  font-size: 14px;
  color: var(--text-2);
  text-align: left;
}

.side-folder:hover {
  color: var(--brand-blue);
  background: var(--bg-0);
}

.side-folder.is-active {
  color: var(--brand-pink);
  background: var(--bg-0);
  font-weight: 600;
}

.side-folder__name {
  display: inline-flex;
  min-width: 0;
  align-items: center;
  gap: 4px;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.side-folder__badge {
  flex: none;
  padding: 0 4px;
  border-radius: 2px;
  font-size: 11px;
  line-height: 16px;
  color: var(--text-3);
  background: var(--bg-2);
}

.side-folder__count {
  flex: none;
  font-size: 12px;
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
}

.side-folder--later {
  margin-top: 6px;
  border-top: 1px solid var(--line-1);
  border-radius: 0 0 var(--radius-sm) var(--radius-sm);
}

.library-main {
  min-width: 0;
  /* 左栏占掉 216px ⇒ 主区比首页窄；沿用首页的 5 列会让卡片只剩 ~200px。
     4 列（1600 下约 255px）更接近 B 站收藏页的卡片比例。 */
  --grid-cols: 4;
}

.library-main__head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 16px;
}

.library-main__title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-1);
}

.library-main__count {
  font-size: 12px;
  color: var(--text-3);
}

/* 卡片右上角的移除按钮：hover / 键盘聚焦才出现（B 站也是悬浮操作） */
.library-cell {
  position: relative;
}

.library-cell__remove {
  position: absolute;
  top: 6px;
  right: 6px;
  z-index: 1;
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  font-size: 12px;
  color: var(--text-inverse);
  background: rgba(0, 0, 0, 0.62);
  opacity: 0;
  transition: opacity 0.15s ease;
}

.library-cell:hover .library-cell__remove,
.library-cell__remove:focus-visible {
  opacity: 1;
}

.library-cell__remove:hover {
  background: var(--brand-pink);
}

@media (max-width: 1400px) {
  .library-main {
    --grid-cols: 3;
  }
}

@media (max-width: 900px) {
  .library-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .library-side {
    position: static;
  }

  .library-main {
    --grid-cols: 2;
  }
}
</style>
