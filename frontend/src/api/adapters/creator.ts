import type { components } from '../../types/api'
import { CONTENT_TYPE_POST, CONTENT_TYPE_VIDEO } from '../contentTypes'
import type { CreatorWorkVM, CreatorWorksVM } from '../../types/ui'
import { formatCount, formatDateTime, formatDuration } from './format'
import { contentHref } from './links'

/**
 * 创作中心「我的稿件」适配器。
 *
 * <h2>数据源为什么是公开主页端点</h2>
 * 本仓没有"创作者的稿件列表"端点（投稿/编辑之外只读公开主页）⇒
 * `GET /users/{id}.contentPage` 就是"我发布的全部作品"，按 `create_time DESC, id DESC`。
 * 代价写在登记台：**翻页会顺带重读一次资料**（同 `profile.ts` 的口径），
 * 且列表口径 = 观众能看到的作品（本仓没有草稿 / 审核态，`content` 无状态列）。
 *
 * <h2>哪些列不渲染</h2>
 * B 站稿件管理有「状态 / 分区 / 评论 / 弹幕 / 分享 / 收益」等列：
 * - **状态**：没有审核域，画「已通过」等于编一个不存在的流程；
 * - **分区**：`ContentVO.categoryId` 只有 0~9 数字，展示成中文要再引一份常量，
 *   而列表的价值在"改哪一条"，不在分区 —— 不渲染；
 * - **评论 / 弹幕 / 分享**：列表不保证带这些计数（`commentCount` 可为 `null`），
 *   带了也只有一个面；列一个满屏 `-` 的表头没有信息量。
 *
 * <p>★ 播放量对图文恒 `null`（后端 `play_count` 对图文结构性恒 0，GAP-05 / GAP-10）——
 * 行里画「—」，不画「0 播放」。
 */
export function toCreatorWork(item: components['schemas']['ContentVO']): CreatorWorkVM {
  const isPost = item.type === CONTENT_TYPE_POST
  const isVideo = item.type === CONTENT_TYPE_VIDEO
  const id = item.id ?? 0
  return {
    id,
    title: item.title?.trim() || '无标题',
    isPost,
    typeText: isPost ? '图文' : '视频',
    coverUrl: item.coverUrl?.trim() || null,
    durationText: isVideo ? formatDuration(item.durationSeconds) : null,
    playCountText: isVideo ? formatCount(item.playCount) : null,
    createdAtText: formatDateTime(item.createTime),
    viewHref: contentHref(id, isPost),
    editHref: `/creator/edit/${id}`,
  }
}

/** `PageResult<ContentVO>` → 我的稿件分页（期望值按分页契约推，不按本页条数猜 total）。 */
export function toCreatorWorks(
  page: components['schemas']['PageResultContentVO'] | undefined,
): CreatorWorksVM {
  const list = page?.list ?? []
  return {
    items: list.map((item) => toCreatorWork(item)),
    page: page?.page ?? 1,
    total: page?.total ?? list.length,
    totalPages: page?.totalPages ?? 0,
  }
}
