/**
 * 首页推荐流的**状态机**（纯函数，无 Vue / DOM）。
 *
 * <p>为什么单独一层：翻页的正确性全在"这一页该不该收、收到哪里、旧请求怎么淘汰"上，
 * 这些在组件里只能靠肉眼看。本文件只做状态迁移；请求、滚动、骨架由
 * `composables/useRecommendFeed` 与 `pages/HomePage.vue` 接。
 *
 * <p>三条口径（依据 `architecture/domain/content/content-推荐流.md`）：
 * <ol>
 *   <li><b>`cursor === null` ⇔ 到底</b>（后端不变量；前端不靠"本页不满"猜）；</li>
 *   <li><b>`epoch`</b> 每次"整批替换"（首屏 / 刷新）+1。在途响应带回旧 epoch ⇒ 丢弃：
 *       否则刷新后旧序的续页会拼进新列表（重复 + 错序）；</li>
 *   <li><b>刷新失败保留旧 items / cursor</b>：列表没换，旧游标仍然有效。</li>
 * </ol>
 *
 * <p>★ 首屏空 **不等于** 到底：`items=[]` 走空态（池子真空 / 池读失败同形），
 * 只有"拿到过内容、`cursor=null`"才是"这次的推荐到底了"。
 */

/** `idle` 兼两义：还没开始（items 空）与"有下一页的稳态"（items 非空）；由 `feedView` 分开。 */
export type FeedPhase = 'idle' | 'initial' | 'loadingMore' | 'done' | 'error'

export interface FeedState<T> {
  items: T[]
  /** 上页返回的 `nextCursor`；null = 到底 */
  cursor: string | null
  phase: FeedPhase
  /** 仅 `phase==='error'` 有值；页面据此决定"页面级错误"还是"行内重试" */
  error: string | null
  /** 整批替换的代际；见文件头 ② */
  epoch: number
}

export interface FeedPage<T> {
  items: T[]
  nextCursor: string | null
}

export type FeedView =
  | 'initial'
  | 'refreshing'
  | 'ready'
  | 'loadingMore'
  | 'done'
  | 'empty'
  | 'error'
  | 'errorMore'

/**
 * 空页但游标非空 = 服务端违反了"空页只可能出现在候选耗尽时"。
 * 前端**必须停**（否则连续补页会打成死循环），并把它当失败暴露出来，不伪装成"到底"。
 */
export const EMPTY_PAGE_ERROR = '推荐流返回了空页，请稍后重试'

export function emptyFeed<T>(): FeedState<T> {
  return { items: [], cursor: null, phase: 'idle', error: null, epoch: 0 }
}

/** 首屏 / 刷新共用：开新代际，**不动 items**（刷新期间不闪屏；失败也留旧内容）。 */
export function beginRefresh<T>(state: FeedState<T>): FeedState<T> {
  if (state.phase === 'initial') return state
  return { ...state, phase: 'initial', error: null, epoch: state.epoch + 1 }
}

/** 第一页：**替换**列表（不是追加）。`epoch` 不匹配说明已被一次刷新淘汰。 */
export function receiveFirstPage<T>(state: FeedState<T>, page: FeedPage<T>, epoch: number): FeedState<T> {
  if (epoch !== state.epoch) return state
  if (!page.items.length && page.nextCursor !== null) {
    return { ...state, phase: 'error', error: EMPTY_PAGE_ERROR }
  }
  return {
    ...state,
    items: page.items,
    cursor: page.nextCursor,
    phase: page.nextCursor === null ? 'done' : 'idle',
    error: null,
  }
}

/** 续页前置：只有"稳态 + 有游标"才允许发（单飞由这里兜住，组件不必再判）。 */
export function beginMore<T>(state: FeedState<T>): FeedState<T> {
  if (state.phase !== 'idle' || state.cursor === null) return state
  return { ...state, phase: 'loadingMore', error: null }
}

/** 续页：**追加**；顺序即服务端返回的顺序，前端不再排序。 */
export function receiveMore<T>(state: FeedState<T>, page: FeedPage<T>, epoch: number): FeedState<T> {
  if (epoch !== state.epoch || state.phase !== 'loadingMore') return state
  if (!page.items.length && page.nextCursor !== null) {
    return { ...state, phase: 'error', error: EMPTY_PAGE_ERROR }
  }
  return {
    ...state,
    items: [...state.items, ...page.items],
    cursor: page.nextCursor,
    phase: page.nextCursor === null ? 'done' : 'idle',
    error: null,
  }
}

/** 续页失败：进 `error`（行内重试），**保留 cursor**，重试仍打同一页。 */
export function failMore<T>(state: FeedState<T>, message: string, epoch: number): FeedState<T> {
  if (epoch !== state.epoch || state.phase !== 'loadingMore') return state
  return { ...state, phase: 'error', error: message }
}

/** 刷新失败：有旧内容 ⇒ 回到原稳态（游标仍有效）；没有 ⇒ 页面级错误。 */
export function failRefresh<T>(state: FeedState<T>, message: string): FeedState<T> {
  if (state.phase !== 'initial') return state
  if (!state.items.length) return { ...state, phase: 'error', error: message }
  return { ...state, phase: state.cursor === null ? 'done' : 'idle', error: null }
}

/** 行内重试：`error → loadingMore`，错误清掉（重试期间不继续显示旧错误）。 */
export function retryMore<T>(state: FeedState<T>): FeedState<T> {
  if (state.phase !== 'error') return state
  return { ...state, phase: 'loadingMore', error: null }
}

export function shouldLoadMore<T>(state: FeedState<T>): boolean {
  return state.phase === 'idle' && state.cursor !== null
}

/**
 * 渲染态（页面只 switch 这一个值，不再散落 `items.length && phase === ...`）。
 *
 * - `initial`：首屏骨架（含还没开始的第一帧）；
 * - `refreshing`：有旧内容时的刷新，**不铺骨架**（否则闪屏）；
 * - `error` / `errorMore`：整页错误 vs 列表底部的行内重试；
 * - `empty` / `done`：首屏空 vs 刷到底 —— 两者不能混。
 */
export function feedView<T>(state: FeedState<T>): FeedView {
  if (state.phase === 'initial') return state.items.length ? 'refreshing' : 'initial'
  if (state.phase === 'loadingMore') return 'loadingMore'
  if (state.phase === 'error') return state.items.length ? 'errorMore' : 'error'
  if (state.phase === 'done') return state.items.length ? 'done' : 'empty'
  return state.items.length ? 'ready' : 'initial'
}
