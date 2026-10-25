import { CONTENT_TYPE_POST, CONTENT_TYPE_VIDEO } from '../contentTypes'
import type { components } from '../../types/api'
import type { VideoCardVM } from '../../types/ui'
import { formatDuration, formatRelativeDate } from './format'
import { spaceHref, videoHref } from './links'

/**
 * `ContentVO` → 卡片视图模型。
 *
 * <p>★ 这里是**缺口显式 fallback** 的落点：
 * - 播放量：`ContentVO` 没有任何计数 ⇒ `playCountText` 恒 `null`（角标隐藏）。
 *   **不许**为了好看去调详情端点回填或编数字 —— 那正是"假装做了"。
 * - 弹幕数：本仓没有弹幕域 ⇒ 恒 `null`，永不显示。
 * - `durationSeconds` 可能是 `null`（后端未回填）⇒ 不渲染时长角标。
 * - `coverUrl` 可能是 `null`（图文无封面）⇒ 交给卡片的占位块。
 */
export function toVideoCard(
  item: components['schemas']['ContentVO'],
  now: Date = new Date(),
): VideoCardVM {
  return {
    id: item.id ?? 0,
    title: item.title?.trim() || '无标题',
    href: videoHref(item.id),
    coverUrl: item.coverUrl?.trim() || null,
    // 图文没有时长概念：即使后端误回填也不显示
    durationText: item.type === CONTENT_TYPE_VIDEO ? formatDuration(item.durationSeconds) : null,
    authorName: item.authorName?.trim() || '未知UP主',
    authorHref: spaceHref(item.authorId),
    dateText: formatRelativeDate(item.createTime, now),
    playCountText: null, // 列表 VO 无计数（等后端给列表带计数后再改这里）
    danmakuText: null, // 无弹幕域（永不销账）
    isPost: item.type === CONTENT_TYPE_POST,
  }
}
