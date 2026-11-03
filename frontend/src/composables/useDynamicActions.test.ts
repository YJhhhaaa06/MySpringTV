import { describe, expect, it } from 'vitest'
import type { DynamicCardVM } from '../types/ui'
import { useDynamicActions } from './useDynamicActions'

/** 只关心 id / commentCount 的卡片桩（其余字段与本组用例无关） */
function card(id: number, commentCount: number | null = 0): DynamicCardVM {
  return { id, commentCount } as DynamicCardVM
}

describe('useDynamicActions', () => {
  it('评论同时只开一个：开 A → 开 B 关 A → 再点 B 收起', () => {
    const actions = useDynamicActions()
    const a = card(1)
    const b = card(2)

    actions.toggleComments(a)
    expect(actions.openCommentsId.value).toBe(1)

    // ★ 单值：开 B 必须把 A 关掉（否则会同时挂两个 CommentSection / 打两次请求）
    actions.toggleComments(b)
    expect(actions.openCommentsId.value).toBe(2)

    actions.toggleComments(b)
    expect(actions.openCommentsId.value).toBeNull()
  })

  it('换页 / 重载时 closeComments 收起；计数回写落到卡片', () => {
    const actions = useDynamicActions()
    const item = card(7, 3)

    actions.toggleComments(item)
    actions.onCommentCountChanged(item, 4)
    expect(item.commentCount).toBe(4)

    actions.closeComments()
    expect(actions.openCommentsId.value).toBeNull()
  })
})
