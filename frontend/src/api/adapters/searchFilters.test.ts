import { describe, expect, it } from 'vitest'
import { normalizeSearchFilters, parseSearchFilters, toSearchQuery } from './searchFilters'

describe('parseSearchFilters（URL → 筛选）', () => {
  it('合法枚举原样透传', () => {
    expect(parseSearchFilters({ type: 'VIDEO' })).toEqual({ type: 'VIDEO', order: undefined })
    expect(parseSearchFilters({ order: 'PLAY' })).toEqual({ type: undefined, order: 'PLAY' })
    expect(parseSearchFilters({ type: 'VIDEO', order: 'PLAY' })).toEqual({ type: 'VIDEO', order: 'PLAY' })
  })

  it('★ 非法值静默丢弃（后端对非法枚举回 400，手改 URL 不该让整页挂掉）', () => {
    // 小写 / 数字 / 未知词 都不是后端认的字面量
    expect(parseSearchFilters({ type: 'video' }).type).toBeUndefined()
    expect(parseSearchFilters({ type: '1' }).type).toBeUndefined()
    expect(parseSearchFilters({ order: 'hot' }).order).toBeUndefined()
    expect(parseSearchFilters({ order: 'latest' }).order).toBeUndefined()
  })

  it('★ 图文永远不发 PLAY（图文 play_count 结构性恒 0，最多播放在那里没有意义）', () => {
    expect(parseSearchFilters({ type: 'POST', order: 'PLAY' })).toEqual({ type: 'POST', order: undefined })
    // 对照：视频同一份 URL 参数要保留 PLAY（否则"图文丢掉"可能只是"全都丢掉"）
    expect(parseSearchFilters({ type: 'VIDEO', order: 'PLAY' })).toEqual({ type: 'VIDEO', order: 'PLAY' })
  })

  it('同名参数出现多次时只认第一个（?type=a&type=b）', () => {
    expect(parseSearchFilters({ type: ['POST', 'VIDEO'] }).type).toBe('POST')
    expect(parseSearchFilters({ type: [] }).type).toBeUndefined()
  })

  it('空对象 / null 值 ⇒ 综合 + 最新', () => {
    expect(parseSearchFilters({})).toEqual({ type: undefined, order: undefined })
    expect(parseSearchFilters({ type: null, order: null })).toEqual({ type: undefined, order: undefined })
  })
})

describe('toSearchQuery（筛选 → URL）', () => {
  it('★ 缺省值不写进 URL（综合 = 无 type；最新 = 无 order，含显式传 LATEST）', () => {
    expect(toSearchQuery({})).toEqual({})
    expect(toSearchQuery({ order: 'LATEST' })).toEqual({})
  })

  it('非缺省值才写键', () => {
    expect(toSearchQuery({ type: 'VIDEO' })).toEqual({ type: 'VIDEO' })
    expect(toSearchQuery({ type: 'VIDEO', order: 'PLAY' })).toEqual({ type: 'VIDEO', order: 'PLAY' })
  })

  it('★ 图文 + PLAY ⇒ 只留 type（两处入口同一份规则，不各判一次）', () => {
    expect(toSearchQuery({ type: 'POST', order: 'PLAY' })).toEqual({ type: 'POST' })
  })

  it('非法输入与 parse 同口径（归一后再落 URL）', () => {
    expect(toSearchQuery({ type: 'video', order: 'hot' })).toEqual({})
  })

  it('normalizeSearchFilters 是两处入口共用的唯一判定', () => {
    expect(normalizeSearchFilters({ type: 'POST', order: 'PLAY' })).toEqual({ type: 'POST', order: undefined })
  })
})
