/**
 * 互动态的**写接口**（点赞 / 投币 / 收藏 / 关注）。
 *
 * <p>口径见 `architecture/domain/favorite/favorite.md` §六（哪个面读哪几维）与
 * `types/ui.ts` 的三态注释。本层只做"端点 + 幂等键"的接线，**不做乐观更新**
 * —— 那是调用方（`composables/useVideoInteractions.ts`）的事。
 *
 * <h2>幂等键只给两条 KEYED 端点</h2>
 * `POST /contents/{id}/coins`、`POST /comments`（后者在 `api/comments.ts`）是 `KEYED`；
 * 点赞 / 收藏 / 关注都是 `NATURAL`（重试不改变 DB 终态），带键没有意义。
 */
import type { components } from '../types/api'
import type { InteractionVM, VideoDetailVM } from '../types/ui'
import { toVideoDetail } from './adapters/videoDetail'
import { deleteJson, getJson, postJson, putJson } from './http'
import { withIdempotencyKey } from './idempotency'

export type CoinBalanceVO = components['schemas']['CoinBalanceVO']
export type FavoriteStatusVO = components['schemas']['FavoriteStatusVO']
export type FavoriteFolderVO = components['schemas']['FavoriteFolderVO']
export type SignInVO = components['schemas']['SignInVO']

/** `PUT /contents/{id}/like`（重复点赞不报错，`NATURAL`） */
export function likeContent(contentId: number): Promise<undefined> {
  return putJson('/api/v1/contents/{id}/like', undefined, { path: { id: contentId } })
}

/** `DELETE /contents/{id}/like` → 204 */
export function unlikeContent(contentId: number): Promise<undefined> {
  return deleteJson('/api/v1/contents/{id}/like', { path: { id: contentId } })
}

/**
 * `GET /contents/{id}/like-count`：点赞数的**服务端权威值**（`data` 是裸数字）。
 *
 * <p>列表面（`/feed`）的点赞是好设计：卡片上直接加减只是乐观展示，成功后再读一次这个端点
 * 才算"写后对齐"。比重新拉整页便宜（一个数字 vs 12 张卡 + 三个计数 + 互动态）。
 */
export function fetchLikeCount(contentId: number): Promise<number | undefined> {
  return getJson('/api/v1/contents/{id}/like-count', { path: { id: contentId } })
}

/**
 * `POST /contents/{id}/coins`：投币（1 或 2 枚）。
 *
 * <p>返回投币后的**余额**（不是总币数）—— 界面据它刷新顶栏硬币数。
 * 幂等键由调用方在"用户按下投币"这一刻取一次，失败重试沿用同一个。
 */
export function addCoins(contentId: number, count: 1 | 2): Promise<CoinBalanceVO | undefined> {
  return postJson('/api/v1/contents/{id}/coins', { count }, {
    path: { id: contentId },
    ...withIdempotencyKey(),
  })
}

/** `GET /contents/{id}/favorite`：该内容在**我的哪些夹**里（不是"我的全部夹"） */
export function fetchFavoriteStatus(contentId: number): Promise<FavoriteStatusVO | undefined> {
  return getJson('/api/v1/contents/{id}/favorite', { path: { id: contentId } })
}

/** `GET /favorite-folders`：我的全部收藏夹（含默认夹） */
export function fetchFavoriteFolders(): Promise<FavoriteFolderVO[] | undefined> {
  return getJson('/api/v1/favorite-folders')
}

/** `POST /favorite-folders`：新建收藏夹（重名合法，见幂等登记表的灰色地带备注） */
export function createFavoriteFolder(name: string): Promise<undefined> {
  return postJson('/api/v1/favorite-folders', { name })
}

/** `POST /favorites`：收藏到指定夹；`folderId` 缺省 = 默认夹（后端自动建） */
export function addFavorite(contentId: number, folderId?: number): Promise<undefined> {
  const body = folderId === undefined ? { contentId } : { contentId, folderId }
  return postJson('/api/v1/favorites', body)
}

/** `DELETE /favorites?folderId=&contentIds=` → 204（一次一个夹 + 一批内容） */
export function removeFavorite(folderId: number, contentId: number): Promise<undefined> {
  return deleteJson('/api/v1/favorites', { query: { folderId, contentIds: [contentId] } })
}

/** `PUT /follows/{id}` */
export function followUser(userId: number): Promise<undefined> {
  return putJson('/api/v1/follows/{id}', undefined, { path: { id: userId } })
}

/** `DELETE /follows/{id}` → 204 */
export function unfollowUser(userId: number): Promise<undefined> {
  return deleteJson('/api/v1/follows/{id}', { path: { id: userId } })
}

/**
 * `POST /users/me/check-ins`：每日签到（领硬币，`+1` 枚）。
 *
 * <p>★ **幂等、且重复签到不是错误**：首次「签到成功」（`credited=true`），
 * 今日重复「今日已签到」（`credited=false`）——两者都是 **200**。
 * 所以调用方不应捕 409，也不该把 `credited=false` 当失败（`SignInVO` 的注释里写着原因）。
 */
export function checkInToday(): Promise<SignInVO | undefined> {
  return postJson('/api/v1/users/me/check-ins')
}

/**
 * 重新读一次详情的**计数与互动态**（写操作之后对齐权威值）。
 *
 * <p>为什么只取这两个字段：写操作会改的只有它们；把整个详情重新塞回页面
 * 会让播放器 props 变一轮（同一个 `src` 通常不会重载，但没必要赌这个）。
 * <p>`interaction.coin` 只有详情端点给（没有单独的"我投了几枚"端点），
 * 所以互动态刷新只能走这里。
 */
export async function fetchContentState(contentId: number): Promise<{
  stats: VideoDetailVM['stats']
  interaction: InteractionVM | null
}> {
  const detail = toVideoDetail(await getJson('/api/v1/contents/{id}', { path: { id: contentId } }))
  return { stats: detail.stats, interaction: detail.interaction }
}
