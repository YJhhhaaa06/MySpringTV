import type { components } from '../types/api'
import type { CreatorWorksVM } from '../types/ui'
import { toCreatorWorks } from './adapters/creator'
import { getJson } from './http'

/**
 * 创作中心端点封装。
 *
 * <h2>稿件列表为什么借用公开主页端点</h2>
 * `GET /users/{id}?page=&pageSize=` 的 `contentPage` 就是"我发布的全部作品"
 * （本仓没有草稿 / 审核态，`content` 无状态列）。代价：翻页会顺带重读一次资料 ——
 * 与空间页同一口径（见 `profile.ts`），不为省一次请求而新增端点。
 */
export async function loadMyWorks(
  userId: number,
  page = 1,
  pageSize = 10,
): Promise<CreatorWorksVM> {
  const profile: components['schemas']['ProfileVO'] = await getJson('/api/v1/users/{id}', {
    path: { id: userId },
    query: { page, pageSize },
  })
  return toCreatorWorks(profile.contentPage)
}
