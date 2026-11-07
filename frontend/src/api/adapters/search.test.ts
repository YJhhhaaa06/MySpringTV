import { describe, expect, it } from 'vitest'
import type { components } from '../../types/api'
import { toSearchResult } from './search'

type ContentVO = components['schemas']['ContentVO']

/** 上海正午（**带偏移**：`createTime` 按 +08:00 解释，夹具必须同源） */
const now = new Date('2026-10-25T12:00:00+08:00')

function content(id: number): ContentVO {
  return { id, title: `标题${id}`, authorId: 1, authorName: 'UP', coverUrl: '/c.png', type: 1 }
}

describe('toSearchResult', () => {
  it('Page 分页字段原样透出（page/pageSize/total/totalPages），列表映射成卡片', () => {
    const result = toSearchResult(
      { list: [content(1), content(2)], page: 2, pageSize: 20, total: 25, totalPages: 2 },
      '测试',
      now,
    )
    expect(result.keyword).toBe('测试')
    expect(result.page).toBe(2)
    expect(result.pageSize).toBe(20)
    expect(result.total).toBe(25)
    expect(result.totalPages).toBe(2)
    expect(result.items.map((item) => item.href)).toEqual(['/video/1', '/video/2'])
  })

  it('空结果：total=0 ⇒ totalPages=0（分页组件不渲染）', () => {
    const result = toSearchResult({ list: [], page: 1, pageSize: 20, total: 0, totalPages: 0 }, 'java', now)
    expect(result.items).toEqual([])
    expect(result.total).toBe(0)
    expect(result.totalPages).toBe(0)
  })

  it('list 缺失 ⇒ 空数组，页面不炸', () => {
    const result = toSearchResult({}, 'java', now)
    expect(result.items).toEqual([])
    expect(result.total).toBe(0)
  })

  it('pageSize 缺失时用本次列表长度兜底（不是硬编码 20）', () => {
    const result = toSearchResult({ list: [content(1), content(2), content(3)] }, 'java', now)
    expect(result.pageSize).toBe(3)
  })
})
