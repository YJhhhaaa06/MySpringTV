import { describe, expect, it } from 'vitest'
import type { components } from '../../types/api'
import { toCurrentUser, toAuthorCard } from './user'

type ProfileVO = components['schemas']['ProfileVO']

describe('toCurrentUser', () => {
  it('用昵称首字做头像占位（后端没有 avatar 字段）', () => {
    const user = toCurrentUser({ id: 233, username: 'frontend_demo', phone: '13900000002', admin: false, coin: 3 })
    expect(user.initial).toBe('F')
    expect(user.name).toBe('frontend_demo')
    expect(user.spaceHref).toBe('/placeholder/space?uid=233')
  })

  it('中文昵称取第一个字（不是首字节）', () => {
    expect(toCurrentUser({ username: '一号员工' }).initial).toBe('一')
  })

  it('emoji 昵称按码点取，不切坏代理对', () => {
    expect(toCurrentUser({ username: '🐧企鹅' }).initial).toBe('🐧')
  })

  it('昵称缺失 ⇒ 兜底名字与首字', () => {
    const user = toCurrentUser({})
    expect(user.name).toBe('B站用户')
    expect(user.initial).toBe('B')
  })

  it('coin 缺失 ⇒ 0；admin 只认 true', () => {
    expect(toCurrentUser({}).coin).toBe(0)
    expect(toCurrentUser({}).admin).toBe(false)
    expect(toCurrentUser({ admin: true }).admin).toBe(true)
  })
})

describe('toAuthorCard', () => {
  it('粉丝/关注/投稿都是真接口数字的格式化结果', () => {
    const profile: ProfileVO = {
      userId: 3,
      username: '基米',
      followerCount: 12_345,
      followCount: 2,
      contentPage: { total: 8, page: 1, pageSize: 10, totalPages: 1, list: [] },
    }
    const card = toAuthorCard(profile)
    expect(card.name).toBe('基米')
    expect(card.followerText).toBe('1.2万')
    expect(card.followingText).toBe('2')
    expect(card.worksText).toBe('8')
    expect(card.href).toBe('/placeholder/space?uid=3')
  })

  it('缺失的计数显示 `-`（不假装是 0）', () => {
    expect(toAuthorCard({}).followerText).toBe('-')
    expect(toAuthorCard({}).worksText).toBe('-')
  })
})
