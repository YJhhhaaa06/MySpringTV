import type { components } from '../../types/api'
import type { RelationPageVM, RelationUserVM } from '../../types/ui'
import { initialOf } from './format'

/**
 * `FollowUserVO` → 关注/粉丝卡片。
 *
 * <p>★ `R-37` 起带 `avatarUrl`（随列表响应内联，与 username 同一次 users 查询）；
 * 仍**没有签名 / 等级** ⇒ 卡片保留「名字 + 关注按钮」，**不编签名**
 * （B 站那句「这个人很神秘，什么都没有写」是 B 站自己的默认签名，
 * 我们连"有没有签名"都不知道）。
 *
 * <p>`isFollowed` 是**布尔**而不是三态：两个列表端点都是类级 `@RequiresLogin`
 * （B 站同样要登录，`api.bilibili.com/x/relation/followings` 匿名返回 `-101`），
 * 因此后端一定知道"我是谁"，`false` 是真的"没关注"。
 */
export function toRelationUser(user: components['schemas']['FollowUserVO']): RelationUserVM {
  const name = user.username?.trim() || '未知用户'
  return {
    id: user.userId ?? 0,
    name,
    initial: initialOf(name),
    avatarUrl: user.avatarUrl?.trim() || null,
    followed: user.isFollowed === true,
    isSelf: user.isSelf === true,
  }
}

/** `PageResult<FollowUserVO>` → 一页（Page 分页，与搜索 / 空间作品同口径） */
export function toRelationPage(
  page: components['schemas']['PageResultFollowUserVO'] | undefined,
): RelationPageVM {
  const list = page?.list ?? []
  return {
    items: list.map((item) => toRelationUser(item)),
    page: page?.page ?? 1,
    total: page?.total ?? list.length,
    totalPages: page?.totalPages ?? 0,
  }
}
