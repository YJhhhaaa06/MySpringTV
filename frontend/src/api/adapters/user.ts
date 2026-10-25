import type { components } from '../../types/api'
import type { AuthorCardVM, CurrentUserVM } from '../../types/ui'
import { formatCount } from './format'
import { spaceHref } from './links'

/**
 * `UserInfoVO` → 顶栏用户。
 *
 * <p>★ 后端**没有头像字段**（`UserInfoVO` 只有 id/username/phone/admin/coin/signedToday）
 * ⇒ 头像用昵称首字占位（`initial`）。这是 GAP 台账里的一条「M1 字段缺 / 前端适配」。
 */
export function toCurrentUser(me: components['schemas']['UserInfoVO']): CurrentUserVM {
  const name = me.username?.trim() || 'B站用户'
  const initial = Array.from(name)[0] ?? '用'
  return {
    id: me.id ?? 0,
    name,
    initial: initial.toUpperCase(),
    admin: me.admin === true,
    coin: me.coin ?? 0,
    spaceHref: spaceHref(me.id),
  }
}

/** `ProfileVO` → 播放页侧栏 UP 主卡（粉丝数 / 关注数 / 投稿数全部来自真接口） */
export function toAuthorCard(profile: components['schemas']['ProfileVO']): AuthorCardVM {
  const name = profile.username?.trim() || '未知UP主'
  return {
    id: profile.userId ?? 0,
    name,
    initial: (Array.from(name)[0] ?? 'U').toUpperCase(),
    href: spaceHref(profile.userId),
    followerText: formatCount(profile.followerCount),
    followingText: formatCount(profile.followCount),
    worksText: formatCount(profile.contentPage?.total),
  }
}
