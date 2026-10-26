/**
 * 「我的收藏」两块的 api → ui 映射（收藏夹 / 稍后再看）。
 *
 * <h2>与 `adapters/space.ts` 的分界</h2>
 * 空间页读的是**别人**的公开面（公开夹 / 公开条目，匿名可达），这里读的是**我**的私有面
 * （含私密夹与稍后再看）—— 数据源与登录要求都不同，所以不复用 `toPublicFolder`。
 *
 * <h2>★ 不假装（都来自后端 VO 的真实缺口）</h2>
 * `FavoriteItemVO` / `WatchLaterItemVO` 没有 `authorId` / `durationSeconds` / 发布时间
 * ⇒ 作者只能渲染成纯文本、不显示时长与日期（GAP-16）。
 */
import type { components } from '../../types/api'
import type { MyFolderVM, VideoCardVM } from '../../types/ui'
import { videoHref } from './links'

/** `FavoriteFolderVO`（我的夹，含私密）→ 侧栏条目 */
export function toMyFolder(folder: components['schemas']['FavoriteFolderVO']): MyFolderVM {
  return {
    id: folder.id ?? 0,
    name: folder.name?.trim() || '未命名收藏夹',
    itemCount: typeof folder.itemCount === 'number' ? folder.itemCount : 0,
    isDefault: folder.isDefault === true,
    isPrivate: folder.isPrivate === true,
  }
}

/**
 * `WatchLaterItemVO` → 卡片。
 *
 * <p>与 `toFavoriteItemCard` 同形但**不共用**：两个 VO 的定位键与时间字段不同
 * （`contentId` + `addedTime` vs `id` + `favoriteTime`），硬凑一个只会让两处都别扭
 * （后端 VO 的类注释也明写「刻意不共用 VO」，映射层跟着它走）。
 * <p>`addedTime` 是「加入稍后再看的时间」，**不是发布时间** ⇒ `dateText` 留空。
 */
export function toWatchLaterCard(item: components['schemas']['WatchLaterItemVO']): VideoCardVM {
  return {
    id: item.contentId ?? 0,
    title: item.title?.trim() || '已失效内容',
    href: videoHref(item.contentId),
    coverUrl: item.coverUrl?.trim() || null,
    durationText: null,
    authorName: item.authorName?.trim() || (item.invalid === true ? '' : '未知UP主'),
    authorHref: null,
    dateText: '',
    playCountText: null,
    danmakuText: null,
    isPost: false,
    liked: null,
    invalid: item.invalid === true,
  }
}
