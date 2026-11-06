/**
 * 顶栏账号卡片的「打开 / 延时关闭」计时器（纯逻辑，不依赖 Vue / DOM）。
 *
 * <h2>★ 为什么要延时，不能只靠 `:hover`</h2>
 * 这张卡片是前端**唯一**的主动「退出登录」入口。纯 CSS `:hover` 在头像与卡片之间
 * 有一条 4px 的缝（`.user-chip` 高 40、卡片 `top: 44`），鼠标穿过时 hover 会在
 * 下一帧断开；而 `display: none` 没有任何关闭宽限 ⇒ 卡片立刻消失、且不会自己回来。
 * 实测结论是"正常手速点不到退出登录"——**根因是缝，不是手速**。
 *
 * <p>所以：`hover` 只负责**打开**，**关闭必须带延时**，让指针有时间跨过缝 / 斜穿。
 * 打开不延时（否则每次悬停都先等 180ms，手感发滞）。
 *
 * <p>与 `feed/listState.ts` 同一口径：**不依赖框架**，`onChange` 把状态推给组件。
 * 这样 `menuHover.test.ts` 用 fake timers 就能断言"100ms 还在 / 200ms 没了"，
 * 不需要 jsdom（本仓没有 DOM 测试环境，也不为一张卡片引入）。
 */
export interface HoverMenuOptions {
  /**
   * 离开后多久真正关闭（ms）。
   *
   * <p>默认 **180**：够跨过 4~8px 的缝与一次斜向移动，又不至于"人走了卡片还赖着"。
   * 调小到 0 会让「宽限窗口」用例变红 —— 这正是该用例的判别力所在。
   */
  closeDelayMs?: number
  /** 状态变化回调（组件用它同步到 `ref`；只在真正变化时触发） */
  onChange?: (open: boolean) => void
}

export interface HoverMenu {
  readonly isOpen: boolean
  /** 立即打开并取消待关闭（进入头像 / 卡片时调） */
  open(): void
  /** 安排一次延时关闭；重复调用**重置**计时（不断刷新离开时刻，不会提前关） */
  scheduleClose(): void
  /** 取消待关闭（在延时内回到卡片） */
  cancelClose(): void
  /** 立即关闭（Esc / 点外部 / 路由变化） */
  close(): void
  /** 清定时器（`onBeforeUnmount`）—— 不清会在卸载后继续触发 `onChange`，留下悬挂的副作用 */
  dispose(): void
}

export function createHoverMenu(options: HoverMenuOptions = {}): HoverMenu {
  const closeDelayMs = options.closeDelayMs ?? 180
  let timer: ReturnType<typeof setTimeout> | null = null
  let open = false

  function emit(next: boolean): void {
    if (open === next) return
    open = next
    options.onChange?.(next)
  }

  function clearTimer(): void {
    if (timer !== null) {
      clearTimeout(timer)
      timer = null
    }
  }

  return {
    get isOpen(): boolean {
      return open
    },

    open(): void {
      clearTimer()
      emit(true)
    },

    scheduleClose(): void {
      clearTimer()
      timer = setTimeout(() => {
        timer = null
        emit(false)
      }, closeDelayMs)
    },

    cancelClose(): void {
      clearTimer()
    },

    close(): void {
      clearTimer()
      emit(false)
    },

    dispose(): void {
      clearTimer()
    },
  }
}
