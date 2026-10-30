import type { components } from '../../types/api'
import type { Count, DynamicCardVM, DynamicPageVM } from '../../types/ui'
import { CONTENT_TYPE_POST } from '../contentTypes'
import { cleanDescription, formatCount, formatDuration, formatRelativeDate, initialOf } from './format'
import { likedOf } from './interaction'
import { contentHref, spaceHref } from './links'

/**
 * `ContentVO` → 动态卡（`GET /feed`）。
 *
 * <h2>这是 `likeCount` / `commentCount` 的第一个消费方</h2>
 * 后端自 GAP-01 起在 `/feed` 上带 `playCount + likeCount + commentCount`
 * （`FeedService#FEED_COUNT_FIELDS`），但网格卡只要播放量 ⇒ 后两维一直没人读。
 * 动态卡底部操作栏正好是它们的展示位。
 *
 * <h2>★ 缺口 fallback 仍在这一层</h2>
 * - **图文没有播放量**：后端 `play_count` 对图文结构性恒 0（GAP-05）⇒ 恒 `null`，不画「浏览 0」；
 * - 三个计数都可能是 `null`（本面没给）⇒ 保持 `null`，组件不渲染该数字（≠ 0）；
 * - `interaction` 只回「赞」一维 ⇒ `liked` 是三态，`null` 不归一成 `false`（否则「没查」会画成「没点过」）；
 * - 弹幕数**根本不在这里**：动态卡没有弹幕位，本仓也没有弹幕域。
 */
export function toDynamicCard(
  item: components['schemas']['ContentVO'],
  now: Date = new Date(),
): DynamicCardVM {
  const isPost = item.type === CONTENT_TYPE_POST
  const authorName = item.authorName?.trim() || '未知UP主'
  return {
    id: item.id ?? 0,
    title: item.title?.trim() || '无标题',
    // 图文 → /opus/{id}，视频 → /video/{id}（与首页 / 搜索同一套落点规则）
    href: contentHref(item.id, isPost),
    coverUrl: item.coverUrl?.trim() || null,
    // 图文没有时长概念：即使后端误回填也不显示
    durationText: isPost ? null : formatDuration(item.durationSeconds),
    authorId: item.authorId ?? 0,
    authorName,
    authorHref: spaceHref(item.authorId),
    authorInitial: initialOf(authorName),
    timeText: formatRelativeDate(item.createTime, now),
    // 由 type 推导的**动作**文案（不是后端字段，所以不叫「动态类型」）
    actionText: isPost ? '发布了图文' : '投稿了视频',
    description: cleanDescription(item.description),
    isPost,
    playCountText:
      isPost ? null : typeof item.playCount === 'number' && Number.isFinite(item.playCount)
        ? formatCount(item.playCount)
        : null,
    likeCount: countOf(item.likeCount),
    commentCount: countOf(item.commentCount),
    commentEnabled: item.commentEnabled !== false,
    liked: likedOf(item.interaction),
  }
}

/** `PageResult<ContentVO>` → 动态流一页（形状与搜索 / 空间作品同一套 Page 口径） */
export function toDynamicPage(
  page: components['schemas']['PageResultContentVO'] | undefined,
  now: Date = new Date(),
): DynamicPageVM {
  const list = page?.list ?? []
  return {
    items: list.map((item) => toDynamicCard(item, now)),
    page: page?.page ?? 1,
    total: page?.total ?? list.length,
    totalPages: page?.totalPages ?? 0,
  }
}

/** 计数：非数字 ⇒ null（渲染处不显示，**不假装 0**） */
function countOf(value: number | null | undefined): Count {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}
