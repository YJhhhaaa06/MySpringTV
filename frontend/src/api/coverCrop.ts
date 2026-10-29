/**
 * 封面裁剪的**纯几何规则**（可单测；组件只负责拖动 / 缩放 / 导出）。
 *
 * <h2>为什么裁成 16:9</h2>
 * 本仓所有**封面展示位**都是 16:9（首页 / 搜索 / 历史 / 动态的卡片cover、播放页海报区，
 * 见 `components/common/VideoCard.vue` 与 `pages/VideoPage.vue` 的 `aspect-ratio`），
 * 而 `<img>` 用 `object-fit: cover` 渲染 ⇒ **非 16:9 的图在展示时会被上下（或左右）切掉**，
 * 作者在投稿页看到的和观众看到的不是同一张。
 * 裁剪就是把这个"切掉"从**不可控的黑盒**变成**作者自己选**：裁剪窗恒 16:9，
 * 窗里看到的就是卡片 / 播放器里看到的。
 *
 * <h2>状态为什么是「缩放 + 归一化中心」而不是像素偏移</h2>
 * 舞台尺寸随窗口变化（响应式宽度），像素偏移一换尺寸就失效；而归一化中心
 * （裁剪窗中心在源图上的 0..1 坐标）与舞台无关 ⇒ 拖窗口不会把用户选好的构图挪走。
 * 同时它让"缩放锚点"能写成一个**不动点**等式，可以直接断言（见 `zoomAround`）。
 */

/** 目标比例：16:9（本仓所有封面展示位的比例）。 */
export const COVER_ASPECT = 16 / 9

/** 导出尺寸：1280×720（B 站封面同量级；对 1080p 源是等比缩小，对 720p 源是原尺寸）。 */
export const COVER_OUTPUT_WIDTH = 1280
export const COVER_OUTPUT_HEIGHT = 720

/** 缩放区间：1 = 裁剪窗恰好盖满源图（不出现空白边），4 = 放大四倍（选局部细节）。 */
export const COVER_MIN_ZOOM = 1
export const COVER_MAX_ZOOM = 4

export interface Size {
  width: number
  height: number
}

/**
 * 裁剪状态（与舞台尺寸无关）：
 * `zoom` 相对"最大 16:9 内接矩形"，`centerX/centerY` 是裁剪窗中心在源图上的归一化位置。
 */
export interface CoverCropState {
  zoom: number
  centerX: number
  centerY: number
}

/** 裁剪窗在**源图像素**里的矩形（交给 canvas 的 `drawImage` 九参形式）。 */
export interface CropRect {
  x: number
  y: number
  width: number
  height: number
}

/** 源图 → 舞台的映射：`source * scale + offset = stage`（舞台坐标是 CSS px）。 */
export interface StageLayout {
  scale: number
  offsetX: number
  offsetY: number
}

export interface Point {
  x: number
  y: number
}

function isPositive(value: number): boolean {
  return Number.isFinite(value) && value > 0
}

function clamp(value: number, min: number, max: number): number {
  if (!Number.isFinite(value)) return min
  if (min > max) return (min + max) / 2
  return Math.min(max, Math.max(min, value))
}

/** 源图里能放下的**最大 16:9 矩形**（`zoom = 1` 时裁剪窗就是它）。 */
export function maxCropSize(image: Size): Size {
  if (!isPositive(image.width) || !isPositive(image.height)) {
    return { width: 0, height: 0 }
  }
  const heightFromWidth = image.width / COVER_ASPECT
  if (heightFromWidth <= image.height) {
    return { width: image.width, height: heightFromWidth }
  }
  return { width: image.height * COVER_ASPECT, height: image.height }
}

/** 缩放倍数归一（非数字 / 越界都夹回 `COVER_MIN_ZOOM..COVER_MAX_ZOOM`）。 */
export function clampZoom(zoom: number): number {
  return clamp(zoom, COVER_MIN_ZOOM, COVER_MAX_ZOOM)
}

/** 初始状态：不放大、取正中。 */
export function fitCropState(): CoverCropState {
  return { zoom: COVER_MIN_ZOOM, centerX: 0.5, centerY: 0.5 }
}

/**
 * 把中心夹回"裁剪窗仍在源图内"的范围 —— 这是**不出现空白边**的唯一保证。
 *
 * <p>裁剪窗与源图等高 / 等宽时该轴只有唯一解（0.5），`clamp` 的 `min > max` 分支返回中点，
 * 避免浮点误差把中心推出去。
 */
export function clampCropState(state: CoverCropState, image: Size): CoverCropState {
  const zoom = clampZoom(state.zoom)
  const size = maxCropSize(image)
  if (size.width <= 0 || size.height <= 0) {
    return { zoom, centerX: 0.5, centerY: 0.5 }
  }
  const halfWidth = size.width / zoom / 2 / image.width
  const halfHeight = size.height / zoom / 2 / image.height
  return {
    zoom,
    centerX: clamp(state.centerX, halfWidth, 1 - halfWidth),
    centerY: clamp(state.centerY, halfHeight, 1 - halfHeight),
  }
}

/** 裁剪窗在源图像素里的矩形（**恒为 16:9**，且不越出源图）。 */
export function cropRect(image: Size, state: CoverCropState): CropRect {
  const safe = clampCropState(state, image)
  const size = maxCropSize(image)
  if (size.width <= 0 || size.height <= 0) {
    return { x: 0, y: 0, width: 0, height: 0 }
  }
  const width = size.width / safe.zoom
  const height = size.height / safe.zoom
  return {
    x: safe.centerX * image.width - width / 2,
    y: safe.centerY * image.height - height / 2,
    width,
    height,
  }
}

/**
 * 源图在舞台上的绘制参数：让裁剪窗**正好铺满舞台**。
 *
 * <p>`scale` 取两轴较大值 ⇒ 舞台比例哪怕有 1px 误差也不会露底（多出的部分被舞台裁掉）。
 */
export function stageLayout(image: Size, stage: Size, state: CoverCropState): StageLayout {
  const rect = cropRect(image, state)
  if (!isPositive(rect.width) || !isPositive(rect.height) || !isPositive(stage.width) || !isPositive(stage.height)) {
    return { scale: 1, offsetX: 0, offsetY: 0 }
  }
  const scale = Math.max(stage.width / rect.width, stage.height / rect.height)
  return {
    scale,
    offsetX: stage.width / 2 - (rect.x + rect.width / 2) * scale,
    offsetY: stage.height / 2 - (rect.y + rect.height / 2) * scale,
  }
}

/**
 * 拖动（`dx/dy` 是舞台上的位移，CSS px）：拖的是**图**，裁剪窗相对源图反向移动。
 *
 * <p>典型误写是 `centerX += dx`（把归一化坐标当成了舞台像素），在 1000px 宽的舞台上
 * 拖 10px 就会把构图甩到边缘 —— 单测用"位移 / scale 后正好等于源图位移"钉住。
 */
export function dragBy(
  image: Size,
  stage: Size,
  state: CoverCropState,
  dx: number,
  dy: number,
): CoverCropState {
  const layout = stageLayout(image, stage, state)
  if (!isPositive(layout.scale)) return clampCropState(state, image)
  const rect = cropRect(image, state)
  return clampCropState(
    {
      zoom: state.zoom,
      centerX: (rect.x + rect.width / 2 - dx / layout.scale) / image.width,
      centerY: (rect.y + rect.height / 2 - dy / layout.scale) / image.height,
    },
    image,
  )
}

/**
 * 以舞台上某点（一般是鼠标 / 手指位置）为锚缩放：**锚点下的源图点缩放前后不动**。
 *
 * <p>把光标当作"手指按住的那个像素"，缩放才不会跑焦 —— 这也是 `zoomAround` 唯一要保证的性质，
 * 单测直接按这个等式复算（而不是断言某个具体数值）。
 */
export function zoomAround(
  image: Size,
  stage: Size,
  state: CoverCropState,
  zoom: number,
  anchor: Point,
): CoverCropState {
  const before = cropRect(image, state)
  const layout = stageLayout(image, stage, state)
  if (!isPositive(before.width) || !isPositive(layout.scale)) return clampCropState(state, image)
  const center = { x: before.x + before.width / 2, y: before.y + before.height / 2 }
  const offsetX = anchor.x - stage.width / 2
  const offsetY = anchor.y - stage.height / 2
  // 锚点对应的源图像素
  const source = { x: center.x + offsetX / layout.scale, y: center.y + offsetY / layout.scale }

  const next = clampZoom(zoom)
  const size = maxCropSize(image)
  const width = size.width / next
  const height = size.height / next
  // 缩放后要让同一个源图像素仍落在锚点下 ⇒ 反解新的裁剪窗中心
  const nextCenterX = source.x - (offsetX * width) / stage.width
  const nextCenterY = source.y - (offsetY * height) / stage.height
  return clampCropState(
    { zoom: next, centerX: nextCenterX / image.width, centerY: nextCenterY / image.height },
    image,
  )
}

/**
 * 裁剪产物的文件名：`photo.png` → `photo-cover.jpg`。
 *
 * <p>★ 名字参与两处**正确性**判定：`uploadDraft.fileKey`（同一文件的重试复用已传 URL）
 * 与 `draftSignature`（幂等键指纹）。裁剪后的文件必须与原图**可区分**，
 * 否则"换了一张脸"却复用了旧键 / 旧 URL。对已裁过的名字幂等（`a-cover.jpg` 不再叠后缀），
 * 避免"裁两次"出现 `a-cover-cover-cover.jpg`。
 */
export function coverFileName(originalName: string): string {
  const dot = originalName.lastIndexOf('.')
  const base = dot > 0 ? originalName.slice(0, dot) : originalName
  const stem = base ? (base.endsWith('-cover') ? base : `${base}-cover`) : 'cover'
  return `${stem}.jpg`
}

/** 导出尺寸（按目标比例算高，避免两个常量漂移）。 */
export function coverOutputSize(): Size {
  return { width: COVER_OUTPUT_WIDTH, height: COVER_OUTPUT_HEIGHT }
}
