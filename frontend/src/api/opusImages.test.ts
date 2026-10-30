import { describe, expect, it } from 'vitest'
import { gridColumns, gridPreview, isEditableTarget, rotateBy, stepIndex } from './opusImages'

describe('gridColumns', () => {
  it('1 张单图、2 张两列、3 张及以上三列（九宫格）', () => {
    expect(gridColumns(1)).toBe(1)
    expect(gridColumns(2)).toBe(2)
    expect(gridColumns(3)).toBe(3)
    expect(gridColumns(6)).toBe(3)
    expect(gridColumns(9)).toBe(3)
  })
})

describe('gridPreview', () => {
  it('不超过 9 张 ⇒ 全部平铺，overflow = 0', () => {
    expect(gridPreview([])).toEqual({ shown: [], overflow: 0 })
    expect(gridPreview(['a', 'b'])).toEqual({ shown: ['a', 'b'], overflow: 0 })
    expect(gridPreview(Array.from({ length: 9 }, (_, i) => `i${i}`)).shown).toHaveLength(9)
  })

  it('★ 超过 9 张 ⇒ 只平铺前 9 张，overflow 是"没展示的张数"（不是总张数）', () => {
    const twelve = Array.from({ length: 12 }, (_, i) => `i${i}`)
    const preview = gridPreview(twelve)
    expect(preview.shown).toEqual(twelve.slice(0, 9))
    expect(preview.overflow).toBe(3)
  })
})

describe('stepIndex', () => {
  it('循环前后翻：首张往前是末张、末张往后是首张', () => {
    expect(stepIndex(0, -1, 6)).toBe(5)
    expect(stepIndex(5, 1, 6)).toBe(0)
    expect(stepIndex(2, 1, 6)).toBe(3)
    expect(stepIndex(2, -1, 6)).toBe(1)
  })

  it('空数组 ⇒ 0（不抛错）', () => {
    expect(stepIndex(3, 1, 0)).toBe(0)
  })
})

describe('isEditableTarget', () => {
  it('输入控件 / contenteditable 不接快捷键（否则评论框里按 ←/→ 会切图）', () => {
    expect(isEditableTarget('INPUT', false)).toBe(true)
    expect(isEditableTarget('TEXTAREA', false)).toBe(true)
    expect(isEditableTarget('SELECT', false)).toBe(true)
    expect(isEditableTarget('DIV', true)).toBe(true)
  })

  it('普通容器 / 按钮照常接快捷键', () => {
    expect(isEditableTarget('DIV', false)).toBe(false)
    expect(isEditableTarget('BUTTON', false)).toBe(false)
    expect(isEditableTarget('BODY', false)).toBe(false)
  })
})

describe('rotateBy', () => {
  it('★ 只保留 0 / 90 / 180 / 270：向左归一成 270，不出现负角度', () => {
    expect(rotateBy(0, -90)).toBe(270)
    expect(rotateBy(270, 90)).toBe(0)
    expect(rotateBy(90, 90)).toBe(180)
    expect(rotateBy(180, 90)).toBe(270)
  })

  it('多圈也只回到四个角之一', () => {
    expect(rotateBy(0, 450)).toBe(90)
    expect(rotateBy(90, -450)).toBe(0)
  })
})
