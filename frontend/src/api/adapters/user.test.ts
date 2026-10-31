import { describe, expect, it } from 'vitest'
import type { components } from '../../types/api'
import { toAuthorCard, toCurrentUser } from './user'

type ProfileVO = components['schemas']['ProfileVO']

describe('toCurrentUser', () => {
  it('用昵称首字做头像占位（后端没有 avatar 字段）', () => {
    const user = toCurrentUser({ id: 233, username: 'frontend_demo', phone: '13900000002', admin: false, coin: 3 })
    expect(user.initial).toBe('F')
    expect(user.name).toBe('frontend_demo')
    expect(user.spaceHref).toBe('/space/233')
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

  it('★ signedToday 原样带出（签到按钮「进页就置灰」的唯一判据）', () => {
    // 去掉 toCurrentUser 里那一行映射，本用例必红 —— 这正是"后端有、前端丢"的那一环
    expect(toCurrentUser({ signedToday: true }).signedToday).toBe(true)
    expect(toCurrentUser({ signedToday: false }).signedToday).toBe(false)
  })

  it('signedToday 缺失 ⇒ false（不知道就先别点亮按钮，不当成"今天还没签"去放行）', () => {
    expect(toCurrentUser({}).signedToday).toBe(false)
  })

  it('coin 保留一位小数（取整只在展示层，投币弹层要用真值）', () => {
    expect(toCurrentUser({ coin: 1.5 }).coin).toBe(1.5)
  })

  it('★ historyRecording 与上面两条**反向**：缺失 ⇒ true（按"开"，DB 默认值）', () => {
    // 去掉 toCurrentUser 里那行 `!== false` 映射（或改成 `=== true`），本用例必红。
    // 口径与 signedToday 相反是有意的：那里缺字段宁可"别放行"，这里缺字段宁可"别关掉记录"。
    expect(toCurrentUser({}).historyRecording).toBe(true)
    expect(toCurrentUser({ historyRecording: true }).historyRecording).toBe(true)
    expect(toCurrentUser({ historyRecording: false }).historyRecording).toBe(false)
  })
})

describe('toAuthorCard', () => {
  const profile: ProfileVO = {
    userId: 3,
    username: '基米',
    followerCount: 12_345,
    followCount: 2,
    isFollowed: false,
    contentPage: { total: 8, page: 1, pageSize: 10, totalPages: 1, list: [] },
  }

  it('粉丝/关注/投稿保持数字（关注按钮的乐观加减要用）', () => {
    const card = toAuthorCard(profile)
    expect(card.name).toBe('基米')
    expect(card.initial).toBe('基')
    expect(card.followerCount).toBe(12_345)
    expect(card.followingCount).toBe(2)
    expect(card.worksCount).toBe(8)
    expect(card.href).toBe('/space/3')
  })

  it('缺失的计数 ⇒ null（渲染成 `-`，不假装是 0）', () => {
    expect(toAuthorCard({}).followerCount).toBeNull()
    expect(toAuthorCard({}).worksCount).toBeNull()
  })

  it('★ isFollowed 匿名时是 null ⇒ VM 也是 null（不回退成 false）', () => {
    expect(toAuthorCard({}).followed).toBeNull()
    expect(toAuthorCard({ isFollowed: null } as unknown as ProfileVO).followed).toBeNull()
    expect(toAuthorCard(profile).followed).toBe(false)
    expect(toAuthorCard({ ...profile, isFollowed: true }).followed).toBe(true)
  })
})

