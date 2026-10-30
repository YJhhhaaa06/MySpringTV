/**
 * 评论域端点（读两种分页 + 写三条）。
 *
 * <h2>★ 两种分页必须分开用，不许"统一一下"</h2>
 * <pre>
 * 主楼列表  Page   : ?page=&pageSize=          → {list, total, page, pageSize, totalPages}
 * 楼中楼    Cursor : ?cursor=&pageSize=        → {list, total, pageSize, nextCursor}
 * </pre>
 * 语义也不同：主楼的 `total` 是**主楼条数**；楼中楼的 `total` 是该主楼的 `reply_count`。
 * 强行统一成一种分页器，会让其中一侧的 `total` 变成谎言。
 *
 * <p>`POST /comments` 是 `KEYED`（效果层非幂等：重发就是两条评论）⇒ 幂等键由调用方
 * 在"用户按下发表"那一刻取，**失败重试沿用同一个**（见 `api/idempotency.ts`）。
 */
import type { CommentPageVM, CommentRepliesVM } from '../types/ui'
import { currentUserId } from '../auth/session'
import { toCommentPage, toReplyPage } from './adapters/comment'
import { deleteJson, getJson, postJson, putJson } from './http'
import { IDEMPOTENCY_HEADER } from './idempotency'

/** `GET /contents/{id}/comments?page=&pageSize=`（`total` = 主楼条数） */
export async function fetchComments(
  contentId: number,
  page: number,
  pageSize = 10,
): Promise<CommentPageVM> {
  const result = await getJson('/api/v1/contents/{id}/comments', {
    path: { id: contentId },
    query: { page, pageSize },
  })
  return toCommentPage(result, currentUserId())
}

/** `GET /comments/{id}/replies?cursor=&pageSize=`（游标；`null` = 从楼头开始） */
export async function fetchReplies(
  commentId: number,
  cursor: number | null,
  pageSize = 10,
): Promise<CommentRepliesVM> {
  const result = await getJson('/api/v1/comments/{id}/replies', {
    path: { id: commentId },
    query: { cursor: cursor ?? undefined, pageSize },
  })
  return toReplyPage(result, currentUserId())
}

/**
 * `POST /comments` → 新评论 id。
 *
 * @param parentId 被回复的评论 id（**主楼或楼内回复都行**：后端会把楼内回复归一化到主楼，
 *                 并把 `replyToUserId` 记成被 @ 的人）；主楼评论传 `null`
 * @param idempotencyKey 同一次"发表意图"的重试必须复用同一个键
 */
export async function publishComment(
  contentId: number,
  message: string,
  parentId: number | null,
  idempotencyKey: string,
): Promise<number> {
  const result = await postJson(
    '/api/v1/comments',
    parentId === null ? { contentId, message } : { contentId, message, parentId },
    { headers: { [IDEMPOTENCY_HEADER]: idempotencyKey } },
  )
  return result?.commentId ?? 0
}

/** `DELETE /comments/{id}` → 204（软删除；他人的评论 403、不存在 404） */
export function removeComment(commentId: number): Promise<undefined> {
  return deleteJson('/api/v1/comments/{id}', { path: { id: commentId } })
}

/** `PUT /comments/{id}/like` */
export function likeComment(commentId: number): Promise<undefined> {
  return putJson('/api/v1/comments/{id}/like', undefined, { path: { id: commentId } })
}

/** `DELETE /comments/{id}/like` → 204 */
export function unlikeComment(commentId: number): Promise<undefined> {
  return deleteJson('/api/v1/comments/{id}/like', { path: { id: commentId } })
}
