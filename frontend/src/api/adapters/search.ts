import type { components } from '../../types/api'
import type { SearchResultVM } from '../../types/ui'
import { toVideoCard } from './videoCard'

/** `PageResultContentVO` → 搜索结果页（Page 分页，**不是** cursor —— 两种分页在本仓并存） */
export function toSearchResult(
  result: components['schemas']['PageResultContentVO'],
  keyword: string,
  now: Date = new Date(),
): SearchResultVM {
  const list = result.list ?? []
  return {
    items: list.map((item) => toVideoCard(item, now)),
    keyword,
    page: result.page ?? 1,
    pageSize: result.pageSize ?? list.length,
    total: result.total ?? list.length,
    totalPages: result.totalPages ?? 0,
  }
}
