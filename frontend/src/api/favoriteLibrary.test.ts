import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { LibraryFolderVM, PageVM, VideoCardVM } from '../types/ui'

vi.mock('./favorites', () => ({
  loadMyFolders: vi.fn(),
  loadMyFolderItems: vi.fn(),
  loadWatchLater: vi.fn(),
  removeFavoriteItems: vi.fn(),
  removeWatchLater: vi.fn(),
}))

vi.mock('./profile', () => ({
  loadPublicFolders: vi.fn(),
  loadFolderItems: vi.fn(),
}))

import * as mine from './favorites'
import * as pub from './profile'
import {
  loadLibraryFolders,
  loadLibraryItems,
  loadLibraryLater,
  loadLibraryLaterTotal,
  removeLibraryItem,
  removeLibraryLater,
} from './favoriteLibrary'

const mineFolder: LibraryFolderVM = {
  id: 1,
  name: '默认收藏夹',
  itemCount: 2,
  coverUrl: '/upload/favorite-cover/a.jpg',
  hasCustomCover: true,
  isPrivate: true,
  isDefault: true,
}

const publicFolder: LibraryFolderVM = {
  id: 2,
  name: '公开收藏夹',
  itemCount: 1,
  coverUrl: null,
  hasCustomCover: false,
  isPrivate: false,
  isDefault: null,
}

function emptyPage(): PageVM<VideoCardVM> {
  return { items: [], page: 1, total: 0, totalPages: 0 }
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('夹列表数据源', () => {
  it('★ self 走我的端点：私密夹与默认夹信息都保留', async () => {
    vi.mocked(mine.loadMyFolders).mockResolvedValue([mineFolder])

    await expect(loadLibraryFolders(5, true)).resolves.toEqual([mineFolder])
    expect(mine.loadMyFolders).toHaveBeenCalledOnce()
    expect(pub.loadPublicFolders).not.toHaveBeenCalled()
  })

  it('★ 看别人走公开端点：不去调我的端点（私密夹不可能泄露）', async () => {
    vi.mocked(pub.loadPublicFolders).mockResolvedValue([publicFolder])

    await expect(loadLibraryFolders(5, false)).resolves.toEqual([publicFolder])
    expect(pub.loadPublicFolders).toHaveBeenCalledWith(5)
    expect(mine.loadMyFolders).not.toHaveBeenCalled()
  })
})

describe('夹内条目数据源', () => {
  it('★ self 走 `GET /favorites?folderId=` 的分页封装', async () => {
    vi.mocked(mine.loadMyFolderItems).mockResolvedValue(emptyPage())

    await loadLibraryItems(7, true, 2, 12)
    expect(mine.loadMyFolderItems).toHaveBeenCalledWith(7, 2, 12)
    expect(pub.loadFolderItems).not.toHaveBeenCalled()
  })

  it('★ 看别人走公开夹端点', async () => {
    vi.mocked(pub.loadFolderItems).mockResolvedValue(emptyPage())

    await loadLibraryItems(7, false, 2, 12)
    expect(pub.loadFolderItems).toHaveBeenCalledWith(7, 2, 12)
    expect(mine.loadMyFolderItems).not.toHaveBeenCalled()
  })
})

describe('稍后再看', () => {
  it('分页走 `GET /watch-later`；计数只取一条再读 total', async () => {
    const page = { ...emptyPage(), total: 3 }
    vi.mocked(mine.loadWatchLater).mockResolvedValue(page)

    await expect(loadLibraryLater(2, 12)).resolves.toEqual(page)
    expect(mine.loadWatchLater).toHaveBeenCalledWith(2, 12)

    await expect(loadLibraryLaterTotal()).resolves.toBe(3)
    expect(mine.loadWatchLater).toHaveBeenCalledWith(1, 1)
  })
})

describe('移出', () => {
  it('夹内移出 = 单夹 + 一批内容（当前只一条）', async () => {
    vi.mocked(mine.removeFavoriteItems).mockResolvedValue(undefined)

    await removeLibraryItem(7, 65)
    expect(mine.removeFavoriteItems).toHaveBeenCalledWith(7, [65])
    expect(mine.removeWatchLater).not.toHaveBeenCalled()
  })

  it('稍后再看移出走 `DELETE /watch-later`', async () => {
    vi.mocked(mine.removeWatchLater).mockResolvedValue(undefined)

    await removeLibraryLater(65)
    expect(mine.removeWatchLater).toHaveBeenCalledWith([65])
    expect(mine.removeFavoriteItems).not.toHaveBeenCalled()
  })
})
