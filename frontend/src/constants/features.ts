/**
 * 占位功能的**唯一注册表**（`/placeholder/:feature` 的文案与分类来源）。
 *
 * <p>口径：**永远占位**的功能不进「等后端」清单、不阻塞重制。
 * 因此这里把 kind 分成两类：
 * - `never`：本仓设计上不做（没有域 / 没有任何端点计划），永远占位；
 * - `pending`：后端**已有**接口、只差页面，属**后续窗口**交付。
 *
 * <p>⚠️ 分区（18 项）也在 `never` 里：后端已拍板**不补**分类列表端点，
 * 所以它不是「等后端」，而是「前端常量 + 占位」。
 */

import type { IconName } from '../components/common/icons'

export type FeatureKind = 'never' | 'pending'
export interface FeatureInfo {
  key: string
  label: string
  kind: FeatureKind
  note: string
}

/** 18 个分区（后端没有分类列表端点 ⇒ 前端常量表，**不补**） */
const CHANNEL_TABLE: ReadonlyArray<readonly [string, string]> = [
  ['bangumi', '番剧'],
  ['guochuang', '国创'],
  ['variety', '综艺'],
  ['animation', '动画'],
  ['kichiku', '鬼畜'],
  ['dance', '舞蹈'],
  ['entertainment', '娱乐'],
  ['tech', '科技数码'],
  ['movie', '电影'],
  ['tv', '电视剧'],
  ['documentary', '纪录片'],
  ['game', '游戏'],
  ['music', '音乐'],
  ['film', '影视'],
  ['knowledge', '知识'],
  ['information', '资讯'],
  ['food', '美食'],
  ['animal', '动物圈'],
]

export interface ChannelItem {
  key: string
  label: string
}

export const CHANNELS: ReadonlyArray<ChannelItem> = CHANNEL_TABLE.map(([key, label]) => ({ key, label }))

const DIVISION_NOTE =
  '分区页本专项不做：后端没有分类列表端点（已拍板不补），导航项按方案保留为占位，不进「等后端」清单。'

const registry: Record<string, FeatureInfo> = {}
for (const [key, label] of CHANNEL_TABLE) {
  registry[key] = { key, label, kind: 'never', note: DIVISION_NOTE }
}

Object.assign(registry, {
  live: {
    key: 'live',
    label: '直播',
    kind: 'never',
    note: '本仓没有直播域（无表、无端点、无长连接）。按专项边界永远占位，不排期、不阻塞。',
  },
  'game-center': {
    key: 'game-center',
    label: '游戏中心',
    kind: 'never',
    note: '本仓没有游戏联运域。按专项边界永远占位。',
  },
  mall: {
    key: 'mall',
    label: '会员购',
    kind: 'never',
    note: '本仓没有电商/票务域（优惠券域是签到赠券，与会员购无关）。按专项边界永远占位。',
  },
  manga: {
    key: 'manga',
    label: '漫画',
    kind: 'never',
    note: '本仓没有漫画域（无章节模型、无阅读进度）。永远占位。',
  },
  esports: {
    key: 'esports',
    label: '赛事',
    kind: 'never',
    note: '本仓没有赛程/战队域。永远占位。',
  },
  classroom: {
    key: 'classroom',
    label: '课堂',
    kind: 'never',
    note: '本仓没有课程域。永远占位。',
  },
  message: {
    key: 'message',
    label: '消息',
    kind: 'never',
    note: '本仓没有消息域（无通知表、无推送）。永远占位。',
  },
  popular: {
    key: 'popular',
    label: '热门',
    kind: 'never',
    note: '本仓没有热门榜端点（`GET /contents/recommended` 是推荐流，不是排行榜）。永远占位。',
  },
  danmaku: {
    key: 'danmaku',
    label: '弹幕',
    kind: 'never',
    note: '本仓没有弹幕域（无表、无端点、无 WS）。卡片也不显示弹幕数 —— 假数字一问就穿帮。',
  },
  // ★ `space`（UP 空间）已于 2026-10-25 交付（`space.html` + `/space/:id`）⇒ 从本注册表移除。
  //   `/placeholder/space` 因此落到「页面不存在」，真链接在 `adapters/links.ts#spaceHref`。
  // ★ `favorites` / `history` 已于 2026-10-26 交付（`favorites.html` / `history.html`）⇒ 同样移除；
  //   真链接是 `/favorites` / `/history`（顶栏 `HEADER_ACTIONS`）。
  feed: {
    key: 'feed',
    label: '动态',
    kind: 'pending',
    note: '后端已有 GET /feed（互动态维度 {赞}）。页面在后续窗口交付。',
  },
  upload: {
    key: 'upload',
    label: '投稿 / 创作中心',
    kind: 'pending',
    note: '后端已有 POST /contents/videos 与 /contents/posts（multipart）与删除/换源。页面在后续窗口交付。',
  },
  admin: {
    key: 'admin',
    label: '后台',
    kind: 'pending',
    note: '后端已有 7 个 admin 端点（含 GET /admin/me 角色探测）。页面在后续窗口交付。',
  },
})

export const FEATURES: Readonly<Record<string, FeatureInfo>> = registry

export function resolveFeature(key: string | null | undefined): FeatureInfo | null {
  if (!key) return null
  return FEATURES[key] ?? null
}

export function featureUrl(key: string): string {
  return `/placeholder/${key}`
}

/** 顶栏导航：首页 + 灰态占位项 */
export const HEADER_NAV: ReadonlyArray<{ label: string; href: string; placeholder: boolean }> = [
  { label: '首页', href: '/', placeholder: false },
  ...['bangumi', 'live', 'game-center', 'mall', 'manga', 'esports', 'classroom'].map((key) => ({
    label: FEATURES[key]?.label ?? key,
    href: featureUrl(key),
    placeholder: true,
  })),
]

/**
 * 顶栏右侧图标簇：`收藏` / `历史` 已是真页面（2026-10-26）；
 * 消息 / 动态 / 创作中心仍是**后续窗口**（`pending`，点进占位页）。
 */
export const HEADER_ACTIONS: ReadonlyArray<{
  label: string
  icon: IconName
  href: string
  /** true = 点进 `/placeholder/:feature`（灰态）；false / 缺省 = 真页面 */
  placeholder?: boolean
}> = [
  { label: '消息', icon: 'message', href: featureUrl('message'), placeholder: true },
  { label: '动态', icon: 'dynamic', href: featureUrl('feed'), placeholder: true },
  { label: '收藏', icon: 'star', href: '/favorites' },
  { label: '历史', icon: 'clock', href: '/history' },
  { label: '创作中心', icon: 'dashboard', href: featureUrl('upload'), placeholder: true },
]
