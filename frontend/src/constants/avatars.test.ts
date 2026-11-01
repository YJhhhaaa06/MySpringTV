import { describe, expect, it } from 'vitest'
import {
  DEFAULT_AVATAR_COUNT,
  DEFAULT_AVATAR_IDS,
  defaultAvatarForUser,
  defaultAvatarIdOf,
  defaultAvatarUrl,
  isDefaultAvatarId,
} from './avatars'

describe('默认头像 URL（R-27）', () => {
  it('编号 → 稳定 URL；六张齐全', () => {
    expect(DEFAULT_AVATAR_COUNT).toBe(6)
    expect(DEFAULT_AVATAR_IDS).toEqual([1, 2, 3, 4, 5, 6])
    expect(defaultAvatarUrl(3)).toBe('/avatars/default-3.svg')
    expect(DEFAULT_AVATAR_IDS.map((id) => defaultAvatarUrl(id))).toEqual([
      '/avatars/default-1.svg',
      '/avatars/default-2.svg',
      '/avatars/default-3.svg',
      '/avatars/default-4.svg',
      '/avatars/default-5.svg',
      '/avatars/default-6.svg',
    ])
  })

  it('编号合法性只认 1..6 的整数', () => {
    expect(isDefaultAvatarId(3)).toBe(true)
    expect(isDefaultAvatarId(0)).toBe(false)
    expect(isDefaultAvatarId(7)).toBe(false)
    expect(isDefaultAvatarId(3.5)).toBe(false)
  })

  it('★ userId 回落顺序与上一轮 Math.abs(id)%6 一致（不能改顺序）', () => {
    expect(defaultAvatarForUser(null)).toBeNull()
    expect(defaultAvatarForUser(Number.NaN)).toBeNull()
    expect(defaultAvatarForUser(1)).toBe('/avatars/default-2.svg')
    expect(defaultAvatarForUser(2)).toBe('/avatars/default-3.svg')
    expect(defaultAvatarForUser(6)).toBe('/avatars/default-1.svg')
    expect(defaultAvatarForUser(-1)).toBe('/avatars/default-2.svg')
  })

  it('从 URL 反解默认编号（设置弹层高亮当前默认）', () => {
    expect(defaultAvatarIdOf('/avatars/default-3.svg')).toBe(3)
    expect(defaultAvatarIdOf('/upload/avatar/x.jpg')).toBeNull()
    expect(defaultAvatarIdOf('/avatars/default-9.svg')).toBeNull()
    expect(defaultAvatarIdOf(null)).toBeNull()
  })
})
