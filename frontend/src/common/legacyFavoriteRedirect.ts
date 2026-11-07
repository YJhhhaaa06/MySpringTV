/**
 * 旧收藏页 URL → 空间收藏 tab 的映射（`R-34` 软弃用）。
 *
 * <p>`/favorites` / `/watchlater` 不再提供独立页面，只保留薄重定向壳。旧 query 要尽量
 * 平移到新 query，避免旧书签丢掉"选中的夹 / 页码"：
 * - `folder` → `folder`
 * - `page` → `itemPage`
 * - `/watchlater` 或 `?tab=later` → `view=later`（此时忽略 `folder`）
 *
 * <p>非法 / 缺省值直接忽略（不把 `folder=abc` 写成 `folder=NaN`）。
 */
export function legacyFavoriteTarget(
  routeName: string | null | undefined,
  query: Record<string, unknown>,
  userId: number,
): string {
  const isLater = routeName === 'watchlater' || query.tab === 'later'
  const params = new URLSearchParams()
  params.set('tab', 'folders')
  if (isLater) {
    params.set('view', 'later')
  } else {
    const folderId = positive(query.folder)
    if (folderId > 0) params.set('folder', String(folderId))
  }
  const page = positive(query.page)
  if (page > 1) params.set('itemPage', String(page))
  return `/space/${userId}?${params.toString()}`
}

function positive(raw: unknown): number {
  const value = Number(raw)
  return Number.isInteger(value) && value > 0 ? value : 0
}
