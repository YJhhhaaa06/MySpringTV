import { ref } from 'vue'
import { loadFeed } from '../api/feed'
import { ApiError } from '../api/http'

/**
 * 「有新动态」的**轮询器**（`R-32`）—— 服务端不做推送，这是平替。
 *
 * <h2>为什么是轮询而不是新端点</h2>
 * 一个布尔（"头部有没有比当前第一条更新的"）不值一条新端点：`/feed` 已带鉴权 / 窗口 / 缓存语义，
 * 多一条 `latest` 只会多一份"最新"口径。代价是轮询请求会装载 3 条内容的 VO，无真实流量下不可观测。
 *
 * <h2>节流与失败面</h2>
 * 1 分钟一次；只在标签页**可见**时跑；回到前台立刻补一次；单飞（`inFlight`）；
 * 401 停（会话失效，交给页面身份态）；429 按 `Retry-After` 退避；其余失败**静默**（轮询不该弹 toast）。
 *
 * <h2>生命周期</h2>
 * 本 composable **不碰 Vue 生命周期**（便于纯逻辑复用 / 单测）：页面在 `onBeforeUnmount` 调 {@link stop}，
 * 并把 `visibilitychange` 接到 {@link onVisible}。`watchHead` 由页面在"首屏 / 刷新成功"后调用。
 */
export function useNewDynamics() {
  /** 1 分钟（2026-11-06 拍板）：比 B 站的推送慢，但语义是"会话级提示"，不承诺实时 */
  const POLL_INTERVAL_MS = 60_000
  /** 取 3 条：头部内容被删 / 缓存空标记时，仍能拿到第一条可装载的 id 做比较 */
  const POLL_PAGE_SIZE = 3

  const hasNew = ref(false)

  let timer: ReturnType<typeof setTimeout> | null = null
  /** 比较基准：当前列表第一条的 id；null = 列表空（不轮询） */
  let topId: number | null = null
  let stopped = true
  let inFlight = false
  let cooldownUntil = 0

  function clearTimer(): void {
    if (timer !== null) {
      clearTimeout(timer)
      timer = null
    }
  }

  function schedule(): void {
    clearTimer()
    // 已经立起横幅就不用再问：再问也还是"有新"，只会打请求
    if (stopped || hasNew.value) return
    timer = setTimeout(() => {
      void poll()
    }, POLL_INTERVAL_MS)
  }

  async function poll(): Promise<void> {
    if (stopped || inFlight || topId === null) return
    if (Date.now() < cooldownUntil) {
      schedule()
      return
    }
    if (typeof document !== 'undefined' && document.visibilityState === 'hidden') {
      // 标签页在后台：不跑，也不续排（回前台由 onVisible 补一次）
      return
    }
    inFlight = true
    try {
      // ★ 不带 beforeContentId：带锚点就永远看不到锚点以上的新内容
      const page = await loadFeed(1, POLL_PAGE_SIZE)
      const head = page.items[0]?.id ?? null
      if (head !== null && head > topId) hasNew.value = true
    } catch (exception) {
      if (exception instanceof ApiError) {
        if (exception.status === 401) {
          stop()
          return
        }
        if (exception.retryAfterSeconds !== null) {
          cooldownUntil = Date.now() + Math.max(1, Math.ceil(exception.retryAfterSeconds)) * 1000
        }
      }
      // 其余静默：轮询失败只影响"提示晚一点"，不该打断阅读
    } finally {
      inFlight = false
    }
    schedule()
  }

  /**
   * 列表头换了（首屏 / 刷新成功）：更新比较基准、清横幅、重新计时。
   *
   * @param nextTopId 新列表第一条的 id；null（空列表）⇒ 停止轮询
   */
  function watchHead(nextTopId: number | null): void {
    topId = nextTopId
    hasNew.value = false
    cooldownUntil = 0
    clearTimer()
    if (nextTopId === null) {
      stopped = true
      return
    }
    stopped = false
    schedule()
  }

  /**
   * 停轮询：**同时清掉横幅**。
   *
   * <p>⚠️ 不能只置 `stopped`：切作者 / 退出登录时横幅还在的话，会把"全部动态有新内容"
   * 挂到作者时间线或匿名页上，而且点它只会再刷一次当前源、永远消不掉。
   */
  function stop(): void {
    stopped = true
    hasNew.value = false
    clearTimer()
  }

  /** 标签页回到前台：立刻补一次（不再等满 1 分钟） */
  function onVisible(): void {
    if (stopped) return
    if (typeof document !== 'undefined' && document.visibilityState === 'hidden') {
      clearTimer()
      return
    }
    clearTimer()
    void poll()
  }

  return { hasNew, watchHead, stop, onVisible, pollNow: () => void poll() }
}
