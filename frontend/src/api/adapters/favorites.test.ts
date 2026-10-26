import { describe, expect, it } from 'vitest'
import type { components } from '../../types/api'
import { toMyFolder, toWatchLaterCard } from './favorites'

type FavoriteFolderVO = components['schemas']['FavoriteFolderVO']
type WatchLaterItemVO = components['schemas']['WatchLaterItemVO']

function folder(fields: Partial<Record<keyof FavoriteFolderVO, unknown>> = {}): FavoriteFolderVO {
  return {
    id: 1,
    name: '默认收藏夹',
    itemCount: 3,
    isDefault: true,
    isPrivate: false,
    ...fields,
  } as FavoriteFolderVO
}

function watchLater(fields: Partial<Record<keyof WatchLaterItemVO, unknown>> = {}): WatchLaterItemVO {
  return {
    contentId: 7,
    addedTime: '2026-10-26T09:05:00',
    invalid: false,
    title: '标题',
    coverUrl: '/upload/cover/x.png',
    authorName: '作者',
    ...fields,
  } as WatchLaterItemVO
}

describe('toMyFolder', () => {
  it('夹名去空白；空名给固定文案（不渲染空白卡片）', () => {
    expect(toMyFolder(folder({ name: '  我的夹  ' })).name).toBe('我的夹')
    expect(toMyFolder(folder({ name: '   ' })).name).toBe('未命名收藏夹')
    expect(toMyFolder(folder({ name: null })).name).toBe('未命名收藏夹')
  })

  it('★ 默认夹 / 私密夹标记：缺失或 null 一律 false（不假装是默认夹）', () => {
    const plain = toMyFolder(folder({ isDefault: null, isPrivate: null, itemCount: null }))
    expect(plain.isDefault).toBe(false)
    expect(plain.isPrivate).toBe(false)
    expect(plain.itemCount).toBe(0)
    expect(toMyFolder(folder({ isPrivate: true })).isPrivate).toBe(true)
  })
})

describe('toWatchLaterCard', () => {
  it('正常条目：contentId 当 id，作者无 id ⇒ authorHref 为 null（纯文本）', () => {
    const card = toWatchLaterCard(watchLater())
    expect(card.id).toBe(7)
    expect(card.href).toBe('/video/7')
    expect(card.authorName).toBe('作者')
    expect(card.authorHref).toBeNull()
    expect(card.durationText).toBeNull() // VO 没有时长字段（GAP-16）
  })

  it('★ addedTime 是"加入稍后再看的时间"，不是发布时间 ⇒ dateText 必须留空', () => {
    expect(toWatchLaterCard(watchLater({ addedTime: '2026-10-26T09:05:00' })).dateText).toBe('')
  })

  it('失效条目：标题用后端占位文案、作者/封面不编造', () => {
    const card = toWatchLaterCard(
      watchLater({ invalid: true, title: '内容已失效', coverUrl: null, authorName: null }),
    )
    expect(card.invalid).toBe(true)
    expect(card.title).toBe('内容已失效')
    expect(card.coverUrl).toBeNull()
    expect(card.authorName).toBe('') // 不是「未知UP主」——后端没给就是不给了（R-03 脱敏）
  })
})
