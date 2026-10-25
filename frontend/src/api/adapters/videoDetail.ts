import { CONTENT_TYPE_POST, CONTENT_TYPE_VIDEO } from '../contentTypes'
import type { components } from '../../types/api'
import type { VideoDetailVM } from '../../types/ui'
import { formatCount, formatDateTime, formatDuration } from './format'
import { spaceHref } from './links'

/**
 * `ContentDetailVO` → 播放页视图模型。
 *
 * <p>★ 详情侧**有**五个计数（`playCount/likeCount/coinCount/favoriteCount/commentCount`），
 * 与列表侧（无计数）正好相反 —— 所以"卡片没有播放量、详情有"是本仓的真实契约差异，
 * 不是前端漏做。
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
    images: (detail.imageUrls ?? []).map((url) => url?.trim()).filter((url): url is string => Boolean(url)),
    isPost: detail.type === CONTENT_TYPE_POST,
    commentEnabled: detail.commentEnabled !== false,
    stats: {
      play: formatCount(detail.playCount),
      like: formatCount(detail.likeCount),
      coin: formatCount(detail.coinCount),
      favorite: formatCount(detail.favoriteCount),
      comment: formatCount(detail.commentCount),
    },
  }
}
