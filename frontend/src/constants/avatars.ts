/**
 * 默认头像的唯一前端口径（`R-27`）。
 *
 * <h2>为什么是稳定 URL，而不是 `src/assets` 的 import</h2>
 * 后端 `users.avatar_ref` 存的是选择（`default:1..6` 或自定义 URL），API 出口统一解析成
 * `avatarUrl`。默认头像也必须是一个**稳定 URL**（`/avatars/default-N.svg`），否则后端
 * 无法返回它、批量端点也无法把"选了默认 3"解析成同一个字符串。
 *
 * <p>六张 SVG 在 `frontend/public/avatars/`：
 * dev 由 Vite 直接服务；生产 `npm run stage` 复制到 Spring 静态根。顺序保持不变
 * （sunrise→1、planet→2、cat→3、bird→4、tv→5、cloud→6），与上一轮 `userId % 6` 的分配一致。
 */

export const DEFAULT_AVATAR_COUNT = 6

/** 1..6 的固定集合（设置弹层的六宫格按这个顺序渲染）。 */
export const DEFAULT_AVATAR_IDS = [1, 2, 3, 4, 5, 6] as const

/** 默认头像编号是否合法。 */
export function isDefaultAvatarId(value: number): boolean {
  return Number.isInteger(value) && value >= 1 && value <= DEFAULT_AVATAR_COUNT
}

/** 编号 → 稳定 URL（与后端 `AvatarRefs#defaultUrl` 逐字一致）。 */
export function defaultAvatarUrl(avatarId: number): string {
  return `/avatars/default-${avatarId}.svg`
}

/**
 * 未选过头像时按 `userId` 回落（与上一轮 `Math.abs(userId) % 6` 的顺序一致）。
 *
 * `userId` 为 null / 非有限数 ⇒ null（调用方退回昵称首字）。
 */
export function defaultAvatarForUser(userId: number | null | undefined): string | null {
  if (typeof userId !== 'number' || !Number.isFinite(userId)) return null
  return defaultAvatarUrl((Math.abs(userId) % DEFAULT_AVATAR_COUNT) + 1)
}

/** 当前 URL 是否是某张默认头像；是则返回 1..6，否则 null（设置弹层高亮当前默认）。 */
export function defaultAvatarIdOf(url: string | null | undefined): number | null {
  if (typeof url !== 'string') return null
  const match = /^\/avatars\/default-(\d)\.svg$/.exec(url)
  if (!match) return null
  const id = Number(match[1])
  return isDefaultAvatarId(id) ? id : null
}
