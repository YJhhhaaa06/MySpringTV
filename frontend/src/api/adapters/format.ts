/**
 * 展示层格式化（纯函数，全部可单测）。
 *
 * <p>为什么单独一层：这些规则是**业务口径**（B 站的时长写法、万/亿缩写、相对时间），
 * 不是"实现细节"。写在组件里就只能靠截图回归，写在这里可以按业务规则先写断言。
 */

import { serverNow } from '../serverClock'

/** 时长：`mm:ss`，超过一小时 `h:mm:ss`。null / 0 / 负数 / 非法 ⇒ null（不显示角标） */
export function formatDuration(seconds: number | null | undefined): string | null {
  if (seconds === null || seconds === undefined) return null
  if (!Number.isFinite(seconds) || seconds <= 0) return null
  const total = Math.round(seconds)
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const secs = total % 60
  const pad = (value: number) => String(value).padStart(2, '0')
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(secs)}` : `${pad(minutes)}:${pad(secs)}`
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

/** ISO 字符串 → Date（**按浏览器本地时区**解析）；非法 / 空 ⇒ null（不抛错：一条脏数据不该把整页打挂） */
export function parseDate(iso: string | null | undefined): Date | null {
  if (!iso) return null
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? null : date
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
 * <p>`now` 显式传入是为了可测：断言按业务口径推，不按当前时钟猜。
 *
 * <p>⚠️ 评论时间**不用它**（规则不同：评论跨自然日就变绝对时间，且要精确到分钟）——
 * 见 {@link formatCommentTime}。两者刻意不合并：合并会让首页卡多出 `HH:mm`，
 * 或让评论区丢掉"今天"的日界。
 */
export function formatRelativeDate(iso: string | null | undefined, now: Date = new Date()): string {
  const date = parseDate(iso)
  if (!date) return ''
  const diff = now.getTime() - date.getTime()
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}小时前`
  const days = Math.floor(diff / 86_400_000)
  if (days < 30) return `${days}天前`
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  if (date.getFullYear() === now.getFullYear()) return `${month}-${day}`
  return `${date.getFullYear()}-${month}-${day}`
}

/** 绝对日期：`YYYY-MM-DD HH:mm`（播放页的发布信息）。非法 ⇒ `` */
export function formatDateTime(iso: string | null | undefined): string {
  const date = parseDate(iso)
  if (!date) return ''
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/**
 * 绝对日期（B 站图文详情的口径）：`2026年10月22日 06:02`。非法 ⇒ ``。
 *
 * <p>播放页沿用 {@link formatDateTime}（`2026-05-09 19:43`），两处**刻意不统一**：
 * B 站图文详情就是中文年月日，而视频页的既有口径已经按 ISO 风格校准过。
 */
export function formatDateTimeCn(iso: string | null | undefined): string {
  const date = parseDate(iso)
  if (!date) return ''
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日 ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/**
 * "未来"的容忍窗口：`Date` 头与 `create_time` 都只有秒级精度，
 * 刚发布的评论可能算出 `-1s`。1 分钟内的"未来"仍按"刚刚"处理，不算异常。
 */
const FUTURE_TOLERANCE_MS = 60_000

/** 本地相对 UTC 的分钟偏移（+08:00 ⇒ 480）。`getTimezoneOffset()` 的符号与之相反。 */
function localOffsetMinutes(): number {
  return -new Date().getTimezoneOffset()
}

/** 把 epoch 换成"某时区下的墙上日序号"（用于判自然日，不依赖运行环境时区）。 */
function wallDayIndex(epochMs: number, offsetMinutes: number): number {
  return Math.floor((epochMs + offsetMinutes * 60_000) / 86_400_000)
}

/** 某时区下的 `YYYY-MM-DD HH:MM`（只依赖传入的偏移，不读运行环境时区）。 */
function formatWallDateTime(epochMs: number, offsetMinutes: number): string {
  const wall = new Date(epochMs + offsetMinutes * 60_000)
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${wall.getUTCFullYear()}-${pad(wall.getUTCMonth() + 1)}-${pad(wall.getUTCDate())} ${pad(wall.getUTCHours())}:${pad(wall.getUTCMinutes())}`
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
 * <h2>★ 为什么日界按"传入的 offset"而不是 {@code getFullYear()}</h2>
 * 自然日必须绑一个时区才有意义。默认取浏览器当前偏移（web 的正确做法：按用户本地时区）；
 * 显式传入是为了**可测**——同一组用例可以同时钉 `+08:00` 与 `-05:00` 下的日界，
 * 不依赖跑测试那台机器的时区。
 *
 * <p>`now` 默认取服务器时钟（{@code serverNow}），不是浏览器时钟：见 `serverClock.ts`。
 */
export function formatCommentTime(
  iso: string | null | undefined,
  now: Date = serverNow(),
  offsetMinutes: number = localOffsetMinutes(),
): string {
  const date = parseServerTime(iso)
  if (!date) return ''
  const diff = now.getTime() - date.getTime()
  if (diff < -FUTURE_TOLERANCE_MS) return formatWallDateTime(date.getTime(), offsetMinutes)
  if (wallDayIndex(date.getTime(), offsetMinutes) !== wallDayIndex(now.getTime(), offsetMinutes)) {
    return formatWallDateTime(date.getTime(), offsetMinutes)
  }
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}分钟前`
  return `${Math.floor(diff / 3_600_000)}小时前`
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
