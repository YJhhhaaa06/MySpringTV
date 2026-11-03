/**
 * 用户空间（UP 主主页）端点。
 *
 * <h2>一个端点装两件事</h2>
 * `GET /users/{id}?page=&pageSize=` 同时返回**资料**（粉丝/关注/是否已关注）与
 * **作品的一页**（`contentPage`）—— 所以翻页也是再调它（翻页会顺带重读资料，可接受）。
 *
 * <p>公开收藏夹是另一条：`GET /users/{id}/favorite-folders` 只给**公开**夹的
 * `{id, name, itemCount}`（私密夹后端不返回，这块不用前端过滤）。
 */
import type { components } from '../types/api'
import type {
  DynamicPageVM,
  PublicFolderVM,
  SpaceHeaderVM,
  SpaceWorksVM,
  VideoCardVM,
} from '../types/ui'
import { currentUserId } from '../auth/session'
import { toDynamicPage } from './adapters/feed'
import { toFavoriteItemCard, toPublicFolder, toSpaceHeader, toSpaceWorks } from './adapters/space'
import { getJson } from './http'
import { serverNow } from './serverClock'

export interface SpaceData {
  header: ReturnType<typeof toSpaceHeader>
  works: SpaceWorksVM
  /**
   * 同一份 `contentPage` 的**时间线视图**（`R-31`）：空间「动态」tab 与
   * `/dynamic?author=` 都用它；与 `works` 只差呈现，不额外请求。
   */
  timeline: DynamicPageVM
  /** 只有第一页才给"最新三条"做大卡（B 站的代表作是 UP 自选的，本仓没有这个字段） */
  featured: VideoCardVM[]
}

/** `GET /users/{id}?page=&pageSize=`：资料 + 作品一页 */
export async function loadSpace(userId: number, page = 1, pageSize = 10): Promise<SpaceData> {
  const profile: components['schemas']['ProfileVO'] = await getJson('/api/v1/users/{id}', {
    path: { id: userId }, // 注意：spec 里 {id} 声明成 string，按数字传也能命中
    query: { page, pageSize },
  })
  // "现在"只取一次：works / timeline 是同一响应、同一页，不能各取一次 serverNow
  const now = serverNow()
  const works = toSpaceWorks(profile.contentPage, now)
  return {
    header: toSpaceHeader(profile, currentUserId()),
    works,
    timeline: toDynamicPage(profile.contentPage, now),
    featured: works.page === 1 ? works.items.slice(0, 3) : [],
  }
}

/**
 * `GET /users/{id}?pageSize=1`：**只要头部计数**（动态页左卡用）。
 *
 * <p>为什么不用 `GET /users/me`：`UserInfoVO` 没有粉丝/关注/投稿三个计数
 * （只有 id / username / coin / admin / signedToday）。这三个数在 `ProfileVO` 上，
 * 所以按 id 再读一次公开资料，`pageSize=1` 把作品页压到最小。
 */
export async function loadProfileHeader(userId: number): Promise<SpaceHeaderVM> {
  const profile: components['schemas']['ProfileVO'] = await getJson('/api/v1/users/{id}', {
    path: { id: userId },
    query: { pageSize: 1 },
  })
  return toSpaceHeader(profile, currentUserId())
}

/** `GET /users/{id}/favorite-folders`：该用户**公开**的收藏夹 */
export async function loadPublicFolders(userId: number): Promise<PublicFolderVM[]> {
  const folders = await getJson('/api/v1/users/{id}/favorite-folders', { path: { id: userId } })
  return (folders ?? []).map((folder) => toPublicFolder(folder))
}

/** `GET /favorite-folders/{id}/items?page=&pageSize=`：某个公开夹的条目（Page 分页） */
export async function loadFolderItems(
  folderId: number,
  page = 1,
  pageSize = 10,
): Promise<{ items: VideoCardVM[]; page: number; total: number; totalPages: number }> {
  const result = await getJson('/api/v1/favorite-folders/{id}/items', {
    path: { id: folderId },
    query: { page, pageSize },
  })
  const items = (result?.list ?? []).map((item) => toFavoriteItemCard(item))
  return {
    items,
    page: result?.page ?? 1,
    total: result?.total ?? items.length,
    totalPages: result?.totalPages ?? 0,
  }
}
