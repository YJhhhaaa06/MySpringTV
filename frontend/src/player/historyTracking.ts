/**
 * 「记录浏览历史」开关 → 一次观看会话要不要上报（**纯逻辑**，唯一判据处）。
 *
 * <p>为什么单独成模块：这条判据全是"看不出来"的 —— 关掉开关后少报一次进度，界面没有任何变化；
 * 而写错极性会让**所有用户**的记录静默消失。所以它必须是纯函数、必须能单测。
 * 契约原文见 `architecture/domain/history/history-需求.md` §2.1 与
 * `architecture/domain/history/history-客户端契约.md` §2.1。
 *
 * <h2>三态，不是一个 boolean</h2>
 * <ul>
 *   <li><b>skip</b>：未登录 ⇒ 什么都不做（六个端点全要登录，打了也是一串 401）。
 *       这是既有行为（`usePlaybackProgress` 的"未登录整段不接"）。</li>
 *   <li><b>read-only</b>：登录了但关掉了记录 ⇒ **只读**一条 `GET /progress` 用于续播，
 *       `POST /play` / `PUT /progress` / `POST /flush` / 本地 pending **一个都不写**。
 *       连 `/play` 都不发是 D1 的直接后果：它的去重标记就长在历史行上，发它必然留一行 0 秒记录。</li>
 *   <li><b>track</b>：登录且开着 ⇒ 正常上报。</li>
 * </ul>
 *
 * <h2>★ 只认显式 {@code false}</h2>
 * `historyRecording === undefined`（老后端没有这个字段）按**开**处理 —— 与 DB 默认值 1 一致。
 * 写成 `=== true` 会让一次字段缺失把所有人的记录静默关掉；反过来，"误开"最多多记一条，
 * "误关"要用户自己发现记录少了（而本仓没有删除能力，见 GAP-19）。
 */
export type TrackingPlan = 'skip' | 'read-only' | 'track'

/**
 * 开关的**唯一极性判据**：`undefined`（老后端没这个字段）按"开"处理。
 *
 * <p>历史页的开关 UI 与播放页的上报闸门都必须走它 —— 两处各写一遍 `!== false`，
 * 早晚会有一处被改成 `=== true`（那会把所有人的记录静默关掉）。
 */
export function isRecording(historyRecording: boolean | undefined): boolean {
  return historyRecording !== false
}

export function planTracking(
  authenticated: boolean,
  historyRecording: boolean | undefined,
): TrackingPlan {
  if (!authenticated) return 'skip'
  return isRecording(historyRecording) ? 'track' : 'read-only'
}
