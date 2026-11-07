import { describe, expect, it } from 'vitest'
import {
  AVATAR_ASPECT,
  AVATAR_MAX_ZOOM,
  AVATAR_OUTPUT_SIZE,
  avatarCropRect,
  avatarDragBy,
  avatarFileName,
  avatarMaxCropSize,
  avatarOutputSize,
  avatarStageLayout,
  avatarZoomAround,
  fitCropState,
} from './avatarCrop'
import type { CoverCropState, Size } from './coverCrop'

const STAGE: Size = { width: 320, height: 320 }

function expectSquare(rect: { width: number; height: number }): void {
  expect(rect.width / rect.height).toBeCloseTo(AVATAR_ASPECT, 6)
}

describe('avatarCrop · 1:1 裁剪窗', () => {
  it('zoom = 1 时取源图内最大正方形：横图裁左右、竖图裁上下', () => {
    expect(avatarMaxCropSize({ width: 800, height: 400 })).toEqual({ width: 400, height: 400 })
    expect(avatarMaxCropSize({ width: 300, height: 600 })).toEqual({ width: 300, height: 300 })
    expect(avatarMaxCropSize({ width: 500, height: 500 })).toEqual({ width: 500, height: 500 })
  })

  it('初始状态取正中；放大等比缩小且中心不动', () => {
    const image = { width: 800, height: 400 }
    const base = avatarCropRect(image, fitCropState())
    expect(base.x).toBeCloseTo(200, 6)
    expect(base.y).toBeCloseTo(0, 6)
    expectSquare(base)

    const zoomed = avatarCropRect(image, { ...fitCropState(), zoom: 2 })
    expect(zoomed.width).toBeCloseTo(base.width / 2, 6)
    expect(zoomed.x + zoomed.width / 2).toBeCloseTo(base.x + base.width / 2, 6)
    expectSquare(zoomed)
  })

  it('任何状态都不越出源图（横 / 竖 / 方 / 超宽）', () => {
    const images: Size[] = [
      { width: 800, height: 400 },
      { width: 300, height: 600 },
      { width: 500, height: 500 },
      { width: 4000, height: 600 },
    ]
    const states: CoverCropState[] = [
      fitCropState(),
      { zoom: AVATAR_MAX_ZOOM, centerX: 0, centerY: 1 },
      { zoom: 0.01, centerX: -3, centerY: 4 },
    ]
    for (const image of images) {
      for (const state of states) {
        const rect = avatarCropRect(image, state)
        expectSquare(rect)
        expect(rect.x).toBeGreaterThanOrEqual(-1e-6)
        expect(rect.y).toBeGreaterThanOrEqual(-1e-6)
        expect(rect.x + rect.width).toBeLessThanOrEqual(image.width + 1e-6)
        expect(rect.y + rect.height).toBeLessThanOrEqual(image.height + 1e-6)
      }
    }
  })

  it('拖动：舞台位移换算成源图位移（与封面同一等式，只换比例）', () => {
    const image = { width: 800, height: 800 }
    const state: CoverCropState = { zoom: 2, centerX: 0.5, centerY: 0.5 }
    const before = avatarCropRect(image, state)
    const scale = avatarStageLayout(image, STAGE, state).scale

    const after = avatarCropRect(image, avatarDragBy(image, STAGE, state, 60, 0))
    expect(before.x - after.x).toBeCloseTo(60 / scale, 6)
    expect(after.y).toBeCloseTo(before.y, 6)
  })

  it('锚点缩放：锚点下的源图像素前后不动', () => {
    const image = { width: 800, height: 800 }
    const anchor = { x: 70, y: 50 }
    const before = fitCropState()
    const after = avatarZoomAround(image, STAGE, before, 2, anchor)

    const rectBefore = avatarCropRect(image, before)
    const layoutBefore = avatarStageLayout(image, STAGE, before)
    const sourceBefore = {
      x: (anchor.x - layoutBefore.offsetX) / layoutBefore.scale,
      y: (anchor.y - layoutBefore.offsetY) / layoutBefore.scale,
    }

    const layoutAfter = avatarStageLayout(image, STAGE, after)
    const sourceAfter = {
      x: (anchor.x - layoutAfter.offsetX) / layoutAfter.scale,
      y: (anchor.y - layoutAfter.offsetY) / layoutAfter.scale,
    }

    expect(sourceBefore.x).toBeGreaterThan(rectBefore.x)
    expect(sourceBefore.x).toBeLessThan(rectBefore.x + rectBefore.width)
    expect(sourceAfter.x).toBeCloseTo(sourceBefore.x, 6)
    expect(sourceAfter.y).toBeCloseTo(sourceBefore.y, 6)
  })
})

describe('avatarCrop · 输出契约', () => {
  it('产物 180×180（与 B 站提示一致）', () => {
    expect(avatarOutputSize()).toEqual({ width: AVATAR_OUTPUT_SIZE, height: AVATAR_OUTPUT_SIZE })
    expect(AVATAR_OUTPUT_SIZE).toBe(180)
  })

  it('文件名换扩展名、可重复裁剪、无扩展名也不炸', () => {
    expect(avatarFileName('photo.png')).toBe('photo-avatar.jpg')
    expect(avatarFileName('我的头像.JPG')).toBe('我的头像-avatar.jpg')
    expect(avatarFileName('photo-avatar.jpg')).toBe('photo-avatar.jpg')
    expect(avatarFileName('noext')).toBe('noext-avatar.jpg')
    expect(avatarFileName('')).toBe('avatar.jpg')
    expect(avatarFileName('.hidden')).toBe('.hidden-avatar.jpg')
  })
})
