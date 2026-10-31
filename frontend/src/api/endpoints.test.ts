import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../types/api'
import { loadHome, loadVideoPage, searchContents } from './endpoints'
import { loadFeed } from './feed'
import { getJson } from './http'
import { resetServerClock, syncServerClock } from './serverClock'

/**
 * 端点层的**时钟契约**（`T72`）：端点一个时钟都不许有。
 *
 * <p>为什么单列一个文件：`loadHome` / `loadFeed` / `searchContents` / `loadVideoPage`
 * 只是"取数据 → 交给适配器"，"现在"完全由适配器的 `serverNow()` 决定。
 * 这些函数曾经带 `now: Date = new Date()` —— 那等于给"现在"开第二个来源：端点自己赢一次，
 * 浏览器时钟就回到了适配器手上，而**只测适配器的用例仍然全绿**（假绿）。
 * 这里用假网络层把四条链路都点一遍：不传时钟 + 系统时间被改到 2020 ⇒ 仍按 `Date` 头算。
 */
vi.mock('./http', () => ({ getJson: vi.fn() }))

interface GetJsonMock {
  /** 返回自身以便链式排多次响应（`getJson` 是泛型，`vi.mocked` 推不出参数类型）。 */
  mockResolvedValueOnce: (value: unknown) => GetJsonMock
  mockReset: () => void
}

const mockedGetJson = vi.mocked(getJson) as unknown as GetJsonMock

type ContentVO = components['schemas']['ContentVO']

/** 无封面 ⇒ 不进轮播位，直接落在 `cards` 里（`splitHero` 的规则）。 */
function content(createTime: string, id = 92): ContentVO {
  return {
    id,
    title: '标题',
    authorId: 1,
    authorName: '一号员工',
    categoryId: 0,
    commentEnabled: true,
    coverUrl: null,
    createTime,
    description: '简介',
    durationSeconds: null,
    interaction: null,
    playCount: 0,
    type: 1,
  } as ContentVO
}

const pageOf = (iso: string): components['schemas']['PageResultContentVO'] =>
  ({ list: [content(iso)], page: 1, pageSize: 20, total: 1, totalPages: 1 }) as unknown as
    components['schemas']['PageResultContentVO']

describe('端点层没有自己的时钟（四条链路）', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
    resetServerClock()
    mockedGetJson.mockReset()
  })

  /** 锚点 = 上海 2026-10-25 12:00；再把系统时间改到 2020（端点若自带 `new Date()` 就会暴露）。 */
  function anchorToShanghaiNoon(): void {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.spyOn(performance, 'now').mockReturnValue(1_000)
    syncServerClock('Sun, 25 Oct 2026 04:00:00 GMT')
    vi.setSystemTime(new Date('2020-01-01T00:00:00Z'))
  }

  /** 上海 09:00 ⇒ 距锚点 3 小时。 */
  const createdAt = '2026-10-25T09:00:00'

  it('★ loadHome：不传时钟 ⇒ 用 Date 头', async () => {
    anchorToShanghaiNoon()
    mockedGetJson.mockResolvedValueOnce([content(createdAt)])
    const home = await loadHome()
    expect(home.cards[0]?.dateText).toBe('3小时前')
  })

  it('★ searchContents：不传时钟 ⇒ 用 Date 头', async () => {
    anchorToShanghaiNoon()
    mockedGetJson.mockResolvedValueOnce(pageOf(createdAt))
    const result = await searchContents('关键词', 1)
    expect(result.items[0]?.dateText).toBe('3小时前')
  })

  it('★ loadFeed：不传时钟 ⇒ 用 Date 头', async () => {
    anchorToShanghaiNoon()
    mockedGetJson.mockResolvedValueOnce(pageOf(createdAt))
    const feed = await loadFeed()
    expect(feed.items[0]?.timeText).toBe('3小时前')
  })

  it('★ loadVideoPage 的相关推荐：不传时钟 ⇒ 用 Date 头', async () => {
    anchorToShanghaiNoon()
    mockedGetJson
      .mockResolvedValueOnce({
        id: 92,
        title: '详情',
        authorId: 1,
        authorName: '一号员工',
        createTime: createdAt,
        type: 1,
        commentEnabled: true,
        durationSeconds: 100,
        videoUrl: '/upload/video/a.mp4',
        coverUrl: '/upload/cover/a.png',
        playCount: 0,
        likeCount: 0,
        coinCount: 0,
        favoriteCount: 0,
        commentCount: 0,
        interaction: null,
      })
      .mockResolvedValueOnce({ userId: 1, username: '一号员工' })
      .mockResolvedValueOnce([content(createdAt, 93)])
    const page = await loadVideoPage(92)
    expect(page.related[0]?.dateText).toBe('3小时前')
  })
})
