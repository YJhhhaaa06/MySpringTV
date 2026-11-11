import {
  COVER_MAX_ZOOM,
  COVER_MIN_ZOOM,
  clampCropState,
  cropRect,
  dragBy,
  fitCropState,
  maxCropSize,
  stageLayout,
  zoomAround,
} from './coverCrop'
import type { CoverCropState, CropRect, Point, Size, StageLayout } from './coverCrop'

/**
 * 头像裁剪的**1:1 代理层**（`R-27`）。
 *
 * <p>几何纯函数只在 `coverCrop.ts` 一处（拖动 / 锚点缩放 / 不越界）；
 * 本文件只把 `aspect` 固定成 1、给出 180×180 产物与文件名。这样封面的 16:9 行为
 * **一行都不改**（默认参数仍是 `COVER_ASPECT`），头像也不复制第二套数学。
 */

/** 头像裁剪窗恒为正方形。 */
export const AVATAR_ASPECT = 1

/** 产物边长：B 站提示 180×180（`R-27` 用户拍板）。 */
export const AVATAR_OUTPUT_SIZE = 180

export const AVATAR_MIN_ZOOM = COVER_MIN_ZOOM
export const AVATAR_MAX_ZOOM = COVER_MAX_ZOOM

export function avatarMaxCropSize(image: Size): Size {
  return maxCropSize(image, AVATAR_ASPECT)
}

export function avatarCropRect(image: Size, state: CoverCropState): CropRect {
  return cropRect(image, state, AVATAR_ASPECT)
}

export function avatarStageLayout(image: Size, stage: Size, state: CoverCropState): StageLayout {
  return stageLayout(image, stage, state, AVATAR_ASPECT)
}

export function avatarDragBy(
  image: Size,
  stage: Size,
  state: CoverCropState,
  dx: number,
  dy: number,
): CoverCropState {
  return dragBy(image, stage, state, dx, dy, AVATAR_ASPECT)
}

export function avatarZoomAround(
  image: Size,
  stage: Size,
  state: CoverCropState,
  zoom: number,
  anchor: Point,
): CoverCropState {
  return zoomAround(image, stage, state, zoom, anchor, AVATAR_ASPECT)
}

export function avatarClampCropState(state: CoverCropState, image: Size): CoverCropState {
  return clampCropState(state, image, AVATAR_ASPECT)
}

/** 产物尺寸（宽高同值；避免两个常量漂移）。 */
export function avatarOutputSize(): Size {
  return { width: AVATAR_OUTPUT_SIZE, height: AVATAR_OUTPUT_SIZE }
}

/**
 * 裁剪产物文件名：`photo.png` → `photo-avatar.jpg`。
 *
 * <p>头像上传**不**用文件名参与幂等（服务端每次都生成 UUID），但保持与封面同一套
 * "可区分原图 / 可重复裁剪"的规则：对 `photo-avatar.jpg` 再裁一次不会叠后缀。
 */
export function avatarFileName(originalName: string): string {
  const dot = originalName.lastIndexOf('.')
  const base = dot > 0 ? originalName.slice(0, dot) : originalName
  const stem = base ? (base.endsWith('-avatar') ? base : `${base}-avatar`) : 'avatar'
  return `${stem}.jpg`
}

export { fitCropState }
export type { CoverCropState, CropRect, Point, Size, StageLayout }
