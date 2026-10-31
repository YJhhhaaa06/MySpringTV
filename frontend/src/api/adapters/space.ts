import type { components } from '../../types/api'
import type {
  Count,
  PublicFolderVM,
  SpaceHeaderVM,
  SpaceWorksVM,
  VideoCardVM,
} from '../../types/ui'
import { initialOf } from './format'
import { videoHref } from './links'
import { toVideoCard } from './videoCard'
import { serverNow } from '../serverClock'

/** 计数：非数字 ⇒ null（渲染成 `-`） */
function count(value: number | null | undefined): Count {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

/**
 * `ProfileVO` → 空间页头部。
 *
 * <p>★ 三处降级（都记在 GAP 台账里，界面上**不假装有**）：
 * 没有头像（昵称首字）、没有签名 / 生日 / 地区（资料卡只放真实计数）、
 * 没有 banner 图（渐变底）。
 *
 * <p>`isFollowed` 匿名时是 `null` ⇒ 关注按钮显示"关注"但**点下去先弹登录**
 * （不是"已关注"——那会把"不知道"画成"知道"）。
 */
export function toSpaceHeader(
  profile: components['schemas']['ProfileVO'],
  viewerId: number | null,
): SpaceHeaderVM {
  const name = profile.username?.trim() || '未知UP主'
  const id = profile.userId ?? 0
  return {
    id,
    name,
    initial: initialOf(name),
    followerCount: count(profile.followerCount),
    followingCount: count(profile.followCount),
    worksCount: count(profile.contentPage?.total),
    followed: typeof profile.isFollowed === 'boolean' ? profile.isFollowed : null,
    isSelf: viewerId !== null && id === viewerId,
  }
}

/** `PageResult<ContentVO>` → 空间作品分页（与搜索页同一个卡片口径，`now` 同源取服务器时钟） */
export function toSpaceWorks(
  page: components['schemas']['PageResultContentVO'] | undefined,
  now: Date = serverNow(),
): SpaceWorksVM {
  const list = page?.list ?? []
  return {
    items: list.map((item) => toVideoCard(item, now)),
    page: page?.page ?? 1,
    pageSize: page?.pageSize ?? list.length,
    total: page?.total ?? list.length,
    totalPages: page?.totalPages ?? 0,
  }
}

/** `PublicFavoriteFolderVO` → 收藏夹卡片 */
export function toPublicFolder(folder: components['schemas']['PublicFavoriteFolderVO']): PublicFolderVM {
  return {
    id: folder.id ?? 0,
    name: folder.name?.trim() || '未命名收藏夹',
    itemCount: folder.itemCount ?? 0,
  }
}

/**
 * `FavoriteItemVO` → 卡片。
 *
 * <p>★ 这个 VO 比 `ContentVO` **少一大半信息**：没有 `type`、没有 `durationSeconds`、
 * **没有 `authorId`** ⇒ 作者链接只能留空（卡片渲染成纯文本），时长角标不显示，
 * 发布日期也没有（只有 `favoriteTime`，那是"收藏时间"，拿它当发布日期就是编）。
 * <p>★ 没有 `type` ⇒ 链接只能先落 `/video/{id}`；播放页取到详情发现是图文后会
 * 重定向到 `/opus/{id}`（GAP-16 的前端兜底，见 `architecture/frontend/前端.md` §六）。
 * <p>`invalid === true` = 内容已删 / 不可见，卡片打"已失效"角标而不是藏起来
 * （B 站也是留着灰卡 —— 用户才知道自己收藏过什么）。
 */
export function toFavoriteItemCard(item: components['schemas']['FavoriteItemVO']): VideoCardVM {
  return {
    id: item.contentId ?? 0,
    title: item.title?.trim() || '已失效内容',
    href: videoHref(item.contentId),
    coverUrl: item.coverUrl?.trim() || null,
    // 没有 description / type ⇒ 不渲染文字卡面（也分不出图文）
    previewText: null,
    durationText: null,
    authorName: item.authorName?.trim() || '未知UP主',
    authorHref: null,
    dateText: '',
    playCountText: null,
    danmakuText: null,
    isPost: false,
    liked: null,
    invalid: item.invalid === true,
  }
}
