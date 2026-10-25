import type { components } from '../../types/api'
import type { VideoDetailVM } from '../../types/ui'
import { CONTENT_TYPE_POST, CONTENT_TYPE_VIDEO } from '../contentTypes'
import { formatDateTime, formatDuration } from './format'
import { toInteraction } from './interaction'
import { spaceHref } from './links'

/** 计数：`undefined` / `null` / 非数字 ⇒ null（渲染成 `-`，**不假装是 0**） */
function count(value: number | null | undefined): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

/**
 * `ContentDetailVO` → 播放页视图模型。
 *
 * <p>★ 详情侧**有**五个计数（`playCount/likeCount/coinCount/favoriteCount/commentCount`），
 * 与列表侧（无计数）正好相反 —— 所以"卡片没有播放量、详情有"是本仓的真实契约差异，
 * 不是前端漏做。
 *
 * <p>计数**不在这里格式化**（万/亿是展示口径，且互动态要乐观加减）—— 见 `types/ui.ts` 开头。
 */
export function toVideoDetail(detail: components['schemas']['ContentDetailVO']): VideoDetailVM {
  const isVideo = detail.type === CONTENT_TYPE_VIDEO
  return {
    id: detail.id ?? 0,
    title: detail.title?.trim() || '无标题',
    description: detail.description?.trim() ?? '',
    authorId: detail.authorId ?? 0,
    authorName: detail.authorName?.trim() || '未知UP主',
    authorHref: spaceHref(detail.authorId),
    dateText: formatDateTime(detail.createTime),
    coverUrl: detail.coverUrl?.trim() || null,
    videoUrl: isVideo ? detail.videoUrl?.trim() || null : null,
    durationText: isVideo ? formatDuration(detail.durationSeconds) : null,
    durationSeconds: isVideo ? count(detail.durationSeconds) : null,
    images: (detail.imageUrls ?? []).map((url) => url?.trim()).filter((url): url is string => Boolean(url)),
    isPost: detail.type === CONTENT_TYPE_POST,
    commentEnabled: detail.commentEnabled !== false,
    stats: {
      play: count(detail.playCount),
      like: count(detail.likeCount),
      coin: count(detail.coinCount),
      favorite: count(detail.favoriteCount),
      comment: count(detail.commentCount),
    },
    // ★ 匿名时后端整个返回 null（不是 {}、也不是四个 false）—— 见互动态三态
    interaction: toInteraction(detail.interaction),
  }
}
