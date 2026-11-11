import { afterEach, describe, expect, it, vi } from 'vitest'
import { resetServerClock, serverNow, syncServerClock } from './serverClock'

/**
 * 服务器时钟锚点的判别力（这三条各自都能红）：
 * 1. 无锚点 ⇒ 必须退回浏览器时钟（否则首屏拿到 1970 或 NaN）；
 * 2. 有锚点 ⇒ 基准读的是**单调时钟增量**（把 performance.now 换成固定值就暴露）；
 * 3. 系统时间被改 ⇒ 基准**不跟着跳**（这条专门钉"别用 Date.now 做锚"）。
 */
describe('serverClock', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
    resetServerClock()
  })

  it('无锚点 ⇒ 退回浏览器时钟（不编一个时间）', () => {
    const before = Date.now()
    const now = serverNow().getTime()
    const after = Date.now()
    expect(now).toBeGreaterThanOrEqual(before)
    expect(now).toBeLessThanOrEqual(after)
  })

  it('同步后 ⇒ 基准等于 Date 头的时间', () => {
    vi.spyOn(performance, 'now').mockReturnValue(1_000)
    syncServerClock('Fri, 30 Oct 2026 16:00:00 GMT')
    expect(serverNow().getTime()).toBe(Date.parse('2026-10-30T16:00:00Z'))
  })

  it('★ 锚点走单调时钟：单调时钟前进多少，基准就前进多少', () => {
    const perf = vi.spyOn(performance, 'now').mockReturnValue(1_000)
    syncServerClock('Fri, 30 Oct 2026 16:00:00 GMT')
    const serverEpoch = Date.parse('2026-10-30T16:00:00Z')

    perf.mockReturnValue(91_000)
    expect(serverNow().getTime()).toBe(serverEpoch + 90_000)
  })

  it('★ 系统时间被改到几年前，基准不跟着跳（换成 Date.now 做锚必红）', () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    const perf = vi.spyOn(performance, 'now').mockReturnValue(1_000)
    syncServerClock('Fri, 30 Oct 2026 16:00:00 GMT')
    const serverEpoch = Date.parse('2026-10-30T16:00:00Z')

    perf.mockReturnValue(91_000)
    vi.setSystemTime(new Date('2020-01-01T00:00:00Z'))
    expect(serverNow().getTime()).toBe(serverEpoch + 90_000)
  })

  it('非法 / 缺失 Date 头 ⇒ 保留旧锚点（一个坏响应不清掉可用的基准）', () => {
    const perf = vi.spyOn(performance, 'now').mockReturnValue(1_000)
    syncServerClock('Fri, 30 Oct 2026 16:00:00 GMT')

    perf.mockReturnValue(2_000)
    syncServerClock('not-a-date')
    syncServerClock(null)

    expect(serverNow().getTime()).toBe(Date.parse('2026-10-30T16:00:00Z') + 1_000)
  })
})
