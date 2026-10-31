import { describe, expect, it } from 'vitest'
import type { components } from '../../types/api'
import { toFavoriteItemCard, toPublicFolder, toSpaceHeader, toSpaceWorks } from './space'

type ProfileVO = components['schemas']['ProfileVO']
type PageResultContentVO = components['schemas']['PageResultContentVO']

/** 上海正午（**带偏移**：`createTime` 按 +08:00 解释，夹具必须同源） */
const now = new Date('2026-10-25T12:00:00+08:00')

function profile(fields: Partial<Record<keyof ProfileVO, unknown>> = {}): ProfileVO {
  return {
    userId: 1,
    username: '一号员工',
    followerCount: 9,
    followCount: 1,
    isFollowed: false,
    contentPage: { list: [], total: 30, page: 1, pageSize: 12, totalPages: 3 },
    ...fields,
  } as ProfileVO
}

describe('toSpaceHeader', () => {
  it('资料都是真接口数字；昵称首字当头像（后端没有 avatar）', () => {
    const header = toSpaceHeader(profile(), null)
    expect(header.id).toBe(1)
    expect(header.name).toBe('一号员工')
    expect(header.initial).toBe('一')
    expect(header.followerCount).toBe(9)
    expect(header.followingCount).toBe(1)
    expect(header.worksCount).toBe(30)
  })

  it('★ isSelf：只有"看自己的空间"才不显示关注按钮', () => {
    expect(toSpaceHeader(profile(), 1).isSelf).toBe(true)
    expect(toSpaceHeader(profile(), 2).isSelf).toBe(false)
    expect(toSpaceHeader(profile(), null).isSelf).toBe(false) // 匿名
  })

  it('★ isFollowed 匿名时是 null ⇒ VM 也 null（不回退成 false）', () => {
    expect(toSpaceHeader({ ...profile(), isFollowed: null } as unknown as ProfileVO, null).followed).toBeNull()
    expect(toSpaceHeader(profile(), null).followed).toBe(false)
    expect(toSpaceHeader({ ...profile(), isFollowed: true } as ProfileVO, null).followed).toBe(true)
  })

  it('计数缺失 ⇒ null（渲染成 `-`）', () => {
    expect(toSpaceHeader({}, null).followerCount).toBeNull()
    expect(toSpaceHeader({}, null).worksCount).toBeNull()
  })
})

describe('toSpaceWorks', () => {
  it('作品分页沿用搜索页的卡片口径', () => {
    // interaction 的三维在"空间作品面"里是显式 null（本面只查赞）⇒ cast 掉 spec 的谎言
    const page = {
      list: [
        {
          id: 93,
          title: '🐧小时候被打后的幻想🐧',
          authorId: 1,
          authorName: '一号员工',
          coverUrl: '/upload/cover/a.png',
          createTime: '2026-05-09T19:45:57',
          durationSeconds: 100,
          type: 1,
          interaction: { coin: null, isFavorited: null, isFollowed: null, isLiked: true },
        },
      ],
      total: 30,
      page: 2,
      pageSize: 12,
      totalPages: 3,
    } as unknown as PageResultContentVO
    const vm = toSpaceWorks(page, now)
    expect(vm.total).toBe(30)
    expect(vm.page).toBe(2)
    expect(vm.items[0]?.authorHref).toBe('/space/1')
    expect(vm.items[0]?.durationText).toBe('01:40')
    expect(vm.items[0]?.liked).toBe(true) // 空间的作品面只回「赞」这一维
  })

  it('空 / 缺省 ⇒ 空页', () => {
    expect(toSpaceWorks(undefined, now)).toEqual({ items: [], page: 1, pageSize: 0, total: 0, totalPages: 0 })
  })
})

describe('toPublicFolder', () => {
  it('公开收藏夹：名字与条目数（后端不返回私密夹，前端不用过滤）', () => {
    expect(toPublicFolder({ id: 14, name: '默认收藏夹', itemCount: 3 })).toEqual({
      id: 14,
      name: '默认收藏夹',
      itemCount: 3,
    })
    expect(toPublicFolder({}).name).toBe('未命名收藏夹')
  })
})

describe('toFavoriteItemCard（FavoriteItemVO 比 ContentVO 少一大半字段）', () => {
  it('★ 没有 authorId ⇒ 作者链接为 null（不做死链）', () => {
    const card = toFavoriteItemCard({
      id: 9,
      contentId: 65,
      title: '[AI高松灯] 壱雫空',
      coverUrl: '/upload/cover/a.png',
      authorName: '一号员工',
      favoriteTime: '2026-10-25T10:00:00',
      invalid: false,
    })
    expect(card.authorHref).toBeNull()
    expect(card.authorName).toBe('一号员工')
    expect(card.href).toBe('/video/65')
  })

  it('★ 没有时长 / 发布时间 ⇒ 都不显示（不拿收藏时间冒充发布日期）', () => {
    const card = toFavoriteItemCard({ contentId: 65, favoriteTime: '2026-10-25T10:00:00' })
    expect(card.durationText).toBeNull()
    expect(card.dateText).toBe('')
  })

  it('已失效内容保留灰卡 + 角标（不是藏起来）', () => {
    expect(toFavoriteItemCard({ contentId: 65, invalid: true }).invalid).toBe(true)
    expect(toFavoriteItemCard({ contentId: 65, invalid: false }).invalid).toBe(false)
    expect(toFavoriteItemCard({}).title).toBe('已失效内容')
  })
})
