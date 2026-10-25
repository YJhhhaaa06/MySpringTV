/**
 * 端点封装。页面 × 端点矩阵见 `architecture/frontend/前端.md` §四；
 * 本层只返回**生成类型**，映射成页面形状一律在 `adapters/`（缺口 fallback 的落点）。
 */
import type { components } from '../types/api'
import type { AuthorCardVM, HeroSlideVM, SearchResultVM, VideoCardVM, VideoDetailVM } from '../types/ui'
import { splitHero } from './adapters/hero'
import { toSearchResult } from './adapters/search'
import { toAuthorCard } from './adapters/user'
import { toVideoCard } from './adapters/videoCard'
import { toVideoDetail } from './adapters/videoDetail'
import { getJson } from './http'

export interface HomeData {
  /** 大轮播（真封面 + 真标题） */
  slides: HeroSlideVM[]
  /** 网格卡片；**不含**轮播里已经出现过的那几条 */
  cards: VideoCardVM[]
}

/** `GET /contents/recommended`（固定 12 条、**无 page 参数** ⇒ 只渲染一页，不做无限滚动） */
export async function loadHome(now: Date = new Date()): Promise<HomeData> {
  const items = await getJson('/api/v1/contents/recommended')
  const { slides, rest } = splitHero(items, 3)
  return { slides, cards: rest.map((item) => toVideoCard(item, now)) }
}

/** `GET /contents?keyword=&page=&pageSize=`（**搜索专用**，keyword 必填；空白会 400） */
export async function searchContents(
  keyword: string,
  page: number,
  pageSize = 20,
  now: Date = new Date(),
): Promise<SearchResultVM> {
  const result = await getJson('/api/v1/contents', { query: { keyword, page, pageSize } })
  return toSearchResult(result, keyword, now)
}

export interface VideoPageData {
  detail: VideoDetailVM
  /** UP 主资料：失败/缺失时为 null（页面不该因为侧栏挂了就打不开） */
  author: AuthorCardVM | null
  related: VideoCardVM[]
}

/** 播放页：详情 →（并行）作者资料 + 相关推荐 */
export async function loadVideoPage(id: number, now: Date = new Date()): Promise<VideoPageData> {
  const detail = toVideoDetail(await getJson('/api/v1/contents/{id}', { path: { id } }))

  const authorPromise: Promise<AuthorCardVM | null> =
    detail.authorId > 0
      ? getJson('/api/v1/users/{id}', { path: { id: detail.authorId } })
          .then((profile: components['schemas']['ProfileVO']) => toAuthorCard(profile))
          .catch(() => null)
      : Promise.resolve(null)

  const relatedPromise: Promise<VideoCardVM[]> = getJson('/api/v1/contents/recommended')
    .then((items) =>
      items
        .filter((item) => item.id !== id)
        .slice(0, 6)
        .map((item) => toVideoCard(item, now)),
    )
    .catch(() => [])

  const [author, related] = await Promise.all([authorPromise, relatedPromise])
  return { detail, author, related }
}
