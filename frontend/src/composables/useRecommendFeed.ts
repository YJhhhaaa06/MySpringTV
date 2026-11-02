import { computed, onBeforeUnmount, ref } from 'vue'
import { loadHome, loadHomeMore } from '../api/endpoints'
import { ApiError, describeError } from '../api/http'
import { showToast } from '../common/toast'
import {
  beginMore,
  beginRefresh,
  emptyFeed,
  failMore,
  failRefresh,
  feedView,
  receiveFirstPage,
  receiveMore,
  retryMore,
  shouldLoadMore,
  type FeedState,
} from '../feed/recommendFeed'
import type { HeroSlideVM, VideoCardVM } from '../types/ui'

/**
 * 首页推荐流的行为机：**请求 + 状态迁移 + 失败提示**。
 *
 * <p>迁移规则全在 `feed/recommendFeed.ts`（纯函数，可单测）；这里只做三件"有副作用"的事：
 * 发请求、把失败翻成人话、429 冷却倒计时。滚动 / 哨兵 / DOM 留在 `HomePage`。
 *
 * <p>★ 刷新失败分两路（`R-29` ⑥）：有旧内容 ⇒ **保留列表 + toast**（替换整页更糟）；
 * 没有旧内容 ⇒ 页面级错误态（由 `feedView` 的 `error` 给出）。
 */
export function useRecommendFeed() {
  const state = ref<FeedState<VideoCardVM>>(emptyFeed<VideoCardVM>())
  const slides = ref<HeroSlideVM[]>([])
  /** 429 的"N 秒后再试"；>0 时行内重试按钮禁用 */
  const retryAfter = ref(0)

  let cooldownTimer: ReturnType<typeof setInterval> | null = null

  const view = computed(() => feedView(state.value))
  const errorText = computed(() => state.value.error ?? '')
  const canRetry = computed(() => retryAfter.value <= 0)

  function stopCooldown(): void {
    if (cooldownTimer !== null) {
      clearInterval(cooldownTimer)
      cooldownTimer = null
    }
  }

  function startCooldown(exception: unknown): void {
    if (!(exception instanceof ApiError) || exception.retryAfterSeconds === null) return
    stopCooldown()
    retryAfter.value = Math.max(1, Math.ceil(exception.retryAfterSeconds))
    cooldownTimer = setInterval(() => {
      retryAfter.value -= 1
      if (retryAfter.value <= 0) stopCooldown()
    }, 1000)
  }

  /** 首屏 / 换一批：整批替换；刷新期间保留旧内容（不闪屏）。 */
  async function refresh(): Promise<void> {
    const hadItems = state.value.items.length > 0
    const next = beginRefresh(state.value)
    if (next === state.value) return
    state.value = next
    stopCooldown()
    retryAfter.value = 0
    const epoch = next.epoch

    try {
      const data = await loadHome()
      if (epoch !== state.value.epoch) return
      slides.value = data.slides
      state.value = receiveFirstPage(
        state.value,
        { items: data.cards, nextCursor: data.nextCursor },
        epoch,
      )
    } catch (exception) {
      if (epoch !== state.value.epoch) return
      const message = describeError(exception, '刷新失败，请稍后重试')
      state.value = failRefresh(state.value, message)
      // 有旧内容：列表没换，用 toast 说明；没有旧内容：交给页面级错误态
      if (hadItems) showToast(message, 'error')
    }
  }

  /** 续页的请求部分：调用前必须已经把 phase 置成 `loadingMore`。 */
  async function runMore(): Promise<void> {
    const epoch = state.value.epoch
    const cursor = state.value.cursor
    if (!cursor) return
    try {
      const page = await loadHomeMore(cursor)
      if (epoch !== state.value.epoch) return
      state.value = receiveMore(
        state.value,
        { items: page.cards, nextCursor: page.nextCursor },
        epoch,
      )
    } catch (exception) {
      if (epoch !== state.value.epoch) return
      const message = describeError(exception, '加载失败，请稍后重试')
      state.value = failMore(state.value, message, epoch)
      startCooldown(exception)
    }
  }

  /** 滚动触底 / 哨兵触发：单飞由 `beginMore` 兜住。 */
  async function loadMore(): Promise<void> {
    const next = beginMore(state.value)
    if (next === state.value) return
    state.value = next
    await runMore()
  }

  /** 行内「重试」：复用同一游标；429 冷却期内不起作用。 */
  async function retry(): Promise<void> {
    if (!canRetry.value) return
    const next = retryMore(state.value)
    if (next === state.value) return
    state.value = next
    await runMore()
  }

  onBeforeUnmount(stopCooldown)

  return {
    state,
    slides,
    view,
    errorText,
    retryAfter,
    canRetry,
    refresh,
    loadMore,
    retry,
    shouldLoadMore: () => shouldLoadMore(state.value),
  }
}
