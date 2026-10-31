import { onBeforeUnmount, ref, watch, type Ref } from 'vue'
import type { VideoDetailVM } from '../types/ui'
import { fetchProgress, flushHistory, flushHistoryOnExit, reportPlay, reportProgress } from '../api/history'
import { formatDuration } from '../api/adapters/format'
import { isAuthenticated, restoreSession, sessionState } from '../auth/session'
import { showToast } from '../common/toast'
import { planTracking } from '../player/historyTracking'
import {
  MIN_RESUME_SECONDS,
  clearPending,
  createProgressReporter,
  planResume,
  readPending,
  writePending,
  type ProgressReporter,
} from '../player/progressReporter'

/**
 * 播放进度（DOM 接线层）：把 `<video>` 的事件接到 {@link createProgressReporter}，
 * 并负责**续播定位**与**崩溃补偿**。契约原文见
 * `architecture/domain/history/history-客户端契约.md`。
 *
 * <h2>为什么未登录时整段跳过</h2>
 * 播放历史的五个端点全部 `@RequiresLogin` ⇒ 匿名上报必然 401。与其打一串 401，
 * 不如一开始就不接（也省下每次装载 2 枚限流令牌）。代价：**匿名观看不计播放量**
 * —— 这是本仓契约的真实边界，不是前端漏做。
 *
 * <h2>★ 关掉「记录浏览历史」时**只关写**（`R-25` / D2）</h2>
 * 判据在 {@link planTracking}：`read-only` ⇒ 仍然读一条 `GET /progress`（读不是记录，
 * "从上次看到的地方继续"不该因开关而失去），但 **`/play`、`/progress`、`/flush`、
 * `visibilitychange`、每秒 pending 一个都不写**，并清掉上一段遗留的本地 pending
 * （否则下次打开会因 `needsCatchUp` 补报，等于开关没关住）。
 * 连 `/play` 也不发是 D1 的直接后果：它的去重标记就长在历史行上，发它必然留一行 0 秒记录。
 * ⚠️ **服务端不校验这个开关**（`R-25` 的刻意取舍）：直接调接口仍会记录，见
 * `architecture/domain/history/history-已知代价.md`。
 *
 * <h2>★ 事件分工（契约 §三 的红线）</h2>
 * - `timeupdate`（每秒多次）：**只**写本地 pending + 不碰网络；
 * - 定时器（45s）/ `pause` / `seeked` / `ended`：才上报；
 * - `visibilitychange → hidden`：先 `report` 再 `flush(keepalive)`（§4.2 的顺序）。
 *
 * <p>★ `play` 事件里顺手发一次 `POST /play`（播放量去重计数）——契约要求"开始播放时一次"，
 * 且**不能晚于播放**（晚发会漏计"只看一眼就退出"的那些）。
 */
export interface PlaybackProgress {
  /** 这次会话从第几秒开始（0 = 从头）；界面不必展示，调试与断言用 */
  resumeSeconds: Ref<number>
}

interface Session {
  contentId: number
  el: HTMLVideoElement
  /** `null` = `read-only`（关闭记录）：没有上报器，只有"定位到续播点"那一个监听 */
  reporter: ProgressReporter | null
  playCounted: boolean
  lastPendingWrite: number
  listeners: Array<[keyof HTMLMediaElementEventMap, EventListener]>
  onVisibility: () => void
  readOnly: boolean
}

export function usePlaybackProgress(
  video: Ref<HTMLVideoElement | null>,
  detail: Ref<VideoDetailVM | null>,
): PlaybackProgress {
  const resumeSeconds = ref(0)
  let session: Session | null = null
  let generation = 0

  function teardown(finalize: boolean): void {
    generation += 1
    const current = session
    session = null
    if (!current) return
    for (const [type, listener] of current.listeners) current.el.removeEventListener(type, listener)
    document.removeEventListener('visibilitychange', current.onVisibility)
    current.reporter?.stop()
    if (finalize && !current.readOnly) {
      // 切内容 / 卸载：把最后一段进度报出去再落库（失败也无处可报，页面正在走）
      // ★ `readOnly`（关闭记录）一律不写：这里发的是 `/progress` + `/flush` 两条写请求。
      const seconds = Math.max(0, Math.floor(current.el.currentTime))
      if (seconds > 0 && !current.el.paused) {
        void reportProgress(current.contentId, seconds).catch(() => {})
        flushHistoryOnExit()
      }
    }
  }

  /** 把播放头定位到续播点（`<video>` 上唯一"读出来给用户看"的动作，不写任何东西） */
  function applyResume(el: HTMLVideoElement, target: number): void {
    // 服务端可能给了超过时长的旧值：别 seek 到片尾之外
    const duration = Number.isFinite(el.duration) ? el.duration : Infinity
    if (target >= MIN_RESUME_SECONDS && target < duration - 1) {
      el.currentTime = target
      const text = formatDuration(target)
      if (text) showToast(`已从上次看的位置 ${text} 继续`)
    } else {
      resumeSeconds.value = 0
    }
  }

  async function start(entity: VideoDetailVM): Promise<void> {
    const el = video.value
    if (!el || entity.isPost || !entity.videoUrl) return
    teardown(true)

    const token = generation
    const contentId = entity.id

    // ★ 先把「我是谁 + 开关开没开」落定再决定接不接：
    //   `video.main.ts` 是 **mount 之后** 才 `void restoreSession()` ⇒ 详情可能比 `/users/me`
    //   先回来，此刻 `isAuthenticated()` 还是 false，而本函数只跑一次、**不重试**
    //   ⇒ 整段不上报（既有的潜在漏报，顺手在这里修掉）。
    //   `restoreSession()` 是单飞（见 `auth/session.ts`）⇒ 不会多打请求。
    await restoreSession()
    if (token !== generation) return

    const plan = planTracking(isAuthenticated(), sessionState.user?.historyRecording)
    if (plan === 'skip') return

    // ── 续播基线：已落库 vs 本地最新（§6.2）────────────────────────────
    // ★ 这段在 `read-only` 下**仍然执行**：读 `/progress` 不是"记录"，
    //   关掉开关不该连"从上次看到的地方继续"都失去（D2：只关写）。
    let serverProgress: number | null = null
    try {
      serverProgress = (await fetchProgress(contentId))?.progress ?? 0
    } catch {
      // 读不到就按"没有记录"处理：宁可从头播，也不要因为一次读失败卡住播放器
      serverProgress = null
    }
    if (token !== generation) return

    const pending = readPending(contentId)
    const resumePlan = planResume(serverProgress, pending)
    resumeSeconds.value = resumePlan.start

    // ★ 定位是**读**，两条分支共用（`read-only` 也要能从上次的位置继续）。
    //   元数据可能在本函数的 `await` 期间就已加载（详情到达即设 src）⇒ 那时再挂
    //   `loadedmetadata` 永远不触发、续播静默失效。有元数据就立刻定位，只定位一次。
    let sought = false
    const seek = (): void => {
      if (sought) return
      sought = true
      applyResume(el, resumePlan.start)
    }
    if (el.readyState >= 1) seek()

    if (plan === 'read-only') {
      // 关掉记录 ⇒ 到此为止：**不建 reporter、不挂任何写监听**（`/play`、`/progress`、
      // `/flush`、`visibilitychange`、每秒 pending 全都没有），只留"定位到续播点"这一个。
      // 并把**上一段遗留的本地 pending 清掉** —— 否则下次打开会因 `needsCatchUp` 补报一次，
      // 等于开关没关住。
      clearPending(contentId)
      session = {
        contentId,
        el,
        reporter: null,
        playCounted: false,
        lastPendingWrite: 0,
        listeners: [['loadedmetadata', seek]],
        onVisibility: () => {},
        readOnly: true,
      }
      el.addEventListener('loadedmetadata', seek)
      return
    }

    const reporter = createProgressReporter({
      getPosition: () => el.currentTime,
      report: (seconds) => reportProgress(contentId, seconds),
      flush: async () => {
        await flushHistory()
      },
      onNotice: (message) => showToast(message, 'info'),
    })

    const onVisibility = (): void => {
      if (document.visibilityState !== 'hidden') return
      // §4.2：页面还活着时先 report（await 能完成），再用 keepalive 兜一次落库
      void reportProgress(contentId, el.currentTime)
        .catch(() => {})
        .finally(() => flushHistoryOnExit())
    }

    const listeners: Session['listeners'] = [
      ['loadedmetadata', seek],
      [
        'play',
        () => {
          if (!session || session.contentId !== contentId) return
          if (!session.playCounted) {
            session.playCounted = true
            // 播放量去重计数：失败不影响观看（后端也会回 401/404 之类）
            void reportPlay(contentId).catch(() => {})
          }
          reporter.start()
        },
      ],
      [
        'pause',
        () => {
          // 播完也会触发 pause：此时交给 ended 处理，别重复 flush
          if (el.ended) return
          void reporter.onPause()
        },
      ],
      [
        'seeked',
        () => {
          void reporter.onSeek()
        },
      ],
      [
        'timeupdate',
        () => {
          // ★ 每秒最多写一次 localStorage（localStorage 是同步 API，别放进每帧路径）
          const now = Date.now()
          if (!session || session.contentId !== contentId) return
          if (now - session.lastPendingWrite < 1000) return
          session.lastPendingWrite = now
          writePending(contentId, el.currentTime, now)
        },
      ],
      [
        'ended',
        () => {
          void reporter.onEnded()
          clearPending(contentId)
        },
      ],
    ]

    session = {
      contentId,
      el,
      reporter,
      playCounted: false,
      lastPendingWrite: 0,
      listeners,
      onVisibility,
      readOnly: false,
    }
    for (const [type, listener] of listeners) el.addEventListener(type, listener)
    document.addEventListener('visibilitychange', onVisibility)

    // 本地比服务端新 ⇒ 先补报落库（补的是本地最新，不是上次上报值）
    if (resumePlan.needsCatchUp) {
      try {
        await reportProgress(contentId, resumePlan.start)
        await flushHistory()
        clearPending(contentId)
      } catch {
        // 补报失败：保留 pending，下一次装载会再补（契约 §6.2）
      }
    }
  }

  watch(
    [video, () => detail.value],
    ([el, entity]) => {
      if (el && entity) void start(entity)
    },
    { immediate: true, flush: 'post' },
  )

  onBeforeUnmount(() => teardown(true))

  return { resumeSeconds }
}
