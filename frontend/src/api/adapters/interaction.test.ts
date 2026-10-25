import { describe, expect, it } from 'vitest'
import type { components } from '../../types/api'
import { likedOf, toInteraction } from './interaction'

type InteractionStatusVO = components['schemas']['InteractionStatusVO']

/**
 * 夹具按**运行时**形状造：spec 声明 `coin: integer` / `isFollowed: boolean`（不可为 null），
 * 真实响应里却是显式 `null`（契约漏标 nullable，GAP-07）⇒ 末尾 cast 掉类型谎言。
 */
function vo(fields: Record<string, unknown>): InteractionStatusVO {
  return { ...fields } as unknown as InteractionStatusVO
}

describe('toInteraction', () => {
  it('整块为 null ⇒ null（匿名：本面根本不查）', () => {
    expect(toInteraction(null)).toBeNull()
    expect(toInteraction(undefined)).toBeNull()
  })

  it('★ 三态：followed 的 null 不许归一成 false', () => {
    // /users/{id}、/feed 只查「赞」，其余三维是 null —— 当成 false 会让关注按钮永远显示"关注"
    const vm = toInteraction(vo({ isLiked: true, coin: null, isFavorited: null, isFollowed: null }))
    expect(vm).toEqual({ liked: true, coin: 0, favorited: false, followed: null })
  })

  it('coin 只有数字才算（null ⇒ 0，因为"没投过"和"没给"在投币面板上是同一件事）', () => {
    expect(toInteraction(vo({ coin: 2 }))?.coin).toBe(2)
    expect(toInteraction(vo({}))?.coin).toBe(0)
  })

  it('布尔字段只认 true（缺省 = 未点赞 / 未收藏）', () => {
    expect(toInteraction(vo({}))?.liked).toBe(false)
    expect(toInteraction(vo({ isLiked: true }))?.liked).toBe(true)
    expect(toInteraction(vo({ isFavorited: true }))?.favorited).toBe(true)
  })
})

describe('likedOf', () => {
  it('列表面只有「赞」这一维是真值，其余不参与', () => {
    const likeable = vo({ coin: null, isFavorited: null, isFollowed: null, isLiked: false })
    expect(likedOf(likeable)).toBe(false)
    expect(likedOf(vo({ ...likeable, isLiked: true }))).toBe(true)
  })

  it('匿名 / 缺字段 ⇒ null（"不知道"，不是"没点过"）', () => {
    expect(likedOf(null)).toBeNull()
    expect(likedOf(undefined)).toBeNull()
    expect(likedOf(vo({}))).toBeNull()
  })
})
