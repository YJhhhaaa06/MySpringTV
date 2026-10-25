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

export function spaceHref(userId: number | null | undefined): string {
  if (userId === null || userId === undefined) return '/placeholder/space'
  return `/space/${userId}`
}
