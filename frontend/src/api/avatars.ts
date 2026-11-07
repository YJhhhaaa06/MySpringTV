import type { components } from '../types/api'
import { getJson, putForm, putJson } from './http'

/**
 * 头像端点封装（`R-27`）。
 *
 * <h2>三条路径的分工</h2>
 * <ul>
 *   <li>{@code PUT /users/me/avatar}：multipart 自定义上传（服务端写 ref + 删旧文件）；</li>
 *   <li>{@code PUT /users/me/avatar/default}：JSON 切默认（`avatarId` 1..6）；</li>
 *   <li>{@code GET /users/avatars?userIds=}：作者快照面的批量读（公开，≤100 个 id）。</li>
 * </ul>
 *
 * <p>★ 头像**不进**评论 / 动态 / 关注列表的缓存载荷：这些面只拿得到 userId，
 * 由页面调批量端点实时补 URL。换头像后下一次进页即生效，旧 URL 不会留在缓存里。
 */

export type UserAvatarVO = components['schemas']['UserAvatarVO']

/** 自定义头像上传（调用方负责 180×180 裁剪；服务端只校验类型 + ≤2MB）。 */
export function uploadAvatar(file: File): Promise<UserAvatarVO> {
  const form = new FormData()
  form.append('file', file)
  return putForm('/api/v1/users/me/avatar', form)
}

/** 选择默认头像（1..6）。 */
export function selectDefaultAvatar(avatarId: number): Promise<UserAvatarVO> {
  return putJson('/api/v1/users/me/avatar/default', { avatarId })
}

/** 批量读作者头像；返回的 `avatarUrl` 对存在的用户恒非空。 */
export function fetchUserAvatars(userIds: number[]): Promise<UserAvatarVO[]> {
  const ids = userIds.join(',')
  return getJson('/api/v1/users/avatars', { query: { userIds: ids } })
}

/**
 * 批量响应 → `Map<userId, avatarUrl>`。
 *
 * 非法的 id / URL 静默丢弃：调用方拿不到时 `UserAvatar` 会按 userId 回落默认，
 * 而不是把 `undefined` 画成空头像。
 */
export function toAvatarMap(items: UserAvatarVO[] | undefined): Map<number, string> {
  const map = new Map<number, string>()
  for (const item of items ?? []) {
    const userId = item?.userId
    const avatarUrl = item?.avatarUrl
    if (typeof userId === 'number' && Number.isInteger(userId) && userId > 0
      && typeof avatarUrl === 'string' && avatarUrl) {
      map.set(userId, avatarUrl)
    }
  }
  return map
}
