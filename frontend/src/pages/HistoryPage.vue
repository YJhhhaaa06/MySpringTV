<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { HistoryItemVM } from '../types/ui'
import { updateHistoryRecording } from '../api/account'
import { clearHistory, deleteHistory, groupHistoryByDay, loadHistory } from '../api/history'
import { describeError } from '../api/http'
import { applyHistoryRecording, restoreSession, sessionState } from '../auth/session'
import { requireLogin } from '../auth/loginPrompt'
import { isRecording } from '../player/historyTracking'
import { clearAllPending, clearPending } from '../player/progressReporter'
import AppIcon from '../components/common/AppIcon.vue'
import EmptyState from '../components/common/EmptyState.vue'
import Pagination from '../components/common/Pagination.vue'
import DeleteHistoryConfirm from '../components/history/DeleteHistoryConfirm.vue'
import HistoryCard from '../components/history/HistoryCard.vue'
import RecordingStopConfirm from '../components/history/RecordingStopConfirm.vue'
import {
  isAllSelected,
  shouldStepBackPage,
  toggleSelectAll,
  toggleSelection,
} from '../components/history/historySelection'

/**
 * 观看历史（`/history`）：B 站 2026-10 版式 —— **左侧日期时间轴 + 右侧多列卡片网格**。
 *
 * <h2>版式对照（用户提供的登录态截图）</h2>
 * - 时间轴：`今天` / `昨天` / `MM-DD` 在左栏，竖线 + 空心圆点贯穿整组；
 * - 卡片：封面底「已看/总长」条（`18:09/49:45` / `已看完` / `看到 12:00`）+ 进度线，
 *   标题两行，meta =「UP 主 · 今天 12:57」；
 * - 1600 视口下 **4 列**（左栏 76px + 间距 24px 后约 290px/卡）——
 *   旧版"一行一条"太稀，这一版是照截图重做的。
 *
 * <h2>★ 不渲染的 B 站控件（后端没有对应能力，摆了就是撒谎）</h2>
 * - tabs「综合 / 视频 / 直播 / 专栏」+「更多筛选」+ 搜索框：`GET /history` 只有 `page/pageSize`
 *   （GAP-20，无 `type` / `keyword` 参数）。
 * 这块**不是本窗口漏做**，台账里有记录；后端补了之后再加。
 *
 * <h2>`R-26`（2026-10-31）：删除 / 清空 / 批量管理 —— 已渲染</h2>
 * - **普通态**：工具栏「清空历史」「批量管理」；每张卡 meta 行右端一个垃圾桶（**直删不确认**）；
 * - **管理态**：工具栏换成「全选 / 已选择 N 条历史记录 / 删除 / 退出管理」，
 *   卡片封面左上出现勾选框、点整卡 = 勾选（**删除必过确认框**）；
 * - **全选 = 已加载出来的条目**（B站 2026-10-31 实测：全选后显示"已选择 259 条历史记录"），
 *   不是"整库" ⇒ 它永远走 `contentIds`；「清空历史」才走 `all=true`（刻意显式，见 `api/history.ts`）；
 * - ★ 删除成功后**必须清本地 `history:pending:{contentId}`**（清空则清全部）：`/position` 已经答 0，
 *   但播放页定位取 `max(服务端, 本地)` ⇒ 不清会从被删的那条旧位置续播，第一次上报还会把行重建；
 * - ★ 删空末页要**回退一页**（否则停在空白页上，看起来像"历史全没了"）；
 * - 管理态**不落 localStorage / 不进 URL**（前端只许两个 key，见 `客户端静默机制.md` §3.10）。
 *
 * <h2>「记录浏览历史」开关（`R-25`，2026-10-31 起已渲染）</h2>
 * 标题行右侧，**不吸顶**（随页面滚动移出视野，B站 2560 实测）。
 * - **关闭要确认**（`RecordingStopConfirm`），**打开不确认**；
 * - 拨动**乐观生效**：不等接口、失败不提示、不回弹。断网时的切换服务端收不到 ⇒
 *   刷新后变回原值（"断网期间的关闭不算数"）——B站 实测口径，不是漏做错误处理；
 * - 状态来源只有 `GET /users/me` 的 `historyRecording`（每次整页装载读一次），
 *   **不落 localStorage**（前端只许两个 key，见 `客户端静默机制.md` §3.10）；
 * - 关闭后播放页连 `/play` 都不发 ⇒ 该期间的观看不计播放量（D1，见
 *   `architecture/domain/history/history-已知代价.md`）。
 *
 * <h2>页面级身份态</h2>
 * 整个列表都属于"我" ⇒ 先 `await restoreSession()`，匿名渲染「登录后查看」+ 登录按钮
 * （按钮走动作级闸门 `requireLogin()`，登录成功后 watcher 自动装载）。
 */
const route = useRoute()
const router = useRouter()

const PAGE_SIZE = 20

const sessionReady = ref(false)
const loggedIn = computed(() => sessionState.status === 'authenticated')
const page = computed(() => {
  const value = Number(route.query.page)
  return Number.isInteger(value) && value > 0 ? value : 1
})

const items = ref<HistoryItemVM[]>([])
const total = ref(0)
const totalPages = ref(0)
const loading = ref(false)
const error = ref('')

/** 开关的当前值（极性判据只有一处：`player/historyTracking.ts#isRecording`） */
const recording = computed(() => isRecording(sessionState.user?.historyRecording))
const confirmOpen = ref(false)

// ==================== 删除 / 批量管理（`R-26`） ====================

/** 管理态（页内状态：不落 localStorage、不进 URL —— 一次导航就该回到普通态） */
const managing = ref(false)
/** 已勾选的 contentId（只可能包含**当前已加载页**的条目，见 `historySelection.ts`） */
const selected = ref<Set<number>>(new Set())
/** 待确认的删除形态；`null` = 没有确认框 */
const confirmMode = ref<'selected' | 'all' | null>(null)
/** 删除类请求在途：防连点（两个 DELETE 并发本身无害，但 UI 会闪两次） */
const busy = ref(false)
/** 删除失败的行内提示（不占整页：列表还在，用户还能重试下一次点击） */
const mutationError = ref('')

const loadedIds = computed(() => items.value.map((item) => item.contentId))
const allSelected = computed(() => isAllSelected(loadedIds.value, selected.value))
const selectedCount = computed(() => selected.value.size)

const groups = computed(() => groupHistoryByDay(items.value))

onMounted(async () => {
  await restoreSession()
  sessionReady.value = true
})

watch(
  () => sessionState.status,
  (status) => {
    if (status === 'authenticated') {
      void load()
      return
    }
    if (status === 'anonymous') {
      items.value = []
      total.value = 0
      totalPages.value = 0
    }
  },
  { immediate: true },
)

watch(page, () => {
  // 翻页 = 换了一批"已加载条目" ⇒ 旧的选择必须清掉（否则"全选"会在另一页上静默多选）
  selected.value = new Set()
  if (sessionState.status === 'authenticated') void load()
})

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const result = await loadHistory(page.value, PAGE_SIZE)
    items.value = result.items
    total.value = result.total
    totalPages.value = result.totalPages
  } catch (exception) {
    items.value = []
    error.value = describeError(exception)
  } finally {
    loading.value = false
  }
}

async function signIn(): Promise<void> {
  await requireLogin()
}

/**
 * 拨开关：**关 → 弹确认；开 → 直接生效**（B站 实测口径）。
 *
 * <p>★ 乐观生效：先改本地（界面立刻变），再 fire-and-forget 写服务端。
 * **不**等回执、**不**提示、**不**回弹 —— 断网时的切换服务端没收到，
 * 下一次整页装载 `restoreSession()` 会把它读回原值（"断网期间的关闭不算数"）。
 * 这不是漏写错误处理：错误处理会造出 B站 没有的第二套行为。
 */
function onToggle(): void {
  if (recording.value) {
    confirmOpen.value = true
    return
  }
  applyHistoryRecording(true)
  void updateHistoryRecording(true).catch(() => {})
}

function confirmStop(): void {
  confirmOpen.value = false
  applyHistoryRecording(false)
  void updateHistoryRecording(false).catch(() => {})
}

function cancelStop(): void {
  confirmOpen.value = false
}

function goPage(nextPage: number): void {
  void router.push({ query: nextPage > 1 ? { page: String(nextPage) } : {} })
}

// ==================== 删除（`R-26`） ====================

function enterManage(): void {
  managing.value = true
  selected.value = new Set()
  mutationError.value = ''
}

function exitManage(): void {
  managing.value = false
  selected.value = new Set()
  confirmMode.value = null
}

function onToggleSelect(contentId: number): void {
  selected.value = toggleSelection(selected.value, contentId)
}

function onToggleAll(): void {
  selected.value = toggleSelectAll(loadedIds.value, selected.value)
}

/**
 * 单条直删（普通态的垃圾桶）：**不确认**（B站 实测口径）。
 *
 * <p>★ 删完清本地 pending：`/position` 已经答 0，但播放页定位取 `max(服务端, 本地)`
 * ⇒ 不清就会从这条被删记录的旧位置续播，第一次上报还会把历史行重建出来。
 */
async function removeOne(contentId: number): Promise<void> {
  if (busy.value) return
  busy.value = true
  mutationError.value = ''
  try {
    await deleteHistory([contentId])
    clearPending(contentId)
    await reloadAfterDelete()
  } catch (exception) {
    mutationError.value = describeError(exception)
  } finally {
    busy.value = false
  }
}

/** 确认框点「确认」：批量删已勾选的，或清空全部（两种形态互斥，与端点契约一致） */
async function confirmDelete(): Promise<void> {
  const mode = confirmMode.value
  if (mode === null || busy.value) return
  confirmMode.value = null
  busy.value = true
  mutationError.value = ''
  try {
    if (mode === 'all') {
      await clearHistory()
      clearAllPending()
    } else {
      const ids = [...selected.value]
      await deleteHistory(ids)
      for (const id of ids) clearPending(id)
    }
    exitManage()
    await reloadAfterDelete()
  } catch (exception) {
    mutationError.value = describeError(exception)
  } finally {
    busy.value = false
  }
}

/**
 * 删除后重新取当前页，并在"这一页被删空"时回退一页。
 *
 * <p>★ 不回退的话，末页删光后 `total > 0` 而本页 0 条 ⇒ 停在空白页上，看起来像"历史全没了"。
 */
async function reloadAfterDelete(): Promise<void> {
  await load()
  // ★ 只在"重读成功且本页为空"时回退：读失败时 items 也是空的（error 已置位），
  //   无条件回退会把用户从第 N 页"踢"到 N-1 页 —— 删除明明成功了，却像丢了位置。
  if (!error.value && shouldStepBackPage(items.value.length, page.value)) {
    goPage(page.value - 1)
  }
}
</script>

<template>
  <main class="history-page page-main">
    <div class="page-content">
      <header class="history-head">
        <span class="history-head__icon"><AppIcon name="clock" :size="22" /></span>
        <h1 class="history-head__title">历史记录</h1>
        <div class="history-head__right">
          <span v-if="loggedIn && total" class="history-head__meta">共 {{ total }} 条</span>
          <!-- 记录开关（R-25）：关 → 弹确认；开 → 直接生效。不吸顶，随页面滚动移出视野 -->
          <button
            v-if="loggedIn"
            class="history-switch"
            type="button"
            role="switch"
            :aria-checked="recording"
            @click="onToggle"
          >
            <span class="history-switch__label">记录浏览历史</span>
            <span class="history-switch__track"><span class="history-switch__knob" /></span>
          </button>
        </div>
      </header>

      <p v-if="!sessionReady" class="history-state">正在恢复登录态…</p>

      <template v-else>
        <!-- 工具栏（R-26）：普通态 = 清空 / 批量管理；管理态 = 全选 / 已选择 N 条 / 删除 / 退出管理。
             ★ B站 管理态会用这一行**替换**筛选栏 —— 本仓本来就没有筛选栏（GAP-20），形状一致。 -->
        <div v-if="loggedIn && total" class="history-toolbar" :class="{ 'is-managing': managing }">
          <template v-if="managing">
            <button class="history-toolbar__text" type="button" @click="onToggleAll">
              {{ allSelected ? '取消全选' : '全选' }}
            </button>
            <span class="history-toolbar__count">已选择 {{ selectedCount }} 条历史记录</span>
            <button
              class="history-toolbar__action"
              type="button"
              :disabled="!selectedCount || busy"
              @click="confirmMode = 'selected'"
            >
              <AppIcon name="trash" :size="14" />
              删除
            </button>
            <button
              class="history-toolbar__text history-toolbar__text--end"
              type="button"
              @click="exitManage"
            >退出管理</button>
          </template>
          <template v-else>
            <button class="history-toolbar__action" type="button" @click="confirmMode = 'all'">清空历史</button>
            <button class="history-toolbar__action" type="button" @click="enterManage">批量管理</button>
          </template>
        </div>

        <!-- 删除失败：行内提示（列表还在 ⇒ 不用整页错误态，用户直接再点一次即可） -->
        <p v-if="mutationError" class="history-error">{{ mutationError }}</p>

        <EmptyState
          v-if="!loggedIn"
          icon="clock"
          title="登录后查看观看历史"
          description="历史只属于账号：登录后按最后观看时间倒序，看到哪儿也一起同步（Bearer token，不用 Cookie）。"
        >
          <button class="btn btn-primary" type="button" @click="signIn">立即登录</button>
        </EmptyState>

        <template v-else>
          <div v-if="loading && !items.length" class="history-skeleton">
            <div v-for="n in 8" :key="n" class="history-skeleton__cell">
              <div class="skeleton history-skeleton__cover" />
              <div class="skeleton history-skeleton__line" />
              <div class="skeleton history-skeleton__line history-skeleton__line--short" />
            </div>
          </div>

          <EmptyState v-else-if="error" icon="clock" title="打不开观看历史" :description="error">
            <button class="btn btn-blue" type="button" @click="load">重试</button>
          </EmptyState>

          <EmptyState
            v-else-if="!items.length"
            icon="clock"
            title="还没有观看记录"
            description="在播放页看过视频后，会按天出现在这里。"
          >
            <a class="btn btn-ghost" href="/">去首页看看</a>
          </EmptyState>

          <template v-else>
            <!-- 时间轴的竖线画在**整段**上（不是每组自己的 border），组与组之间才连续 -->
            <div class="history-timeline">
              <section v-for="group in groups" :key="group.key" class="history-group">
                <div class="history-group__rail">
                  <h2 class="history-group__label">{{ group.label }}</h2>
                </div>
                <div class="history-group__main">
                  <div class="history-grid">
                    <HistoryCard
                      v-for="item in group.items"
                      :key="item.contentId"
                      :item="item"
                      :day-label="group.label"
                      :selectable="managing"
                      :selected="selected.has(item.contentId)"
                      @remove="removeOne(item.contentId)"
                      @toggle="onToggleSelect(item.contentId)"
                    />
                  </div>
                </div>
              </section>
            </div>
            <Pagination :page="page" :total-pages="totalPages" @update:page="goPage" />
          </template>
        </template>
      </template>
    </div>

    <!-- 停用确认：只有"关"这一步要它（打开不确认）；点遮罩 / 关闭 = 取消 -->
    <RecordingStopConfirm
      v-if="confirmOpen"
      @close="cancelStop"
      @confirm="confirmStop"
    />

    <!-- 删除确认（R-26）：管理态删除与清空都必过它；卡片上的垃圾桶是直删，不走这里 -->
    <DeleteHistoryConfirm
      v-if="confirmMode"
      :mode="confirmMode"
      @close="confirmMode = null"
      @confirm="confirmDelete"
    />
  </main>
</template>

<style scoped>
.history-page {
  min-height: 100vh;
  /*
   * B 站历史页单独一档宽度（2026-10-27 复核）：
   * - 线上 `history-record` 的 `.history-wrap` 在 `min-width:1415px` 下就是 **1160px**；
   * - 登录态 2560 截图（1707 CSS）实测：时间轴 76 + 4 列 ×258 + 3×17 ≈ 1188。
   * 旧口径「锁 1320」比 B 站宽 11%（卡片 293 vs 258）⇒ 本窗口改成 1160。
   */
  --content-w: 1160px;
}

.history-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--line-2);
}

.history-head__icon {
  display: flex;
  width: 34px;
  height: 34px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: var(--brand-blue);
  background: var(--brand-blue-bg);
}

.history-head__title {
  font-size: 20px;
  font-weight: 600;
  color: var(--text-1);
}

.history-head__right {
  display: flex;
  align-items: center;
  gap: 18px;
  margin-left: auto;
}

.history-head__meta {
  font-size: 12px;
  color: var(--text-3);
}

/* 工具栏（R-26）：普通态贴右（清空 / 批量管理），管理态贴左（全选起手）+ 右侧「退出管理」 */
.history-toolbar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 16px;
  padding-top: 12px;
}

.history-toolbar.is-managing {
  justify-content: flex-start;
}

.history-toolbar__text {
  padding: 0;
  border: 0;
  font-size: 13px;
  color: var(--text-2);
  background: transparent;
  cursor: pointer;
}

.history-toolbar__text:hover {
  color: var(--brand-blue);
}

.history-toolbar__text--end {
  margin-left: auto;
}

.history-toolbar__count {
  font-size: 13px;
  color: var(--text-3);
}

.history-toolbar__action {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 12px;
  border: 1px solid var(--line-2);
  border-radius: 4px;
  font-size: 13px;
  color: var(--text-2);
  background: var(--bg-0);
  cursor: pointer;
}

.history-toolbar__action:hover:not(:disabled) {
  border-color: var(--brand-blue);
  color: var(--brand-blue);
}

.history-toolbar__action:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 删除失败的行内提示：列表还在，所以不用整页错误态 */
.history-error {
  margin-top: 10px;
  font-size: 13px;
  color: var(--brand-pink);
}

/* 「记录浏览历史」开关（R-25）：文字 + 胶囊。★ 刻意**不吸顶** —— B站 实测它会随页面滚走 */
.history-switch {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 0;
  border: 0;
  font-size: 13px;
  color: var(--text-2);
  background: transparent;
  cursor: pointer;
}

.history-switch__label {
  white-space: nowrap;
}

.history-switch__track {
  position: relative;
  width: 36px;
  height: 20px;
  border-radius: 10px;
  background: var(--line-1);
  transition: background 0.2s;
}

.history-switch[aria-checked='true'] .history-switch__track {
  background: var(--brand-blue);
}

.history-switch__knob {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: #fff;
  transition: transform 0.2s;
}

.history-switch[aria-checked='true'] .history-switch__knob {
  transform: translateX(16px);
}

.history-state {
  padding: 48px 0;
  font-size: 13px;
  color: var(--text-3);
  text-align: center;
}

/* 时间轴：竖线画在整段容器上（组间连续），每组 = 左栏标签 + 右栏网格 */
.history-timeline {
  position: relative;
}

.history-timeline::before {
  content: '';
  position: absolute;
  top: 0;
  bottom: 30px;
  left: 76px;
  width: 1px;
  background: var(--line-1);
}

.history-group {
  display: grid;
  grid-template-columns: 76px minmax(0, 1fr);
  margin-top: 26px;
}

.history-group__rail {
  position: relative;
  padding: 6px 24px 0 0;
  text-align: right;
}

.history-group__rail::after {
  content: '';
  position: absolute;
  top: 12px;
  right: -4px;
  width: 8px;
  height: 8px;
  border: 2px solid var(--text-4);
  border-radius: 50%;
  background: var(--bg-0);
}

.history-group__label {
  font-size: 15px;
  font-weight: 600;
  line-height: 22px;
  color: var(--text-2);
}

.history-group__main {
  padding: 0 0 30px 24px;
}

/* B 站 1600 下是 4 列；1372 起主区变窄，逐级降列 */
.history-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 26px 16px;
}

.history-skeleton {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 26px 16px;
  margin-top: 26px;
}

.history-skeleton__cover {
  aspect-ratio: 16 / 9;
}

.history-skeleton__line {
  height: 16px;
  margin-top: 10px;
}

.history-skeleton__line--short {
  width: 45%;
  margin-top: 8px;
}

@media (max-width: 1200px) {
  .history-grid,
  .history-skeleton {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 1000px) {
  .history-grid,
  .history-skeleton {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .history-timeline::before {
    display: none;
  }

  .history-group {
    grid-template-columns: minmax(0, 1fr);
    margin-top: 18px;
  }

  .history-group__rail {
    padding: 0 0 10px;
    text-align: left;
  }

  .history-group__rail::after {
    display: none;
  }

  .history-group__main {
    padding: 0 0 20px;
  }
}
</style>
