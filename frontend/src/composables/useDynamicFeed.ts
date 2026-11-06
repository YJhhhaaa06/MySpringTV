import { computed, onBeforeUnmount, ref } from 'vue'
import { FEED_PAGE_SIZE, loadFeed } from '../api/feed'
import { ApiError, describeError } from '../api/http'
import { loadSpace } from '../api/profile'
import {
  appendUnique,
  nextPageToken,
  sourceKeyOf,
  type DynamicSource,
} from '../feed/dynamicPaging'
import {
  beginMore,
  beginRefresh,
  clearedFeed,
  emptyFeed,
  failMore,
  failRefresh,
  feedView,
  receiveFirstPage,
  receiveMore,
  retryMore,
  shouldLoadMore,
  type FeedState,
} from '../feed/listState'
import type { DynamicCardVM, DynamicPageVM, SpaceHeaderVM } from '../types/ui'

/**
 * 动态流的行为机：**请求 + 状态迁移 + 两种数据源**。
 *
 * <p>迁移规则在 `feed/listState.ts`（与首页推荐流共用）；"下一页是谁、哪些该丢"在
 * `feed/dynamicPaging.ts`。这里只做三件有副作用的事：发请求、把失败翻成人话、切源时清空。
 *
 * <p>★ 两种源的分页口径不同，但**列表状态是同一个问题**：
 * <ul>
 *   <li><b>全部动态</b>（`/feed`）：第 2 页起带**快照锚点**（首屏第一条 id），新内容插到头部
 *       不会让 offset 漂；`total` 是锚点内条数（有界窗口 ≤300）；</li>
 *   <li><b>作者模式</b>（`/users/{id}.contentPage`）：没有锚点，靠**按 id 去重**收敛
 *       （作者在你翻页期间发新内容只会造成重复、不会漏条）。</li>
 * </ul>
 *
 * <p>★ 切源（全部 ↔ 某作者）**先清空**：不能让上一位作者的卡片短暂挂在新标题下。
 * 同一作者刷新 / 重载不清空（避免白屏闪），由状态机的 `beginRefresh` 保留旧 items。
 *
 * <p>⚠️ 与 `useRecommendFeed` 同款盲区：DOM 接线 / 定时器 / 卸载时机没有 jsdom 自动化，
 * 纯逻辑（令牌 / 去重 / 状态迁移）由单测覆盖，其余靠 review + 真浏览器。
 */
export function useDynamicFeed(source: () => DynamicSource) {
  const state = ref<FeedState<DynamicCardVM>>(emptyFeed<DynamicCardVM>())
  /** 当前快照的 total（全部模式 = 锚点内；作者模式 = 作者全部投稿） */
  const total = ref(0)
  /** 作者模式的作者头（同一个 `GET /users/{id}` 顺带返回，不额外请求） */
  const authorHeader = ref<SpaceHeaderVM | null>(null)

  /** 全部模式的快照锚点（首屏第一条 id）；作者模式恒 null */
  let anchorId: number | null = null
  /** 已经装载过的源；变了才清空 */
  let loadedSourceKey = ''
  let disposed = false

  const view = computed(() => feedView(state.value))
  const errorText = computed(() => state.value.error ?? '')

  function messageOf(exception: unknown, src: DynamicSource): string {
    if (exception instanceof ApiError && exception.status === 404 && src.mode === 'author') {
      return '这个用户不存在'
    }
    return describeError(exception, '加载失败，请稍后重试')
  }

  /** 取一页：两种源返回同一个 VM 形状（`items/page/total/totalPages`）。 */
  async function fetchPage(page: number, anchor: number | null, src: DynamicSource): Promise<DynamicPageVM> {
    if (src.mode === 'author') {
      const data = await loadSpace(src.authorId, page, FEED_PAGE_SIZE)
      authorHeader.value = data.header
      return data.timeline
    }
    return loadFeed(page, FEED_PAGE_SIZE, anchor)
  }

  /** 首屏 / 刷新：整批替换（刷新期间保留旧内容，由状态机保证）。 */
  async function refresh(): Promise<boolean> {
    const src = source()
    const key = sourceKeyOf(src)
    if (key !== loadedSourceKey) {
      loadedSourceKey = key
      state.value = clearedFeed(state.value)
      total.value = 0
      anchorId = null
      authorHeader.value = null
    }

    const next = beginRefresh(state.value)
    if (next === state.value) return false
    state.value = next
    const epoch = next.epoch

    try {
      const page = await fetchPage(1, null, src)
      if (disposed || epoch !== state.value.epoch) return false
      total.value = page.total
      // 锚点取**首屏第一条**：服务端按 id<=锚点切后续页，已加载区段就冻结了
      anchorId = page.items[0]?.id ?? null
      state.value = receiveFirstPage(
        state.value,
        { items: page.items, nextCursor: nextPageToken(page) },
        epoch,
      )
      return state.value.phase !== 'error'
    } catch (exception) {
      if (disposed || epoch !== state.value.epoch) return false
      state.value = failRefresh(state.value, messageOf(exception, src))
      return false
    }
  }

  /** 续页的请求部分：调用前 phase 必须已经是 `loadingMore`。 */
  async function runMore(src: DynamicSource): Promise<void> {
    const epoch = state.value.epoch
    const token = state.value.cursor
    const page = token === null ? NaN : Number(token)
    if (!Number.isInteger(page) || page < 1) {
      // 不该发生（beginMore 只在有令牌时放行）；真发生了也必须离开 loadingMore
      state.value = failMore(state.value, '加载状态异常，请刷新页面', epoch)
      return
    }
    try {
      const data = await fetchPage(page, src.mode === 'all' ? anchorId : null, src)
      if (disposed || epoch !== state.value.epoch) return
      total.value = data.total
      state.value = receiveMore(
        state.value,
        { items: appendUnique(state.value.items, data.items), nextCursor: nextPageToken(data) },
        epoch,
      )
    } catch (exception) {
      if (disposed || epoch !== state.value.epoch) return
      state.value = failMore(state.value, messageOf(exception, src), epoch)
    }
  }

  /** 滚动触底 / 哨兵触发（单飞由 `beginMore` 兜住）。 */
  async function loadMore(): Promise<void> {
    const next = beginMore(state.value)
    if (next === state.value) return
    state.value = next
    await runMore(source())
  }

  /** 行内「重试」：复用同一令牌，重试仍打同一页。 */
  async function retry(): Promise<void> {
    const next = retryMore(state.value)
    if (next === state.value) return
    state.value = next
    await runMore(source())
  }

  /** 退出登录 / 页面失效：回到空状态（下一次 refresh 会重新判定源）。 */
  function reset(): void {
    state.value = clearedFeed(state.value)
    total.value = 0
    anchorId = null
    authorHeader.value = null
    loadedSourceKey = ''
  }

  onBeforeUnmount(() => {
    disposed = true
  })

  return {
    state,
    total,
    authorHeader,
    view,
    errorText,
    refresh,
    loadMore,
    retry,
    reset,
    shouldLoadMore: () => shouldLoadMore(state.value),
  }
}
