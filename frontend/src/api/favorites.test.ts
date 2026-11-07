import { beforeEach, describe, expect, it, vi } from 'vitest'
import { deleteMyFolder } from './favorites'
import { deleteJson } from './http'

/**
 * 删除收藏夹的端点层（`R-36`）。
 *
 * <p>这个函数的全部价值就在"把 id 放进路径"：写成 query / body 都会 404
 * （后端只认 `DELETE /favorite-folders/{id}`），界面却只看到"删除失败"。
 *
 * <h2>反向验证（注入 ⇒ 必红）</h2>
 * 把实现改成 `deleteJson('/api/v1/favorite-folders', { query: { folderId } })`
 * ⇒ 本用例红。
 */
vi.mock('./http', () => ({
  deleteJson: vi.fn(() => Promise.resolve(undefined)),
  getJson: vi.fn(),
  putForm: vi.fn(),
  putJson: vi.fn(),
}))

const mockedDelete = vi.mocked(deleteJson)

describe('删除收藏夹端点', () => {
  beforeEach(() => {
    mockedDelete.mockClear()
  })

  it('★id 走路径参数（不是 query / body）', async () => {
    await deleteMyFolder(7)

    expect(mockedDelete).toHaveBeenCalledTimes(1)
    expect(mockedDelete).toHaveBeenCalledWith('/api/v1/favorite-folders/{id}', {
      path: { id: 7 },
    })
  })
})
