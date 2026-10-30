import { describe, expect, it } from 'vitest'
import {
  cleanDescription,
  formatCoinInt,
  formatCommentTime,
  formatCount,
  formatDateTime,
  formatDateTimeCn,
  formatDuration,
  formatRelativeDate,
  initialOf,
  parseServerTime,
} from './format'

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

describe('formatCoinInt', () => {
  it('★ 向下取整：1.5 枚显示 1（四舍五入会与"投 2 枚被拒"自相矛盾）', () => {
    expect(formatCoinInt(1.5)).toBe('1')
    expect(formatCoinInt(1.9)).toBe('1')
    expect(formatCoinInt(0.9)).toBe('0')
  })

  it('整数原样显示', () => {
    expect(formatCoinInt(0)).toBe('0')
    expect(formatCoinInt(2)).toBe('2')
    expect(formatCoinInt(100)).toBe('100')
  })

  it('缺失 / 非有限值 ⇒ `-`；负数按 0（脏值不画成负硬币）', () => {
    expect(formatCoinInt(null)).toBe('-')
    expect(formatCoinInt(undefined)).toBe('-')
    expect(formatCoinInt(Number.NaN)).toBe('-')
    expect(formatCoinInt(-1)).toBe('0')
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

describe('formatDateTimeCn', () => {
  it('ISO ⇒ B 站图文详情口径 `YYYY年M月D日 HH:mm`', () => {
    expect(formatDateTimeCn('2026-10-22T06:02:00')).toBe('2026年10月22日 06:02')
    // 月 / 日不补零（B 站是 10月1日，不是 10月01日）
    expect(formatDateTimeCn('2026-01-01T00:00:00')).toBe('2026年1月1日 00:00')
  })

  it('空 / 非法 ⇒ 空串', () => {
    expect(formatDateTimeCn(null)).toBe('')
    expect(formatDateTimeCn('x')).toBe('')
  })
})

describe('formatCommentTime（评论区：自然日 + 分钟/小时）', () => {
  /** +08:00（契约语义时区）。显式传入 ⇒ 用例不依赖跑测试那台机器的时区。 */
  const SH = 8 * 60
  /** UTC-5（对照：同一条评论在不同时区下的"今天"不同）。 */
  const NY = -5 * 60

  it('同一自然日：刚刚 / N分钟前 / N小时前', () => {
    const now = new Date('2026-10-30T14:00:00+08:00')
    expect(formatCommentTime('2026-10-30T13:59:30', now, SH)).toBe('刚刚')
    expect(formatCommentTime('2026-10-30T13:32:00', now, SH)).toBe('28分钟前')
    expect(formatCommentTime('2026-10-30T13:00:00', now, SH)).toBe('1小时前')
    expect(formatCommentTime('2026-10-30T08:00:00', now, SH)).toBe('6小时前')
  })

  it('★ 跨自然日 ⇒ 绝对时间（不是"24 小时内"：23:59 发的，次日 00:01 看就是绝对时间）', () => {
    const now = new Date('2026-10-31T00:01:00+08:00')
    expect(formatCommentTime('2026-10-30T23:59:00', now, SH)).toBe('2026-10-30 23:59')
  })

  it('★ 日界按传入偏移：同一条评论在 UTC-5 下仍属"同一天"，因此还是相对时间', () => {
    // 上海 10-30 23:24 = UTC-5 的 10-30 10:24；上海 10-31 01:00 = UTC-5 的 10-30 12:00
    const now = new Date('2026-10-31T01:00:00+08:00')
    expect(formatCommentTime('2026-10-30T23:24:00', now, SH)).toBe('2026-10-30 23:24')
    expect(formatCommentTime('2026-10-30T23:24:00', now, NY)).toBe('1小时前')
  })

  it('跨年也恒带年（不做"当年省年"）', () => {
    const now = new Date('2026-10-30T14:00:00+08:00')
    expect(formatCommentTime('2021-01-07T06:31:00', now, SH)).toBe('2021-01-07 06:31')
  })

  it('★ 迟于 now 超过 1 分钟 ⇒ 绝对时间（旧实现把负差值算成"刚刚"，这是明确的错误分支）', () => {
    const now = new Date('2020-01-01T00:00:00+08:00')
    expect(formatCommentTime('2026-10-30T14:00:00', now, SH)).toBe('2026-10-30 14:00')
  })

  it('1 分钟内的"未来"仍算刚刚（Date 头与 create_time 都只有秒级精度）', () => {
    const now = new Date('2026-10-30T13:59:30+08:00')
    expect(formatCommentTime('2026-10-30T14:00:00', now, SH)).toBe('刚刚')
  })

  it('null / 非法 ⇒ 空串（渲染层据此不显示，不写"刚刚"顶上）', () => {
    expect(formatCommentTime(null, new Date('2026-10-30T14:00:00+08:00'), SH)).toBe('')
    expect(formatCommentTime('not-a-date', new Date('2026-10-30T14:00:00+08:00'), SH)).toBe('')
  })
})

describe('parseServerTime（契约 §2.7：无偏移串按 +08:00 解释）', () => {
  it('无偏移 ⇒ 得到正确的时间点（不是浏览器本地时区）', () => {
    expect(parseServerTime('2026-10-30T14:12:59')?.getTime())
      .toBe(Date.parse('2026-10-30T14:12:59+08:00'))
  })

  it('已带偏移 ⇒ 原样解析（不重复追加时区）', () => {
    expect(parseServerTime('2026-10-30T06:12:59Z')?.getTime())
      .toBe(Date.parse('2026-10-30T06:12:59Z'))
  })

  it('空 / 非法 ⇒ null', () => {
    expect(parseServerTime(null)).toBeNull()
    expect(parseServerTime('x')).toBeNull()
  })
})

describe('cleanDescription', () => {
  it('★ 后端空简介占位 `-` ⇒ 空串（不把占位符画给用户）', () => {
    expect(cleanDescription('-')).toBe('')
    expect(cleanDescription('  -  ')).toBe('')
    expect(cleanDescription('正文')).toBe('正文')
    expect(cleanDescription(null)).toBe('')
  })
})

describe('initialOf', () => {
  it('取昵称首字并大写（头像占位；后端没有头像字段）', () => {
    expect(initialOf('frontend_demo')).toBe('F')
    expect(initialOf('一号员工')).toBe('一')
    // 表情符号按 code point 取，不切出半个代理对
    expect(initialOf('🐧企鹅')).toBe('🐧')
  })

  it('空 / 空白 ⇒ U（不画一个空圆）', () => {
    expect(initialOf('')).toBe('U')
    expect(initialOf('   ')).toBe('U')
    expect(initialOf(null)).toBe('U')
    expect(initialOf(undefined)).toBe('U')
  })
})
