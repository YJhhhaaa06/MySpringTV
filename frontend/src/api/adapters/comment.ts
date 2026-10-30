import type { components } from '../../types/api'
import type { CommentPageVM, CommentRepliesVM, CommentVM, CurrentUserVM } from '../../types/ui'
import { serverNow } from '../serverClock'
import { formatCommentTime } from './format'

/**
 * 评论（`CommentVO` / 楼中楼）→ 页面视图模型。
 *
 * <h2>两处显式降级（GAP 台账）</h2>
 * <ol>
 *   <li><b>没有头像</b>：GAP-08，昵称首字 + 渐变底。</li>
 *   <li><b>时间可能缺失</b>：`createTime` 自 GAP-12 起由后端返回，但有两个空值来源 ——
 *       存量 Redis 元素（key 无版本前缀，10 分钟 TTL 内读到的是老 JSON）与脏数据。
 *       缺失 ⇒ 空串，渲染层**不显示**（不写"刚刚"顶上：那是编数字的变体）。</li>
 * </ol>
 *
 * <h2>★ 楼中楼的 children 运行时是 `CommentVO`，不是 `CommentCacheDTO`</h2>
 * spec 把 `CommentVO.children` 声明成 `CommentCacheDTO`（少了 `isLiked` 等字段），
 * 但服务端 `convertToCommentVO` 递归转换后**带 `isLiked`**（`CommentService` 逐字段拷贝）。
 * 生成类型在这里会撒谎 ⇒ 按 `CommentVO` 读（有实测响应为据），而不是当成没有点赞态的 DTO。
 */
export function toComment(
  node: components['schemas']['CommentVO'],
  viewerId: number | null,
  now: Date = serverNow(),
): CommentVM {
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
    replies: children.map((child) => toComment(child, viewerId, now)),
    replyToName: node.replyToUsername?.trim() || null,
    canDelete: viewerId !== null && authorId === viewerId,
    timeText: formatCommentTime(node.createTime, now),
  }
}

/** `PageResult<CommentVO>` → 评论分页（Page；`total` = **主楼条数**）。 */
export function toCommentPage(
  result: components['schemas']['PageResultCommentVO'] | undefined,
  viewerId: number | null,
  now: Date = serverNow(),
): CommentPageVM {
  const list = result?.list ?? []
  return {
    items: list.map((item) => toComment(item, viewerId, now)),
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
  now: Date = serverNow(),
): CommentRepliesVM {
  const list = result?.list ?? []
  return {
    items: list.map((item) => toComment(item, viewerId, now)),
    nextCursor: typeof result?.nextCursor === 'number' ? result.nextCursor : null,
    total: result?.total ?? 0,
  }
}

/**
 * 本地刚发布的评论（**id 来自服务端**，其余字段来自当前登录用户与输入框）。
 *
 * <p>为什么可以本地拼而不是回读一遍：`POST /comments` 已经返回 `commentId`，
 * 作者/文本/父级都是我们自己发出去的确定值；回读一页只为拿这些已知字段是白花一次请求。
 *
 * <p>★ 时间文本直接给 `刚刚`：`CommentCreatedVO` 只回 `commentId`，不回传 `createTime`；
 * 而"本机刚提交成功"本身就是"刚刚"，**不依赖任何时钟**。若拿客户端 now 反算，
 * 用户改过系统时间时反而会算出负差值（旧实现就显示成"刚刚"，属于碰巧同形）。
 * 刷新后以服务端 `createTime` 为准。
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
    timeText: '刚刚',
  }
}
