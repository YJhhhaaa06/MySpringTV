import type { components } from '../types/api'
import { putForm, putJson } from './http'

/**
 * 头像**写**端点封装（`R-27`）。
 *
 * <h2>两条路径</h2>
 * <ul>
 *   <li>{@code PUT /users/me/avatar}：multipart 自定义上传（服务端写 ref + 延迟回收旧文件）；</li>
 *   <li>{@code PUT /users/me/avatar/default}：JSON 切默认（`avatarId` 1..6）。</li>
 * </ul>
 *
 * <p>★ `R-37` 起**没有**"批量读作者头像"这一层：评论 / 动态 / 关注列表 / 详情作者
 * 都把生效 `avatarUrl` 随响应内联，前端不再二次请求 `GET /users/avatars`。
 * 本文件只保留两个写端点。
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
