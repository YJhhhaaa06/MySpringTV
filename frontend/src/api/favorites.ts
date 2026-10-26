/**
 * 「我的收藏」端点封装（收藏夹 / 夹内条目 / 稍后再看）。
 *
 * <h2>为什么不是 `profile.ts`</h2>
 * `profile.ts` 读的是**他人公开面**（`/users/{id}/favorite-folders`、匿名可达）；
 * 本文件读的是**我的私有面**，全部 `@RequiresLogin`：
 * - `GET /api/v1/favorite-folders`（含私密夹）
 * - `GET /api/v1/favorites?folderId=`（**夹内分页**，含失效占位）
 * - `GET /api/v1/watch-later`
 *
 * <p>⚠️ 空间的 `GET /favorite-folders/{id}/items` 是另一条端点（只公开夹、匿名可看），
 * 别为了"少写一个函数"混用。
 */
import type { MyFolderVM, PageVM, VideoCardVM } from '../types/ui'
import { toMyFolder, toWatchLaterCard } from './adapters/favorites'
import { toFavoriteItemCard } from './adapters/space'
import { deleteJson, getJson, putJson } from './http'

/** `GET /favorite-folders`：我的全部夹（含默认夹与私密夹） */
export async function loadMyFolders(): Promise<MyFolderVM[]> {
  const folders = await getJson('/api/v1/favorite-folders')
  return (folders ?? []).map((folder) => toMyFolder(folder))
}

/** `GET /favorites?folderId=&page=&pageSize=`：**我的**某个夹的条目（Page） */
export async function loadMyFolderItems(
  folderId: number,
  page = 1,
  pageSize = 12,
): Promise<PageVM<VideoCardVM>> {
  const result = await getJson('/api/v1/favorites', {
    query: { folderId, page, pageSize },
  })
  const list = result?.list ?? []
  return {
    items: list.map((item) => toFavoriteItemCard(item)),
    page: result?.page ?? 1,
    total: result?.total ?? list.length,
    totalPages: result?.totalPages ?? 0,
  }
}

/** `GET /watch-later`：稍后再看清单（加入时间倒序；失效条目占位） */
export async function loadWatchLater(page = 1, pageSize = 12): Promise<PageVM<VideoCardVM>> {
  const result = await getJson('/api/v1/watch-later', { query: { page, pageSize } })
  const list = result?.list ?? []
  return {
    items: list.map((item) => toWatchLaterCard(item)),
    page: result?.page ?? 1,
    total: result?.total ?? list.length,
    totalPages: result?.totalPages ?? 0,
  }
}

/**
 * `DELETE /favorites?folderId=&contentIds=` → 204：从**一个**夹里批量移出。
 *
 * <p>失效条目也删得掉（后端按 `(folderId, contentId)` 定位、不校验内容存在性）——
 * 这正是"失效占位仍保留 contentId"的原因。
 */
export function removeFavoriteItems(folderId: number, contentIds: number[]): Promise<undefined> {
  return deleteJson('/api/v1/favorites', { query: { folderId, contentIds } })
}

/**
 * `PUT /watch-later/{id}` → 204：加入稍后再看（**只收视频**；重复加入 = 移到最前，幂等）。
 *
 * <p>⚠️ 没有"是否已在池中"的查询端点（favorite.md §六 红线）——调用方**不要**在装载时
 * 用它反推状态，那会变成每个卡片一次请求。页面内记忆见 `common/watchLaterSession.ts`。
 */
export function addWatchLater(contentId: number): Promise<undefined> {
  return putJson('/api/v1/watch-later/{id}', undefined, { path: { id: contentId } })
}

/** `DELETE /watch-later?contentIds=` → 204：从稍后再看批量移出（不校验内容存在性） */
export function removeWatchLater(contentIds: number[]): Promise<undefined> {
  return deleteJson('/api/v1/watch-later', { query: { contentIds } })
}
