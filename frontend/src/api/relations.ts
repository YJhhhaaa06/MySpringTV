/**
 * 关注 / 粉丝列表（`GET /users/{id}/following|followers`）。
 *
 * <p>★ 两个端点都是类级 `@RequiresLogin` —— B 站也一样：
 * `api.bilibili.com/x/relation/followings?vmid=2` 匿名返回 `{"code":-101,"message":"账号未登录"}`
 * （2026-10-27 实测）。所以这不是本仓的缺口，是**关注关系的隐私口径**；
 * 前端在空间页的这两个 tab 里渲染「登录后查看」，而不是把 401 画成空列表。
 *
 * <p>载荷是 `PageResult<FollowUserVO>`：只有 `userId / username / isFollowed / isSelf`，
 * 没有头像与签名（GAP 台账）⇒ 卡片只放首字头像 + 名字 + 关注按钮。
 */
import type { RelationPageVM } from '../types/ui'
import { toRelationPage } from './adapters/relation'
import { getJson } from './http'

/** B 站是 3 列 × 10 行 = 30 人/页 */
export const RELATION_PAGE_SIZE = 30

/** `GET /users/{id}/following`：`id` 关注了谁 */
export async function loadFollowing(
  userId: number,
  page = 1,
  pageSize = RELATION_PAGE_SIZE,
): Promise<RelationPageVM> {
  const result = await getJson('/api/v1/users/{id}/following', {
    path: { id: userId },
    query: { page, pageSize },
  })
  return toRelationPage(result)
}

/** `GET /users/{id}/followers`：谁关注了 `id` */
export async function loadFollowers(
  userId: number,
  page = 1,
  pageSize = RELATION_PAGE_SIZE,
): Promise<RelationPageVM> {
  const result = await getJson('/api/v1/users/{id}/followers', {
    path: { id: userId },
    query: { page, pageSize },
  })
  return toRelationPage(result)
}
