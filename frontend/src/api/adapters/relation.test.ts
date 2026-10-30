import { describe, expect, it } from 'vitest'
import type { components } from '../../types/api'
import { toRelationPage, toRelationUser } from './relation'

type FollowUserVO = components['schemas']['FollowUserVO']

function user(overrides: Partial<Record<keyof FollowUserVO, unknown>> = {}): FollowUserVO {
  return {
    userId: 4,
    username: '五个字神人',
    isFollowed: true,
    isSelf: false,
    ...overrides,
  } as FollowUserVO
}

describe('toRelationUser', () => {
  it('映射 id / 名字 / 首字头像 / 关注态', () => {
    const vm = toRelationUser(user())
    expect(vm.id).toBe(4)
    expect(vm.name).toBe('五个字神人')
    expect(vm.initial).toBe('五')
    expect(vm.followed).toBe(true)
    expect(vm.isSelf).toBe(false)
  })

  it('★ 已关注 / 未关注是两个真值：false 不许被归一成 null（也不是「本面不查」）', () => {
    // 两个列表端点都要求登录（B 站同样返回 -101），后端一定知道"我是谁" ⇒ false 是真的没关注
    expect(toRelationUser(user({ isFollowed: false })).followed).toBe(false)
  })

  it('自己（isSelf）与时关注自己互不影响', () => {
    expect(toRelationUser(user({ isSelf: true, isFollowed: false }))).toMatchObject({
      isSelf: true,
      followed: false,
    })
  })

  it('空名字 ⇒ 未知用户 + 首字 U（不画空圆）', () => {
    expect(toRelationUser(user({ username: '   ' }))).toMatchObject({
      name: '未知用户',
      initial: '未',
    })
    expect(toRelationUser(user({ username: null })).name).toBe('未知用户')
  })
})

describe('toRelationPage', () => {
  it('一页的总数 / 页码来自信封，不是本页条数', () => {
    const page = toRelationPage({
      list: [user({ userId: 3 }), user({ userId: 4 })],
      total: 11,
      page: 1,
      pageSize: 30,
      totalPages: 1,
    })
    expect(page.items).toHaveLength(2)
    expect(page.total).toBe(11)
    expect(page.totalPages).toBe(1)
  })

  it('空页 / 缺省 ⇒ 空数组 + 安全默认值', () => {
    expect(toRelationPage(undefined)).toEqual({ items: [], page: 1, total: 0, totalPages: 0 })
  })
})
