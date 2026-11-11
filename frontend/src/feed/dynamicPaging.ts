/**
 * 动态流的**分页口径**（纯函数，无 Vue / DOM）—— 与通用状态机 `feed/listState.ts` 分工：
 * 状态机只管"收不收、往哪拼、旧响应怎么淘汰"，这里管"下一页的令牌是什么、哪些条目该丢"。
 *
 * <p>两条规则都有判别力：
 * <ol>
 *   <li><b>令牌 = 下一页页码</b>：`page >= totalPages` ⇒ `null`（到底）。动态流的 `total` 在
 *       **全部模式**下因快照锚点而稳定、在**作者模式**下是作者全部投稿 ⇒ 这一判据是精确的，
 *       不靠"本页不满"猜（服务端会跳过失效内容，短页 ≠ 到底）；</li>
 *   <li><b>按 id 去重</b>：作者模式没有快照锚点，作者在你翻页期间发新内容会让 offset 整体后移 ——
 *       只会造成**重复**、不会漏条，故去重即可收敛。</li>
 * </ol>
 */
import type { DynamicPageVM } from '../types/ui'

/** 动态页的数据源：全部动态（`/feed`）或某个作者（`/users/{id}.contentPage`）。 */
export type DynamicSource = { mode: 'all' } | { mode: 'author'; authorId: number }

/** 数据源身份键：变了 ⇒ 整批清空重来（不能让上一位作者的卡片挂在新标题下）。 */
export function sourceKeyOf(source: DynamicSource): string {
  return source.mode === 'all' ? 'all' : `author:${source.authorId}`
}

/** 下一页令牌；`null` = 到底。 */
export function nextPageToken(page: Pick<DynamicPageVM, 'page' | 'totalPages'>): string | null {
  return page.page < page.totalPages ? String(page.page + 1) : null
}

/** 追加时按 `id` 去重（已加载的顺序即服务端顺序，不再排序）。 */
export function appendUnique<T extends { id: number }>(existing: T[], incoming: T[]): T[] {
  if (!existing.length) return incoming
  const seen = new Set(existing.map((item) => item.id))
  return incoming.filter((item) => !seen.has(item.id))
}
