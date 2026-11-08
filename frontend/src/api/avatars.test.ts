import { describe, expect, it, vi, beforeEach } from 'vitest'
import { selectDefaultAvatar, uploadAvatar } from './avatars'
import { putForm, putJson } from './http'

vi.mock('./http', () => ({
  putForm: vi.fn(),
  putJson: vi.fn(),
}))

describe('头像写端点封装（R-27；读侧 R-37 已内联）', () => {
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
})
