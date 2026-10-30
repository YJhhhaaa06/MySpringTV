import type { components } from '../../types/api'
import type { CommentPageVM, CommentRepliesVM, CommentVM, CurrentUserVM } from '../../types/ui'

/**
 * 评论（`CommentVO` / 楼中楼）→ 页面视图模型。
 *
 * <h2>★ 两处必须显式降级（GAP 台账）</h2>
 * <ol>
 *   <li><b>没有时间</b>：`CommentVO` **没有 `createTime`** ⇒ `timeText` 恒空串，
 *       界面上不显示时间。**不许**写"刚刚"顶上 —— 那是编数字的变体。</li>
 *   <li><b>没有头像</b>：同 GAP-08，昵称首字 + 渐变底。</li>
 * </ol>
 *
 * <h2>★ 楼中楼的 children 运行时是 `CommentVO`，不是 `CommentCacheDTO`</h2>
 * spec 把 `CommentVO.children` 声明成 `CommentCacheDTO`（少了 `isLiked` 等字段），
 * 但服务端 `convertToCommentVO` 递归转换后**带 `isLiked`**（`CommentService` 逐字段拷贝）。
 * 生成类型在这里会撒谎 ⇒ 按 `CommentVO` 读（有实测响应为据），而不是当成没有点赞态的 DTO。
 */
export function toComment(node: components['schemas']['CommentVO'], viewerId: number | null): CommentVM {
  const authorId = node.userId ?? 0
  const name = node.username?.trim() || '匿名用户'
  const children = (node.children ?? []) as components['schemas']['CommentVO'][]
  return {
    id: node.commentId ?? 0,
    parentId: node.parentId ?? null,
    authorId,
    authorName: name,
    initial: (Array.from(name)[0] ?? 'U').toUpperCase(),
    content: node.content?.trim() ?? '',
    likeCount: typeof node.likeCount === 'number' ? node.likeCount : 0,
    liked: node.isLiked === true,
    // replyCount 是**该主楼下的回复总数**，children 只是前 K=2 条预览
    replyCount: typeof node.replyCount === 'number' ? node.replyCount : 0,
    replies: children.map((child) => toComment(child, viewerId)),
    replyToName: node.replyToUsername?.trim() || null,
    canDelete: viewerId !== null && authorId === viewerId,
    timeText: '',
  }
}

/** `PageResult<CommentVO>` → 评论分页（Page；`total` = **主楼条数**）。 */
export function toCommentPage(
  result: components['schemas']['PageResultCommentVO'] | undefined,
  viewerId: number | null,
): CommentPageVM {
  const list = result?.list ?? []
  return {
    items: list.map((item) => toComment(item, viewerId)),
    total: result?.total ?? 0,
    page: result?.page ?? 1,
    pageSize: result?.pageSize ?? list.length,
    totalPages: result?.totalPages ?? 0,
  }
}

/** `CursorResult<CommentVO>` → 楼中楼一页（游标；`nextCursor === null` ⇒ 到底）。 */
export function toReplyPage(
  result: components['schemas']['CursorResultCommentVO'] | undefined,
  viewerId: number | null,
): CommentRepliesVM {
  const list = result?.list ?? []
  return {
    items: list.map((item) => toComment(item, viewerId)),
    nextCursor: typeof result?.nextCursor === 'number' ? result.nextCursor : null,
    total: result?.total ?? 0,
  }
}

/**
 * 本地刚发布的评论（**id 来自服务端**，其余字段来自当前登录用户与输入框）。
 *
 * <p>为什么可以本地拼而不是回读一遍：`POST /comments` 已经返回 `commentId`，
 * 作者/文本/父级都是我们自己发出去的确定值；回读一页只为拿这些已知字段是白花一次请求。
 * <p>⚠️ 时间字段仍然空着（后端没有），所以新发的评论**立刻就没有时间**，
 * 与刷出来的旧评论一致 —— 不一致才是要警惕的。
 */
export function toPublishedComment(input: {
  id: number
  parentId: number | null
  content: string
  viewer: CurrentUserVM | null
  replyToName?: string | null
}): CommentVM {
  const name = input.viewer?.name?.trim() || '我'
  return {
    id: input.id,
    parentId: input.parentId,
    authorId: input.viewer?.id ?? 0,
    authorName: name,
    initial: input.viewer?.initial || (Array.from(name)[0] ?? '我').toUpperCase(),
    content: input.content,
    likeCount: 0,
    liked: false,
    replyCount: 0,
    replies: [],
    replyToName: input.replyToName ?? null,
    canDelete: true,
    timeText: '',
  }
}
