/**
 * 关注动态流（`GET /feed`）。
 *
 * <p>**必须登录**（后端类级 `@RequiresLogin`，B 站同样要登录）：匿名请求是 401，
 * 不是"空列表"。页面因此走**页面级身份态**（见 `architecture/frontend/前端.md` §五）。
 *
 * <p>分页是 **Page**（`page/pageSize/total/totalPages`），但无限滚动时第 2 页起要带
 * **快照锚点** `beforeContentId`（= 首屏第一条的 id）：否则新内容插到头部会让 offset 整体后移，
 * 第 2 页重复上一页末条。带锚点时 `total` = 锚点内条数，与已冻结的列表一致。
 */
import type { DynamicPageVM } from '../types/ui'
import { toDynamicPage } from './adapters/feed'
import { getJson } from './http'

/** B 站动态流一屏大约 10–12 条；本仓按 12 条/页（与首页一屏一致） */
export const FEED_PAGE_SIZE = 12

/**
 * `now` **不在这里给默认值**：`undefined` 落到 `toDynamicPage` 的 `serverNow()`。
 * 端点层再写一个 `new Date()` 就有两个"现在"来源（见 `endpoints.ts#loadHome` 的说明）。
 *
 * @param beforeContentId 快照锚点（首屏第一条 id）；首屏 / 刷新传 `null`
 */
export async function loadFeed(
  page = 1,
  pageSize = FEED_PAGE_SIZE,
  beforeContentId: number | null = null,
  now?: Date,
): Promise<DynamicPageVM> {
  const result = await getJson('/api/v1/feed', {
    query: { page, pageSize, ...(beforeContentId ? { beforeContentId } : {}) },
  })
  return toDynamicPage(result, now)
}

/**
 * `/dynamic` 的 query：**作者模式必须留在 URL 里**（可分享 / 刷新不丢筛选）。
 *
 * <p>`R-32` 起去掉了 `page`（无限滚动不写页码）；旧链接里的 `page` 不再读取，落到第 1 页。
 */
export function dynamicQuery(authorId: number | null): Record<string, string> {
  return authorId ? { author: String(authorId) } : {}
}
