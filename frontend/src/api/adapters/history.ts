/**
 * 观看历史的 api → ui 映射。
 *
 * <h2>为什么与卡片分开</h2>
 * 历史是「回看」场景：要画**观看进度**与**观看时刻**，而卡片那套（播放量 / 弹幕 / 发布时间）
 * 在这里一大半是假字段。`HistoryItemVO` 恰好同时给了 `progress` 与 `durationSeconds`，
 * 所以进度条可以**真算**（其它列表 VO 没有计数，见 GAP-01）。
 *
 * <h2>★ 三处不假装</h2>
 * - 没有总时长（`durationSeconds = null`，本仓存量内容全是这样）⇒ 只给「看到 mm:ss」，
 *   进度条按 0 不渲染，**不猜一个比例**；
 * - `progress = 0` 不写「看到 00:00」（那不是"看过"的事实）；
 * - 失效条目后端只给「内容已失效」+ `null` 封面/作者 ⇒ 不补「未知UP主」这类假文案，
 *   但**保留观看时间**（历史数据是真的）。
 */
import type { components } from '../../types/api'
import type { HistoryGroupVM, HistoryItemVM } from '../../types/ui'
import { serverNow } from '../serverClock'
import { formatClockTime, formatDayLabel, formatDuration } from './format'
import { videoHref } from './links'

/**
 * `HistoryItemVO` → 历史卡片（进度按 `progress / durationSeconds` 真算）。
 *
 * <p>角标口径对齐 B 站历史页：有总时长 ⇒ `已看/总长`（`18:09/49:45`）；已看完 ⇒ `已看完`；
 * 没有总时长（本仓存量内容常见）⇒ 只给 `看到 mm:ss`，**不猜比例**；没进度 ⇒ 空串（不渲染角标）。
 *
 * <p>观看时刻按**用户本地时区**呈现（解析按契约 §2.7）；`offsetMinutes` 只给用例钉时区。
 */
export function toHistoryItem(
  item: components['schemas']['HistoryItemVO'],
  offsetMinutes?: number,
): HistoryItemVM {
  const invalid = item.invalid === true
  const progress = typeof item.progress === 'number' && item.progress > 0 ? item.progress : 0
  const duration = typeof item.durationSeconds === 'number' && item.durationSeconds > 0 ? item.durationSeconds : 0
  const watchedAt = item.watchedTime ?? ''

  const progressText = formatDuration(progress)
  const durationText = formatDuration(duration)
  let badgeText = ''
  if (!invalid && progress > 0) {
    if (duration > 0) {
      badgeText = progress >= duration ? '已看完' : `${progressText}/${durationText}`
    } else {
      badgeText = `看到 ${progressText ?? ''}`
    }
  }
  const progressPercent = !invalid && duration > 0
    ? Math.min(100, Math.max(0, Math.round((progress / duration) * 100)))
    : 0

  return {
    contentId: item.contentId ?? 0,
    href: videoHref(item.contentId),
    title: item.title?.trim() || '已失效内容',
    coverUrl: invalid ? null : item.coverUrl?.trim() || null,
    authorName: invalid ? '' : item.authorName?.trim() || '',
    invalid,
    badgeText,
    progressPercent,
    watchedTimeText: formatClockTime(watchedAt, offsetMinutes),
    watchedAt,
  }
}

/**
 * 按天分组：**保持服务端顺序**（最后观看倒序），只在首次出现某天时建组。
 *
 * <p>用 Map 而不是"和上一组比"：后者在脏数据（跨天交错）下会产出两个同名分组。
 * <p>`now` 默认取**服务器时钟**（与卡片 / 评论同源）；"今天/昨天"按用户本地日历日判
 * （{@link formatDayLabel}）。
 */
export function groupHistory(
  items: HistoryItemVM[],
  now: Date = serverNow(),
  offsetMinutes?: number,
): HistoryGroupVM[] {
  const groups = new Map<string, HistoryGroupVM>()
  for (const item of items) {
    const { key, label } = formatDayLabel(item.watchedAt, now, offsetMinutes)
    let group = groups.get(key)
    if (!group) {
      group = { key, label, items: [] }
      groups.set(key, group)
    }
    group.items.push(item)
  }
  return [...groups.values()]
}
