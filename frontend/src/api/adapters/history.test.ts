import { describe, expect, it } from 'vitest'
import type { components } from '../../types/api'
import type { HistoryItemVM } from '../../types/ui'
import { groupHistory, toHistoryItem } from './history'

type HistoryItemVO = components['schemas']['HistoryItemVO']

/** +08:00（契约语义时区）：`watchedTime` 是服务端串，呈现要按用户本地时区。 */
const SH = 8 * 60

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
    const item = toHistoryItem(history(), SH)
    expect(item.contentId).toBe(7)
    expect(item.href).toBe('/video/7')
    expect(item.badgeText).toBe('02:00/10:00')
    expect(item.progressPercent).toBe(20) // 120 / 600
    // 服务端串 09:05（上海）⇒ +08:00 用户看到 09:05
    expect(item.watchedTimeText).toBe('09:05')
    expect(item.watchedAt).toBe('2026-10-26T09:05:00')
  })

  it('★ 观看时刻按用户本地时区呈现（同一串在 UTC 下是 01:05）', () => {
    // TZ=UTC 且不传偏移 ⇒ 本地就是 UTC。旧实现把上海墙上时间当本地时间抄 ⇒ 会错误地给 09:05。
    expect(toHistoryItem(history()).watchedTimeText).toBe('01:05')
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
      SH,
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

describe('groupHistory', () => {
  /** 上海正午（带偏移：不带的裸串在 TZ=UTC 下是 12:00Z，等于上海 20:00，夹具意图就漂了）。 */
  const now = new Date('2026-10-26T12:00:00+08:00')
  const at = (watchedAt: string): HistoryItemVM => toHistoryItem(history({ watchedTime: watchedAt }))

  it('保持服务端顺序、按天聚合（今天 2 条 / 昨天 1 条 / 10-24 1 条）', () => {
    const groups = groupHistory(
      [at('2026-10-26T11:00:00'), at('2026-10-26T09:00:00'), at('2026-10-25T22:00:00'), at('2026-10-24T08:00:00')],
      now,
      SH,
    )
    expect(groups.map((group) => group.label)).toEqual(['今天', '昨天', '10-24'])
    expect(groups.map((group) => group.items.length)).toEqual([2, 1, 1])
  })

  it('★ 跨天交错也只有一个「今天」，不会产出两个同名分组', () => {
    const groups = groupHistory(
      [at('2026-10-26T11:00:00'), at('2026-10-25T22:00:00'), at('2026-10-26T09:00:00')],
      now,
      SH,
    )
    expect(groups.map((group) => group.label)).toEqual(['今天', '昨天'])
    expect(groups[0]?.items.length).toBe(2)
  })

  it('★ 分组日界绑用户时区：同一天的战绩在 UTC 视角下被拆成两天', () => {
    const items = [at('2026-10-26T00:30:00'), at('2026-10-25T23:30:00')]
    // 上海：两条都在 10-26 与 10-25 —— 仍是两天；UTC：00:30 那条落到 10-25 16:30
    expect(groupHistory(items, now, SH).map((group) => group.key)).toEqual(['today', 'yesterday'])
    expect(groupHistory(items, now, 0).map((group) => group.key)).toEqual(['yesterday'])
  })
})
