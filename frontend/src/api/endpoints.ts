/**
 * 端点封装。页面 × 端点矩阵见 `architecture/frontend/前端.md` §四；
 * 本层只返回**生成类型**，映射成页面形状一律在 `adapters/`（缺口 fallback 的落点）。
 */
import type { AuthorCardVM, HeroSlideVM, SearchResultVM, VideoCardVM, VideoDetailVM } from '../types/ui'
import { CONTENT_TYPE_VIDEO } from './contentTypes'
import { splitHero } from './adapters/hero'
import { toSearchResult } from './adapters/search'
import type { SearchFilters } from './adapters/searchFilters'
import { toAuthorCard } from './adapters/user'
import { toVideoCard } from './adapters/videoCard'
import { toOpusDetail, toVideoDetail } from './adapters/videoDetail'
import { getJson } from './http'

export interface HomeData {
  /** 大轮播（真封面 + 真标题） */
  slides: HeroSlideVM[]
  /** 网格卡片；**不含**轮播里已经出现过的那几条 */
  cards: VideoCardVM[]
}

/**
 * `GET /contents/recommended`（固定 12 条、**无 page 参数** ⇒ 只渲染一页，不做无限滚动）
 *
 * <p>★ 端点层**完全不碰时钟**：「现在」由卡片适配器的 `serverNow()` 决定（唯一来源）。
 * 这里曾经有 `now: Date = new Date()` —— 那等于给"现在"开第二个来源，端点自己赢一次，
 * 浏览器时钟就回到了适配器手上，而只测适配器的用例仍然全绿（`T72` 已删）。
 */
export async function loadHome(): Promise<HomeData> {
  const items = await getJson('/api/v1/contents/recommended')
  const { slides, rest } = splitHero(items, 3)
  return { slides, cards: rest.map((item) => toVideoCard(item)) }
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
 * <p>★ 相关推荐**只放视频**（B 站视频页也是视频推荐）：本仓 `/contents/recommended`
 * 含图文 ⇒ 过滤掉图文，同时保证「相关推荐同标签 SPA 路由」只会 push `/video/{id}`。
 */
export async function loadVideoPage(id: number): Promise<VideoPageData> {
  const detail = toVideoDetail(await getJson('/api/v1/contents/{id}', { path: { id } }))
  if (detail.isPost) {
    return { detail, author: null, related: [] }
  }

  const authorPromise: Promise<AuthorCardVM | null> = loadAuthorCard(detail.authorId)

  const relatedPromise: Promise<VideoCardVM[]> = getJson('/api/v1/contents/recommended')
    .then((items) =>
      items
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
