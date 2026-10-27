/**
 * 展示层格式化（纯函数，全部可单测）。
 *
 * <p>为什么单独一层：这些规则是**业务口径**（B 站的时长写法、万/亿缩写、相对时间），
 * 不是"实现细节"。写在组件里就只能靠截图回归，写在这里可以按业务规则先写断言。
 */

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

/** ISO 字符串 → Date；非法 / 空 ⇒ null（不抛错：一条脏数据不该把整页打挂） */
export function parseDate(iso: string | null | undefined): Date | null {
  if (!iso) return null
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? null : date
}

/**
 * 相对发布时间（B 站口径）：`刚刚` / `N分钟前` / `N小时前` / `N天前` / `MM-DD` / `YYYY-MM-DD`。
 *
 * <p>`now` 显式传入是为了可测：断言按业务口径推，不按当前时钟猜。
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
