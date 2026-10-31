import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../types/api'
import { loadHome, searchContents } from './endpoints'
import { getJson } from './http'
import { resetServerClock, syncServerClock } from './serverClock'

/**
 * 端点层的**时钟透传**契约（`T72`）。
 *
 * <p>为什么单列一个文件：`loadHome` / `searchContents` 等端点只是"取数据 → 交给适配器"，
 * 它们的 `now` 参数**必须保持可选且不给默认值** —— 一旦有人在这里补一个 `new Date()`，
 * 浏览器时钟就又回到了适配器手上，而只测适配器的用例仍然全绿（假绿）。
 * 这里用假网络层把两个方向都钉住：不传 ⇒ 服务器锚点；传了 ⇒ 以传入值为准。
 */
vi.mock('./http', () => ({ getJson: vi.fn() }))

/**
 * `getJson` 是泛型函数，`vi.mocked` 推不出 `mockResolvedValue` 的参数类型（会退化成 `never`）
 * ⇒ 这里只要它"能被喂任意响应"的最小形状，不用库的完整 Mock 类型。
 */
const mockedGetJson = vi.mocked(getJson) as unknown as {
  mockResolvedValue: (value: unknown) => void
  mockReset: () => void
}

type ContentVO = components['schemas']['ContentVO']

/** 无封面 ⇒ 不进轮播位，直接落在 `cards` 里（`splitHero` 的规则）。 */
function content(createTime: string): ContentVO {
  return {
    id: 92,
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

describe('端点层的"现在"（loadHome / searchContents）', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
    resetServerClock()
    mockedGetJson.mockReset()
  })

  /** 锚点 = 上海 2026-10-25 12:00；系统时间改到 2020（退回浏览器钟就会算成"未来"）。 */
  function anchorToShanghaiNoon(): void {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.spyOn(performance, 'now').mockReturnValue(1_000)
    syncServerClock('Sun, 25 Oct 2026 04:00:00 GMT')
    vi.setSystemTime(new Date('2020-01-01T00:00:00Z'))
  }

  /** 上海 09:00 ⇒ 距锚点 3 小时。 */
  const createdAt = '2026-10-25T09:00:00'

  it('★ 不传 now ⇒ 用 Date 头算（端点层不许自己补 new Date()）', async () => {
    anchorToShanghaiNoon()
    mockedGetJson.mockResolvedValue([content(createdAt)])
    const home = await loadHome()
    expect(home.cards[0]?.dateText).toBe('3小时前')
  })

  it('★ 搜索链路同样透传：不传 ⇒ 服务器锚点', async () => {
    anchorToShanghaiNoon()
    mockedGetJson.mockResolvedValue(pageOf(createdAt))
    const result = await searchContents('关键词', 1)
    expect(result.items[0]?.dateText).toBe('3小时前')
  })

  it('显式传 now ⇒ 以传入值为准（透传没被吞掉）', async () => {
    anchorToShanghaiNoon()
    mockedGetJson.mockResolvedValue([content(createdAt)])
    const home = await loadHome(new Date('2026-10-25T10:00:00+08:00'))
    expect(home.cards[0]?.dateText).toBe('1小时前')
  })
})
