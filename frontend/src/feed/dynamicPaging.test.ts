import { describe, expect, it } from 'vitest'
import { appendUnique, nextPageToken, sourceKeyOf } from './dynamicPaging'

describe('nextPageToken', () => {
  it('还有下一页 ⇒ 页码 +1；最后一页 ⇒ null（到底）', () => {
    expect(nextPageToken({ page: 1, totalPages: 3 })).toBe('2')
    expect(nextPageToken({ page: 3, totalPages: 3 })).toBeNull()
  })

  it('空列表（totalPages=0）⇒ 第 1 页就是到底，不发第二次请求', () => {
    expect(nextPageToken({ page: 1, totalPages: 0 })).toBeNull()
  })
})

describe('appendUnique', () => {
  it('首屏原样返回；续页只留没出现过的 id（保序）', () => {
    expect(appendUnique([], [{ id: 3 }, { id: 2 }])).toEqual([{ id: 3 }, { id: 2 }])
    expect(appendUnique([{ id: 3 }, { id: 2 }], [{ id: 2 }, { id: 1 }])).toEqual([{ id: 1 }])
  })

  it('整页都重复 ⇒ 空数组（由状态机按"空页 + 令牌"判失败 / 到底，不在这里伪装）', () => {
    expect(appendUnique([{ id: 2 }], [{ id: 2 }])).toEqual([])
  })
})

describe('sourceKeyOf', () => {
  it('全部 / 不同作者是三个不同的源', () => {
    expect(sourceKeyOf({ mode: 'all' })).toBe('all')
    expect(sourceKeyOf({ mode: 'author', authorId: 7 })).toBe('author:7')
    expect(sourceKeyOf({ mode: 'author', authorId: 8 })).not.toBe(sourceKeyOf({ mode: 'author', authorId: 7 }))
  })
})
