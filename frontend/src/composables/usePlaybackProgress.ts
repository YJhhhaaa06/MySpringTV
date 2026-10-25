import { onBeforeUnmount, ref, watch, type Ref } from 'vue'
import type { VideoDetailVM } from '../types/ui'
import { fetchProgress, flushHistory, flushHistoryOnExit, reportPlay, reportProgress } from '../api/history'
import { formatDuration } from '../api/adapters/format'
import { isAuthenticated } from '../auth/session'
import { showToast } from '../common/toast'
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
  reporter: ProgressReporter
  playCounted: boolean
  lastPendingWrite: number
  listeners: Array<[keyof HTMLMediaElementEventMap, EventListener]>
  onVisibility: () => void
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
    current.reporter.stop()
    if (finalize) {
      // 切内容 / 卸载：把最后一段进度报出去再落库（失败也无处可报，页面正在走）
      const seconds = Math.max(0, Math.floor(current.el.currentTime))
      if (seconds > 0 && !current.el.paused) {
        void reportProgress(current.contentId, seconds).catch(() => {})
        flushHistoryOnExit()
      }
    }
  }

  async function start(entity: VideoDetailVM): Promise<void> {
    const el = video.value
    if (!el || entity.isPost || !entity.videoUrl) return
    teardown(true)
    if (!isAuthenticated()) return

    const token = generation
    const contentId = entity.id

    // ── 续播基线：已落库 vs 本地最新（§6.2）────────────────────────────
    let serverProgress: number | null = null
    try {
      serverProgress = (await fetchProgress(contentId))?.progress ?? 0
    } catch {
      // 读不到就按"没有记录"处理：宁可从头播，也不要因为一次读失败卡住播放器
      serverProgress = null
    }
    if (token !== generation) return

    const pending = readPending(contentId)
    const plan = planResume(serverProgress, pending)
    resumeSeconds.value = plan.start

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
      [
        'loadedmetadata',
        () => {
          // 服务端可能给了超过时长的旧值：别 seek 到片尾之外
          const duration = Number.isFinite(el.duration) ? el.duration : Infinity
          const target = plan.start
          if (target >= MIN_RESUME_SECONDS && target < duration - 1) {
            el.currentTime = target
            const text = formatDuration(target)
            if (text) showToast(`已从上次看的位置 ${text} 继续`)
          } else {
            resumeSeconds.value = 0
          }
        },
      ],
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
    }
    for (const [type, listener] of listeners) el.addEventListener(type, listener)
    document.addEventListener('visibilitychange', onVisibility)

    // 本地比服务端新 ⇒ 先补报落库（补的是本地最新，不是上次上报值）
    if (plan.needsCatchUp) {
      try {
        await reportProgress(contentId, plan.start)
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
