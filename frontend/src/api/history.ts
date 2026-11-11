/**
 * 播放历史端点。
 *
 * <p>口径要点（照抄契约，别自己发明）：
 * - 全部**需要登录**（类级 `@RequiresLogin`）⇒ 未登录时 401 不是"没有记录"；
 * - `PUT /progress` 只进 Redis 缓冲、**不落库**；`POST /history/flush` 才落库；
 * - `flush` **没有 contentId 参数**：它写的是该用户缓冲里的**全部**内容；
 * - `PUT /progress` 是**唯一**可能 503 + `Retry-After` 的端点（Redis 不可用时），
 *   客户端必须按服务端给的节奏退避（见 `progressReporter.ts`）。
 *
 * <p>★ 2026-10-26 追加**列表读端点** `GET /history`（历史页交付）。
 * ★ 2026-10-31（`R-26`）追加**删除端点** `DELETE /history`：批量（`contentIds`）与清空（`all=true`）
 * 是**同一条路由**的两种形态，互斥（见 `deleteHistory` / `clearHistory`）。
 */
import type { components } from '../types/api'
import type { HistoryItemVM, PageVM } from '../types/ui'
import { readToken } from '../auth/token'
import { groupHistory, toHistoryItem } from './adapters/history'
import { deleteJson, getJson, postJson, putJson } from './http'

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

/** `GET /history`：观看历史（最后观看倒序；失效条目占位、**保留 progress**） */
export async function loadHistory(page = 1, pageSize = 20): Promise<PageVM<HistoryItemVM>> {
  const result = await getJson('/api/v1/history', { query: { page, pageSize } })
  const list = result?.list ?? []
  return {
    items: list.map((item) => toHistoryItem(item)),
    page: result?.page ?? 1,
    total: result?.total ?? list.length,
    totalPages: result?.totalPages ?? 0,
  }
}

/**
 * 按天分组（今天 / 昨天 / MM-DD）。
 *
 * <p>分组依赖"现在是几点"，属于**展示口径**，所以留在页面这一侧、不进端点层；
 * 这里只做一个转发，省得页面同时 import 两个模块。
 */
export const groupHistoryByDay = groupHistory

/**
 * 删除若干条历史（`DELETE /api/v1/history?contentIds=`）。
 *
 * <p>★ **幂等**：服务端对"行不存在"也回 204 ⇒ 陈旧页面 / 别的标签页已经删过都不是错误，
 * 调用方不需要区分"真删了"与"本来就没有"。
 * <p>★ 与 {@link clearHistory} **互斥**：同时给会被服务端 400（见 `history.md` §八）。
 * <p>★ 上限 **500 条**（服务端校验，超出 400）：历史页的「全选」只选**已加载**的条目（B站 实测口径），
 * 一页 20 条 ⇒ 正常操作离上限很远；真撞上了要给用户一句可解释的提示，而不是静默失败。
 */
export function deleteHistory(contentIds: number[]): Promise<undefined> {
  return deleteJson('/api/v1/history', { query: { contentIds } })
}

/**
 * 清空历史（`DELETE /api/v1/history?all=true`）。
 *
 * <p>★ 必须**显式** `all=true`：服务端刻意不用"少传 contentIds"表达清空，
 * 那样一次前端 bug 就是不可逆的数据事故（见 `history.md` §八）。
 * <p>★ 同 {@link deleteHistory}：幂等，空历史也 204。
 */
export function clearHistory(): Promise<undefined> {
  return deleteJson('/api/v1/history', { query: { all: true } })
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
