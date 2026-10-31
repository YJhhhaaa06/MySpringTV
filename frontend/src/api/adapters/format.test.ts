import { describe, expect, it } from 'vitest'
import {
  cleanDescription,
  formatCoinInt,
  formatClockTime,
  formatCommentTime,
  formatCount,
  formatDateTime,
  formatDateTimeCn,
  formatDayLabel,
  formatDuration,
  formatRelativeDate,
  initialOf,
  parseServerTime,
} from './format'

/**
 * ★ 本文件的所有期望值都跑在 `TZ=UTC` 下（`vitest.config.ts` 的 `test.env`）。
 *
 * <p>这不是装饰：契约 §2.7 的服务端串是**上海墙上时间**，而呈现按**浏览器本地时区**。
 * 只有让测试机的本地时区 ≠ +08:00，"补 +08:00 再按本地渲染"与"直接按本地解析"
 * 才会给出**不同**答案（`T72`）。`+08:00` 机器上两者结果完全相同 ⇒ 测不出实现有没有补。
 */

/** +08:00（契约语义时区）。需要钉时区的函数显式传它 ⇒ 用例不依赖跑测试那台机器的时区。 */
const SH = 8 * 60
/** UTC-5（对照：同一时刻在不同时区下的"今天"不同）。 */
const NY = -5 * 60

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
  /** 上海正午（**必须带偏移**：不带的话在 TZ=UTC 下它是 12:00Z = 上海 20:00，夹具意图就漂了）。 */
  const now = new Date('2026-10-25T12:00:00+08:00')

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

  it('★ 迟于 now 超过 1 分钟 ⇒ 绝对日期（相对时间没有"未来"这一档）', () => {
    // 夹具意图：上海 10-26 00:00 比 now（上海 10-25 12:00）晚 12 小时 ⇒ 不许算成"刚刚"
    expect(formatRelativeDate('2026-10-26T00:00:00', now, SH)).toBe('10-26')
    // 跨年边界：同样是"未来"，落 YYYY-MM-DD
    expect(formatRelativeDate('2027-01-03T00:00:00', now, SH)).toBe('2027-01-03')
  })

  it('同日未来也走绝对日期（列表卡只有"日期"粒度，所以是当天日期）', () => {
    // 这是**刻意**的：卡片不渲染时刻，硬造一个 HH:mm 会破坏卡片版式；
    // 显示当天日期至少不撒谎（评论链路是 YYYY-MM-DD HH:MM，粒度本就不同）。
    expect(formatRelativeDate('2026-10-25T18:00:00', now, SH)).toBe('10-25')
  })

  it('1 分钟内的"未来"仍算刚刚（Date 头与 create_time 只有秒级精度）', () => {
    expect(formatRelativeDate('2026-10-25T12:00:30', now)).toBe('刚刚')
  })

  it('★ 绝对日期的年月日按传入时区算，不按运行环境时区', () => {
    // 2026-01-01 00:30（上海）= 2025-12-31 16:30Z。UTC 视角下它属"去年"，上海视角下才属"今年"。
    const later = new Date('2026-02-20T12:00:00+08:00')
    expect(formatRelativeDate('2026-01-01T00:30:00', later, SH)).toBe('01-01')
    expect(formatRelativeDate('2026-01-01T00:30:00', later, 0)).toBe('2025-12-31')
  })

  it('空 / 非法 ⇒ 不炸（非法给空串）', () => {
    expect(formatRelativeDate(null, now)).toBe('')
    expect(formatRelativeDate('not-a-date', now)).toBe('')
  })
})

describe('formatDateTime', () => {
  it('★ 服务端串按 +08:00 解释，再按浏览器本地时区呈现（TZ=UTC ⇒ 减 8 小时）', () => {
    // 改口径前这里是 '2026-05-09 19:43'（把上海墙上时间当成本地时间抄出来）。
    // §2.7 早就要求"按 +08:00 解释 + 按用户本地时区呈现"，T72 才把实现补齐 ⇒ 契约跟随。
    expect(formatDateTime('2026-05-09T19:43:40')).toBe('2026-05-09 11:43')
  })

  it('★ 不再把已发布的内容显示成"未来"（跨时区偏移 8 小时的回归）', () => {
    // 上海 2026-10-31 02:00 = 2026-10-30 18:00Z。UTC 用户的钟此刻是 10-30 13:00，
    // 旧实现会画出 10-31 02:00（比用户的钟快 13 小时）⇒ 用户看到"未来发布的视频"。
    expect(formatDateTime('2026-10-31T02:00:00')).toBe('2026-10-30 18:00')
  })

  it('已带偏移的串原样解析（不重复补时区）', () => {
    expect(formatDateTime('2026-10-30T06:12:59Z')).toBe('2026-10-30 06:12')
  })

  it('空 / 非法 ⇒ 空串', () => {
    expect(formatDateTime(undefined)).toBe('')
    expect(formatDateTime('x')).toBe('')
  })
})

describe('formatDateTimeCn', () => {
  it('★ 解析口径同 formatDateTime，格式走 B 站图文详情 `YYYY年M月D日 HH:mm`', () => {
    expect(formatDateTimeCn('2026-10-22T06:02:00')).toBe('2026年10月21日 22:02')
    // 月 / 日不补零（B 站是 10月1日，不是 10月01日）
    expect(formatDateTimeCn('2026-01-01T00:00:00')).toBe('2025年12月31日 16:00')
  })

  it('空 / 非法 ⇒ 空串', () => {
    expect(formatDateTimeCn(null)).toBe('')
    expect(formatDateTimeCn('x')).toBe('')
  })
})

describe('formatCommentTime（评论区：自然日 + 分钟/小时）', () => {
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

  it('★ 同一自然日内、未来超过 1 分钟也走绝对时间（这条才是"未来分支"的判别力）', () => {
    // 若删掉未来分支：同日 + 负 diff ⇒ 命中 `diff < 60_000` ⇒ "刚刚"（错）。跨日的用例杀不掉它。
    const now = new Date('2026-10-30T14:00:00+08:00')
    expect(formatCommentTime('2026-10-30T15:00:00', now, SH)).toBe('2026-10-30 15:00')
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

describe('formatClockTime（历史页「观看时刻」）', () => {
  it('同一串按不同时区给不同时刻（解析口径与呈现口径都在这一条里）', () => {
    expect(formatClockTime('2026-10-26T09:05:00', SH)).toBe('09:05')
    // TZ=UTC 下不传偏移 ⇒ 本地就是 UTC ⇒ 01:05（旧实现会原样抄出 09:05）
    expect(formatClockTime('2026-10-26T09:05:00')).toBe('01:05')
    expect(formatClockTime('2026-10-26T09:05:00', NY)).toBe('20:05') // 前一天 20:05
  })

  it('空 / 非法 ⇒ 空串', () => {
    expect(formatClockTime(null)).toBe('')
    expect(formatClockTime('x')).toBe('')
  })
})

describe('formatDayLabel（历史页日组：今天 / 昨天 / 日期）', () => {
  const now = new Date('2026-10-26T12:00:00+08:00')

  it('按本地日历日分组：今天 / 昨天 / 同年 MM-DD / 跨年 YYYY-MM-DD', () => {
    expect(formatDayLabel('2026-10-26T00:01:00', now, SH).label).toBe('今天')
    expect(formatDayLabel('2026-10-25T23:59:00', now, SH).label).toBe('昨天')
    expect(formatDayLabel('2026-10-24T18:00:00', now, SH).label).toBe('10-24')
    expect(formatDayLabel('2025-12-31T18:00:00', now, SH).label).toBe('2025-12-31')
  })

  it('★ 日界绑时区：同一条记录在 UTC 下就落到"昨天"', () => {
    // 上海 10-26 00:01 = 10-25 16:01Z；now 上海 10-26 12:00 = 04:00Z 10-26
    expect(formatDayLabel('2026-10-26T00:01:00', now, SH).key).toBe('today')
    expect(formatDayLabel('2026-10-26T00:01:00', now, 0).key).toBe('yesterday')
  })

  it('分组键不随展示文案变：跨年用例的 key 必须含年', () => {
    expect(formatDayLabel('2025-12-31T18:00:00', now, SH).key).toBe('2025-12-31')
    expect(formatDayLabel('2026-10-24T18:00:00', now, SH).key).toBe('2026-10-24')
  })

  it('时间缺失/非法归「时间未知」，不塞进今天', () => {
    expect(formatDayLabel(null, now, SH).label).toBe('时间未知')
    expect(formatDayLabel('not-a-date', now, SH).label).toBe('时间未知')
  })
})

describe('parseServerTime（契约 §2.7：无偏移串按 +08:00 解释）', () => {
  it('无偏移 ⇒ 得到正确的时间点（不是浏览器本地时区）', () => {
    expect(parseServerTime('2026-10-30T14:12:59')?.getTime())
      .toBe(Date.parse('2026-10-30T14:12:59+08:00'))
  })

  it('已带偏移 ⇒ 原样解析（不重复追加时区）', () => {
    expect(parseServerTime('2026-10-30T06:12:59Z')?.toISOString())
      .toBe('2026-10-30T06:12:59.000Z')
    expect(parseServerTime('2026-10-30T09:12:59-05:00')?.toISOString())
      .toBe('2026-10-30T14:12:59.000Z')
  })

  it('非法偏移形态 ⇒ null（宁可消失，也不许当成"无偏移"再拼一遍时区）', () => {
    // `+08` 不是合法 ISO 偏移（V8 不认）。判据是"不静默偏 8 小时"，不是"必须宽容解析"。
    expect(parseServerTime('2026-10-30T14:12:59+08')).toBeNull()
  })

  it('空格分隔的服务端串同样按 +08:00 解释（不能落回浏览器本地时区）', () => {
    expect(parseServerTime('2026-10-30 14:12:59')?.toISOString())
      .toBe('2026-10-30T06:12:59.000Z')
  })

  it('★ 测试环境时区固定为 UTC —— parseServerTime 的判别力前提', () => {
    // 本机若是 +08:00，"补 +08:00"与"按本地解析"结果相同 ⇒ 上面几条测不出实现是否真补了。
    // 由 vitest.config.ts 的 test.env.TZ 钉住；这条红 = 前提破了，不是本功能坏了。
    expect(new Date().getTimezoneOffset()).toBe(0)
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
