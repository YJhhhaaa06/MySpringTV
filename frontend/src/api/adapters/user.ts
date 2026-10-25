import type { components } from '../../types/api'
import type { AuthorCardVM, Count, CurrentUserVM } from '../../types/ui'
import { spaceHref } from './links'

/** 计数：非数字 ⇒ null（渲染成 `-`） */
function count(value: number | null | undefined): Count {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

/** 昵称首字（按码点取，不切坏 emoji 代理对） */
function firstChar(name: string): string {
  return Array.from(name)[0] ?? '用'
}

/**
 * `UserInfoVO` → 顶栏用户。
 *
 * <p>★ 后端**没有头像字段**（`UserInfoVO` 只有 id/username/phone/admin/coin/signedToday）
 * ⇒ 头像用昵称首字占位（`initial`）。这是 GAP 台账里的一条「M1 字段缺 / 前端适配」。
 */
export function toCurrentUser(me: components['schemas']['UserInfoVO']): CurrentUserVM {
  const name = me.username?.trim() || 'B站用户'
  return {
    id: me.id ?? 0,
    name,
    initial: firstChar(name).toUpperCase(),
    admin: me.admin === true,
    coin: me.coin ?? 0,
    spaceHref: spaceHref(me.id),
  }
}

/**
 * `ProfileVO` → 播放页侧栏 UP 主卡（粉丝数 / 关注数 / 投稿数全部来自真接口）。
 *
 * <p>计数保持**数字**：关注按钮的乐观更新要加减，展示层的万/亿由渲染处格式化。
 * <p>`isFollowed` 匿名时后端给 `null` ⇒ VM 也是 `null`（三态，**不回退成 false**）。
 */
export function toAuthorCard(profile: components['schemas']['ProfileVO']): AuthorCardVM {
  const name = profile.username?.trim() || '未知UP主'
  return {
    id: profile.userId ?? 0,
    name,
    initial: firstChar(name).toUpperCase(),
    href: spaceHref(profile.userId),
    followerCount: count(profile.followerCount),
    followingCount: count(profile.followCount),
    worksCount: count(profile.contentPage?.total),
    followed: typeof profile.isFollowed === 'boolean' ? profile.isFollowed : null,
  }
}
