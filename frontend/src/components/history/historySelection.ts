/**
 * 历史页「批量管理」的选择规则（**纯函数**，`R-26`）。
 *
 * <p>为什么单独成模块：这三条规则全是"点错了看不出来"的 ——
 * 全选的判据错一次会让"全选"永远不亮或永远亮着、删除后的翻页回退错一次会让用户
 * 停在一页空白上（看起来像"删完之后历史全没了"）。DOM 接线在 `HistoryPage.vue`，
 * 这里只留能单测的部分（同 `player/historyTracking.ts` 的手法）。
 *
 * <p>口径来源（B站 2026-10-31 实测）：
 * - **全选 = 已加载出来的全部条目**，不是"整库"（用户实拍：全选后显示"已选择 259 条历史记录"）；
 *   ⇒ 它永远走 `contentIds`，不会走 `all=true`（`all=true` 只服务工具栏的「清空历史」）；
 * - 管理态里除了删除**什么都做不了** ⇒ 选择集合只服务一个动作，不需要"操作类型"维度。
 */

/** 勾选 / 取消勾选一条（返回新集合，避免调用方误用同一个引用触发不了响应式）。 */
export function toggleSelection(selected: ReadonlySet<number>, contentId: number): Set<number> {
  const next = new Set(selected)
  if (next.has(contentId)) {
    next.delete(contentId)
    return next
  }
  next.add(contentId)
  return next
}

/**
 * 「全选 / 取消全选」二态切换。
 *
 * <p>★ 判据是"**当前已加载的**条目是否已被全选"，不是 `total`：
 * 分页下 `total` 包含没加载的页，用它判会让"全选"永远点不亮（或者一点就声称选了 259 条、
 * 实际只发出 20 个 id —— 那是更坏的一种：界面在撒谎）。
 */
export function toggleSelectAll(ids: readonly number[], selected: ReadonlySet<number>): Set<number> {
  if (ids.length > 0 && ids.every((id) => selected.has(id))) return new Set()
  return new Set(ids)
}

/** 是否已全选（只对**已加载**的条目成立；空列表恒 false —— 全选框此时应禁用）。 */
export function isAllSelected(ids: readonly number[], selected: ReadonlySet<number>): boolean {
  return ids.length > 0 && ids.every((id) => selected.has(id))
}

/**
 * 删除后当前页变空时，是否要回退一页。
 *
 * <p>★ 为什么需要它：删掉末页的全部条目后，`GET /history?page=N` 会返回空列表，
 * 而 `total` 又大于 0 ⇒ 页面停在"空白的一页"上，看起来像"历史没了"。
 * 判据只看两个数：本页剩余条数与当前页码（`page > 1` 才回退，第 1 页删空就该显示空状态）。
 */
export function shouldStepBackPage(remainingOnPage: number, page: number): boolean {
  return remainingOnPage === 0 && page > 1
}
