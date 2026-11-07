import { describe, expect, it } from 'vitest'
import {
  FOLDER_NAME_MAX,
  folderEditPatch,
  folderNameChangeError,
  folderNameError,
  hasFolderEditChanges,
  normalizeFolderName,
} from './folderEdit'

describe('normalizeFolderName', () => {
  it('去掉首尾空白；中间空白保留（不替用户改文案）', () => {
    expect(normalizeFolderName('  我的 夹  ')).toBe('我的 夹')
    expect(normalizeFolderName('   ')).toBe('')
  })
})

describe('folderNameError', () => {
  it('产品上限固定 20（与新建弹窗一致，不跟后端 100 漂移）', () => {
    expect(FOLDER_NAME_MAX).toBe(20)
  })

  it('空 / 全空白 → 不能为空', () => {
    expect(folderNameError('')).toBe('收藏夹名称不能为空')
    expect(folderNameError('   ')).toBe('收藏夹名称不能为空')
  })

  it('恰好 20 字合法；21 字超长（按 trim 后的长度算）', () => {
    // 边界用字面量 20/21，不用被测常量构造期望 —— 常量改成 100 时这里必须红
    expect(folderNameError('a'.repeat(20))).toBe('')
    expect(folderNameError(`  ${'a'.repeat(20)}  `)).toBe('')
    expect(folderNameError('a'.repeat(21))).toContain('20')
  })
})

describe('folderNameChangeError', () => {
  it('名字没改（含只差首尾空白）⇒ 不校验：既有长名也能只改公开', () => {
    const longName = 'a'.repeat(80)
    expect(folderNameChangeError(longName, longName)).toBe('')
    expect(folderNameChangeError(longName, `  ${longName}  `)).toBe('')
  })

  it('名字改了才校验：空 / 超长报错', () => {
    expect(folderNameChangeError('默认收藏夹', '')).toBe('收藏夹名称不能为空')
    expect(folderNameChangeError('默认收藏夹', 'a'.repeat(21))).toContain('20')
    expect(folderNameChangeError('默认收藏夹', '新名字')).toBe('')
  })
})

describe('folderEditPatch', () => {
  const folder = { name: '默认收藏夹', isPrivate: false }

  it('什么都没改（含只差首尾空白）⇒ 空对象，调用方不发请求', () => {
    const patch = folderEditPatch(folder, ' 默认收藏夹 ', false)
    expect(patch).toEqual({})
    expect(hasFolderEditChanges(patch)).toBe(false)
  })

  it('只改名 ⇒ 只带 name，不把 isPrivate 也塞进去', () => {
    const patch = folderEditPatch(folder, ' 新名字 ', false)
    expect(patch).toEqual({ name: '新名字' })
    expect(hasFolderEditChanges(patch)).toBe(true)
  })

  it('只改公开开关 ⇒ 只带 isPrivate', () => {
    expect(folderEditPatch(folder, '默认收藏夹', true)).toEqual({ isPrivate: true })
    expect(folderEditPatch({ ...folder, isPrivate: true }, '默认收藏夹', false)).toEqual({
      isPrivate: false,
    })
  })

  it('名称与公开都改 ⇒ 两个字段都带', () => {
    expect(folderEditPatch(folder, '新名字', true)).toEqual({ name: '新名字', isPrivate: true })
  })
})
