import { CONTENT_TYPE_POST, CONTENT_TYPE_VIDEO } from '../contentTypes'
import type { components } from '../../types/api'
import type { VideoCardVM } from '../../types/ui'
import { formatCount, formatDuration, formatRelativeDate } from './format'
import { likedOf } from './interaction'
import { spaceHref, videoHref } from './links'

/**
 * `ContentVO` → 卡片视图模型。
 *
 * <p>★ 这里是**缺口显式 fallback** 的落点：
 * - 播放量：GAP-01（2026-10-26 后端交付）起列表带 `playCount` ⇒ 按 B 站口径格式化成
 *   `万 / 亿`，由卡片封面左下角渲染。`null` / 缺省 = **本面没有给**（不装成 0）。
 *   **图文不显示**：后端 `content.play_count` 对图文结构性恒 0（GAP-05 / GAP-10），
 *   画一个「浏览 0」等于把"没接管道"说成"没人看"；等有真实浏览计数再按 `type` 切图标。
 * - 弹幕数：本仓没有弹幕域 ⇒ 恒 `null`，永不显示。
 * - `durationSeconds` 可能是 `null`（存量文件缺失 / 解析失败）⇒ 不渲染时长角标；绝不补 0。
 * - `coverUrl` 可能是 `null`（图文无封面）⇒ 交给卡片的占位块。
 */
export function toVideoCard(
  item: components['schemas']['ContentVO'],
  now: Date = new Date(),
): VideoCardVM {
  const isVideo = item.type === CONTENT_TYPE_VIDEO
  return {
    id: item.id ?? 0,
    title: item.title?.trim() || '无标题',
    href: videoHref(item.id),
    coverUrl: item.coverUrl?.trim() || null,
    // 图文没有时长概念：即使后端误回填也不显示
    durationText: isVideo ? formatDuration(item.durationSeconds) : null,
    authorName: item.authorName?.trim() || '未知UP主',
    authorHref: spaceHref(item.authorId),
    dateText: formatRelativeDate(item.createTime, now),
    // `null` = 本面没给（≠ 0）；图文在 GAP-05 销账前一律不显示（见上方注释）
    playCountText:
      isVideo && typeof item.playCount === 'number' && Number.isFinite(item.playCount)
        ? formatCount(item.playCount)
        : null,
    danmakuText: null, // 无弹幕域（永不销账）
    isPost: item.type === CONTENT_TYPE_POST,
    // 列表面只回「赞」这一维（三态：null = 本面不查 / 未登录）
    liked: likedOf(item.interaction),
  }
}
