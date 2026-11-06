import { describe, expect, it } from 'vitest'
import { legacyFavoriteTarget } from './legacyFavoriteRedirect'

describe('legacyFavoriteTarget', () => {
  it('普通收藏页：folder / page 平移到空间收藏 tab', () => {
    expect(legacyFavoriteTarget('favorites', { folder: '3', page: '2' }, 5)).toBe(
      '/space/5?tab=folders&folder=3&itemPage=2',
    )
  })

  it('缺省 / 非法参数不写进新 URL', () => {
    expect(legacyFavoriteTarget('favorites', {}, 5)).toBe('/space/5?tab=folders')
    expect(legacyFavoriteTarget('favorites', { folder: 'abc', page: '0' }, 5)).toBe(
      '/space/5?tab=folders',
    )
  })

  it('★ 稍后再看：独立路由与旧 ?tab=later 都映射成 view=later，且不让 folder 混进来', () => {
    expect(legacyFavoriteTarget('watchlater', { page: '2' }, 5)).toBe(
      '/space/5?tab=folders&view=later&itemPage=2',
    )
    expect(legacyFavoriteTarget('favorites', { tab: 'later', folder: '3' }, 5)).toBe(
      '/space/5?tab=folders&view=later',
    )
  })
})
