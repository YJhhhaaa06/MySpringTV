import type { components } from '../types/api'
import { getJson, postJson, putJson } from './http'

/** `GET /users/me`（`@RequiresLogin`）：当前用户。 */
export function fetchMe(): Promise<components['schemas']['UserInfoVO']> {
  return getJson('/api/v1/users/me')
}

/**
 * `PUT /users/me/history-recording`：停用 / 恢复「记录浏览历史」（`R-25`）。
 *
 * <p>★ 调用方**不等结果**（`void ... .catch(() => {})`）：B站 实测口径是"乐观生效、
 * 失败不提示、不回弹"，断网期间的切换不算数（服务端没收到 ⇒ 刷新后回原值）。
 * 所以这里**不做**重试、不抛给 UI —— 见 `architecture/domain/history/history-需求.md` §2.1。
 *
 * <p>为什么是 `PUT`：它替换 `history-recording` 这个子资源的取值，同值重写结果不变 ⇒
 * 天然幂等，重试安全（服务端登记为 `NATURAL`，不需要幂等键）。
 */
export function updateHistoryRecording(
  historyRecording: boolean,
): Promise<components['schemas']['HistoryRecordingVO'] | undefined> {
  return putJson('/api/v1/users/me/history-recording', { historyRecording })
}

/** `POST /sessions`：手机号 + 密码换 token（**创建会话**，不是 `login` 动词路径）。 */
export function createSession(phone: string, password: string): Promise<components['schemas']['LoginVO']> {
  return postJson('/api/v1/sessions', { phone, password })
}
