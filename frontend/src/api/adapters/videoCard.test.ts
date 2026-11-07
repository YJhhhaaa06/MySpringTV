import { afterEach, describe, expect, it, vi } from 'vitest'
import type { components } from '../../types/api'
import { resetServerClock, syncServerClock } from '../serverClock'
import { toDynamicCard } from './feed'
import { toSearchResult } from './search'
import { toSpaceWorks } from './space'
import { toVideoCard } from './videoCard'

type ContentVO = components['schemas']['ContentVO']

function content(overrides: Partial<Record<keyof ContentVO, unknown>> = {}): ContentVO {
  // spec 把这些字段声明成"可缺省但不可为 null"，而真实匿名响应会给显式 null
  // （契约层漏标 nullable）⇒ 夹具按**运行时**形状造，末行 cast 掉类型谎言。
  return {
    id: 92,
    title: '这期具有教育意义🐧',
    authorId: 1,
    authorName: '一号员工',
    categoryId: 0,
    commentEnabled: true,
    coverUrl: '/upload/cover/f021511b-2bb3-4097-b810-e1c84a900b86.png',
    createTime: '2026-05-09T19:43:40',
    description: '简介',
    durationSeconds: null,
    interaction: null,
    playCount: 0,
    type: 1,
    ...overrides,
  } as ContentVO
}

/** 上海正午（**带偏移**：`createTime` 按 +08:00 解释，夹具必须同源） */
const now = new Date('2026-10-25T12:00:00+08:00')

describe('toVideoCard', () => {
  it('从 ContentVO 映射出卡片：id / 标题 / 跨应用链接 / 作者占位链接', () => {
    const card = toVideoCard(content(), now)
    expect(card.id).toBe(92)
    expect(card.title).toBe('这期具有教育意义🐧')
    expect(card.href).toBe('/video/92')
    expect(card.authorName).toBe('一号员工')
    // 空间页已交付（2026-10-25）⇒ 跨应用链接指向 /space/{id}（links.ts 是唯一出处）
    expect(card.authorHref).toBe('/space/1')
    // 2026-05-09 距 now 超过 30 天且同年 ⇒ MM-DD
    expect(card.dateText).toBe('05-09')
  })

  it('发布时间按业务口径给相对文本', () => {
    expect(toVideoCard(content({ createTime: '2026-10-25T09:00:00' }), now).dateText).toBe('3小时前')
  })

  it('durationSeconds 为 null ⇒ 不渲染时长角标（文件缺失 / 解析失败都是未知）', () => {
    expect(toVideoCard(content(), now).durationText).toBeNull()
  })

  it('视频有时长 ⇒ mm:ss', () => {
    expect(toVideoCard(content({ durationSeconds: 754 }), now).durationText).toBe('12:34')
  })

  it('图文（type=2）没有时长概念：即使后端回填了 durationSeconds 也不显示', () => {
    const card = toVideoCard(content({ type: 2, durationSeconds: 100 }), now)
    expect(card.isPost).toBe(true)
    expect(card.durationText).toBeNull()
  })

  it('coverUrl 缺失 / 空白 ⇒ null（卡片走占位块，不拼出坏 URL）', () => {
    expect(toVideoCard(content({ coverUrl: undefined }), now).coverUrl).toBeNull()
    expect(toVideoCard(content({ coverUrl: '   ' }), now).coverUrl).toBeNull()
  })

  it('作者名缺失 ⇒ 未知UP主（不回显空字符串）', () => {
    expect(toVideoCard(content({ authorName: undefined }), now).authorName).toBe('未知UP主')
  })

  it('★ GAP-01：列表带 playCount ⇒ 万 / 亿文本（能变红：改回恒 null 必红）', () => {
    // 期望值出自 B 站展示口径（formatCount 的既有规则），不是抄实现：
    //   12345 → 1.2万（保留一位小数、去掉 .0）/ 100000000 → 1亿
    expect(toVideoCard(content({ playCount: 12345 }), now).playCountText).toBe('1.2万')
    expect(toVideoCard(content({ playCount: 100_000_000 }), now).playCountText).toBe('1亿')
    // 0 是真实值（≠ 本面没给 null）⇒ 显示 "0"，不藏起来
    expect(toVideoCard(content({ playCount: 0 }), now).playCountText).toBe('0')
  })

  it('★ playCount 缺省 / 显式 null ⇒ null（不假装 0）', () => {
    expect(toVideoCard(content({ playCount: undefined }), now).playCountText).toBeNull()
    expect(toVideoCard(content({ playCount: null }), now).playCountText).toBeNull()
  })

  it('★ 图文不显示播放量（后端 play_count 对图文结构性恒 0，见 GAP-05 / GAP-10）', () => {
    expect(toVideoCard(content({ type: 2, playCount: 0 }), now).playCountText).toBeNull()
    // 即使将来误回填非 0，也不把「播放」语义套到图文上
    expect(toVideoCard(content({ type: 2, playCount: 123 }), now).playCountText).toBeNull()
  })

  it('★ 没有弹幕域 ⇒ 弹幕数恒 null，永远不显示', () => {
    expect(toVideoCard(content(), now).danmakuText).toBeNull()
  })

  it('★ 落点按 type 分流：视频 /video/{id}，图文 /opus/{id}', () => {
    expect(toVideoCard(content(), now).href).toBe('/video/92')
    expect(toVideoCard(content({ type: 2 }), now).href).toBe('/opus/92')
  })

  it('★ 纯文字图文：previewText 用真简介；视频 null；没简介给空串（组件渲染中性占位）', () => {
    const post = toVideoCard(content({ type: 2, coverUrl: null, description: '  正文第一行  ' }), now)
    expect(post.previewText).toBe('正文第一行')
    expect(toVideoCard(content({ type: 2, description: null }), now).previewText).toBe('')
    // 后端空简介占位是 "-"（不是空串）⇒ 卡面必须回落到中性占位，不画一个孤零零的 "-"
    expect(toVideoCard(content({ type: 2, description: '-' }), now).previewText).toBe('')
    expect(toVideoCard(content(), now).previewText).toBeNull()
  })

  it('★ 互动态三态：匿名 ⇒ null，"点过赞"才是 true', () => {
    // 列表面（/users/{id}）只回"赞"这一维；null 表示本面不查，不等于"没点过"
    expect(toVideoCard(content(), now).liked).toBeNull()
    expect(toVideoCard(content({ interaction: { isLiked: true } }), now).liked).toBe(true)
    expect(toVideoCard(content({ interaction: { isLiked: false } }), now).liked).toBe(false)
  })
})


/**
 * ★ 「现在」的来源：**跨适配器**的接线测试（漏改一个就红）。
 *
 * <p>四个卡片适配器各自有一个 `now` 默认值，"现在"的唯一来源就是它们。
 * 只要有一个还写着 `new Date()`，那条链路就会退回浏览器时钟 —— 而只看单适配器的用例
 * 仍全绿（每条用例本来就显式传 `now`）。所以这里在同一次锚定下把四条都点一遍。
 *
 * <p>端点层是否有自己的时钟由 `../endpoints.test.ts` 单独钉（那边走假网络层）。
 */
describe('「现在」的来源（跨适配器接线）', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
    resetServerClock()
  })

  /** 锚点 = 上海 2026-10-25 12:00；再把系统时间改到 2020（用浏览器钟会算出"未来"）。 */
  function anchorToShanghaiNoon(): void {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.spyOn(performance, 'now').mockReturnValue(1_000)
    syncServerClock('Sun, 25 Oct 2026 04:00:00 GMT')
    vi.setSystemTime(new Date('2020-01-01T00:00:00Z'))
  }

  /** 上海 09:00 ⇒ 距锚点 3 小时。 */
  const createdAt = '2026-10-25T09:00:00'
  const pageOf = (iso: string) => ({
    list: [content({ createTime: iso })],
    page: 1,
    pageSize: 10,
    total: 1,
    totalPages: 1,
  }) as unknown as components['schemas']['PageResultContentVO']

  it('★ 不传 now ⇒ 四条链路都按 Date 头算，而不是按被改过的系统时间', () => {
    anchorToShanghaiNoon()
    expect(toVideoCard(content({ createTime: createdAt })).dateText).toBe('3小时前')
    expect(toDynamicCard(content({ createTime: createdAt })).timeText).toBe('3小时前')
    expect(toSpaceWorks(pageOf(createdAt)).items[0]?.dateText).toBe('3小时前')
    expect(toSearchResult(pageOf(createdAt), '关键词').items[0]?.dateText).toBe('3小时前')
  })

  it('显式传 now ⇒ 以传入值为准（用例可复算，不受锚点影响）', () => {
    anchorToShanghaiNoon()
    expect(toVideoCard(content({ createTime: createdAt }), new Date('2026-10-25T10:00:00+08:00')).dateText)
      .toBe('1小时前')
  })
})
