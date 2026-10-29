import type { components } from '../types/api'
import type { CreatorWorksVM } from '../types/ui'
import { toCreatorWorks } from './adapters/creator'
import type { UpdateContentPayload } from './creatorEdit'
import { getJson, putJson } from './http'

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

/**
 * 取回一条稿件用于编辑（`GET /contents/{id}`）。
 *
 * <p>详情是**公开**端点 ⇒ 所有权在这里判不了，也不该判：非作者打开编辑页看到的是
 * "这不是你的稿件"（页面级），真正的拦截在保存时的 403。把校验放在能判的地方，
 * 比在每个调用点各猜一次更可靠。
 */
export async function loadEditableContent(
  contentId: number,
): Promise<components['schemas']['ContentDetailVO']> {
  return getJson('/api/v1/contents/{id}', { path: { id: contentId } })
}

/**
 * 保存编辑（`PUT /contents/{id}`）：元数据 + **整份**媒体清单。
 *
 * <p>不带幂等键：该端点在 `IdempotencyScope` 里是 `natural`（整份覆盖天然幂等，
 * 重试最多刷新一次 `update_time`）。返回 204 无体。
 */
export async function saveContentEdit(
  contentId: number,
  payload: UpdateContentPayload,
): Promise<void> {
  await putJson('/api/v1/contents/{id}', payload, { path: { id: contentId } })
}
