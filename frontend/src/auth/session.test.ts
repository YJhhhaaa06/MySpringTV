import { describe, expect, it } from 'vitest'
import type { CurrentUserVM } from '../types/ui'
import { markSignedIn } from './session'

/**
 * 签到回执的**状态迁移**。
 *
 * <p>为什么只测这个纯函数：`applySignInResult` 只是"取当前用户 → 换成本函数的产物"，
 * 而"签到后按钮该不该变灰"这个判断完整地落在本函数里。没有 DOM 环境（本仓不装 jsdom）
 * ⇒ 把判别力放在这里，组件只负责把 `signedToday` 绑到 `disabled`。
 */
const user: CurrentUserVM = {
  id: 7,
  name: '五个字神人',
  initial: '五',
  admin: false,
  coin: 0.5,
  signedToday: false,
  spaceHref: '/space/7',
}

describe('markSignedIn', () => {
  it('★ 签到成功 ⇒ 今天置为已签，并采用服务端余额', () => {
    const next = markSignedIn(user, 1.5)
    expect(next?.signedToday).toBe(true)
    expect(next?.coin).toBe(1.5)
  })

  it('★ 余额缺失 ⇒ 保留本地余额（不把 undefined 画成 0 枚）', () => {
    expect(markSignedIn(user, undefined)?.coin).toBe(0.5)
    expect(markSignedIn(user, null)?.coin).toBe(0.5)
    expect(markSignedIn(user, Number.NaN)?.coin).toBe(0.5)
  })

  it('已签过的用户再调用 ⇒ 仍是已签（幂等，不翻转）', () => {
    const signed = { ...user, signedToday: true }
    expect(markSignedIn(signed, 2)?.signedToday).toBe(true)
  })

  it('未登录 ⇒ null（不是造一个假用户）', () => {
    expect(markSignedIn(null, 1)).toBeNull()
  })

  it('不修改入参（reactive 里的旧对象被就地改会跳过依赖追踪）', () => {
    const snapshot = { ...user }
    markSignedIn(user, 3)
    expect(user).toEqual(snapshot)
  })
})
