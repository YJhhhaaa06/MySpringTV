/**
 * 跨应用链接的**唯一出处**（跨应用整页跳转、新标签打开）。
 *
 * <p>★ UP 空间页本窗口未交付 ⇒ 暂挂占位路由（`/placeholder/space?uid=…`）。
 * 空间页落地后**只改这里**，卡片与播放页都不用动。
 */

/** 内容 id 是数据库自增（仿不了 B 站的 BV 号）—— 路径形状仿，id 语义仿不了 */
export function videoHref(contentId: number | null | undefined): string {
  return contentId === null || contentId === undefined ? '/placeholder/video' : `/video/${contentId}`
}

export function spaceHref(userId: number | null | undefined): string {
  if (userId === null || userId === undefined) return '/placeholder/space'
  return `/placeholder/space?uid=${userId}`
}
