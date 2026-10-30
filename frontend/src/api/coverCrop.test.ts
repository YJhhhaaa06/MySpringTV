import { describe, expect, it } from 'vitest'
import {
  COVER_ASPECT,
  COVER_MAX_ZOOM,
  COVER_MIN_ZOOM,
  COVER_OUTPUT_HEIGHT,
  COVER_OUTPUT_WIDTH,
  clampZoom,
  coverFileName,
  coverOutputSize,
  cropRect,
  dragBy,
  fitCropState,
  maxCropSize,
  stageLayout,
  zoomAround,
} from './coverCrop'
import type { CoverCropState, Size } from './coverCrop'

const STAGE: Size = { width: 640, height: 360 }

/** 断言裁剪窗是 16:9（用比例复算，而不是硬编码宽高）。 */
function expectCoverRatio(rect: { width: number; height: number }): void {
  expect(rect.width / rect.height).toBeCloseTo(COVER_ASPECT, 6)
}

describe('coverCrop · 裁剪窗几何', () => {
  it('zoom = 1 时取源图内最大的 16:9 矩形：横图裁左右、竖图裁上下、方图裁上下', () => {
    // 1920×1080 本身就是 16:9 ⇒ 一点都不能切（否则"裁剪"把内容吃掉了）
    expect(maxCropSize({ width: 1920, height: 1080 })).toEqual({ width: 1920, height: 1080 })

    // 1080×1920 竖图：宽度顶满，高度按 16:9 算 ⇒ 只保留中间 607.5
    const portrait = maxCropSize({ width: 1080, height: 1920 })
    expect(portrait.width).toBeCloseTo(1080, 6)
    expect(portrait.height).toBeCloseTo(607.5, 6)

    // 1000×1000 方图：宽度顶满 ⇒ 高度 562.5（B 站封面常见的"上面标题被切掉"就是这一刀）
    const square = maxCropSize({ width: 1000, height: 1000 })
    expect(square.width).toBeCloseTo(1000, 6)
    expect(square.height).toBeCloseTo(562.5, 6)
  })

  it('初始状态取正中：竖图上下留白相等', () => {
    const rect = cropRect({ width: 1080, height: 1920 }, fitCropState())
    expect(rect.y).toBeCloseTo((1920 - 607.5) / 2, 6)
    expectCoverRatio(rect)
  })

  it('zoom 放大 = 裁剪窗等比缩小、中心不动', () => {
    const image = { width: 1920, height: 1080 }
    const base = cropRect(image, fitCropState())
    const zoomed = cropRect(image, { ...fitCropState(), zoom: 2 })
    expect(zoomed.width).toBeCloseTo(base.width / 2, 6)
    expect(zoomed.height).toBeCloseTo(base.height / 2, 6)
    // 中心不变（x + w/2 仍等于源图中心）
    expect(zoomed.x + zoomed.width / 2).toBeCloseTo(base.x + base.width / 2, 6)
    expectCoverRatio(zoomed)
  })

  it('中心被夹在源图内：拖到极左 / 极右都不会让裁剪窗越界（不出现空白边）', () => {
    const image = { width: 1920, height: 1080 }
    const left = cropRect(image, { zoom: 2, centerX: 0, centerY: 0 })
    const right = cropRect(image, { zoom: 2, centerX: 1, centerY: 1 })

    expect(left.x).toBeCloseTo(0, 6)
    expect(left.y).toBeCloseTo(0, 6)
    expect(right.x + right.width).toBeCloseTo(image.width, 6)
    expect(right.y + right.height).toBeCloseTo(image.height, 6)
  })

  it('任何状态都保持 16:9 且落在源图内（覆盖横 / 竖 / 方 / 超宽四类源图）', () => {
    const images: Size[] = [
      { width: 1920, height: 1080 },
      { width: 1080, height: 1920 },
      { width: 1000, height: 1000 },
      { width: 4000, height: 600 },
    ]
    const states: CoverCropState[] = [
      fitCropState(),
      { zoom: 1.7, centerX: 0, centerY: 1 },
      { zoom: COVER_MAX_ZOOM, centerX: 0.13, centerY: 0.92 },
      { zoom: 99, centerX: -3, centerY: 4 },
    ]
    for (const image of images) {
      for (const state of states) {
        const rect = cropRect(image, state)
        expectCoverRatio(rect)
        expect(rect.x).toBeGreaterThanOrEqual(-1e-6)
        expect(rect.y).toBeGreaterThanOrEqual(-1e-6)
        expect(rect.x + rect.width).toBeLessThanOrEqual(image.width + 1e-6)
        expect(rect.y + rect.height).toBeLessThanOrEqual(image.height + 1e-6)
      }
    }
  })

  it('源图尺寸非法（0 / NaN）返回空矩形，不把 NaN 传给 canvas', () => {
    const rect = cropRect({ width: 0, height: 0 }, fitCropState())
    expect(rect).toEqual({ x: 0, y: 0, width: 0, height: 0 })
    expect(Number.isFinite(stageLayout({ width: Number.NaN, height: 10 }, STAGE, fitCropState()).scale)).toBe(true)
  })
})

describe('coverCrop · 舞台映射（拖动 / 缩放的物理含义）', () => {
  it('裁剪窗正好铺满舞台：源图矩形经 scale/offset 后落在 [0, 舞台宽] 上', () => {
    const image = { width: 1080, height: 1920 }
    const state: CoverCropState = { zoom: 2.5, centerX: 0.3, centerY: 0.7 }
    const rect = cropRect(image, state)
    const layout = stageLayout(image, STAGE, state)

    expect(rect.x * layout.scale + layout.offsetX).toBeCloseTo(0, 6)
    expect(rect.y * layout.scale + layout.offsetY).toBeCloseTo(0, 6)
    expect(rect.width * layout.scale).toBeCloseTo(STAGE.width, 6)
    expect(rect.height * layout.scale).toBeCloseTo(STAGE.height, 6)
  })

  it('拖动 dx 舞台像素 = 裁剪窗在源图上反向移动 dx / scale 像素', () => {
    const image = { width: 1920, height: 1080 }
    const state: CoverCropState = { zoom: 2, centerX: 0.5, centerY: 0.5 }
    const before = cropRect(image, state)
    const scale = stageLayout(image, STAGE, state).scale

    // 往右拖 60px（把图往右推）⇒ 裁剪窗相对源图往左移 60/scale
    const after = cropRect(image, dragBy(image, STAGE, state, 60, 0))
    expect(before.x - after.x).toBeCloseTo(60 / scale, 6)
    // 纵向不动
    expect(after.y).toBeCloseTo(before.y, 6)
  })

  it('拖动超量被夹住：一直往右拖，裁剪窗停在源图左边缘', () => {
    const image = { width: 1920, height: 1080 }
    let state: CoverCropState = { zoom: 2, centerX: 0.5, centerY: 0.5 }
    for (let step = 0; step < 50; step += 1) {
      state = dragBy(image, STAGE, state, 200, 0)
    }
    expect(cropRect(image, state).x).toBeCloseTo(0, 6)
  })

  it('以光标为锚缩放：锚点下的源图像素前后不动（缩放不跑焦）', () => {
    const image = { width: 1920, height: 1080 }
    const anchor = { x: 120, y: 90 } // 舞台左上角附近，不在中心
    const before = fitCropState()
    const after = zoomAround(image, STAGE, before, 2.2, anchor)

    const rectBefore = cropRect(image, before)
    const layoutBefore = stageLayout(image, STAGE, before)
    // 舞台 = 源图 × scale + offset ⇒ 反过来解锚点对应的源图像素
    const sourceBefore = {
      x: (anchor.x - layoutBefore.offsetX) / layoutBefore.scale,
      y: (anchor.y - layoutBefore.offsetY) / layoutBefore.scale,
    }

    const rectAfter = cropRect(image, after)
    const layoutAfter = stageLayout(image, STAGE, after)
    const sourceAfter = {
      x: (anchor.x - layoutAfter.offsetX) / layoutAfter.scale,
      y: (anchor.y - layoutAfter.offsetY) / layoutAfter.scale,
    }

    // 前置条件：锚点确实落在裁剪窗内（否则这段区域不会被导出，等式没有意义）
    expect(sourceBefore.x).toBeGreaterThan(rectBefore.x)
    expect(sourceBefore.x).toBeLessThan(rectBefore.x + rectBefore.width)
    expect(sourceBefore.y).toBeGreaterThan(rectBefore.y)
    expect(sourceBefore.y).toBeLessThan(rectBefore.y + rectBefore.height)
    expect(rectAfter.x).not.toBeCloseTo(rectBefore.x, 3)

    expect(after.zoom).toBeCloseTo(2.2, 6)
    expect(sourceAfter.x).toBeCloseTo(sourceBefore.x, 6)
    expect(sourceAfter.y).toBeCloseTo(sourceBefore.y, 6)
  })

  it('缩放倍数夹在 1..4：缩到最小不会露出空白、放大不会变成像素糊', () => {
    const image = { width: 1920, height: 1080 }
    expect(clampZoom(0.2)).toBe(COVER_MIN_ZOOM)
    expect(clampZoom(40)).toBe(COVER_MAX_ZOOM)
    expect(clampZoom(Number.NaN)).toBe(COVER_MIN_ZOOM)
    // 缩到 1 以下 ⇒ 裁剪窗仍是"内接矩形"（等于 zoom=1）
    expect(cropRect(image, { ...fitCropState(), zoom: 0.1 })).toEqual(cropRect(image, fitCropState()))
  })
})

describe('coverCrop · 输出契约', () => {
  it('导出 1280×720（16:9），与目标比例只在一处定义', () => {
    expect(coverOutputSize()).toEqual({ width: COVER_OUTPUT_WIDTH, height: COVER_OUTPUT_HEIGHT })
    expect(COVER_OUTPUT_WIDTH / COVER_OUTPUT_HEIGHT).toBeCloseTo(COVER_ASPECT, 6)
  })

  it('裁剪产物文件名：换扩展名、可重复裁剪、无扩展名也不炸', () => {
    expect(coverFileName('photo.png')).toBe('photo-cover.jpg')
    expect(coverFileName('我的封面.PNG')).toBe('我的封面-cover.jpg')
    // 幂等：对已裁过的图再裁一次，不叠 -cover
    expect(coverFileName('photo-cover.jpg')).toBe('photo-cover.jpg')
    expect(coverFileName('noext')).toBe('noext-cover.jpg')
    expect(coverFileName('')).toBe('cover.jpg')
    // 点开头（.gitignore 这类）不能被当成扩展名剁掉
    expect(coverFileName('.hidden')).toBe('.hidden-cover.jpg')
  })
})
