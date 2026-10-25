/**
 * 播放历史端点（客户端只用这五个，见 `architecture/domain/history/history-客户端契约.md` §二）。
 *
 * <p>口径要点（照抄契约，别自己发明）：
 * - 全部**需要登录**（类级 `@RequiresLogin`）⇒ 未登录时 401 不是"没有记录"；
 * - `PUT /progress` 只进 Redis 缓冲、**不落库**；`POST /history/flush` 才落库；
 * - `flush` **没有 contentId 参数**：它写的是该用户缓冲里的**全部**内容；
 * - `PUT /progress` 是**唯一**可能 503 + `Retry-After` 的端点（Redis 不可用时），
 *   客户端必须按服务端给的节奏退避（见 `progressReporter.ts`）。
 */
import type { components } from '../types/api'
import { readToken } from '../auth/token'
import { getJson, postJson, putJson } from './http'

/** `POST /contents/{id}/play`：播放量去重计数（幂等，重复调不报错） */
export function reportPlay(contentId: number): Promise<undefined> {
  return postJson('/api/v1/contents/{id}/play', undefined, { path: { id: contentId } })
}

/** `PUT /contents/{id}/progress` body `{progress}`（**秒**，整数；超时长会被 clamp，不报错） */
export function reportProgress(contentId: number, progress: number): Promise<undefined> {
  return putJson('/api/v1/contents/{id}/progress', { progress: Math.max(0, Math.floor(progress)) }, {
    path: { id: contentId },
  })
}

/** `GET /contents/{id}/progress` → `{progress}`；无记录返回 0（**不是 404**） */
export function fetchProgress(contentId: number): Promise<components['schemas']['HistoryPositionVO'] | undefined> {
  return getJson('/api/v1/contents/{id}/progress', { path: { id: contentId } })
}

/** `POST /history/flush`：把本用户缓冲落库（无参数） */
export function flushHistory(): Promise<undefined> {
  return postJson('/api/v1/history/flush')
}

/**
 * 退出信号：`fetch(keepalive)` —— **不是** `navigator.sendBeacon`。
 *
 * <p>契约 §4.1 的原话：`sendBeacon` 带不了 `Authorization` 头（只支持简单请求），
 * 而本仓 token 只走该头 ⇒ 用它必然 401 ⇒ 退出信号静默丢失（"永久丢"的一类）。
 * `keepalive` 让请求在页面卸载后继续发完（本端点无 body，不存在 64KB 上限问题）。
 *
 * <p>为什么走裸 `fetch` 而不是 `api/http.ts`：那个层带 15s 超时与 AbortController，
 * 在卸载路径上既没用（页面已经走了）又可能把请求 abort 掉；且这里**必须吞掉所有错误**
 * ——页面正在卸载，没有地方能处理异常。
 */
export function flushHistoryOnExit(): void {
  const token = readToken()
  if (!token) return
  try {
    void fetch('/api/v1/history/flush', {
      method: 'POST',
      keepalive: true,
      headers: { Authorization: `Bearer ${token}` },
    }).catch(() => {
      // 页面已卸载：退出信号失败只能是"等兜底窗口"，无处可报
    })
  } catch {
    // 同上
  }
}
