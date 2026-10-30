/**
 * 跨应用链接的**唯一出处**（跨应用整页跳转、新标签打开）。
 *
 * <p>★ 空间页（`space.html`）已落地（2026-10-25）⇒ `spaceHref` 从占位路由切回真路径。
 * 切回只改这一处：卡片、播放页、顶栏头像都从这里取链接。
 */

/** 内容 id 是数据库自增（仿不了 B 站的 BV 号）—— 路径形状仿，id 语义仿不了 */
export function videoHref(contentId: number | null | undefined): string {
  return contentId === null || contentId === undefined ? '/placeholder/video' : `/video/${contentId}`
}

/** 图文详情（opus）落点；B 站图文类标准链接就是 `/opus/{id}`（id 本仓仍是自增内容 id） */
export function opusHref(contentId: number | null | undefined): string {
  return contentId === null || contentId === undefined ? '/placeholder/opus' : `/opus/${contentId}`
}

/**
 * 内容详情的**唯一落点规则**：`type=POST → /opus/{id}`，视频 → `/video/{id}`。
 *
 * <p>带 `ContentVO.type` 的面（首页网格 / 轮播 / 搜索 / 空间作品 / 动态流 / 相关推荐）
 * 一律走这里；**收藏夹 / 历史 / 稍后再看**的条目 VO 没有 `type`（GAP-16 / GAP-20），
 * 继续用 {@link videoHref} 兜底 —— `/video/{id}` 取到详情发现是图文后由播放页重定向到
 * `/opus/{id}`（口径见 `architecture/frontend/前端.md` §一 / §六）。
 */
export function contentHref(
  contentId: number | null | undefined,
  isPost: boolean,
): string {
  return isPost ? opusHref(contentId) : videoHref(contentId)
}

export function spaceHref(userId: number | null | undefined): string {
  if (userId === null || userId === undefined) return '/placeholder/space'
  return `/space/${userId}`
}
