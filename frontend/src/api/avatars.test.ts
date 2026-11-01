import { describe, expect, it, vi, beforeEach } from 'vitest'
import { fetchUserAvatars, selectDefaultAvatar, toAvatarMap, uploadAvatar } from './avatars'
import { getJson, putForm, putJson } from './http'

vi.mock('./http', () => ({
  getJson: vi.fn(),
  putForm: vi.fn(),
  putJson: vi.fn(),
}))

describe('头像端点封装（R-27）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('自定义上传：multipart PUT 到 /users/me/avatar', async () => {
    vi.mocked(putForm).mockResolvedValue({ userId: 233, avatarUrl: '/upload/avatar/x.jpg' } as never)
    const file = new File([new Uint8Array([1, 2, 3])], 'avatar.jpg', { type: 'image/jpeg' })

    await expect(uploadAvatar(file)).resolves.toEqual({
      userId: 233,
      avatarUrl: '/upload/avatar/x.jpg',
    })
    expect(putForm).toHaveBeenCalledWith('/api/v1/users/me/avatar', expect.any(FormData))
    const form = vi.mocked(putForm).mock.calls[0]![1] as FormData
    expect(form.get('file')).toBe(file)
  })

  it('切换默认：JSON PUT {avatarId}', async () => {
    vi.mocked(putJson).mockResolvedValue({ userId: 1, avatarUrl: '/avatars/default-4.svg' } as never)
    await expect(selectDefaultAvatar(4)).resolves.toEqual({
      userId: 1,
      avatarUrl: '/avatars/default-4.svg',
    })
    expect(putJson).toHaveBeenCalledWith('/api/v1/users/me/avatar/default', { avatarId: 4 })
  })

  it('批量：userIds 逗号拼接后才进 query', async () => {
    vi.mocked(getJson).mockResolvedValue([
      { userId: 1, avatarUrl: '/avatars/default-2.svg' },
      { userId: 2, avatarUrl: '/upload/avatar/y.jpg' },
    ] as never)
    await expect(fetchUserAvatars([1, 2])).resolves.toHaveLength(2)
    expect(getJson).toHaveBeenCalledWith('/api/v1/users/avatars', {
      query: { userIds: '1,2' },
    })
  })

  it('toAvatarMap 丢弃非法条目；合法条目按 userId 建 Map', () => {
    const map = toAvatarMap([
      { userId: 1, avatarUrl: '/avatars/default-2.svg' },
      { userId: 2, avatarUrl: '/upload/avatar/y.jpg' },
      { userId: 0, avatarUrl: '/x.jpg' },
      { userId: 3, avatarUrl: '' },
      { userId: 4.5, avatarUrl: '/x.jpg' },
    ])
    expect(map.size).toBe(2)
    expect(map.get(1)).toBe('/avatars/default-2.svg')
    expect(map.get(2)).toBe('/upload/avatar/y.jpg')
    expect(map.has(0)).toBe(false)
    expect(map.has(4.5)).toBe(false)
  })

  it('toAvatarMap 对 undefined / 空数组 ⇒ 空 Map（不把缺字段画成空头像）', () => {
    expect(toAvatarMap(undefined).size).toBe(0)
    expect(toAvatarMap([]).size).toBe(0)
  })
})
