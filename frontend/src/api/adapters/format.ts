/**
 * 展示层格式化（纯函数，全部可单测）。
 *
 * <p>为什么单独一层：这些规则是**业务口径**（B 站的时长写法、万/亿缩写、相对时间），
 * 不是"实现细节"。写在组件里就只能靠截图回归，写在这里可以按业务规则先写断言。
 */

import { serverNow } from '../serverClock'

/** 两位补零（时长 / 日期 / 时刻共用一份，别在各函数里各写一遍）。 */
function pad2(value: number): string {
  return String(value).padStart(2, '0')
}

/** 时长：`mm:ss`，超过一小时 `h:mm:ss`。null / 0 / 负数 / 非法 ⇒ null（不显示角标） */
export function formatDuration(seconds: number | null | undefined): string | null {
  if (seconds === null || seconds === undefined) return null
  if (!Number.isFinite(seconds) || seconds <= 0) return null
  const total = Math.round(seconds)
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const secs = total % 60
  return hours > 0 ? `${hours}:${pad2(minutes)}:${pad2(secs)}` : `${pad2(minutes)}:${pad2(secs)}`
}

/** 计数：万 / 亿（B 站口径，保留一位小数并去掉 `.0`）。null / 负数 ⇒ `-` */
export function formatCount(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value) || value < 0) return '-'
  if (value < 10_000) return String(value)
  if (value < 100_000_000) return `${trimDecimal(value / 10_000)}万`
  return `${trimDecimal(value / 100_000_000)}亿`
}

function trimDecimal(value: number): string {
  const rounded = Math.round(value * 10) / 10
  return Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(1)
}

/**
 * 硬币余额（**整数口径**）：`1.5 ⇒ 1`、`1.9 ⇒ 1`、`2.0 ⇒ 2`。
 *
 * <h2>★ 为什么取整、为什么是向下</h2>
 * 硬币在后端存 `coin_deci`（**十分位**：`15` = 1.5 枚），只在 VO 出口换算一次成一位小数 ——
 * 前端拿到的 `1.5` 是**真实余额**，不是显示噪音，所以**不能在适配层取整**
 * （投币弹层要按 B 站移动端口径显示 `1.5`，并且 `balance < 2` 的判定要用真值）。
 *
 * 取整只发生在**这两个展示位**：顶栏头像卡与空间页「硬币」卡。
 * 依据是实测的 B 站口径 —— 个人主页与桌面端各处的硬币数**只显示整数**，
 * 只有移动端投币页能看到小数。向下而非四舍五入：`1.9` 枚时显示 `2` 会让
 * "我有 2 枚" 与 "投 2 枚被拒（余额不足）" 自相矛盾，宁可少显示半枚。
 *
 * <p>缺失 / 非有限值 ⇒ `-`（与 {@link formatCount} 同口径，不把脏值画成 `0`）。
 */
export function formatCoinInt(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '-'
  return String(Math.floor(Math.max(0, value)))
}

/**
 * 服务端时间串 → Date（**按契约 §2.7 的 Asia/Shanghai 语义**解析）。
 *
 * <p>对外时间是 `yyyy-MM-ddTHH:mm:ss`、本地时间、**不引入偏移**。直接 `new Date(iso)`
 * 会把它当**浏览器本地时区** ⇒ 跨时区时偏 8 小时，甚至把已发布的内容显示成"未来"。
 * 这里显式补 `+08:00`，先拿到**正确的时间点**（epoch），之后才能按用户本地时区呈现。
 *
 * <p>已带偏移（`Z` / `+08:00`）的串原样解析；非法 ⇒ null。
 */
export function parseServerTime(iso: string | null | undefined): Date | null {
  if (!iso) return null
  // 只给"含时间部分且无偏移"的串补时区：纯日期串（`2026-10-30`）按 ES 规范是 UTC 午夜，补了反而非法。
  // 偏移面覆盖 `Z` / `+08:00` / `+08` / `-0500`；空格分隔（`2026-10-30 14:12:59`）同样按服务端时间解释。
  const hasOffset = /(?:Z|[+-]\d{2}(?::?\d{2})?)$/i.test(iso)
  const hasTime = /[T ]\d{2}:\d{2}/.test(iso)
  const text = hasTime && !hasOffset ? `${iso}+08:00` : iso
  const date = new Date(text)
  return Number.isNaN(date.getTime()) ? null : date
}

/**
 * 相对发布时间（**列表卡口径**）：`刚刚` / `N分钟前` / `N小时前` / `N天前` / `MM-DD` / `YYYY-MM-DD`。
 *
 * <p>`now` 默认取**服务器时钟**（{@code serverNow}），不是浏览器时钟：见 `serverClock.ts`。
 * 显式传入是为了可测：断言按业务口径推，不按当前时钟猜。
 *
 * <p>`offsetMinutes` 只影响**绝对日期**两档（同年 `MM-DD` / 跨年 `YYYY-MM-DD`）与"是否同年"的判定：
 * 不传 ⇒ 按各自时刻的本地偏移取（DST 安全）；传了 ⇒ 钉死这个偏移（用例钉时区用）。
 *
 * <p>★ 迟于 `now` 超过 1 分钟 ⇒ 走绝对日期（`T72` 与评论口径统一）：相对时间没有"未来"
 * 这一档，把负差值算成"刚刚"是明确的错误分支（`formatCommentTime` 早就这么判）。
 *
 * <p>⚠️ 评论时间**不用它**（规则不同：评论跨自然日就变绝对时间，且要精确到分钟）——
 * 见 {@link formatCommentTime}。两者刻意不合并：合并会让首页卡多出 `HH:mm`，
 * 或让评论区丢掉"今天"的日界。
 */
export function formatRelativeDate(
  iso: string | null | undefined,
  now: Date = serverNow(),
  offsetMinutes?: number,
): string {
  const date = parseServerTime(iso)
  if (!date) return ''
  const diff = now.getTime() - date.getTime()
  if (diff < -FUTURE_TOLERANCE_MS) return formatWallDate(date.getTime(), now.getTime(), offsetMinutes)
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}小时前`
  const days = Math.floor(diff / 86_400_000)
  if (days < 30) return `${days}天前`
  return formatWallDate(date.getTime(), now.getTime(), offsetMinutes)
}

/**
 * 绝对日期：`YYYY-MM-DD HH:mm`（播放页的发布信息）。非法 ⇒ ``。
 *
 * <p>解析按 §2.7（`parseServerTime`，服务端串 = 上海墙上时间），**呈现按浏览器本地时区**
 * （`getFullYear()` 等本地 getter 本身按目标时刻取偏移 ⇒ DST 安全，不需要额外传 offset）。
 */
export function formatDateTime(iso: string | null | undefined): string {
  const date = parseServerTime(iso)
  if (!date) return ''
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())} ${pad2(date.getHours())}:${pad2(date.getMinutes())}`
}

/**
 * 绝对日期（B 站图文详情的口径）：`2026年10月22日 06:02`。非法 ⇒ ``。
 *
 * <p>播放页沿用 {@link formatDateTime}（`2026-05-09 19:43`），两处**刻意不统一**：
 * B 站图文详情就是中文年月日，而视频页的既有口径已经按 ISO 风格校准过。
 * 解析 / 呈现口径与 {@link formatDateTime} 完全一致（只是格式不同）。
 */
export function formatDateTimeCn(iso: string | null | undefined): string {
  const date = parseServerTime(iso)
  if (!date) return ''
  return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日 ${pad2(date.getHours())}:${pad2(date.getMinutes())}`
}

/**
 * "未来"的容忍窗口：`Date` 头与 `create_time` 都只有秒级精度，
 * 刚发布的评论 / 内容可能算出 `-1s`。1 分钟内的"未来"仍按"刚刚"处理，不算异常。
 *
 * <p>★ 评论与列表卡**共用这一个阈值**（`T72` 统一口径）：超过它就不再假装知道相对时间。
 */
const FUTURE_TOLERANCE_MS = 60_000

/**
 * 某个时刻在**浏览器本地时区**的偏移（分钟，`+08:00` ⇒ 480）。
 *
 * <h2>★ 为什么必须按"目标时刻"取，不能取"此刻"</h2>
 * 跨 DST 的时区里，同一个浏览器在三月与十一月的偏移不同。用"此刻"的偏移去换算半年前的时间
 * 会差 1 小时 —— 恰好能把 `00:30` 的事件算进前一天。`getTimezoneOffset()` 本身就是按
 * **调用它的那个 instant** 算的 ⇒ 传入目标 epoch 即得正确值（`T72` 修的就是这一点）。
 */
function offsetAt(epochMs: number): number {
  return -new Date(epochMs).getTimezoneOffset()
}

/** 传了 `offsetMinutes` 就用它（用例钉时区），否则按目标时刻取本地偏移（DST 安全）。 */
function resolveOffset(epochMs: number, explicit?: number): number {
  return explicit ?? offsetAt(epochMs)
}

/** 某时区下的墙上日历分量（只依赖传入偏移，不读运行环境时区）。 */
function wallParts(epochMs: number, offsetMinutes: number) {
  const wall = new Date(epochMs + offsetMinutes * 60_000)
  return {
    year: wall.getUTCFullYear(),
    month: wall.getUTCMonth() + 1,
    day: wall.getUTCDate(),
    hour: wall.getUTCHours(),
    minute: wall.getUTCMinutes(),
  }
}

/** 把 epoch 换成"某时区下的墙上日序号"（用于判自然日，不依赖运行环境时区）。 */
function wallDayIndex(epochMs: number, offsetMinutes: number): number {
  return Math.floor((epochMs + offsetMinutes * 60_000) / 86_400_000)
}

/** 某时区下的 `YYYY-MM-DD HH:MM`（只依赖传入的偏移，不读运行环境时区）。 */
function formatWallDateTime(epochMs: number, offsetMinutes: number): string {
  const { year, month, day, hour, minute } = wallParts(epochMs, offsetMinutes)
  return `${year}-${pad2(month)}-${pad2(day)} ${pad2(hour)}:${pad2(minute)}`
}

/**
 * 某时区下的绝对日期：与 `reference` **同年** ⇒ `MM-DD`，否则 `YYYY-MM-DD`。
 *
 * <p>"同年"要按两个时刻**各自的**墙上年比较（跨年零点前后，两边可能不在同一年）。
 */
function formatWallDate(epochMs: number, referenceEpochMs: number, explicit?: number): string {
  const parts = wallParts(epochMs, resolveOffset(epochMs, explicit))
  const monthDay = `${pad2(parts.month)}-${pad2(parts.day)}`
  const referenceYear = wallParts(referenceEpochMs, resolveOffset(referenceEpochMs, explicit)).year
  return parts.year === referenceYear ? monthDay : `${parts.year}-${monthDay}`
}

/**
 * 评论发布时间（B 站 **web 端**评论区口径）。
 *
 * <h2>规则（按 2026-10-30 的实测截图定）</h2>
 * <ul>
 *   <li>同一**自然日**内：`刚刚`（&lt;1 分钟）/ `N分钟前`（&lt;1 小时）/ `N小时前`；</li>
 *   <li>跨自然日：`YYYY-MM-DD HH:MM`（**恒带年**，不做"当年省年"）；</li>
 *   <li>迟于 `now` 超过 1 分钟（客户端时钟慢 / 数据脏）：走绝对时间 ——
 *       **不假装知道相对时间**（旧实现把负差值算成"刚刚"，这是明确的错误分支）。</li>
 * </ul>
 *
 * <h2>★ 为什么日界按"offset"算而不是 {@code getFullYear()}</h2>
 * 自然日必须绑一个时区才有意义，而"今天"与"这条时间"可能落在不同的偏移上（DST 边界）。
 * 默认按**各自时刻**的本地偏移算（DST 安全）；显式传入 `offsetMinutes` 是为了**可测** ——
 * 同一组用例可以同时钉 `+08:00` 与 `-05:00` 下的日界，不依赖跑测试那台机器的时区。
 *
 * <p>`now` 默认取服务器时钟（{@code serverNow}），不是浏览器时钟：见 `serverClock.ts`。
 */
export function formatCommentTime(
  iso: string | null | undefined,
  now: Date = serverNow(),
  offsetMinutes?: number,
): string {
  const date = parseServerTime(iso)
  if (!date) return ''
  const dateOffset = resolveOffset(date.getTime(), offsetMinutes)
  const nowOffset = resolveOffset(now.getTime(), offsetMinutes)
  const diff = now.getTime() - date.getTime()
  if (diff < -FUTURE_TOLERANCE_MS) return formatWallDateTime(date.getTime(), dateOffset)
  if (wallDayIndex(date.getTime(), dateOffset) !== wallDayIndex(now.getTime(), nowOffset)) {
    return formatWallDateTime(date.getTime(), dateOffset)
  }
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}分钟前`
  return `${Math.floor(diff / 3_600_000)}小时前`
}

/**
 * 观看时刻 `HH:mm`（历史页时间轴的左列）。非法 / 空 ⇒ ``。
 *
 * <p>解析按 §2.7、呈现按**用户本地时区**（`offsetMinutes` 同上：只给用例钉时区）。
 */
export function formatClockTime(iso: string | null | undefined, offsetMinutes?: number): string {
  const date = parseServerTime(iso)
  if (!date) return ''
  const { hour, minute } = wallParts(date.getTime(), resolveOffset(date.getTime(), offsetMinutes))
  return `${pad2(hour)}:${pad2(minute)}`
}

/**
 * 日组标签（历史页口径）：`今天` / `昨天` / 同年 `MM-DD` / 跨年 `YYYY-MM-DD`；非法 ⇒ `时间未知`。
 *
 * <p>按**用户本地日历日**比较（不是"距今多少小时"）：23:59 与次日 00:01 必须分两组。
 * 两边各按自己时刻的本地偏移算（DST 安全）；`offsetMinutes` 同 {@link formatClockTime}。
 *
 * <p>为什么放在 format 层而不是历史适配器：分组键是**日期展示口径**，与历史页的字段映射无关；
 * 放这里，"自然日怎么算"全仓只有一份实现（评论 / 历史共用 {@link wallDayIndex}）。
 * 未来时刻（`days < 0`）落绝对日期 —— 与 {@link formatRelativeDate} 同一条判据。
 */
export function formatDayLabel(
  iso: string | null | undefined,
  now: Date,
  offsetMinutes?: number,
): { key: string; label: string } {
  const date = parseServerTime(iso)
  if (!date) return { key: 'unknown', label: '时间未知' }
  const dateOffset = resolveOffset(date.getTime(), offsetMinutes)
  const nowOffset = resolveOffset(now.getTime(), offsetMinutes)
  const days = wallDayIndex(now.getTime(), nowOffset) - wallDayIndex(date.getTime(), dateOffset)
  if (days === 0) return { key: 'today', label: '今天' }
  if (days === 1) return { key: 'yesterday', label: '昨天' }
  const parts = wallParts(date.getTime(), dateOffset)
  const monthDay = `${pad2(parts.month)}-${pad2(parts.day)}`
  const key = `${parts.year}-${monthDay}`
  const nowYear = wallParts(now.getTime(), nowOffset).year
  return { key, label: parts.year === nowYear ? monthDay : key }
}

/**
 * 简介展示值：把后端的**空简介占位 `-`** 视为空串。
 *
 * <p>`UploadController.normalizeDescription` 对空简介写 {@code "-"}（TV 原样）；
 * 展示层如果把 {@code "-"} 原样画出来，用户会以为作者真的只写了「-」。
 * 图文卡 / 详情 / 动态卡用它；视频页因此也从「-」变成「这个内容还没有简介」。
 */
export function cleanDescription(value: string | null | undefined): string {
  const text = value?.trim() ?? ''
  return text === '-' ? '' : text
}

/**
 * 头像占位：昵称首字（大写）。
 *
 * <p>后端没有头像字段（GAP 台账）⇒ 全站用「昵称首字 + 渐变底」代替，
 * 与 B 站默认头像的**位置/尺寸**一致，但不引用不存在的 URL。
 * 空昵称回 `U`（不画一个空圆）。
 */
export function initialOf(name: string | null | undefined): string {
  const chars = Array.from((name ?? '').trim())
  return (chars[0] ?? 'U').toUpperCase()
}
