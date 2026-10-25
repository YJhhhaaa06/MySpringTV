import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/http'
import {
  MIN_DEGRADED_INTERVAL_MS,
  NORMAL_INTERVAL_MS,
  PENDING_TTL_MS,
  clearPending,
  createProgressReporter,
  planBackoff,
  planRecovery,
  planResume,
  readPending,
  shouldStopReporting,
  writePending,
} from './progressReporter'

/**
 * 播放进度客户端契约的**判别性**测试。
 *
 * 断言口径来自 `architecture/domain/history/history-客户端契约.md`
 * （§三 节奏 / §五 503 退避 / §六 崩溃补偿 / §八 错误码），不是抄实现。
 */

const NOW = new Date('2026-10-25T12:00:00Z').getTime()

beforeEach(() => {
  vi.stubGlobal('localStorage', {
    store: new Map<string, string>(),
    getItem(key: string) {
      return (this.store as Map<string, string>).get(key) ?? null
    },
    setItem(key: string, value: string) {
      ;(this.store as Map<string, string>).set(key, value)
    },
    removeItem(key: string) {
      ;(this.store as Map<string, string>).delete(key)
    },
  } satisfies Partial<Storage>)
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('planResume（续播基线 = max(已落库, 本地最新)）', () => {
  it('本地比服务端新 ⇒ 从本地继续，并要求补报', () => {
    const plan = planResume(120, { progress: 640, ts: NOW - 1000 }, NOW)
    expect(plan.start).toBe(640)
    expect(plan.needsCatchUp).toBe(true)
  })

  it('本地比服务端旧（另一台设备看到更后面）⇒ 只认服务端，且**不许补报**', () => {
    // 不比较就补报会把别处的新进度覆盖成旧的（服务端 LWW，不做单调守卫）
    const plan = planResume(600, { progress: 300, ts: NOW - 1000 }, NOW)
    expect(plan.start).toBe(600)
    expect(plan.needsCatchUp).toBe(false)
  })

  it('本地标记过期（> 30m）⇒ 丢弃（会话早结束，别拿旧值跳）', () => {
    const plan = planResume(120, { progress: 999, ts: NOW - PENDING_TTL_MS - 1 }, NOW)
    expect(plan.start).toBe(120)
    expect(plan.needsCatchUp).toBe(false)
  })

  it('服务端读不到（null）也按 0 处理，不抛错', () => {
    expect(planResume(null, null, NOW)).toEqual({ start: 0, needsCatchUp: false })
    expect(planResume(undefined, { progress: 42, ts: NOW }, NOW).start).toBe(42)
  })
})

describe('本地崩溃补偿标记', () => {
  it('写入后能读回（整数秒）', () => {
    writePending(65, 640.7, NOW)
    expect(readPending(65, NOW)).toEqual({ progress: 640, ts: NOW })
  })

  it('按内容隔离：另一条内容读不到这条的进度', () => {
    writePending(65, 100, NOW)
    expect(readPending(66, NOW)).toBeNull()
  })

  it('过期 / 损坏 / 脏数据 ⇒ null（不把播放器打挂）', () => {
    writePending(65, 100, NOW - PENDING_TTL_MS - 5)
    expect(readPending(65, NOW)).toBeNull()
    localStorage.setItem('history:pending:66', '{not json')
    expect(readPending(66, NOW)).toBeNull()
    localStorage.setItem('history:pending:67', JSON.stringify({ progress: 'x', ts: NOW }))
    expect(readPending(67, NOW)).toBeNull()
  })

  it('清掉之后读不到', () => {
    writePending(65, 100, NOW)
    clearPending(65)
    expect(readPending(65, NOW)).toBeNull()
  })
})

describe('退避与恢复', () => {
  it('503 + Retry-After：按服务端节奏退避，且不低于 30s 下限', () => {
    expect(planBackoff(new ApiError(503, 'X', '服务不可用', [], 30), NORMAL_INTERVAL_MS)).toEqual({
      intervalMs: 30_000,
      degraded: true,
    })
    // 服务端说 5s 也不能真的 5s 一次（那等于没退避）
    expect(planBackoff(new ApiError(503, 'X', '服务不可用', [], 5), NORMAL_INTERVAL_MS).intervalMs).toBe(
      MIN_DEGRADED_INTERVAL_MS,
    )
    // 服务端说 120s 就听它的
    expect(planBackoff(new ApiError(503, 'X', '服务不可用', [], 120), NORMAL_INTERVAL_MS).intervalMs).toBe(120_000)
  })

  it('503 没带 Retry-After / 网络失败 ⇒ 保持原节奏（不瞎猜一个数）', () => {
    expect(planBackoff(new ApiError(503, 'X', '服务不可用'), NORMAL_INTERVAL_MS)).toEqual({
      intervalMs: NORMAL_INTERVAL_MS,
      degraded: false,
    })
    expect(planBackoff(new ApiError(0, 'NETWORK_ERROR', '网络异常'), NORMAL_INTERVAL_MS).degraded).toBe(false)
  })

  it('429 也按 Retry-After 退避（限流不是"没有记录"）', () => {
    expect(planBackoff(new ApiError(429, 'RATE_LIMITED', '请求过于频繁', [], 2), NORMAL_INTERVAL_MS)).toEqual({
      intervalMs: MIN_DEGRADED_INTERVAL_MS,
      degraded: true,
    })
  })

  it('★ 恢复条件：收到 200 必须回到正常节奏（只降不升 = 进度长期落后）', () => {
    expect(planRecovery()).toEqual({ intervalMs: NORMAL_INTERVAL_MS, degraded: false })
  })

  it('400 / 401 / 403 / 404 停止上报；503 / 429 / 网络失败继续', () => {
    for (const status of [400, 401, 403, 404]) expect(shouldStopReporting(new ApiError(status, 'X', 'm'))).toBe(true)
    for (const status of [0, 429, 500, 503]) expect(shouldStopReporting(new ApiError(status, 'X', 'm'))).toBe(false)
    expect(shouldStopReporting(new Error('boom'))).toBe(false)
  })
})

describe('createProgressReporter（节奏与生命周期）', () => {
  function setup(report: (seconds: number) => Promise<void>) {
    const calls: number[] = []
    const flushes = { count: 0 }
    const notices: string[] = []
    let position = 10
    const reporter = createProgressReporter({
      getPosition: () => position,
      report: async (seconds) => {
        calls.push(seconds)
        await report(seconds)
      },
      flush: async () => {
        flushes.count += 1
      },
      onNotice: (message) => notices.push(message),
    })
    return {
      reporter,
      calls,
      flushes,
      notices,
      setPosition: (value: number) => {
        position = value
      },
    }
  }

  it('45s 一条（不是在 timeupdate 里逐帧报）', async () => {
    vi.useFakeTimers()
    const ctx = setup(async () => {})
    ctx.reporter.start()
    await vi.advanceTimersByTimeAsync(NORMAL_INTERVAL_MS - 1)
    expect(ctx.calls).toHaveLength(0)
    await vi.advanceTimersByTimeAsync(1)
    expect(ctx.calls).toEqual([10])
    await vi.advanceTimersByTimeAsync(NORMAL_INTERVAL_MS)
    expect(ctx.calls).toHaveLength(2)
  })

  it('暂停 = 立刻上报 + flush（不等下一个 45s）', async () => {
    vi.useFakeTimers()
    const ctx = setup(async () => {})
    ctx.reporter.start()
    ctx.setPosition(88)
    await ctx.reporter.onPause()
    expect(ctx.calls).toEqual([88])
    expect(ctx.flushes.count).toBe(1)
  })

  it('503 后退避到 30s，恢复 200 后回到 45s 并清掉降级', async () => {
    vi.useFakeTimers()
    let fail = true
    const ctx = setup(async () => {
      if (fail) throw new ApiError(503, 'X', '服务不可用', [], 30)
    })
    ctx.reporter.start()
    await vi.advanceTimersByTimeAsync(NORMAL_INTERVAL_MS)
    expect(ctx.reporter.state()).toMatchObject({ intervalMs: MIN_DEGRADED_INTERVAL_MS, degraded: true })
    expect(ctx.notices.some((text) => text.includes('降频'))).toBe(true)

    // 降到 30s 后：30s 内不再上报，30s 时才来一次
    await vi.advanceTimersByTimeAsync(MIN_DEGRADED_INTERVAL_MS - 1)
    expect(ctx.calls).toHaveLength(1)
    fail = false
    await vi.advanceTimersByTimeAsync(1)
    expect(ctx.calls).toHaveLength(2)
    expect(ctx.reporter.state()).toMatchObject({ intervalMs: NORMAL_INTERVAL_MS, degraded: false })
    expect(ctx.notices.some((text) => text.includes('恢复'))).toBe(true)
  })

  it('404 ⇒ 停止上报（重试必然同样失败）', async () => {
    vi.useFakeTimers()
    const ctx = setup(async () => {
      throw new ApiError(404, 'CONTENT_NOT_FOUND', '内容不存在')
    })
    ctx.reporter.start()
    await vi.advanceTimersByTimeAsync(NORMAL_INTERVAL_MS)
    expect(ctx.reporter.state().stopped).toBe(true)
    await vi.advanceTimersByTimeAsync(NORMAL_INTERVAL_MS * 3)
    expect(ctx.calls).toHaveLength(1) // 只在第一次尝试过
  })

  it('stop 之后不再上报（卸载 / 切内容）', async () => {
    vi.useFakeTimers()
    const ctx = setup(async () => {})
    ctx.reporter.start()
    ctx.reporter.stop()
    await vi.advanceTimersByTimeAsync(NORMAL_INTERVAL_MS * 2)
    expect(ctx.calls).toHaveLength(0)
  })
})
