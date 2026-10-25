import type { components } from '../../types/api'
import type { InteractionVM } from '../../types/ui'

/**
 * `InteractionStatusVO` → 互动态（**三态**，见 `types/ui.ts`）。
 *
 * <h2>★ 三态怎么落到 VM 上</h2>
 * <pre>
 * interaction == null           → null          （匿名：本面根本不查）
 * interaction.isFollowed == null→ followed: null（本面只查了赞，如 /users/{id}、/feed）
 * interaction.coin == null      → coin: 0       （币数没有 null 的含义；缺省即没投）
 * </pre>
 *
 * <p>⚠️ **不许把 `null` 归一成 `false`**：那会让"后端没告诉我"伪装成"肯定没关注"，
 * 用户点一下关注 → 后端 409/幂等 → 界面显示"关注成功"，而真实状态从来没被读过。
 */
export function toInteraction(
  status: components['schemas']['InteractionStatusVO'] | null | undefined,
): InteractionVM | null {
  if (!status) return null
  return {
    liked: status.isLiked === true,
    coin: typeof status.coin === 'number' && Number.isFinite(status.coin) ? status.coin : 0,
    favorited: status.isFavorited === true,
    followed: typeof status.isFollowed === 'boolean' ? status.isFollowed : null,
  }
}

/**
 * 列表面只回「赞」这一维（`/users/{id}`、`/feed`）：`interaction` 里其余三维是 `null`。
 *
 * @returns `true/false` 为真实状态；`null` = 本面不查（未登录 / 端点没查这一维）
 */
export function likedOf(
  status: components['schemas']['InteractionStatusVO'] | null | undefined,
): boolean | null {
  return typeof status?.isLiked === 'boolean' ? status.isLiked : null
}
