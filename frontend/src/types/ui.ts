/**
 * 页面期望形状（手写层）。
 *
 * <p>铁律：**组件只吃这里的类型**，不许直接吃 `types/api.d.ts`。
 * 例外：纯透传页（表单 / 详情只读页）可以直接用生成类型，别为教条写 adapter。
 *
 * <p>本文件**允许包含后端暂时没有的字段**（如 `playCountText` / `danmakuText`）——
 * 那正是 adapter 的 fallback 落点：后端补字段时只改 adapter 与删一个 fallback，组件零改动。
 *
 * <h2>★ 计数一律「原始数字」，不预先格式化成文本</h2>
 * 互动态要**乐观更新**（点赞后 `+1`、点完再点 `-1`），预格式化的 `"1.2万"` 没法加减；
 * 而且「万/亿」是**展示口径**，会随窗口宽度/文案变化。⇒ VM 只搬数字，格式化留给渲染处。
 */

/** 计数：`null` = 后端没给（渲染成 `-`，**不假装是 0**） */
export type Count = number | null

/** 列表卡片（首页网格 / 搜索 / 相关推荐 / 空间投稿共用）。 */
export interface VideoCardVM {
  id: number
  title: string
  /** 跨应用链接：`/video/{id}`（新标签打开，见 §十二） */
  href: string
  coverUrl: string | null
  /** 时长角标（`mm:ss` / `h:mm:ss`）；null = 不显示（纵向图文或后端未回填时长） */
  durationText: string | null
  authorName: string
  /** UP 主空间链接；`null` = 这个 VO 没带作者 id（收藏夹条目 `FavoriteItemVO` 就是） */
  authorHref: string | null
  /** 相对发布时间（B 站口径：`3天前` / `09-28`）；空串 = 不显示（收藏条目没有发布时间） */
  dateText: string
  /** 播放量。列表 VO 没有任何计数 ⇒ 恒 null（**隐藏角标，不许用假数字**） */
  playCountText: string | null
  /** 弹幕数文本。本仓没有弹幕域 ⇒ 恒 null（**永不显示**） */
  danmakuText: string | null
  /** 图文（type=2）在卡片上的差异（计数语义是「浏览」而不是「播放」） */
  isPost: boolean
  /** 该内容是否已被**当前登录用户**点过赞（列表面只回这一维，见互动态三态） */
  liked: boolean | null
  /** 内容已删除 / 不可见（收藏夹条目才有这个标记）⇒ 卡片打灰、标题写"已失效" */
  invalid?: boolean
}

/** 首页大轮播的一屏（真封面 + 真标题，不是运营广告位 —— 见 GAP 台账） */
export interface HeroSlideVM {
  id: number
  title: string
  href: string
  coverUrl: string
  authorName: string
}

/** 当前登录用户（`GET /users/me`；★ 后端没有 avatar 字段，见 GAP 台账） */
export interface CurrentUserVM {
  id: number
  name: string
  /** 头像占位：昵称首字（等 avatars 端点/字段，GAP 台账） */
  initial: string
  admin: boolean
  coin: number
  /** 个人空间跨应用链接 */
  spaceHref: string
}

/** 搜索 / 用户空间的作品分页（Page 分页，不是 cursor） */
export interface SearchResultVM {
  items: VideoCardVM[]
  keyword: string
  page: number
  pageSize: number
  total: number
  totalPages: number
}

/**
 * 互动态（`InteractionStatusVO`）。
 *
 * <p>★ **三态**：整个对象为 `null` = 匿名（本面不查）；`followed === null` = 本面**只查了赞**
 * （`/users/{id}`、`/feed` 的面）。`null` **不等于** `false` —— 拿 null 当 false 渲染，
 * 用户会看到"我没关注"然后点一下变成"关注"，实际后端早就关注了。
 */
export interface InteractionVM {
  liked: boolean
  /** 已投币数（0..2；B 站口径：一条内容最多两枚） */
  coin: number
  favorited: boolean
  followed: boolean | null
}

/** 播放页侧栏的 UP 主卡（`GET /users/{id}`，真粉丝数/投稿数） */
export interface AuthorCardVM {
  id: number
  name: string
  initial: string
  href: string
  followerCount: Count
  followingCount: Count
  worksCount: Count
  /** 当前用户是否已关注；null = 本面不查（未登录） */
  followed: boolean | null
}

/** 播放页详情 */
export interface VideoDetailVM {
  id: number
  title: string
  description: string
  authorId: number
  authorName: string
  authorHref: string
  /** 发布日期文本（`2026-05-09 19:43`） */
  dateText: string
  coverUrl: string | null
  /** 视频源；图文为 null（用 images 渲染） */
  videoUrl: string | null
  durationText: string | null
  /** 时长秒数（播放进度上报要用它做 clamp 与"看完"判断） */
  durationSeconds: number | null
  /** 图文的多图（视频为空数组） */
  images: string[]
  isPost: boolean
  commentEnabled: boolean
  /** 详情侧是**有计数**的（与列表相反），全部为真接口数字；null 渲染成 `-` */
  stats: {
    play: Count
    like: Count
    coin: Count
    favorite: Count
    comment: Count
  }
  /** 匿名 = null（后端整个字段返回 null，不是 `{}` 也不是四个 false） */
  interaction: InteractionVM | null
}

/**
 * 评论（`CommentVO` / 楼中楼）。
 *
 * <p>★ 后端 `CommentVO` **没有 `createTime`**（GAP 台账 M1）⇒ `timeText` 恒空串，
 * 界面上不出现时间；**不许**拿"刚刚"顶上（那是编数字的变体）。
 */
export interface CommentVM {
  id: number
  parentId: number | null
  authorId: number
  authorName: string
  /** 头像占位：昵称首字 */
  initial: string
  content: string
  likeCount: number
  liked: boolean
  /** 楼中楼：该主楼下的回复总数（**不是**已加载条数） */
  replyCount: number
  /** 预览回复（后端每页只随行前 K=2 条） */
  replies: CommentVM[]
  /** 被回复者（楼中楼的 `回复 @xxx` 前缀）；主楼为 null */
  replyToName: string | null
  /** 当前登录用户能否删（自己的评论才显示删除按钮） */
  canDelete: boolean
  /** 空串 = 后端没有时间字段（GAP），渲染处直接不显示 */
  timeText: string
}

/** 评论区分页（Page：主楼；楼中楼用游标，见 CommentRepliesVM） */
export interface CommentPageVM {
  items: CommentVM[]
  total: number
  page: number
  pageSize: number
  totalPages: number
}

/** 楼中楼一页（Cursor 分页，别和 Page 混用） */
export interface CommentRepliesVM {
  items: CommentVM[]
  /** null = 已到底 */
  nextCursor: number | null
  total: number
}

/** 收藏夹（`GET /favorite-folders`） */
export interface FavoriteFolderVM {
  id: number
  name: string
  itemCount: number
  isDefault: boolean
  isPrivate: boolean
  /** 当前内容是否已在这个夹里（由 `GET /contents/{id}/favorite` 的 folders 求交集） */
  contains: boolean
}

/** 空间页头部（`GET /users/{id}`；后端没有头像 / 签名 / banner ⇒ 全部降级） */
export interface SpaceHeaderVM {
  id: number
  name: string
  initial: string
  followerCount: Count
  followingCount: Count
  worksCount: Count
  followed: boolean | null
  /** 是不是自己的空间（自己 ⇒ 不显示关注按钮） */
  isSelf: boolean
}

/** 空间页投稿分页 */
export interface SpaceWorksVM {
  items: VideoCardVM[]
  page: number
  pageSize: number
  total: number
  totalPages: number
}

/** 公开收藏夹条目（空间的「收藏」tab） */
export interface PublicFolderVM {
  id: number
  name: string
  itemCount: number
}
