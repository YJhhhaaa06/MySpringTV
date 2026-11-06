import { describe, expect, it } from 'vitest'
import {
  EMPTY_PAGE_ERROR,
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
  type FeedPage,
  type FeedState,
} from './listState'

/**
 * 状态机的判别力只来自"迁移写错就会红"：
 * 每条期望都由口径推导（`cursor=null` ⇔ 到底 / 刷新淘汰在途续页 / 失败保留旧列表），
 * 不是抄实现。反向注入判据：去掉 epoch 判断 ⇒「刷新淘汰在途续页」「旧失败晚到」必红。
 */

const page = (items: number[], nextCursor: string | null): FeedPage<number> => ({ items, nextCursor })

/** ready + 还有下一页（`idle` 的常态形态）。 */
function ready(cursor: string | null = 'v1.7.3'): FeedState<number> {
  return receiveFirstPage(beginRefresh(emptyFeed<number>()), page([1, 2], cursor), 1)
}

describe('列表续页状态机（推荐流 / 动态流共用）', () => {
  it('空状态：算首屏（铺骨架），不算"没有内容"也不是"到底"', () => {
    const state = emptyFeed<number>()
    expect(feedView(state)).toBe('initial')
    expect(shouldLoadMore(state)).toBe(false)
  })

  it('整批替换开新代际，且刷新期间不动 items；重复刷新不叠加', () => {
    const before = ready()
    const refreshing = beginRefresh(before)
    expect(refreshing.epoch).toBe(before.epoch + 1)
    expect(refreshing.phase).toBe('initial')
    expect(refreshing.items).toEqual(before.items)
    expect(feedView(refreshing)).toBe('refreshing')
    expect(beginRefresh(refreshing)).toBe(refreshing)
  })

  it('第一页替换列表；有游标 = ready，游标 null = 到底', () => {
    const withCursor = ready()
    expect(withCursor.items).toEqual([1, 2])
    expect(withCursor.phase).toBe('idle')
    expect(feedView(withCursor)).toBe('ready')
    expect(shouldLoadMore(withCursor)).toBe(true)

    const ended = receiveFirstPage(beginRefresh(emptyFeed<number>()), page([1, 2], null), 1)
    expect(ended.phase).toBe('done')
    expect(feedView(ended)).toBe('done')
    expect(shouldLoadMore(ended)).toBe(false)
  })

  it('首屏拿不到内容 = empty，不是"到底"', () => {
    const state = receiveFirstPage(beginRefresh(emptyFeed<number>()), page([], null), 1)
    expect(feedView(state)).toBe('empty')
  })

  it('空页但游标非空 ⇒ 失败（防连续补页死循环），不伪装成到底', () => {
    const state = receiveFirstPage(beginRefresh(emptyFeed<number>()), page([], 'v1.7.9'), 1)
    expect(state.phase).toBe('error')
    expect(state.error).toBe(EMPTY_PAGE_ERROR)
    expect(state.items).toEqual([])
    expect(state.cursor).toBeNull()
    expect(feedView(state)).toBe('error')
  })

  it('★ 已到底后刷新遇到空页（游标非空）⇒ 页面级错误，不冒充续页错误', () => {
    const ended = receiveFirstPage(beginRefresh(emptyFeed<number>()), page([1, 2], null), 1)
    const refreshing = beginRefresh(ended)
    const broken = receiveFirstPage(refreshing, page([], 'v1.9.0'), refreshing.epoch)

    // 旧列表清掉：否则 feedView 会因 items 非空给出 errorMore，而它的重试是"续页"
    expect(broken.items).toEqual([])
    expect(broken.cursor).toBeNull()
    expect(feedView(broken)).toBe('error')
    // 页面级错误只能刷新，不能进 loadingMore（否则光标为空、请求发不出去，永久骨架）
    expect(retryMore(broken)).toBe(broken)
  })

  it('旧代际的第一页响应被丢弃（刷新已发生）', () => {
    const state = ready()
    expect(receiveFirstPage(state, page([9], 'v1.7.9'), state.epoch - 1)).toBe(state)
  })

  it('续页：追加而不是替换，顺序保持；游标 null 收尾', () => {
    const loading = beginMore(ready())
    expect(loading.phase).toBe('loadingMore')
    expect(feedView(loading)).toBe('loadingMore')
    expect(shouldLoadMore(loading)).toBe(false)

    const appended = receiveMore(loading, page([3, 4], 'v1.7.5'), loading.epoch)
    expect(appended.items).toEqual([1, 2, 3, 4])
    expect(appended.cursor).toBe('v1.7.5')
    expect(appended.phase).toBe('idle')

    const ended = receiveMore(beginMore(appended), page([5], null), appended.epoch)
    expect(ended.items).toEqual([1, 2, 3, 4, 5])
    expect(ended.phase).toBe('done')
    expect(feedView(ended)).toBe('done')
  })

  it('没有游标时拒绝发续页', () => {
    const state = receiveFirstPage(beginRefresh(emptyFeed<number>()), page([1], null), 1)
    expect(beginMore(state)).toBe(state)
  })

  it('续页空页但游标非空 ⇒ 失败（行内重试），不清已加载内容', () => {
    const loading = beginMore(ready())
    const state = receiveMore(loading, page([], 'v1.7.9'), loading.epoch)
    expect(state.items).toEqual([1, 2])
    expect(feedView(state)).toBe('errorMore')
    expect(state.error).toBe(EMPTY_PAGE_ERROR)
  })

  it('★ 刷新淘汰在途续页：旧续页晚到不会拼进新列表', () => {
    const loading = beginMore(ready())
    const oldEpoch = loading.epoch

    // 刷新 + 新列表 + 又一次续页在途：此刻 phase 仍是 loadingMore，
    // 只有 epoch 能区分"这是上一代的响应"（只看 phase 的实现在这里会假绿）。
    const refreshing = beginRefresh(loading)
    const fresh = receiveFirstPage(refreshing, page([7, 8], 'v1.8.0'), refreshing.epoch)
    const newLoading = beginMore(fresh)
    expect(newLoading.phase).toBe('loadingMore')

    const stale = receiveMore(newLoading, page([99], 'v1.9.0'), oldEpoch)
    expect(stale).toBe(newLoading)
    expect(stale.items).toEqual([7, 8])
    expect(stale.cursor).toBe('v1.8.0')
  })

  it('续页失败进行内错误；重试复用同一游标并清掉错误', () => {
    const loading = beginMore(ready())
    const failed = failMore(loading, '网络异常', loading.epoch)
    expect(failed.phase).toBe('error')
    expect(feedView(failed)).toBe('errorMore')
    expect(failed.cursor).toBe('v1.7.3')

    const retried = retryMore(failed)
    expect(retried.phase).toBe('loadingMore')
    expect(retried.error).toBeNull()
    expect(retried.cursor).toBe('v1.7.3')
  })

  it('旧代际的续页失败被丢弃（新续页在途时旧失败晚到）', () => {
    const oldLoading = beginMore(ready())
    const oldEpoch = oldLoading.epoch
    const refreshing = beginRefresh(oldLoading)
    const fresh = receiveFirstPage(refreshing, page([7, 8], 'v1.8.0'), refreshing.epoch)
    const newLoading = beginMore(fresh)
    expect(newLoading.phase).toBe('loadingMore')

    // 只看 phase 的实现在这里会把新列表打成 errorMore —— 必须靠 epoch 区分
    expect(failMore(newLoading, '旧请求失败', oldEpoch)).toBe(newLoading)
  })

  it('刷新失败保留旧列表并回到原稳态（游标仍有效）', () => {
    const failed = failRefresh(beginRefresh(ready()), '网络异常')
    expect(failed.items).toEqual([1, 2])
    expect(failed.cursor).toBe('v1.7.3')
    expect(failed.phase).toBe('idle')
    expect(failed.error).toBeNull()
    expect(feedView(failed)).toBe('ready')
  })

  it('刷到底之后再刷新失败，回到 done 而不是 idle', () => {
    const ended = receiveFirstPage(beginRefresh(emptyFeed<number>()), page([1], null), 1)
    const failed = failRefresh(beginRefresh(ended), '网络异常')
    expect(failed.phase).toBe('done')
    expect(feedView(failed)).toBe('done')
  })

  it('首屏刷新失败 = 页面级错误（没有旧内容可留）', () => {
    const failed = failRefresh(beginRefresh(emptyFeed<number>()), '连不上后端')
    expect(failed.phase).toBe('error')
    expect(feedView(failed)).toBe('error')
    expect(failed.error).toBe('连不上后端')
  })

  it('纯函数不改入参（追加不能就地 push）', () => {
    const before = ready()
    const snapshot = [...before.items]
    const loading = beginMore(before)
    receiveMore(loading, page([3], null), loading.epoch)
    expect(before.items).toEqual(snapshot)
  })

  it('★ 清空保留代际：切数据源后旧源的响应不会被当成新源的第一页', () => {
    const old = ready('v1.7.3')
    const cleared = clearedFeed(old)
    expect(cleared.items).toEqual([])
    expect(cleared.epoch).toBe(old.epoch)

    const next = beginRefresh(cleared)
    expect(next.epoch).toBe(old.epoch + 1)
    // 旧代际的响应回来 ⇒ 丢弃（否则换作者时会把上一位作者的卡片写进新列表）
    expect(receiveFirstPage(next, page([9], null), old.epoch)).toBe(next)
  })
})
