/**
 * 页面期望形状（手写层）。
 *
 * <p>铁律：**组件只吃这里的类型**，不许直接吃 `types/api.d.ts`。
 * 例外：纯透传页（表单 / 详情只读页）可以直接用生成类型，别为教条写 adapter。
 *
 * <p>本文件**允许包含后端暂时没有的字段**（如 `danmakuText`）——
 * 那正是 adapter 的 fallback 落点：后端补字段时只改 adapter 与删一个 fallback，组件零改动。
 *
 * <h2>★ 计数：会变的用原始数字，只读展示的可预格式化</h2>
 * 互动态要**乐观更新**（点赞后 `+1`、点完再点 `-1`），预格式化的 `"1.2万"` 没法加减
 * ⇒ 点赞 / 投币这类计数在 VM 里一律是**数字**。
 * 播放量 / 弹幕数**只读展示**、没有加减场景，格式化口径（万 / 亿）由 adapter 统一实现，
 * 组件不重复造一份；两者都用 `null` 表示"本面没有给"，不假装 0。
 */

/** 计数：`null` = 后端没给（渲染成 `-`，**不假装是 0**） */
export type Count = number | null

/** 列表卡片（首页网格 / 搜索 / 相关推荐 / 空间投稿共用）。 */
export interface VideoCardVM {
  id: number
  title: string
  /** 跨应用链接：视频 `/video/{id}`、图文 `/opus/{id}`（`contentHref` 按 type 决定，新标签打开） */
  href: string
  coverUrl: string | null
  /**
   * 纯文字图文的卡面文案（**只用真实 `description`**）：
   * - 图文且无封面 ⇒ 简介（空串 = 没写简介，渲染成中性的「图文动态」，不编内容）；
   * - 视频 / 收藏夹等没有简介的面 ⇒ `null`（不渲染文字卡面）。
   */
  previewText: string | null
  /** 时长角标（`mm:ss` / `h:mm:ss`）；null = 不显示（图文 / 文件缺失 / 解析失败） */
  durationText: string | null
  authorName: string
  /** UP 主空间链接；`null` = 这个 VO 没带作者 id（收藏夹条目 `FavoriteItemVO` 就是） */
  authorHref: string | null
  /** 相对发布时间（B 站口径：`3天前` / `09-28`）；空串 = 不显示（收藏条目没有发布时间） */
  dateText: string
  /** 播放量文本（B 站口径：万 / 亿）。null = 不显示（图文 / 本面未给） */
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
  /**
   * 硬币余额，**一位小数**（后端 `coin_deci / 10`，`1.5` 就是一枚半）。
   * ⚠️ 展示时要按 B 站口径取整：主页 / 顶栏是整数，只有投币弹层给一位小数 ——
   * 见 `api/adapters/format.ts#formatCoinInt`。别在这里提前取整（投币弹层要用小数）。
   */
  coin: number
  /**
   * 今天（**上海自然日**，后端 `SiteTime`）是否已签到。
   *
   * ★ 签到按钮**进页就置灰**的唯一判据：服务端权威值，不落 localStorage
   * （理由见 `architecture/frontend/前端.md` §五「签到状态不落本地」）。
   */
  signedToday: boolean
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
 * 通用 **Page 分页**形状（收藏 / 稍后再看 / 历史共用；区别只在 item 类型）。
 *
 * <p>与 `CommentRepliesVM`（Cursor）**刻意分开**：两种分页的边界（`page/totalPages` vs
 * `nextCursor`）与翻页交互都不同，硬统一只会让两边都别扭（见 `architecture/frontend/前端.md` §四）。
 */
export interface PageVM<T> {
  items: T[]
  page: number
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
 * <p>时间：`createTime` 自 GAP-12 起由后端返回，由 `formatCommentTime` 转成 `timeText`。
 * 空串 = 缺时间（存量缓存 / 脏数据）⇒ 渲染层不显示节点；**不许**用"刚刚"兜空值。
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
  /** 评论时间（`刚刚` / `N分钟前` / `N小时前` / `YYYY-MM-DD HH:MM`）；空串 = 缺时间或脏数据，渲染处不显示 */
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

/**
 * 我的收藏夹（`GET /favorite-folders`，**含私密夹**；与 `PublicFolderVM` 是两种视图：
 * 后者只给公开夹、匿名可见）。
 */
export interface MyFolderVM {
  id: number
  name: string
  itemCount: number
  isDefault: boolean
  isPrivate: boolean
}

/**
 * 历史列表里的一条（`GET /history`）。
 *
 * <p>与 `VideoCardVM` **刻意分开**：历史是"回看"场景，封面要带**观看进度角标**
 * （B 站口径 `已看/总长`，如 `18:09/49:45`），卡片 meta 要显示**观看时刻**
 * （`今天 12:57`），而卡片那套（播放量 / 弹幕 / 发布时间）在这里全是假字段。
 */
export interface HistoryItemVM {
  contentId: number
  /** 跨应用链接；失效条目页面**不渲染成链接**（打不开） */
  href: string
  title: string
  coverUrl: string | null
  /** 失效时为空串（后端不返回原作者 —— R-03 脱敏） */
  authorName: string
  invalid: boolean
  /**
   * 封面右下角角标（B 站历史页口径）：
   * `18:09/49:45`（已看/总长）· `已看完` · `看到 12:00`（没有总时长时只给已看）· 空串（没进度）
   */
  badgeText: string
  /** 进度条百分比 `0..100`；**算不出时是 0**（没有总时长就不画比例，不猜） */
  progressPercent: number
  /** 观看时刻 `HH:mm`（日期由分组头/卡片 meta 承担） */
  watchedTimeText: string
  /** 原始观看时间（分组用；空串 = 后端没给） */
  watchedAt: string
}

/** 历史按天分组（今天 / 昨天 / MM-DD / YYYY-MM-DD） */
export interface HistoryGroupVM {
  key: string
  label: string
  items: HistoryItemVM[]
}

/**
 * 动态页的一张卡（`GET /feed` 的 `ContentVO`）。
 *
 * <p>与 `VideoCardVM` **刻意分开**：动态卡的展示位是 B 站的「UP 主 + 时间 + 投稿了视频」
 * 然后一张**大横卡**（封面 + 标题 + 简介），底部还有一条点赞/评论操作栏；
 * 网格卡那套（封面统计条 / 两行标题 / author·date meta）在这里全不对。
 *
 * <p>★ 三个计数都是**真接口**：`playCount`（图文没有，GAP-05）、`likeCount`、`commentCount`
 * —— 后两维自 GAP-01 起后端就带在 `/feed` 上，直到本页才有消费方。
 */
export interface DynamicCardVM {
  id: number
  title: string
  /** 跨应用链接：`/video/{id}`（动态没有独立详情页，B 站的动态详情是另一套文档） */
  href: string
  coverUrl: string | null
  /** 时长角标；图文恒 null */
  durationText: string | null
  authorId: number
  authorName: string
  authorHref: string
  /** 头像占位：昵称首字（后端没有头像字段，GAP 台账） */
  authorInitial: string
  /** 相对时间（`刚刚` / `4分钟前` / `05-09`） */
  timeText: string
  /** 动态动作文案：`投稿了视频` / `发布了图文`（由 `type` 推导，不是后端字段） */
  actionText: string
  description: string
  isPost: boolean
  /** 播放量文本；图文恒 null（`play_count` 对图文结构性恒 0） */
  playCountText: string | null
  /** 会随点赞加减 ⇒ 原始数字（`null` = 本面没给，不装成 0） */
  likeCount: Count
  commentCount: Count
  commentEnabled: boolean
  /** 当前用户是否点过赞（`/feed` 只回「赞」一维；`null` = 本面不查） */
  liked: boolean | null
}

/** 动态流一页（Page 分页；B 站是无限滚动，本仓按 §二 的列表口径用 Page） */
export interface DynamicPageVM {
  items: DynamicCardVM[]
  page: number
  total: number
  totalPages: number
}

/**
 * 关注 / 粉丝列表里的一个人（`FollowUserVO`）。
 *
 * <p>★ 这个 VO 只有 `userId / username / isFollowed / isSelf` —— **没有头像、没有签名**
 * （GAP 台账）⇒ 卡片只渲染首字头像、名字与关注按钮，不编签名。
 */
export interface RelationUserVM {
  id: number
  name: string
  initial: string
  /** 当前登录用户是否关注了 TA（端点要求登录 ⇒ 这个布尔一定是真值，不是三态） */
  followed: boolean
  isSelf: boolean
}

/** 关注 / 粉丝一页（`PageResult<FollowUserVO>`） */
export interface RelationPageVM {
  items: RelationUserVM[]
  page: number
  total: number
  totalPages: number
}

/**
 * 创作中心「我的稿件」一行。
 *
 * <p>数据源是 `GET /users/{id}.contentPage`（公开主页的作品页）——本仓**没有**
 * "创作者的稿件列表"端点，也没有草稿 / 审核态（`content` 没有状态列），
 * 所以这里渲染的就是"我公开发布的作品"，不编状态列。
 */
export interface CreatorWorkVM {
  id: number
  title: string
  isPost: boolean
  /** 「视频」/「图文」——行首类型角标 */
  typeText: string
  coverUrl: string | null
  /** 时长只对视频有值；`null` = 未解析 / 图文，不补 0 */
  durationText: string | null
  /** 播放量：图文恒 `null`（后端 `play_count` 对图文结构性恒 0，GAP-05） */
  playCountText: string | null
  createdAtText: string
  /** 查看（观众视角）：`/video/{id}` 或 `/opus/{id}` */
  viewHref: string
  /** 编辑（作者视角）：`/creator/edit/{id}` */
  editHref: string
}

/** 我的稿件一页 */
export interface CreatorWorksVM {
  items: CreatorWorkVM[]
  page: number
  total: number
  totalPages: number
}
