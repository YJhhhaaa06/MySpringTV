import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../types/api'
import { loadHome, loadHomeMore, loadVideoPage } from './endpoints'
import { getJson } from './http'

/**
 * 推荐流端点的**请求形状**（`T91-2`）：
 * 游标原样回传、一页 20 条、只有第一页拆轮播、侧栏走 `?type=1`。
 * 这些都不该在页面里重复判断，所以钉在端点层。
 */
vi.mock('./http', () => ({ getJson: vi.fn() }))

interface GetJsonMock {
  mockResolvedValueOnce: (value: unknown) => GetJsonMock
  mockReset: () => void
  mock: { calls: unknown[][] }
}

const mockedGetJson = vi.mocked(getJson) as unknown as GetJsonMock

type ContentVO = components['schemas']['ContentVO']

function content(id: number, coverUrl: string | null, type = 1): ContentVO {
  return {
    id,
    title: `标题 ${id}`,
    authorId: 1,
    authorName: '一号员工',
    categoryId: 0,
    commentEnabled: true,
    coverUrl,
    createTime: '2026-11-02T10:00:00',
    description: '简介',
    durationSeconds: 100,
    interaction: null,
    playCount: 0,
    type,
  } as ContentVO
}

/** 视频详情（`loadVideoPage` 的第一步） */
function detail(id = 92): components['schemas']['ContentDetailVO'] {
  return {
    id,
    title: '详情',
    authorId: 1,
    authorName: '一号员工',
    categoryId: 0,
    createTime: '2026-11-02T10:00:00',
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
  } as components['schemas']['ContentDetailVO']
}

describe('推荐流端点', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    mockedGetJson.mockReset()
  })

  it('首屏：只带 pageSize，不带 cursor；nextCursor 原样回传', async () => {
    mockedGetJson.mockResolvedValueOnce({
      list: [content(1, '/c1.png'), content(2, '/c2.png'), content(3, '/c3.png'), content(4, null)],
      nextCursor: 'v1.739201.4',
    })

    const home = await loadHome()

    expect(mockedGetJson).toHaveBeenCalledWith('/api/v1/contents/recommended', {
      query: { pageSize: 20 },
    })
    expect(home.slides.map((slide) => slide.id)).toEqual([1, 2, 3])
    expect(home.cards.map((card) => card.id)).toEqual([4])
    expect(home.nextCursor).toBe('v1.739201.4')
  })

  it('续页：cursor 原样回传，且**不拆轮播**（4 条带封面必须全在网格里）', async () => {
    mockedGetJson.mockResolvedValueOnce({
      list: [content(11, '/c11.png'), content(12, '/c12.png'), content(13, '/c13.png'), content(14, '/c14.png')],
      nextCursor: null,
    })

    const page = await loadHomeMore('v1.739201.4')

    expect(mockedGetJson).toHaveBeenCalledWith('/api/v1/contents/recommended', {
      query: { pageSize: 20, cursor: 'v1.739201.4' },
    })
    expect(page.cards.map((card) => card.id)).toEqual([11, 12, 13, 14])
    expect(page.nextCursor).toBeNull()
  })

  it('缺省 nextCursor（老后端 / 显式 null）一律归一成"到底"', async () => {
    mockedGetJson.mockResolvedValueOnce({ list: [content(1, null)] })
    const home = await loadHome()
    expect(home.nextCursor).toBeNull()
  })

  it('侧栏相关推荐：带数字口径的 type=1，并把图文挡在播放页版式之外', async () => {
    mockedGetJson
      .mockResolvedValueOnce(detail(92))
      .mockResolvedValueOnce({ userId: 1, username: '一号员工' })
      .mockResolvedValueOnce({
        list: [content(92, '/self.png'), content(93, '/v.png', 1), content(94, '/p.png', 2)],
        nextCursor: null,
      })

    const page = await loadVideoPage(92)

    const relatedCall = mockedGetJson.mock.calls.find(
      (call: unknown[]) => call[0] === '/api/v1/contents/recommended',
    )
    expect(relatedCall?.[1]).toEqual({ query: { type: 1 } })
    expect(page.related.map((card) => card.id)).toEqual([93])
  })
})
