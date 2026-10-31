import { describe, expect, it } from 'vitest'
import type { components } from '../../types/api'
import { toCreatorWork, toCreatorWorks } from './creator'

/**
 * 创作中心稿件行的契约测试。
 *
 * <p>期望值来自**业务口径**，不是照抄实现：
 * - 视频行给播放量、图文行**不给**（后端 `play_count` 对图文结构性恒 0，GAP-05）；
 * - 落点按 `type` 分流（`contentHref` 的唯一规则：VIDEO → `/video/{id}`、POST → `/opus/{id}`）；
 * - 编辑入口恒 `/creator/edit/{id}`（作者视角，与观众落点不同）。
 */

type ContentVO = components['schemas']['ContentVO']

function content(overrides: Partial<ContentVO> = {}): ContentVO {
  return {
    id: 92,
    authorId: 7,
    type: 1,
    title: '  一个视频  ',
    description: '',
    categoryId: 4,
    authorName: 'up',
    coverUrl: '/upload/cover/a.png',
    durationSeconds: 3725,
    createTime: '2026-10-29T08:05:00',
    playCount: 12345,
    ...overrides,
  }
}

describe('稿件行', () => {
  it('视频：时长 h:mm:ss、播放量按万缩写、落点 /video/{id}', () => {
    const work = toCreatorWork(content())
    expect(work.typeText).toBe('视频')
    expect(work.isPost).toBe(false)
    expect(work.durationText).toBe('1:02:05')
    expect(work.playCountText).toBe('1.2万')
    expect(work.title).toBe('一个视频')
    expect(work.viewHref).toBe('/video/92')
    expect(work.editHref).toBe('/creator/edit/92')
  })

  it('图文：不显示播放量与时长的"0"，落点 /opus/{id}', () => {
    const work = toCreatorWork(content({ type: 2, durationSeconds: null, playCount: 0 }))
    expect(work.typeText).toBe('图文')
    expect(work.isPost).toBe(true)
    // 0 对图文不是"没人看"，是"没有这条管道" ⇒ null（行里画 —），不是 '0'
    expect(work.playCountText).toBeNull()
    expect(work.durationText).toBeNull()
    expect(work.viewHref).toBe('/opus/92')
    expect(work.editHref).toBe('/creator/edit/92')
  })

  it('★ 发布时间是契约 §2.7 的时间出口之一：按 +08:00 解释、按本地呈现（T72）', () => {
    // 夹具 createTime 是上海 2026-10-29 08:05；TZ=UTC 测试机 ⇒ 00:05。
    // 若这里退回 `new Date(item.createTime)` 直拼，会印成 08:05（比用户的钟快 8 小时）。
    expect(toCreatorWork(content()).createdAtText).toBe('2026-10-29 00:05')
  })

  it('缺封面 / 缺标题 / 缺发布时间的降级：不编内容', () => {
    // 发布时间在 spec 里是非空 string；运行时缺失只可能是空串 / 非法值，两条都按"不显示"处理
    const work = toCreatorWork(content({ title: '   ', coverUrl: null, createTime: '' }))
    expect(work.title).toBe('无标题')
    expect(work.coverUrl).toBeNull()
    expect(work.createdAtText).toBe('')
  })

  it('时长非法（0 / 负 / 缺）⇒ 不渲染角标', () => {
    expect(toCreatorWork(content({ durationSeconds: 0 })).durationText).toBeNull()
    expect(toCreatorWork(content({ durationSeconds: -3 })).durationText).toBeNull()
    expect(toCreatorWork(content({ durationSeconds: null })).durationText).toBeNull()
  })
})

describe('分页', () => {
  it('total / totalPages 取契约值，不用本页条数冒充', () => {
    const page = toCreatorWorks({
      list: [content(), content({ id: 93 })],
      page: 2,
      pageSize: 10,
      total: 47,
      totalPages: 5,
    })
    expect(page.page).toBe(2)
    expect(page.total).toBe(47)
    expect(page.totalPages).toBe(5)
    expect(page.items).toHaveLength(2)
  })

  it('空页 / 缺省：不抛错、total 按 0 条（不是"未知"）', () => {
    expect(toCreatorWorks(undefined)).toEqual({ items: [], page: 1, total: 0, totalPages: 0 })
  })
})
