/**
 * 图文详情图片区的**纯规则**（可单测；组件只负责布局与交互）。
 *
 * <p>两种形态（B 站 2026-10 图文详情实测）：
 * - **九宫格**：最多 3 列，前 9 张平铺；超过 9 张在最后一格显示 `+N`；
 * - **放大态**：一张大图 + 底部缩小平铺的缩略图条；可左右旋转、切换。
 *
 * <p>规则写成纯函数，是因为"列数 / 截断 / 旋转归一"都能按业务口径先写断言，
 * 不必靠截图回归；组件只做"把状态画出来"。
 */

/** 九宫格上限：B 站超过 9 张时不再继续平铺，用 +N 提示去放大态看全部 */
export const OPUS_GRID_MAX = 9

/** 网格列数：1 张单图、2 张两列、3 张及以上三列（九宫格） */
export function gridColumns(count: number): 1 | 2 | 3 {
  if (count <= 1) return 1
  if (count === 2) return 2
  return 3
}

/** 九宫格只展示前 9 张；`overflow` = 没展示的张数（0 表示全部展示） */
export function gridPreview(images: readonly string[]): { shown: string[]; overflow: number } {
  return {
    shown: images.slice(0, OPUS_GRID_MAX),
    overflow: Math.max(0, images.length - OPUS_GRID_MAX),
  }
}

/** 放大态切换图片：循环前后翻（空数组 ⇒ 0） */
export function stepIndex(current: number, delta: number, total: number): number {
  if (total <= 0) return 0
  return ((current + delta) % total + total) % total
}

/** 旋转只保留 0 / 90 / 180 / 270（向左 = -90、向右 = +90；不无限累加） */
export function rotateBy(rotation: number, delta: number): number {
  return ((rotation + delta) % 360 + 360) % 360
}

/**
 * 键盘快捷键只在**非输入控件**上生效：图片放大后滚到评论区打字时，
 * ←/→ 不能切图、Esc 不能「收起」（真实交互 bug；不是快捷键本身的问题）。
 */
export function isEditableTarget(tagName: string, isContentEditable: boolean): boolean {
  const tag = tagName.toUpperCase()
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || isContentEditable
}
