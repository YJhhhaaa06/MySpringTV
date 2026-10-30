import type { components } from '../types/api'
import { getJson, postJson } from './http'

/** `GET /users/me`（`@RequiresLogin`）：当前用户。 */
export function fetchMe(): Promise<components['schemas']['UserInfoVO']> {
  return getJson('/api/v1/users/me')
}

/** `POST /sessions`：手机号 + 密码换 token（**创建会话**，不是 `login` 动词路径）。 */
export function createSession(phone: string, password: string): Promise<components['schemas']['LoginVO']> {
  return postJson('/api/v1/sessions', { phone, password })
}
