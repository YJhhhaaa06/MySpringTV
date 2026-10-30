import { describe, expect, it } from 'vitest'
import type { components } from '../../types/api'
import type { HistoryItemVM } from '../../types/ui'
import { groupHistory, historyDayLabel, toHistoryItem } from './history'

type HistoryItemVO = components['schemas']['HistoryItemVO']

function history(fields: Partial<Record<keyof HistoryItemVO, unknown>> = {}): HistoryItemVO {
  return {
    contentId: 7,
    watchedTime: '2026-10-26T09:05:00',
    invalid: false,
    title: '标题',
    coverUrl: '/upload/cover/x.png',
    authorName: '作者',
    progress: 120,
    durationSeconds: 600,
    ...fields,
  } as HistoryItemVO
}

describe('toHistoryItem', () => {
  it('正常条目：角标 = 已看/总长（B 站口径），进度按 progress / durationSeconds 真算', () => {
    const item = toHistoryItem(history())
    expect(item.contentId).toBe(7)
    expect(item.href).toBe('/video/7')
    expect(item.badgeText).toBe('02:00/10:00')
    expect(item.progressPercent).toBe(20) // 120 / 600
    expect(item.watchedTimeText).toBe('09:05')
    expect(item.watchedAt).toBe('2026-10-26T09:05:00')
  })

  it('★ 角标格式与 B 站一致：`18:09/49:45`（截图里的真实写法）', () => {
    const item = toHistoryItem(history({ progress: 1089, durationSeconds: 2985 }))
    expect(item.badgeText).toBe('18:09/49:45')
    expect(item.progressPercent).toBe(36) // round(1089 / 2985 * 100)
  })

  it('看完：progress ≥ duration ⇒ 100% + 「已看完」', () => {
    const item = toHistoryItem(history({ progress: 600 }))
    expect(item.progressPercent).toBe(100)
    expect(item.badgeText).toBe('已看完')
  })

  it('★ 没有总时长：只给「看到 mm:ss」，**不画比例**（percent 0 而不是猜一个）', () => {
    const item = toHistoryItem(history({ progress: 30, durationSeconds: null }))
    expect(item.badgeText).toBe('看到 00:30')
    expect(item.progressPercent).toBe(0)
  })

  it('进度 0：不渲染角标（「看到 00:00」不是用户看过的事实）', () => {
    const item = toHistoryItem(history({ progress: 0 }))
    expect(item.badgeText).toBe('')
    expect(item.progressPercent).toBe(0)
  })

  it('失效条目：不显示进度 / 作者，但**保留观看时间**（历史数据是真的）', () => {
    const item = toHistoryItem(
      history({ invalid: true, title: '内容已失效', coverUrl: null, authorName: null, durationSeconds: null }),
    )
    expect(item.invalid).toBe(true)
    expect(item.title).toBe('内容已失效')
    expect(item.coverUrl).toBeNull()
    expect(item.authorName).toBe('')
    expect(item.badgeText).toBe('')
    expect(item.progressPercent).toBe(0)
    expect(item.watchedTimeText).toBe('09:05')
  })
})

describe('historyDayLabel', () => {
  const now = new Date('2026-10-26T12:00:00')

  it('按本地日历日分组：今天 / 昨天 / 同年 MM-DD / 跨年 YYYY-MM-DD', () => {
    expect(historyDayLabel('2026-10-26T00:01:00', now).label).toBe('今天')
    expect(historyDayLabel('2026-10-25T23:59:00', now).label).toBe('昨天')
    expect(historyDayLabel('2026-10-24T18:00:00', now).label).toBe('10-24')
    expect(historyDayLabel('2025-12-31T18:00:00', now).label).toBe('2025-12-31')
  })

  it('时间缺失/非法归「时间未知」，不塞进今天', () => {
    expect(historyDayLabel(null, now).label).toBe('时间未知')
    expect(historyDayLabel('not-a-date', now).label).toBe('时间未知')
  })
})

describe('groupHistory', () => {
  const now = new Date('2026-10-26T12:00:00')
  const at = (watchedAt: string): HistoryItemVM => toHistoryItem(history({ watchedTime: watchedAt }))

  it('保持服务端顺序、按天聚合（今天 2 条 / 昨天 1 条 / 10-24 1 条）', () => {
    const groups = groupHistory(
      [at('2026-10-26T11:00:00'), at('2026-10-26T09:00:00'), at('2026-10-25T22:00:00'), at('2026-10-24T08:00:00')],
      now,
    )
    expect(groups.map((group) => group.label)).toEqual(['今天', '昨天', '10-24'])
    expect(groups.map((group) => group.items.length)).toEqual([2, 1, 1])
  })

  it('★ 跨天交错也只有一个「今天」，不会产出两个同名分组', () => {
    const groups = groupHistory(
      [at('2026-10-26T11:00:00'), at('2026-10-25T22:00:00'), at('2026-10-26T09:00:00')],
      now,
    )
    expect(groups.map((group) => group.label)).toEqual(['今天', '昨天'])
    expect(groups[0]?.items.length).toBe(2)
  })
})
