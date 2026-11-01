import type { CommentVM } from '../types/ui'

/**
 * 作者快照面的 userId 收集（`R-27` / `T83` 的纯规则部分）。
 *
 * <p>评论 / 动态 / 关注列表只带 userId；批量头像端点需要"去重后的正整数集合"。
 * 把这些规则抽出来单测，组件里不再各写一遍 Set 逻辑。
 */

/** 任意带 `authorId` 的条目 → 去重、只留正整数的 id 列表（保持首次出现顺序）。 */
export function authorIdsOf(items: ReadonlyArray<{ authorId: number }>): number[] {
  const ids = new Set<number>()
  for (const item of items) {
    if (typeof item.authorId === 'number' && Number.isInteger(item.authorId) && item.authorId > 0) {
      ids.add(item.authorId)
    }
  }
  return [...ids]
}

/** 评论页：主楼 + 预览楼中楼的作者 id（更深楼中楼由 `CommentItem` 游标加载后回传）。 */
export function collectCommentAuthorIds(items: ReadonlyArray<CommentVM>): number[] {
  const ids = new Set<number>()
  for (const item of items) {
    if (item.authorId > 0) ids.add(item.authorId)
    for (const reply of item.replies) {
      if (reply.authorId > 0) ids.add(reply.authorId)
    }
  }
  return [...ids]
}

/**
 * 合并新一批 id（翻页 / 游标加载出更多楼中楼）。
 *
 * <p>没有新增时返回**原数组引用**：调用方据此避免给 `useUserAvatars` 触发无意义的重读。
 */
export function mergeAvatarIds(current: ReadonlyArray<number>, incoming: ReadonlyArray<number>): number[] {
  const merged = new Set(current)
  for (const id of incoming) {
    if (typeof id === 'number' && Number.isInteger(id) && id > 0) merged.add(id)
  }
  return merged.size === current.length ? (current as number[]) : [...merged]
}
