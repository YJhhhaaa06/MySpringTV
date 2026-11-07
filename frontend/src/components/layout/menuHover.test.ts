import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createHoverMenu } from './menuHover'

/**
 * 顶栏账号卡片的关闭宽限。
 *
 * <p>这些用例的判别力都压在**时间**上：把 `closeDelayMs` 改成 0、去掉 `cancelClose` 里的
 * `clearTimeout`、或让 `dispose` 不清理定时器，都会各红一条。别把它们改成"调一次就断言状态"，
 * 那样计时器整个失效也照样绿。
 */
describe('createHoverMenu', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('进入立即打开（打开方向不延时）', () => {
    const changes: boolean[] = []
    const menu = createHoverMenu({ onChange: (open) => changes.push(open) })

    menu.open()

    expect(menu.isOpen).toBe(true)
    expect(changes).toEqual([true])
  })

  it('★ 离开后 100ms 仍在、200ms 后关闭（宽限窗口 = 能点到退出登录的前提）', () => {
    const menu = createHoverMenu({ closeDelayMs: 180 })
    menu.open()
    menu.scheduleClose()

    vi.advanceTimersByTime(100)
    expect(menu.isOpen).toBe(true)

    vi.advanceTimersByTime(100)
    expect(menu.isOpen).toBe(false)
  })

  it('延时内回到卡片 ⇒ 取消关闭，不再触发 onChange(false)', () => {
    const onChange = vi.fn()
    const menu = createHoverMenu({ closeDelayMs: 180, onChange })
    menu.open()
    menu.scheduleClose()

    vi.advanceTimersByTime(100)
    menu.cancelClose()
    vi.advanceTimersByTime(1000)

    expect(menu.isOpen).toBe(true)
    expect(onChange).toHaveBeenCalledTimes(1)
    expect(onChange).not.toHaveBeenCalledWith(false)
  })

  it('★ 延时内重新进入 ⇒ open() 自己取消待关闭（跨 8px 缝走的正是这条路）', () => {
    const menu = createHoverMenu({ closeDelayMs: 180 })
    menu.open()
    menu.scheduleClose()
    vi.advanceTimersByTime(100)

    // 指针回到卡片：组件的 mouseenter 调的是 open()，不是 cancelClose()
    menu.open()
    vi.advanceTimersByTime(1000)

    expect(menu.isOpen).toBe(true)
  })

  it('重复 open 不重复通知（状态没变就不 emit）', () => {
    const onChange = vi.fn()
    const menu = createHoverMenu({ onChange })

    menu.open()
    menu.open()
    menu.open()

    expect(onChange).toHaveBeenCalledTimes(1)
    expect(onChange).toHaveBeenCalledWith(true)
  })

  it('重复 scheduleClose 重置计时（指针在缝里来回不会提前关）', () => {
    const menu = createHoverMenu({ closeDelayMs: 180 })
    menu.open()
    menu.scheduleClose()
    vi.advanceTimersByTime(120)

    menu.scheduleClose()
    vi.advanceTimersByTime(120) // 距第一次 240ms，距第二次 120ms
    expect(menu.isOpen).toBe(true)

    vi.advanceTimersByTime(60)
    expect(menu.isOpen).toBe(false)
  })

  it('★ dispose 后定时器不再回调（卸载后不留悬挂副作用）', () => {
    const onChange = vi.fn()
    const menu = createHoverMenu({ closeDelayMs: 180, onChange })
    menu.open()
    menu.scheduleClose()
    menu.dispose()

    vi.advanceTimersByTime(1000)

    expect(onChange).toHaveBeenCalledTimes(1)
    expect(onChange).not.toHaveBeenCalledWith(false)
  })
})
