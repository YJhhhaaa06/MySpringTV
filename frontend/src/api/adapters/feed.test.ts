import { describe, expect, it } from 'vitest'
import type { components } from '../../types/api'
import { toDynamicCard, toDynamicPage } from './feed'

type ContentVO = components['schemas']['ContentVO']

function content(overrides: Partial<Record<keyof ContentVO, unknown>> = {}): ContentVO {
  // 夹具按**运行时**形状造：spec 把这些字段声明成"可缺省但不可为 null"，
  // 真实响应会给显式 null（GAP-07）⇒ 末行 cast 掉类型谎言。
  return {
    id: 93,
    title: '🐧小时候被打后的幻想🐧',
    authorId: 1,
    authorName: '一号员工',
    categoryId: 0,
    commentEnabled: true,
    coverUrl: '/upload/cover/x.png',
    createTime: '2026-05-09T19:45:57',
    description: '咕咕嘎嘎',
    durationSeconds: 61,
    interaction: { coin: null, isFavorited: null, isFollowed: null, isLiked: false },
    playCount: 1,
    likeCount: 2,
    commentCount: 3,
    type: 1,
    ...overrides,
  } as ContentVO
}

const now = new Date('2026-05-09T20:00:00')

describe('toDynamicCard', () => {
  it('视频卡：动作文案「投稿了视频」+ 播放量 / 点赞 / 评论三个真计数', () => {
    const card = toDynamicCard(content({ playCount: 12345, likeCount: 678, commentCount: 9 }), now)
    expect(card.actionText).toBe('投稿了视频')
    expect(card.isPost).toBe(false)
    expect(card.playCountText).toBe('1.2万')
    // 期望值出自业务口径：/feed 自 GAP-01 起带后两维，卡片底部操作栏正是它们的位置
    expect(card.likeCount).toBe(678)
    expect(card.commentCount).toBe(9)
    expect(card.durationText).toBe('01:01')
    expect(card.timeText).toBe('14分钟前')
  })

  it('★ 计数 0 是真实值 ⇒ 显示 0；缺失 / null ⇒ null（不假装 0）', () => {
    expect(toDynamicCard(content({ likeCount: 0, commentCount: 0 }), now).likeCount).toBe(0)
    expect(toDynamicCard(content({ likeCount: null }), now).likeCount).toBeNull()
    expect(toDynamicCard(content({ commentCount: undefined }), now).commentCount).toBeNull()
  })

  it('★ 图文：动作文案「发布了图文」，不显示播放量（play_count 结构性恒 0，GAP-05）', () => {
    // 即使后端误回填非 0，也不把「播放」语义套到图文上
    const card = toDynamicCard(content({ type: 2, playCount: 123, durationSeconds: 100 }), now)
    expect(card.actionText).toBe('发布了图文')
    expect(card.isPost).toBe(true)
    expect(card.playCountText).toBeNull()
    expect(card.durationText).toBeNull()
    // 图文仍然有点赞 / 评论两个真计数
    expect(card.likeCount).toBe(2)
    expect(card.commentCount).toBe(3)
  })

  it('★ 互动态三态：没查（null）≠ 没点过（false）', () => {
    expect(toDynamicCard(content({ interaction: null }), now).liked).toBeNull()
    expect(
      toDynamicCard(content({ interaction: { isLiked: false } }), now).liked,
    ).toBe(false)
    expect(toDynamicCard(content({ interaction: { isLiked: true } }), now).liked).toBe(true)
  })

  it('作者名 / 标题 / 简介缺失时给中性占位，不渲染空串', () => {
    const card = toDynamicCard(content({ authorName: '  ', title: '', description: null }), now)
    expect(card.authorName).toBe('未知UP主')
    expect(card.authorInitial).toBe('未')
    expect(card.title).toBe('无标题')
    expect(card.description).toBe('')
  })

  it('跳转链接：内容 → /video/{id}，作者 → /space/{id}（跨应用整页）', () => {
    const card = toDynamicCard(content({ id: 93, authorId: 1 }), now)
    expect(card.href).toBe('/video/93')
    expect(card.authorHref).toBe('/space/1')
  })

  it('评论开关透传（关闭时组件不把「评论」画成可写）', () => {
    expect(toDynamicCard(content({ commentEnabled: false }), now).commentEnabled).toBe(false)
    expect(toDynamicCard(content({ commentEnabled: true }), now).commentEnabled).toBe(true)
  })
})

describe('toDynamicPage', () => {
  it('分页信封原样透传（total / totalPages 不是本页条数）', () => {
    const page = toDynamicPage(
      {
        list: [content({ id: 1 }), content({ id: 2 })],
        total: 20,
        page: 2,
        pageSize: 12,
        totalPages: 2,
      },
      now,
    )
    expect(page.items).toHaveLength(2)
    expect(page.total).toBe(20)
    expect(page.page).toBe(2)
    expect(page.totalPages).toBe(2)
  })

  it('空页 / 缺省 ⇒ 空数组 + 安全默认值', () => {
    expect(toDynamicPage(undefined, now)).toEqual({
      items: [],
      page: 1,
      total: 0,
      totalPages: 0,
    })
  })
})
