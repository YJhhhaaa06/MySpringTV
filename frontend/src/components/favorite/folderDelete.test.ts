import { describe, expect, it } from 'vitest'
import type { LibraryFolderVM } from '../../types/ui'
import {
  canDeleteFolder,
  isDeletedFolderStillActive,
  nextFolderAfterDelete,
} from './folderDelete'

function folder(id: number, options: Partial<LibraryFolderVM> = {}): LibraryFolderVM {
  return {
    id,
    name: `夹 ${id}`,
    itemCount: 0,
    coverUrl: null,
    hasCustomCover: false,
    isPrivate: false,
    isDefault: false,
    ...options,
  }
}

describe('folderDelete', () => {
  it('★默认夹不给删除入口（isDefault=true）；自建夹给', () => {
    expect(canDeleteFolder(folder(1, { isDefault: true }))).toBe(false)
    expect(canDeleteFolder(folder(2, { isDefault: false }))).toBe(true)
  })

  it('★公开面 isDefault=null（不知道）也不给删除入口 —— 不拿未知当"不是默认"', () => {
    expect(canDeleteFolder(folder(3, { isDefault: null }))).toBe(false)
  })

  it('删除当前夹后优先落到默认夹（哪怕它在列表里既不是第一个也不是最后一个）', () => {
    // 夹具专门让 id 序 / 列表序都与"默认夹优先"反向：
    //   取第一个剩余 ⇒ 1；取最后一个 / 最大 id ⇒ 3；只有"默认夹优先"才得 2。
    const target = folder(4)
    const first = folder(1)
    const defaultFolder = folder(2, { isDefault: true })
    const last = folder(3)

    expect(nextFolderAfterDelete([target, first, defaultFolder, last], 4)?.id).toBe(2)
  })

  it('没有默认夹时落到剩余列表的第一个（不是最后一个）', () => {
    const first = folder(1)
    const second = folder(2)
    const third = folder(3)

    expect(nextFolderAfterDelete([first, second, third], 1)?.id).toBe(2)
  })

  it('删掉最后一个夹返回 null（右栏回到"选择一个收藏夹"）', () => {
    expect(nextFolderAfterDelete([folder(1)], 1)).toBeNull()
  })

  it('不修改入参列表', () => {
    const list = [folder(1), folder(2)]
    nextFolderAfterDelete(list, 1)

    expect(list.map((item) => item.id)).toEqual([1, 2])
  })
})

describe('isDeletedFolderStillActive', () => {
  it('回包时仍停在被删的夹 → 需要换落点', () => {
    expect(isDeletedFolderStillActive('folders', 'folder', 7, 7)).toBe(true)
  })

  it('★请求期间切到另一夹 → 不能把用户拉回被删夹的替代夹', () => {
    expect(isDeletedFolderStillActive('folders', 'folder', 8, 7)).toBe(false)
  })

  it('★请求期间切到稍后再看 → 不换落点', () => {
    expect(isDeletedFolderStillActive('folders', 'later', 0, 7)).toBe(false)
  })

  it('★请求期间切到主页 / 其它 tab → 不换落点', () => {
    expect(isDeletedFolderStillActive('home', 'folder', 7, 7)).toBe(false)
  })
})
