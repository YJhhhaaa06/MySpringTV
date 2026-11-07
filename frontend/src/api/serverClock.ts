/**
 * 服务器时钟锚点（相对时间的**基准**来源）。
 *
 * <h2>为什么需要它</h2>
 * 评论时间 = 服务器写入的 `create_time` 与"现在"的差。若"现在"取自浏览器时钟，
 * 两个输入就**不同源**：用户把系统时间改成几年前 ⇒ 差值恒为负 ⇒ 所有评论显示"刚刚"。
 * HTTP 响应头 `Date` 由**服务器**生成，与 `create_time` 同源 ⇒ 用它校准"现在"。
 *
 * <h2>★ 为什么锚在 {@code performance.now()} 而不是 {@code Date.now()}</h2>
 * `Date.now()` 会跟着系统时间跳变：锚点若存 `serverEpoch - Date.now()`，
 * 用户改完时间后 `Date.now()` 跳到 2020，偏移量却还是旧值 ⇒ 算出来照样错。
 * `performance.now()` 是**单调时钟**，系统时间怎么改都不影响它 ⇒ 锚点不会被带跑。
 *
 * <h2>精度与边界</h2>
 * `Date` 头是**秒级**、且是响应**生成**时刻（未扣 RTT）。误差 < 1s + RTT/2，
 * 对"分钟/小时"级展示无影响；"刚刚"的 60s 阈值允许 ±1s 抖动。
 * 无锚点（首屏请求前 / 全部失败）时退回浏览器时钟——此时退化到"旧行为"，
 * 由 formatCommentTime 的未来分支兜底（不假装知道相对时间）。
 */

/** 服务器"现在"对应的 epoch（毫秒）；null = 尚未同步。 */
let serverEpochMs: number | null = null

/** 同步那一刻的单调时钟读数（毫秒）。 */
let perfAnchorMs: number | null = null

/**
 * 用一次 HTTP 响应的 `Date` 头同步锚点。
 *
 * <p>缺失 / 非法（`Date.parse` 返回 NaN）⇒ **保持原锚点不动**：
 * 一个坏响应不该把已知可用的基准清掉。
 */
export function syncServerClock(dateHeader: string | null | undefined): void {
  if (!dateHeader) return
  const epoch = Date.parse(dateHeader)
  if (!Number.isFinite(epoch)) return
  serverEpochMs = epoch
  perfAnchorMs = performance.now()
}

/**
 * 服务器"现在"。
 *
 * <p>有锚点 ⇒ `锚点时刻 + 单调时钟增量`（与浏览器系统时间**无关**）；
 * 无锚点 ⇒ 退回 `new Date()`（诚实退化，而不是编一个时间）。
 */
export function serverNow(): Date {
  if (serverEpochMs === null || perfAnchorMs === null) return new Date()
  return new Date(serverEpochMs + (performance.now() - perfAnchorMs))
}

/** 测试用：清空锚点。模块级状态，用例之间必须隔离（否则前一条用例的锚点会污染后一条）。 */
export function resetServerClock(): void {
  serverEpochMs = null
  perfAnchorMs = null
}
