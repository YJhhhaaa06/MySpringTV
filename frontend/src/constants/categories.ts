/**
 * 投稿分区（**后端 `categoryId` 的真实取值**）。
 *
 * <p>★ 这不是首页那 18 个频道（`constants/features.ts#CHANNELS`）：
 * 后端上传校验只接受**单个数字字符** 0~9（`UploadController#parseCategoryId`），
 * 数值到名字的映射沿用旧项目 `publish.js` 的 `CATEGORIES` 表（TV 原样，10 项）。
 * 两套常量服务两个不同的东西 —— 频道墙是视觉导航，分区是投稿契约值。
 */
export interface UploadCategory {
  id: number
  label: string
}

export const UPLOAD_CATEGORIES: ReadonlyArray<UploadCategory> = [
  { id: 0, label: '其他' },
  { id: 1, label: '游戏' },
  { id: 2, label: '音乐' },
  { id: 3, label: '资讯' },
  { id: 4, label: '动画' },
  { id: 5, label: '娱乐' },
  { id: 6, label: '动物' },
  { id: 7, label: '体育' },
  { id: 8, label: '鬼畜' },
  { id: 9, label: '绘画' },
]

/** 默认分区 = 0「其他」（旧项目发布页的初值；不替用户猜"动画"）。 */
export const DEFAULT_CATEGORY_ID = 0
