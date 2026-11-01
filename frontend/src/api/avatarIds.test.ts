import { describe, expect, it } from 'vitest'
import type { CommentVM } from '../types/ui'
import { authorIdsOf, collectCommentAuthorIds, mergeAvatarIds } from './avatarIds'

function comment(authorId: number, replies: Array<{ authorId: number }> = []): CommentVM {
  return { authorId, replies } as unknown as CommentVM
}

describe('avatarIds：作者快照面的 id 收集（R-27）', () => {
  it('authorIdsOf 去重、只留正整数、保持首次出现顺序', () => {
    expect(authorIdsOf([{ authorId: 2 }, { authorId: 2 }, { authorId: 0 }, { authorId: -1 }, { authorId: 3 }]))
      .toEqual([2, 3])
  })

  it('collectCommentAuthorIds 把主楼与预览楼中楼一起收，且 dedupe', () => {
    const items = [
      comment(1, [{ authorId: 2 }, { authorId: 1 }]),
      comment(2, [{ authorId: 3 }, { authorId: 0 }]),
    ]
    expect(collectCommentAuthorIds(items)).toEqual([1, 2, 3])
  })

  it('mergeAvatarIds：新增才换引用；没有新增时原样返回（不触发无意义重读）', () => {
    const current = [1, 2]
    expect(mergeAvatarIds(current, [2, 1, 0, -1])).toBe(current)
    expect(mergeAvatarIds(current, [2, 3, 3])).toEqual([1, 2, 3])
    expect(mergeAvatarIds([], [4, 4, 0])).toEqual([4])
  })
})
