import { describe, expect, it, vi, beforeEach } from 'vitest'
import { nextTick, ref } from 'vue'
import { chunkAvatarIds, normalizeAvatarIds, useUserAvatars } from './useUserAvatars'
import { fetchUserAvatars } from '../api/avatars'

vi.mock('../api/avatars', () => ({
  fetchUserAvatars: vi.fn(),
  toAvatarMap: (items: Array<{ userId: number; avatarUrl: string }>) =>
    new Map(items.map((item) => [item.userId, item.avatarUrl])),
}))

describe('normalizeAvatarIds / chunkAvatarIds', () => {
  it('去重 + 只留正整数（评论/动态里同一作者可能重复出现）', () => {
    expect(normalizeAvatarIds([1, 1, 2, null, undefined, 0, -3, 2.5, 3])).toEqual([1, 2, 3])
  })

  it('按 100 分块，且不改变顺序', () => {
    const ids = Array.from({ length: 205 }, (_, index) => index + 1)
    const chunks = chunkAvatarIds(ids)
    expect(chunks.map((chunk) => chunk.length)).toEqual([100, 100, 5])
    expect(chunks[0]![0]).toBe(1)
    expect(chunks[2]![4]).toBe(205)
    expect(chunkAvatarIds([])).toEqual([])
  })
})

describe('useUserAvatars：按页一次批量，失败不阻塞页面', () => {
  beforeEach(() => {
    vi.mocked(fetchUserAvatars).mockReset()
  })

  it('ids 变化 ⇒ 只发一次请求（去重后）；结果进 Map', async () => {
    vi.mocked(fetchUserAvatars).mockResolvedValue([
      { userId: 1, avatarUrl: '/avatars/default-2.svg' },
      { userId: 2, avatarUrl: '/upload/avatar/y.jpg' },
    ])
    const ids = ref([1, 1, 2])
    const { avatars } = useUserAvatars(ids)

    await vi.waitFor(() => expect(avatars.value.get(1)).toBe('/avatars/default-2.svg'))
    expect(fetchUserAvatars).toHaveBeenCalledTimes(1)
    expect(fetchUserAvatars).toHaveBeenCalledWith([1, 2])
    expect(avatars.value.get(2)).toBe('/upload/avatar/y.jpg')
  })

  it('请求失败 ⇒ 保留空 Map（UserAvatar 回落 userId 默认，不阻塞页面）', async () => {
    vi.mocked(fetchUserAvatars).mockRejectedValue(new Error('offline'))
    const ids = ref([9])
    const { avatars } = useUserAvatars(ids)

    await nextTick()
    await vi.waitFor(() => expect(fetchUserAvatars).toHaveBeenCalledTimes(1))
    expect(avatars.value.size).toBe(0)
  })

  it('空 ids ⇒ 不发请求', async () => {
    const ids = ref<Array<number | null>>([null, -1])
    const { avatars } = useUserAvatars(ids)

    await nextTick()
    expect(fetchUserAvatars).not.toHaveBeenCalled()
    expect(avatars.value.size).toBe(0)
  })
})
