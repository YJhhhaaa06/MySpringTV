import type { components } from '../types/api'
import { ApiError, postForm } from './http'
import { IDEMPOTENCY_HEADER } from './idempotency'
import { buildPostForm, buildVideoForm } from './uploadDraft'
import type { PostDraft, VideoDraft } from './uploadDraft'

/**
 * 投稿端点封装（`POST /api/v1/contents/videos` / `/posts`，两条都是 `KEYED`）。
 *
 * <p>调用方必须在**按下提交**时取键、同一次意图的重试复用（`SubmitKeyHolder`），
 * 本层只负责把它放进请求头 —— 不在这里自动生成，理由见 `api/idempotency.ts`。
 */

export type UploadProgress = (loaded: number, total: number) => void

type ContentCreated = components['schemas']['ContentCreatedVO']

/** 成功响应必须带 contentId；缺了就是服务端契约破了，不能把 undefined 当 id 用。 */
function contentIdOf(data: ContentCreated | undefined): number {
  const contentId = data?.contentId
  if (typeof contentId !== 'number' || contentId <= 0) {
    throw new ApiError(200, 'NO_CONTENT_ID', '投稿成功但没有返回内容 id')
  }
  return contentId
}

/** 发布视频（multipart：`video` + `cover` 必传，文本字段在 form 里）。 */
export async function uploadVideo(
  draft: VideoDraft,
  idempotencyKey: string,
  onProgress?: UploadProgress,
): Promise<number> {
  const data = await postForm('/api/v1/contents/videos', buildVideoForm(draft), {
    headers: { [IDEMPOTENCY_HEADER]: idempotencyKey },
    onProgress,
  })
  return contentIdOf(data)
}

/** 发布图文（multipart：`cover` 可选、`image` 0~n 张，按 part 顺序写 sort）。 */
export async function uploadPost(
  draft: PostDraft,
  idempotencyKey: string,
  onProgress?: UploadProgress,
): Promise<number> {
  const data = await postForm('/api/v1/contents/posts', buildPostForm(draft), {
    headers: { [IDEMPOTENCY_HEADER]: idempotencyKey },
    onProgress,
  })
  return contentIdOf(data)
}
