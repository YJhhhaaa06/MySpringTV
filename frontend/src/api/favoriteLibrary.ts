/**
 * 收藏浏览器（空间「收藏」tab）的数据源选择。
 *
 * <h2>★ self / public 两条路径，绝不混用</h2>
 * - self：`GET /favorite-folders` + `GET /favorites?folderId=`（含私密夹，需登录）；
 * - public：`GET /users/{id}/favorite-folders` + `GET /favorite-folders/{id}/items`
 *   （只公开夹，匿名可看）。
 *
 * <p>公开端点已经过滤私密夹，前端**不再过滤**；但 self 判定必须由调用方在
 * `restoreSession()` 之后用 `currentUserId() === userId` 得出 —— 抢在登录态落定前
 * 判定会把"看自己的空间"画成访客视角（私密夹先消失再出现）。
 *
 * <p>稍后再看是**另一张表**（`GET /watch-later`），只在 self 且 `view=later` 时读；
 * 它不参与夹列表 / 夹数。
 */
import type { LibraryFolderVM, PageVM, VideoCardVM } from '../types/ui'
import {
  loadMyFolderItems,
  loadMyFolders,
  loadWatchLater,
  removeFavoriteItems,
  removeWatchLater,
} from './favorites'
import { loadFolderItems, loadPublicFolders } from './profile'

/** 夹列表：self 含私密，public 由后端过滤 */
export function loadLibraryFolders(ownerId: number, isSelf: boolean): Promise<LibraryFolderVM[]> {
  return isSelf ? loadMyFolders() : loadPublicFolders(ownerId)
}

/** 夹内条目：self 走我的分页端点，public 走公开夹端点（公开端点只认夹 id） */
export function loadLibraryItems(
  folderId: number,
  isSelf: boolean,
  page = 1,
  pageSize = 12,
): Promise<PageVM<VideoCardVM>> {
  return isSelf
    ? loadMyFolderItems(folderId, page, pageSize)
    : loadFolderItems(folderId, page, pageSize)
}

/** 稍后再看（仅 self；与收藏夹是两张表） */
export function loadLibraryLater(page = 1, pageSize = 12): Promise<PageVM<VideoCardVM>> {
  return loadWatchLater(page, pageSize)
}

/** 侧栏「稍后再看」计数：只取一页一条，拿 total */
export async function loadLibraryLaterTotal(): Promise<number> {
  const page = await loadWatchLater(1, 1)
  return page.total
}

/** 从收藏夹移出单条（self；后端按 `(folderId, contentId)` 定位、不校验内容存在性） */
export function removeLibraryItem(folderId: number, contentId: number): Promise<undefined> {
  return removeFavoriteItems(folderId, [contentId])
}

/** 从稍后再看移出单条（self） */
export function removeLibraryLater(contentId: number): Promise<undefined> {
  return removeWatchLater([contentId])
}
