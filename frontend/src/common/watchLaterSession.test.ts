import { beforeEach, describe, expect, it } from 'vitest'
import {
  isWatchLaterMarked,
  markWatchLaterAdded,
  markWatchLaterRemoved,
  resetWatchLaterSession,
} from './watchLaterSession'

/**
 * 稍后再看的"页面内记忆"口径（纯逻辑，node 环境不碰 DOM）：
 * **同一 URL 记得、换 URL 不记得** —— 这正是 B 站 2026-10-26 实测行为。
 */
describe('稍后再看：URL 作用域记忆', () => {
  beforeEach(() => resetWatchLaterSession())

  it('同一 URL 记得，换 URL 不记得', () => {
    markWatchLaterAdded(65, '/')

    expect(isWatchLaterMarked(65, '/')).toBe(true)
    expect(isWatchLaterMarked(65, '/search?keyword=java')).toBe(false)
    expect(isWatchLaterMarked(65, '/video/65')).toBe(false)
  })

  it('换 URL 后重新加入，只对新 URL 生效', () => {
    markWatchLaterAdded(65, '/')
    markWatchLaterAdded(65, '/search?keyword=java')

    expect(isWatchLaterMarked(65, '/')).toBe(false)
    expect(isWatchLaterMarked(65, '/search?keyword=java')).toBe(true)
  })

  it('取消后立刻忘记（与看的是哪个 URL 无关）', () => {
    markWatchLaterAdded(65, '/watchlater')
    markWatchLaterRemoved(65)

    expect(isWatchLaterMarked(65, '/watchlater')).toBe(false)
  })

  it('不同内容互不影响', () => {
    markWatchLaterAdded(65, '/')
    markWatchLaterAdded(66, '/')

    expect(isWatchLaterMarked(65, '/')).toBe(true)
    expect(isWatchLaterMarked(66, '/')).toBe(true)

    markWatchLaterRemoved(65)
    expect(isWatchLaterMarked(65, '/')).toBe(false)
    expect(isWatchLaterMarked(66, '/')).toBe(true)
  })

  it('id 不合法时不记（0 / 负数）', () => {
    markWatchLaterAdded(0, '/')

    expect(isWatchLaterMarked(0, '/')).toBe(false)
  })
})
