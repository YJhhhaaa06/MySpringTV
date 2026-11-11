import { reactive, ref } from 'vue'
import type { DynamicCardVM } from '../types/ui'
import { ApiError, describeError } from '../api/http'
import { fetchLikeCount, likeContent, unlikeContent } from '../api/interactions'
import { requireLogin } from '../auth/loginPrompt'
import { signOut } from '../auth/session'
import { showToast } from '../common/toast'

/**
 * 动态卡的两个动作（`R-31`）：点赞 + 内联评论展开。
 *
 * <p>抽成 composable 给 `/dynamic` 与空间动态 tab 共用 —— 两处卡片长得一样，
 * 点赞的乐观更新 / 写后对齐 / 401 处理不该复制两份。
 *
 * <h2>★ 评论是**页面级单值**</h2>
 * `openCommentsId` 同时只保存一个 id：滚过几十张卡时不会挂几十个 `CommentSection`、
 * 也不会打几十个评论请求。卡片只是受控显示（`comments-open` + `toggle-comments`）。
 *
 * <h2>点赞口径（沿用 `T10` 的三态）</h2>
 * - `liked === null` = 本面没查；点下去视为 `true`（乐观更新）；
 * - 成功后回读 `GET /contents/{id}/like-count` 做**写后对齐**，对齐失败不回滚
 *   （主写已经成功，回滚反而画出旧值）；
 * - 失败回滚到点击前；401 走 `signOut()` + toast。
 */
export function useDynamicActions() {
  const likeBusy = reactive(new Set<number>())
  const openCommentsId = ref<number | null>(null)

  async function toggleLike(card: DynamicCardVM): Promise<void> {
    if (likeBusy.has(card.id)) return
    if (!(await requireLogin())) return
    const target = card.liked !== true
    const beforeLiked = card.liked
    const beforeCount = card.likeCount
    likeBusy.add(card.id)
    // 乐观更新（三态：null = 本面没查，点下去就是 true）
    card.liked = target
    if (typeof beforeCount === 'number') card.likeCount = Math.max(0, beforeCount + (target ? 1 : -1))
    try {
      if (target) await likeContent(card.id)
      else await unlikeContent(card.id)
      // 写后对齐：服务端权威值。对齐失败不回滚（主写已经成功，回滚反而画出旧值）
      try {
        const authoritative = await fetchLikeCount(card.id)
        if (typeof authoritative === 'number' && Number.isFinite(authoritative)) card.likeCount = authoritative
      } catch {
        /* 计数对齐是尽力而为 */
      }
    } catch (exception) {
      card.liked = beforeLiked
      card.likeCount = beforeCount
      if (exception instanceof ApiError && exception.status === 401) {
        signOut()
        showToast('登录已过期，请重新登录', 'error')
        return
      }
      showToast(describeError(exception), 'error')
    } finally {
      likeBusy.delete(card.id)
    }
  }

  /** 同时只开一个：点已展开的那张 = 收起 */
  function toggleComments(card: DynamicCardVM): void {
    openCommentsId.value = openCommentsId.value === card.id ? null : card.id
  }

  function onCommentCountChanged(card: DynamicCardVM, count: number): void {
    card.commentCount = count
  }

  /** 换页 / 重载 / 切换作者时收起（旧的评论面板已无对应卡片） */
  function closeComments(): void {
    openCommentsId.value = null
  }

  return {
    likeBusy,
    toggleLike,
    openCommentsId,
    toggleComments,
    onCommentCountChanged,
    closeComments,
  }
}
