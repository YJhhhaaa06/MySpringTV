import { describe, expect, it } from 'vitest'
import { planTracking } from './historyTracking'

/**
 * 「记录浏览历史」开关的决策表（`R-25`）。
 *
 * <p>★ 判别力在哪：把 `planTracking` 的 `historyRecording === false` 改成 `=== true`
 * 或 `!historyRecording`，"undefined 按开处理"与"关掉只读"两类断言必红 ——
 * 而这两种写错在实际界面上**看不出来**（只会表现为"记录莫名少了"或"关不掉"）。
 */
describe('planTracking（开关 → 上报计划）', () => {
  it('未登录 ⇒ skip（既有行为：五个端点全要登录，打了也是 401）', () => {
    expect(planTracking(false, true)).toBe('skip')
    expect(planTracking(false, false)).toBe('skip')
  })

  it('登录 + 开着 ⇒ track', () => {
    expect(planTracking(true, true)).toBe('track')
  })

  it('★ 登录 + 关掉 ⇒ read-only（可读续播，一个写都不发，连 /play 都不发）', () => {
    expect(planTracking(true, false)).toBe('read-only')
  })

  it('★ 缺字段（老后端 / 未带回）⇒ 按开处理，不是按关', () => {
    expect(planTracking(true, undefined)).toBe('track')
  })
})
