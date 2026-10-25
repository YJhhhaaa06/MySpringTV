import { describe, expect, it } from 'vitest'
import type { components } from '../../types/api'
import { splitHero } from './hero'

type ContentVO = components['schemas']['ContentVO']

function content(id: number | undefined, coverUrl: string | null | undefined): ContentVO {
  // 契约层把 coverUrl 声明成 `string?`（不可为 null），实际匿名响应会给显式 null ⇒ 夹具按运行时形状造
  return { id, title: `标题${id ?? ''}`, authorName: 'UP', coverUrl, type: 1 } as ContentVO
}

describe('splitHero', () => {
  it('前 3 条有封面的进轮播，其余按原顺序进网格，且不重复', () => {
    const items = [
      content(1, '/a.png'),
      content(2, null),
      content(3, '/c.png'),
      content(4, '/d.png'),
      content(5, '/e.png'),
    ]
    const { slides, rest } = splitHero(items, 3)
    expect(slides.map((slide) => slide.id)).toEqual([1, 3, 4])
    expect(rest.map((item) => item.id)).toEqual([2, 5])
  })

  it('没有封面的内容永远不进轮播（否则大图位会是空白色块）', () => {
    const { slides, rest } = splitHero([content(1, null), content(2, '  '), content(3, '/c.png')], 3)
    expect(slides.map((slide) => slide.id)).toEqual([3])
    expect(rest.map((item) => item.id)).toEqual([1, 2])
  })

  it('limit 生效：多出来的有封面内容回落到网格', () => {
    const { slides, rest } = splitHero(
      [content(1, '/a.png'), content(2, '/b.png'), content(3, '/c.png'), content(4, '/d.png')],
      2,
    )
    expect(slides.map((slide) => slide.id)).toEqual([1, 2])
    expect(rest.map((item) => item.id)).toEqual([3, 4])
  })

  it('limit 为 0 ⇒ 全部进网格', () => {
    const { slides, rest } = splitHero([content(1, '/a.png')], 0)
    expect(slides).toEqual([])
    expect(rest).toHaveLength(1)
  })

  it('id 缺失的内容不能当轮播（拼不出链接），回落到网格由卡片兜底', () => {
    const { slides, rest } = splitHero([content(undefined, '/a.png'), content(2, '/b.png')], 3)
    expect(slides.map((slide) => slide.id)).toEqual([2])
    expect(rest).toHaveLength(1)
  })

  it('轮播项带真链接与真标题', () => {
    const { slides } = splitHero([content(7, '/a.png')], 1)
    expect(slides[0]).toMatchObject({ id: 7, title: '标题7', href: '/video/7', coverUrl: '/a.png', authorName: 'UP' })
  })
})
