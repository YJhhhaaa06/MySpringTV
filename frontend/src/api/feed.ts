/**
 * 关注动态流（`GET /feed`）。
 *
 * <p>**必须登录**（后端类级 `@RequiresLogin`，B 站同样要登录）：匿名请求是 401，
 * 不是"空列表"。页面因此走**页面级身份态**（见 `architecture/frontend/前端.md` §五）。
 *
 * <p>分页是 **Page**（`page/pageSize/total/totalPages`），不是游标；
 * `total` = 读侧窗口实际条数（有界窗口，后端深翻返回空页）—— 前端照常按 `totalPages` 翻页即可。
 */
import type { DynamicPageVM } from '../types/ui'
import { toDynamicPage } from './adapters/feed'
import { getJson } from './http'

/** B 站动态流一屏大约 10–12 条；本仓按 12 条/页（与首页一屏一致） */
export const FEED_PAGE_SIZE = 12

export async function loadFeed(
  page = 1,
  pageSize = FEED_PAGE_SIZE,
  now: Date = new Date(),
): Promise<DynamicPageVM> {
  const result = await getJson('/api/v1/feed', { query: { page, pageSize } })
  return toDynamicPage(result, now)
}
