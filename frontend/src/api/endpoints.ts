/**
 * 端点封装。页面 × 端点矩阵见 `architecture/frontend/前端.md` §四；
 * 本层只返回**生成类型**，映射成页面形状一律在 `adapters/`（缺口 fallback 的落点）。
 */
import type { components } from '../types/api'
import type { AuthorCardVM, HeroSlideVM, SearchResultVM, VideoCardVM, VideoDetailVM } from '../types/ui'
import { CONTENT_TYPE_VIDEO } from './contentTypes'
import { splitHero } from './adapters/hero'
import { toSearchResult } from './adapters/search'
import type { SearchFilters } from './adapters/searchFilters'
import { toAuthorCard } from './adapters/user'
import { toVideoCard } from './adapters/videoCard'
import { toOpusDetail, toVideoDetail } from './adapters/videoDetail'
import { getJson } from './http'

/** 推荐流一页（端点层内部形状；页面只吃 VM） */
export interface RecommendPageData {
  cards: VideoCardVM[]
  /** null = 到底；**不**用"本页不满"去猜 */
  nextCursor: string | null
}

export interface HomeData extends RecommendPageData {
  /** 大轮播（真封面 + 真标题） */
  slides: HeroSlideVM[]
}

/** 后端缺省也是 20；这里显式传，让"一页多少条"在调用点可见。 */
const RECOMMEND_PAGE_SIZE = 20

/**
 * `GET /contents/recommended` → `{list, nextCursor}`（游标信封，见后端
 * `architecture/domain/content/content-推荐流.md`）。
 *
 * <p>★ 端点层**完全不碰时钟**：「现在」由卡片适配器的 `serverNow()` 决定（唯一来源）。
 * 这里曾经有 `now: Date = new Date()` —— 那等于给"现在"开第二个来源，端点自己赢一次，
 * 浏览器时钟就回到了适配器手上，而只测适配器的用例仍然全绿（`T72` 已删）。
 */
async function fetchRecommendPage(cursor?: string): Promise<{
  items: components['schemas']['ContentVO'][]
  nextCursor: string | null
}> {
  const page = await getJson('/api/v1/contents/recommended', {
    query: {
      pageSize: RECOMMEND_PAGE_SIZE,
      ...(cursor ? { cursor } : {}),
    },
  })
  // `nextCursor` 可能是缺省 / 显式 null；两者都归一成 null（= 到底）
  return { items: page.list ?? [], nextCursor: page.nextCursor ?? null }
}

/** 首屏：**只有第一页**参与轮播拆分（后续页一律进网格，否则第 2 页前 3 条会被抽走）。 */
export async function loadHome(): Promise<HomeData> {
  const page = await fetchRecommendPage()
  const { slides, rest } = splitHero(page.items, 3)
  return { slides, cards: rest.map((item) => toVideoCard(item)), nextCursor: page.nextCursor }
}

/**
 * 续页：按 `cursor` 原样回传（前端不解析、不拼装）。
 *
 * <p>★ 这里**不调 `splitHero`**：轮播只属于首屏。若续页也拆，带封面的前 3 条会从网格里
 * 消失（而轮播位在页面顶部，用户根本看不到它们"去了哪"）。
 */
export async function loadHomeMore(cursor: string): Promise<RecommendPageData> {
  const page = await fetchRecommendPage(cursor)
  return { cards: page.items.map((item) => toVideoCard(item)), nextCursor: page.nextCursor }
}

/**
 * `GET /contents?keyword=&type=&order=&page=&pageSize=`（**搜索专用**，keyword 必填；空白会 400）。
 *
 * <p>GAP-10（2026-10-27）：`type`（VIDEO/POST）与 `order`（LATEST/PLAY）都是可选枚举、
 * 大小写敏感、非法值 400 ⇒ 由 `adapters/searchFilters` 归一后才允许进 URL。
 */
export async function searchContents(
  keyword: string,
  page: number,
  filters: SearchFilters = {},
  pageSize = 20,
): Promise<SearchResultVM> {
  const result = await getJson('/api/v1/contents', {
    query: { keyword, page, pageSize, ...filters },
  })
  return toSearchResult(result, keyword)
}

export interface VideoPageData {
  detail: VideoDetailVM
  /** UP 主资料：失败/缺失时为 null（页面不该因为侧栏挂了就打不开） */
  author: AuthorCardVM | null
  related: VideoCardVM[]
}

/**
 * `GET /users/{id}` → 侧栏 UP 主卡。失败返回 null（侧栏降级为空，不阻塞页面）。
 *
 * <p>单独抽出来是因为它有两个调用点：播放页首次装载，以及**登录 / 退出后重读**
 * （`isFollowed` 匿名时是 `null`，登录后必须重读，否则关注按钮停在假象上）。
 */
export async function loadAuthorCard(userId: number): Promise<AuthorCardVM | null> {
  if (!Number.isInteger(userId) || userId <= 0) return null
  try {
    return toAuthorCard(await getJson('/api/v1/users/{id}', { path: { id: userId } }))
  } catch {
    return null
  }
}

/**
 * 播放页：详情 →（并行）作者资料 + 相关推荐。
 *
 * <p>★ 图文**不在播放页版式里渲染**：收藏夹等没有 `type` 的入口会先落 `/video/{id}`，
 * 这里取到详情发现 `isPost` 就提前返回（不再白花作者 / 相关两次请求），由调用方
 * `VideoPage` 重定向到 `/opus/{id}`（GAP-16 的前端兜底）。
 * <p>★ 相关推荐**只放视频**（B 站视频页也是视频推荐）：请求带 `?type=1`（数字口径，
 * 不是搜索那个 `VIDEO`）由服务端按视频索引取池；客户端仍留一道 `type` 过滤 —— 契约漂移时
 * 图文会经同标签 SPA 路由落进播放页版式，那是坏得更明显的错。
 */
export async function loadVideoPage(id: number): Promise<VideoPageData> {
  const detail = toVideoDetail(await getJson('/api/v1/contents/{id}', { path: { id } }))
  if (detail.isPost) {
    return { detail, author: null, related: [] }
  }

  const authorPromise: Promise<AuthorCardVM | null> = loadAuthorCard(detail.authorId)

  const relatedPromise: Promise<VideoCardVM[]> = getJson('/api/v1/contents/recommended', {
    query: { type: 1 },
  })
    .then((page) =>
      (page.list ?? [])
        .filter((item) => item.id !== id && item.type === CONTENT_TYPE_VIDEO)
        .slice(0, 6)
        .map((item) => toVideoCard(item)),
    )
    .catch(() => [])

  const [author, related] = await Promise.all([authorPromise, relatedPromise])
  return { detail, author, related }
}

/**
 * 图文详情（`/opus/{id}`）：只取详情。
 *
 * <p>不需要 `GET /users/{id}`：图文详情的作者行只有首字头像 + 名字 + 时间（没有粉丝数 /
 * 关注按钮）；也不要 `recommended`（B 站图文详情没有相关推荐侧栏）。
 */
export async function loadOpusPage(id: number): Promise<VideoDetailVM> {
  return toOpusDetail(await getJson('/api/v1/contents/{id}', { path: { id } }))
}
