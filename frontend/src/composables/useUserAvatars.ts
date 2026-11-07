import { ref, watch, type Ref } from 'vue'
import { fetchUserAvatars, toAvatarMap } from '../api/avatars'

/**
 * 作者快照面的头像补读（`R-27`）。
 *
 * <h2>为什么是"按页一次批量"，而不是把头像塞进评论 / 动态缓存</h2>
 * 评论、feed、关注列表里的作者信息是**缓存载荷**；把 `avatarUrl` 写进去后，用户换头像
 * 要么长期显示旧 URL，要么旧文件被删后缓存里的 URL 直接 404。批量端点让头像保持实时：
 * 每次进页 / 翻页按当前 userId 集合读一次，不参与任何服务端缓存。
 *
 * <h2>行为</h2>
 * <ul>
 *   <li>id 去重、只留正整数；超过 100 个自动分块（与后端上限一致）；</li>
 *   <li>失败**静默**：保留上一次已拿到的 Map，缺失者由 `UserAvatar` 按 userId 回落默认；</li>
 *   <li>竞态保护：只有最后一次请求的 token 能写回，翻页快切不会把旧页头像盖到新页。</li>
 * </ul>
 *
 * <p>返回的 Map 只在**页面内存**里，不落 localStorage（前端只许两个 key 的纪律不变）。
 */
export function useUserAvatars(ids: Ref<Array<number | null | undefined>>) {
  const avatars = ref<Map<number, string>>(new Map())
  let token = 0

  watch(
    ids,
    async (next) => {
      const unique = normalizeAvatarIds(next)
      const current = ++token
      if (!unique.length) {
        avatars.value = new Map()
        return
      }
      try {
        const merged = new Map<number, string>()
        for (const chunk of chunkAvatarIds(unique)) {
          const items = await fetchUserAvatars(chunk)
          if (current !== token) return
          for (const [userId, url] of toAvatarMap(items)) {
            merged.set(userId, url)
          }
        }
        if (current === token) avatars.value = merged
      } catch {
        // 头像失败不阻塞页面：保留旧 Map，未命中的用户由 UserAvatar 回落默认头像。
      }
    },
    { immediate: true },
  )

  return { avatars }
}

/** 去重 + 只留正整数；供单测直接复算。 */
export function normalizeAvatarIds(ids: ReadonlyArray<number | null | undefined>): number[] {
  const seen = new Set<number>()
  for (const id of ids) {
    if (typeof id === 'number' && Number.isInteger(id) && id > 0) {
      seen.add(id)
    }
  }
  return [...seen]
}

/** 按后端上限 100 分块；不改变顺序。 */
export function chunkAvatarIds(ids: ReadonlyArray<number>, size = 100): number[][] {
  const chunks: number[][] = []
  for (let index = 0; index < ids.length; index += size) {
    chunks.push(ids.slice(index, index + size))
  }
  return chunks
}
