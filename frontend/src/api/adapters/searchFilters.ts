/**
 * 搜索页的筛选 / 排序口径（GAP-10，2026-10-27）。
 *
 * <p>后端契约：`GET /api/v1/contents?keyword=&type=VIDEO|POST&order=LATEST|PLAY`
 * ——两个参数都是**可选枚举、大小写敏感**，非法值是 400（不是静默回落）。
 *
 * <p>所以这一层是**白名单闸门**，理由有两条，都是"页面会被 URL 打死"级别的：
 * <ol>
 *   <li><b>手改 URL 不能让整页 400</b>：`?type=video`（小写）在服务端是 400，
 *       用户只会在地址栏改一个字母，不该看到"搜索失败"；这里把非法值当"不筛"。</li>
 *   <li><b>图文永远不发 PLAY</b>：图文（`POST`）的 `content.play_count` 结构性恒 0
 *       （`/play` 只收视频）⇒ "最多播放"在图文上没有意义（用户 2026-10-27 拍板：不渲染该按钮）。
 *       即使 URL 被手写成 `type=POST&order=PLAY`，也不发给后端 —— 让"退化路径"从客户端不可达。</li>
 * </ol>
 *
 * <p>缺省值**一并归一成 `undefined`**：`LATEST` 与不传逐字节等价（后端用例钉住），
 * 若保留 `LATEST`，排序条的激活态会认不出来（它的两个选项是 `undefined` / `PLAY`）。
 * 缺省值也**不写进 URL**：`综合` = 无 `type`，`最新发布` = 无 `order`。URL 因此保持短、可对比、可分享。
 */
import type { operations } from '../../types/api'

/** 生成类型里该端点的 query（类型直接取自 spec，避免两处手写漂移）。 */
type KeywordSearchQuery = NonNullable<operations['keywordSearch']['parameters']['query']>

/** 与后端 `ContentType` 同名同值（大小写敏感）。 */
export type SearchType = NonNullable<KeywordSearchQuery['type']>

/** 与后端 `SearchOrder` 同名同值（大小写敏感）。 */
export type SearchOrder = NonNullable<KeywordSearchQuery['order']>

export interface SearchFilters {
  /** `undefined` = 综合（不筛） */
  type?: SearchType
  /** `undefined` = 最新发布 */
  order?: SearchOrder
}

const SEARCH_TYPES: readonly SearchType[] = ['VIDEO', 'POST']
const SEARCH_ORDERS: readonly SearchOrder[] = ['LATEST', 'PLAY']

/** `route.query` 的原始值可能是数组（`?type=a&type=b`）⇒ 只认第一个。 */
function firstValue(value: unknown): unknown {
  return Array.isArray(value) ? value[0] : value
}

function pick<T extends string>(value: unknown, allowed: readonly T[]): T | undefined {
  return typeof value === 'string' && (allowed as readonly string[]).includes(value) ? (value as T) : undefined
}

/**
 * 归一：非法值 → `undefined`（= 不筛 / 最新）；`LATEST` → `undefined`；图文 → 丢掉 `order`。
 *
 * <p>URL 解析和"写回 URL"共用这一份，避免两条路径各判一次、口径漂移
 * （2026-10-27 审查实测过：`parse` 保留 `LATEST` 而 `toSearchQuery` 丢弃 ⇒ 排序条无激活态）。
 */
export function normalizeSearchFilters(input: { type?: unknown; order?: unknown }): SearchFilters {
  const type = pick(input.type, SEARCH_TYPES)
  const order = pick(input.order, SEARCH_ORDERS)
  return { type, order: type === 'POST' || order === 'LATEST' ? undefined : order }
}

/** `route.query` → 筛选。**非法值静默丢弃**（见文件头第 1 条）。 */
export function parseSearchFilters(query: Record<string, unknown>): SearchFilters {
  return normalizeSearchFilters({ type: firstValue(query.type), order: firstValue(query.order) })
}

/** 筛选 → 要写回 URL 的 query（**缺省键不出现**；归一是同一份）。 */
export function toSearchQuery(filters: { type?: unknown; order?: unknown }): Record<string, string> {
  const { type, order } = normalizeSearchFilters(filters)
  const query: Record<string, string> = {}
  if (type) query.type = type
  if (order) query.order = order
  return query
}
