import { ApiError } from '../api/http'

/**
 * 播放进度上报的**纯逻辑**（节奏 / 退避 / 续播基线 / 本地崩溃补偿）。
 *
 * <p>唯一事实源是 `architecture/domain/history/history-客户端契约.md`；
 * 这里把其中**能单测的部分**抽出来（§三 节奏、§五 503 退避、§六 崩溃补偿），
 * DOM 接线在 `composables/usePlaybackProgress.ts`。
 *
 * <h2>为什么这些规则必须可单测</h2>
 * 它们全是"看不出来"的：上报间隔多 30s 没人在界面上发现，503 时忘了恢复条件会让
 * 进度长期落后，崩溃补偿的 TTL 写错会让用户重开时跳回旧位置。**只有断言能拦住它们。**
 */

/** 正常节奏（契约 §三：30~60s 区间，取中值） */
export const NORMAL_INTERVAL_MS = 45_000

/** 降级下限（服务端说 5s 就回来也不能比这个更密——那等于没退避） */
export const MIN_DEGRADED_INTERVAL_MS = 30_000

/** 本地"当前进度"标记的有效期：超过它说明会话早已结束（契约 §6.1，= 缓冲 TTL 30m） */
export const PENDING_TTL_MS = 30 * 60 * 1000

/** 续播定位的最小秒数：小于它就别跳（3s 以内跳一下反而像卡住） */
export const MIN_RESUME_SECONDS = 3

const PENDING_PREFIX = 'history:pending:'

export interface PendingProgress {
  /** 秒（整数） */
  progress: number
  /** 最后一次写入时刻（毫秒） */
  ts: number
}

function storage(): Storage | null {
  try {
    return globalThis.localStorage ?? null
  } catch {
    return null
  }
}

/** 读本地的"当前进度"；过期 / 损坏 ⇒ null（**不抛错**：崩过一次的脏数据不该把播放器打挂） */
export function readPending(contentId: number, now: number = Date.now()): PendingProgress | null {
  const raw = storage()?.getItem(PENDING_PREFIX + contentId)
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as PendingProgress
    if (!Number.isFinite(parsed?.progress) || !Number.isFinite(parsed?.ts)) return null
    if (now - parsed.ts > PENDING_TTL_MS) return null
    return { progress: Math.max(0, Math.floor(parsed.progress)), ts: parsed.ts }
  } catch {
    return null
  }
}

/** 写本地"当前进度"（每秒一次，由调用方节流；**不是**"上次上报的值"） */
export function writePending(contentId: number, progress: number, now: number = Date.now()): void {
  try {
    storage()?.setItem(
      PENDING_PREFIX + contentId,
      JSON.stringify({ progress: Math.max(0, Math.floor(progress)), ts: now }),
    )
  } catch {
    // 隐私模式 / 配额满：退化为"没有崩溃补偿"，不影响正常上报
  }
}

export function clearPending(contentId: number): void {
  try {
    storage()?.removeItem(PENDING_PREFIX + contentId)
  } catch {
    // 同上
  }
}

export interface ResumePlan {
  /** 播放头应该定位到的秒数（0 = 从头） */
  start: number
  /** 本地比服务端新 ⇒ 需要先补报 + flush（否则那一段只活在本地） */
  needsCatchUp: boolean
}

/**
 * 续播基线 = `max(已落库, 本地最新)`（契约 §6.2）。
 *
 * <p>★ 为什么不能只认服务端：`GET /progress` 只读 DB（R-20），崩溃 / 没 flush 的会话
 * 新进度还在本地 ⇒ 直接拿它会**退到旧位置**。
 * <p>★ 为什么要与服务端比较：多设备下本地可能是旧值（另一台看到更后面），
 * 不比较就补报会把别处的新进度**覆盖成旧的**（服务端是 LWW，不做单调守卫）。
 */
export function planResume(
  serverProgress: number | null | undefined,
  pending: PendingProgress | null,
  now: number = Date.now(),
): ResumePlan {
  const server =
    typeof serverProgress === 'number' && Number.isFinite(serverProgress) ? Math.max(0, Math.floor(serverProgress)) : 0
  if (!pending || now - pending.ts > PENDING_TTL_MS) return { start: server, needsCatchUp: false }
  const local = Math.max(0, Math.floor(pending.progress))
  return { start: Math.max(server, local), needsCatchUp: local > server }
}

/** 该不该停止上报（400/401/403/404 重试必然同样失败，契约 §八） */
export function shouldStopReporting(error: unknown): boolean {
  if (!(error instanceof ApiError)) return false
  return error.status === 400 || error.status === 401 || error.status === 403 || error.status === 404
}

export interface BackoffPlan {
  intervalMs: number
  degraded: boolean
}

/**
 * 失败后的下一次间隔（契约 §五）。
 *
 * <p>可重试的两类：`503`（Redis 不可用，服务端给了 `Retry-After`）与 `429`（限流）。
 * ★ 不自己叠指数退避 —— `Retry-After` 就是服务端"多久后再来"的节奏；
 * ★ 但也不信比 `MIN_DEGRADED_INTERVAL_MS` 更密的值（服务端说 5s 不等于我们该 5s 一次）。
 * <p>其余（网络失败 / 5xx 无 Retry-After）保持原节奏重试 —— 进度还在本地，恢复后会覆盖。
 */
export function planBackoff(error: unknown, currentIntervalMs: number): BackoffPlan {
  const retryable =
    error instanceof ApiError && (error.status === 503 || error.status === 429) ? error.retryAfterSeconds : null
  if (retryable === null) return { intervalMs: currentIntervalMs, degraded: false }
  return {
    intervalMs: Math.max(retryable * 1000, MIN_DEGRADED_INTERVAL_MS),
    degraded: true,
  }
}

/** 收到 200 ⇒ 回到正常节奏（★ 必须有恢复条件，否则降频会永久留着） */
export function planRecovery(): BackoffPlan {
  return { intervalMs: NORMAL_INTERVAL_MS, degraded: false }
}

export interface ReporterOptions {
  /** 当前播放秒数（读 DOM 的 currentTime） */
  getPosition: () => number
  /** 上报一次（注入以便单测；生产实现 = `PUT /contents/{id}/progress`） */
  report: (seconds: number) => Promise<void>
  /** 落库（`POST /history/flush`） */
  flush: () => Promise<void>
  /** 降级 / 恢复时给用户一句提示（可选） */
  onNotice?: (message: string) => void
}

export interface ProgressReporter {
  /** 开始播放：启动定时上报（暂停时由 {@link ProgressReporter.onPause} 停掉） */
  start: () => void
  /** 暂停：立刻上报 + flush（"用户主动停在这里，值得立刻落库"） */
  onPause: () => Promise<void>
  /** 拖动进度条后：立刻上报 */
  onSeek: () => Promise<void>
  /** 播完：最后一次上报 + flush */
  onEnded: () => Promise<void>
  /** 卸载 / 切内容：停掉定时器（幂等） */
  stop: () => void
  /** 只读状态（测试与调试用） */
  readonly state: () => { intervalMs: number; degraded: boolean; stopped: boolean }
}

/**
 * 上报循环（**一次观看会话一个实例**）。
 *
 * <p>节奏：`45s` 定时 + 暂停 / 拖动 / 播完的即时上报。**不在 `timeupdate` 里上报**
 * ——那是每秒几十次（契约 §三）。`timeupdate` 只用来写本地 pending 与更新 UI。
 */
export function createProgressReporter(options: ReporterOptions): ProgressReporter {
  let intervalMs = NORMAL_INTERVAL_MS
  let degraded = false
  let stopped = false
  let timer: ReturnType<typeof setInterval> | null = null
  let inFlight = false

  function clearTimer(): void {
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
  }

  function schedule(): void {
    clearTimer()
    if (stopped) return
    timer = setInterval(() => {
      void tick()
    }, intervalMs)
  }

  async function tick(): Promise<void> {
    if (stopped || inFlight) return
    inFlight = true
    try {
      await options.report(options.getPosition())
      if (degraded) {
        const recovery = planRecovery()
        intervalMs = recovery.intervalMs
        degraded = recovery.degraded
        schedule()
        options.onNotice?.('进度上报已恢复正常')
      }
    } catch (error) {
      if (shouldStopReporting(error)) {
        // 400 / 401 / 403 / 404：重试必然同样失败 ⇒ 停止，别把 429 也刷出来
        stop()
        return
      }
      const plan = planBackoff(error, intervalMs)
      if (plan.degraded && !degraded) {
        options.onNotice?.(`进度上报降频：${Math.round(plan.intervalMs / 1000)} 秒后再试`)
      }
      intervalMs = plan.intervalMs
      degraded = plan.degraded
      schedule()
    } finally {
      inFlight = false
    }
  }

  function stop(): void {
    stopped = true
    clearTimer()
  }

  async function flushQuietly(): Promise<void> {
    try {
      await options.flush()
    } catch {
      // flush 失败不清本地 pending（§6.1：清了就等于承认已落库）
    }
  }

  return {
    start: () => {
      if (stopped) return
      schedule()
    },
    onPause: async () => {
      clearTimer()
      if (stopped) return
      await tick()
      await flushQuietly()
    },
    onSeek: async () => {
      if (stopped) return
      await tick()
    },
    onEnded: async () => {
      clearTimer()
      if (stopped) return
      await tick()
      await flushQuietly()
      stop()
    },
    stop,
    state: () => ({ intervalMs, degraded, stopped }),
  }
}
