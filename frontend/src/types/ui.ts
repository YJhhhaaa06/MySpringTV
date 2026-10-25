/**
 * 页面期望形状（手写层）。
 *
 * <p>铁律：**组件只吃这里的类型**，不许直接吃 `types/api.d.ts`。
 * 例外：纯透传页（表单 / 详情只读页）可以直接用生成类型，别为教条写 adapter。
 *
 * <p>本文件**允许包含后端暂时没有的字段**（如 `playCountText` / `danmakuText`）——
 * 那正是 adapter 的 fallback 落点：后端补字段时只改 adapter 与删一个 fallback，组件零改动。
 */

/** 列表卡片（首页网格 / 搜索 / 相关推荐共用）。 */
export interface VideoCardVM {
  id: number
  title: string
  /** 跨应用链接：`/video/{id}`（新标签打开，见 §十二） */
  href: string
  coverUrl: string | null
  /** 时长角标（`mm:ss` / `h:mm:ss`）；null = 不显示（纵向图文或后端未回填时长） */
  durationText: string | null
  authorName: string
  authorHref: string
  /** 相对发布时间（B 站口径：`3天前` / `09-28`） */
  dateText: string
  /** 播放量文本。列表 VO 没有任何计数 ⇒ 恒 null（**隐藏角标，不许用假数字**） */
  playCountText: string | null
  /** 弹幕数文本。本仓没有弹幕域 ⇒ 恒 null（**永不显示**） */
  danmakuText: string | null
  /** 图文（type=2）在卡片上的差异（计数语义是「浏览」而不是「播放」） */
  isPost: boolean
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

/** 播放页侧栏的 UP 主卡（`GET /users/{id}`，真粉丝数/投稿数） */
export interface AuthorCardVM {
  id: number
  name: string
  initial: string
  href: string
  followerText: string
  followingText: string
  worksText: string
}

/** 播放页（只读） */
export interface VideoDetailVM {
  id: number
  title: string
  description: string
  authorId: number
  authorName: string
  authorHref: string
  /** 发布日期文本（`2026-05-09`） */
  dateText: string
  coverUrl: string | null
  /** 视频源；图文为 null（用 images 渲染） */
  videoUrl: string | null
  durationText: string | null
  /** 图文的多图（视频为空数组） */
  images: string[]
  isPost: boolean
  commentEnabled: boolean
  /** 详情侧是**有计数**的（与列表相反），全部为真接口数字；空值渲染成 `-` */
  stats: {
    play: string
    like: string
    coin: string
    favorite: string
    comment: string
  }
}
