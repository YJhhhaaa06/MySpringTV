import { describe, expect, it } from 'vitest'
import { formatCount, formatDateTime, formatDuration, formatRelativeDate } from './format'

describe('formatDuration', () => {
  it('不足一小时按 mm:ss', () => {
    expect(formatDuration(59)).toBe('00:59')
    expect(formatDuration(65)).toBe('01:05')
    expect(formatDuration(754)).toBe('12:34')
  })

  it('超过一小时按 h:mm:ss', () => {
    expect(formatDuration(3600)).toBe('1:00:00')
    expect(formatDuration(3661)).toBe('1:01:01')
  })

  it('四舍五入到秒（59.6 ⇒ 01:00，不是 00:59）', () => {
    expect(formatDuration(59.6)).toBe('01:00')
  })

  it('缺失 / 零 / 负数 / 非法 ⇒ null（角标不渲染）', () => {
    expect(formatDuration(null)).toBeNull()
    expect(formatDuration(undefined)).toBeNull()
    expect(formatDuration(0)).toBeNull()
    expect(formatDuration(-3)).toBeNull()
    expect(formatDuration(Number.NaN)).toBeNull()
  })
})

describe('formatCount', () => {
  it('一万以下原样显示', () => {
    expect(formatCount(0)).toBe('0')
    expect(formatCount(9999)).toBe('9999')
  })

  it('万 / 亿：保留一位小数并去掉 .0', () => {
    expect(formatCount(10_000)).toBe('1万')
    expect(formatCount(12_345)).toBe('1.2万')
    expect(formatCount(1_672_000)).toBe('167.2万')
    expect(formatCount(99_999)).toBe('10万')
    expect(formatCount(100_000_000)).toBe('1亿')
    expect(formatCount(123_456_789)).toBe('1.2亿')
  })

  it('缺失 / 负数 ⇒ `-`（不假装是 0）', () => {
    expect(formatCount(null)).toBe('-')
    expect(formatCount(undefined)).toBe('-')
    expect(formatCount(-1)).toBe('-')
  })
})

describe('formatRelativeDate', () => {
  const now = new Date('2026-10-25T12:00:00')

  it('一分钟内 ⇒ 刚刚', () => {
    expect(formatRelativeDate('2026-10-25T11:59:30', now)).toBe('刚刚')
  })

  it('一小时内 ⇒ N分钟前', () => {
    expect(formatRelativeDate('2026-10-25T11:30:00', now)).toBe('30分钟前')
  })

  it('一天内 ⇒ N小时前', () => {
    expect(formatRelativeDate('2026-10-25T09:00:00', now)).toBe('3小时前')
  })

  it('30 天内 ⇒ N天前', () => {
    expect(formatRelativeDate('2026-10-23T12:00:00', now)).toBe('2天前')
    expect(formatRelativeDate('2026-09-28T12:00:00', now)).toBe('27天前')
  })

  it('超过 30 天：同年 ⇒ MM-DD，跨年 ⇒ YYYY-MM-DD', () => {
    expect(formatRelativeDate('2026-08-01T12:00:00', now)).toBe('08-01')
    expect(formatRelativeDate('2025-12-31T12:00:00', now)).toBe('2025-12-31')
  })

  it('未来时间 / 空 / 非法 ⇒ 不炸（未来按刚刚，非法给空串）', () => {
    expect(formatRelativeDate('2026-10-26T00:00:00', now)).toBe('刚刚')
    expect(formatRelativeDate(null, now)).toBe('')
    expect(formatRelativeDate('not-a-date', now)).toBe('')
  })
})

describe('formatDateTime', () => {
  it('ISO ⇒ 本地 `YYYY-MM-DD HH:mm`', () => {
    expect(formatDateTime('2026-05-09T19:43:40')).toBe('2026-05-09 19:43')
  })

  it('空 / 非法 ⇒ 空串', () => {
    expect(formatDateTime(undefined)).toBe('')
    expect(formatDateTime('x')).toBe('')
  })
})
