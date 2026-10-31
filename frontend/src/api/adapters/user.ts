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
 * <p>★ 后端**没有头像字段**（`UserInfoVO` 只有 id/username/phone/admin/coin/signedToday/historyRecording）
 * ⇒ 头像用昵称首字占位（`initial`）。这是 GAP 台账里的一条「M1 字段缺 / 前端适配」。
 *
 * <p>★ `signedToday` 必须原样带出：它是签到按钮「进页就置灰」的唯一判据
 * （`UserInfoVO` 加这一列**就是为此**，见 `architecture/domain/coin/coin.md` §二）。
 * 早先这里只映射了 `coin`，字段在 spec 里、在 VO 里都有，却在适配层被丢掉 ——
 * 于是"没有签到状态"看起来像后端没做。
 */
export function toCurrentUser(me: components['schemas']['UserInfoVO']): CurrentUserVM {
  const name = me.username?.trim() || 'B站用户'
  return {
    id: me.id ?? 0,
    name,
    initial: firstChar(name).toUpperCase(),
    admin: me.admin === true,
    coin: me.coin ?? 0,
    // 只认 true：缺字段 = 不知道，但签到按钮宁可"先别亮"（同 `admin` 的口径）
    signedToday: me.signedToday === true,
    // ★ 与上面两条**反向**口径（只认 false）：这是隐私开关，缺字段 = 老后端没有这个能力 ⇒
    //   按"开"处理（与 DB 默认值 1 一致）。写成 `=== true` 会让一次字段缺失把所有人的记录静默关掉。
    historyRecording: me.historyRecording !== false,
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
