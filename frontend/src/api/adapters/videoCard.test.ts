import { describe, expect, it } from 'vitest'
import type { components } from '../../types/api'
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
    type: 1,
    ...overrides,
  } as ContentVO
}

const now = new Date('2026-10-25T12:00:00')

describe('toVideoCard', () => {
  it('从 ContentVO 映射出卡片：id / 标题 / 跨应用链接 / 作者占位链接', () => {
    const card = toVideoCard(content(), now)
    expect(card.id).toBe(92)
    expect(card.title).toBe('这期具有教育意义🐧')
    expect(card.href).toBe('/video/92')
    expect(card.authorName).toBe('一号员工')
    // 空间页后续窗口才交付 ⇒ 暂挂占位路由（改 links.ts 一处即可换回 /space/{id}）
    expect(card.authorHref).toBe('/placeholder/space?uid=1')
    // 2026-05-09 距 now 超过 30 天且同年 ⇒ MM-DD
    expect(card.dateText).toBe('05-09')
  })

  it('发布时间按业务口径给相对文本', () => {
    expect(toVideoCard(content({ createTime: '2026-10-25T09:00:00' }), now).dateText).toBe('3小时前')
  })

  it('durationSeconds 为 null ⇒ 不渲染时长角标（dev 数据当前全是 null）', () => {
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

  it('★ 列表 VO 没有计数 ⇒ 播放量恒 null（不许编 0 / 不许回源详情）', () => {
    expect(toVideoCard(content(), now).playCountText).toBeNull()
  })

  it('★ 没有弹幕域 ⇒ 弹幕数恒 null，永远不显示', () => {
    expect(toVideoCard(content(), now).danmakuText).toBeNull()
  })

  it('★ 互动态三态：匿名 ⇒ null，"点过赞"才是 true', () => {
    // 列表面（/users/{id}）只回"赞"这一维；null 表示本面不查，不等于"没点过"
    expect(toVideoCard(content(), now).liked).toBeNull()
    expect(toVideoCard(content({ interaction: { isLiked: true } }), now).liked).toBe(true)
    expect(toVideoCard(content({ interaction: { isLiked: false } }), now).liked).toBe(false)
  })
})
