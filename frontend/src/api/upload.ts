import type { components } from '../types/api'
import { ApiError, postForm, postJson } from './http'
import { IDEMPOTENCY_HEADER } from './idempotency'
import {
  fileKey,
  postPayload,
  postUploadPlan,
  totalPlanBytes,
  videoPayload,
  videoUploadPlan,
} from './uploadDraft'
import type { AssetType, CreateContentPayload, PostDraft, UploadPlanEntry, VideoDraft } from './uploadDraft'

/**
 * 投稿端点封装（2026-10-29 起：**upload-only + JSON 媒体清单**）。
 *
 * <h2>为什么不是一条 multipart</h2>
 * 后端把「传字节」与「绑定媒体」拆成两步：
 * {@code POST /api/v1/uploads/{type}} 只落盘、返回不可变相对 URL；
 * {@code POST /api/v1/contents} 按**整份清单**建内容。
 * 对前端最实在的收益是**重试不必重传视频** —— 这正是"上传与提交解耦"的目的。
 *
 * <h2>★ 重试必须复用同一个 URL（不是优化，是正确性）</h2>
 * 创建端点带幂等键，且**指纹包含请求体**（{@code SHA-256(method+路径+body)}）。
 * 若重试时重新上传拿到新 URL，请求体就变了 ⇒ 服务端按指纹冲突回
 * {@code IDEMPOTENCY_KEY_CONFLICT}（409），"重试"反而变成一个新的失败。
 * 所以 {@link AssetUploadPool} 在**一次提交意图内**记住"这个文件已传成哪个 URL"，
 * 重试命中缓存直接复用。
 *
 * <p>调用方在**按下提交**时取幂等键、同一次意图的重试复用（`SubmitKeyHolder`），
 * 本层只负责把它放进请求头 —— 不在这里自动生成，理由见 `api/idempotency.ts`。
 */

/** 进度回调：`loaded/total` 是**本次投稿全部文件**的累计字节（不是单个文件）。 */
export type UploadProgress = (loaded: number, total: number) => void

/** 单文件上传动作（可注入，便于单测；生产实现就是 {@link uploadAsset}）。 */
export type AssetUploader = (
  file: File,
  type: AssetType,
  onProgress?: UploadProgress,
) => Promise<UploadedAsset>

/** upload-only 端点的返回：`url` 必填；`durationSeconds` 只对视频有值，解析失败为 `null`。 */
export interface UploadedAsset {
  url: string
  durationSeconds: number | null
}

type UploadedAssetVO = components['schemas']['UploadedAssetVO']
type ContentCreated = components['schemas']['ContentCreatedVO']

/** 成功响应必须带 contentId；缺了就是服务端契约破了，不能把 undefined 当 id 用。 */
function contentIdOf(data: ContentCreated | undefined): number {
  const contentId = data?.contentId
  if (typeof contentId !== 'number' || contentId <= 0) {
    throw new ApiError(200, 'NO_CONTENT_ID', '投稿成功但没有返回内容 id')
  }
  return contentId
}

function assetUrlOf(data: UploadedAssetVO | undefined): string {
  const url = data?.url
  if (typeof url !== 'string' || !url) {
    throw new ApiError(200, 'NO_ASSET_URL', '上传成功但没有返回文件地址')
  }
  return url
}

/**
 * 单文件上传（`POST /api/v1/uploads/{type}`）：**只落盘、不写库**。
 *
 * 返回的 `url` 在提交清单之前对观众完全不可见（未绑定的文件不参与任何内容装配）。
 */
export async function uploadAsset(
  file: File,
  type: AssetType,
  onProgress?: UploadProgress,
): Promise<UploadedAsset> {
  const form = new FormData()
  form.append('file', file)
  const data: UploadedAssetVO | undefined = await postForm('/api/v1/uploads/{type}', form, {
    path: { type },
    onProgress,
  })
  return { url: assetUrlOf(data), durationSeconds: data?.durationSeconds ?? null }
}

/**
 * 页面会话内的"文件 → 已上传 URL"记忆。
 *
 * <p>复用范围刻意收在**一次提交意图**之内：提交成功后 `clear()`；
 * 下一次投稿即使选了同一个文件也会重新上传 —— 服务端按"同一 URL 不能被两个内容引用"校验，
 * 静默复用只会换来一个看不懂的 400。
 *
 * <p>上传动作可注入：单测据此断言"同一文件第二次不发请求"，不需要真网络
 * （这条是重试正确性的前提，见文件头）。
 */
export class AssetUploadPool {
  private readonly uploaded = new Map<string, UploadedAsset>()
  private readonly uploader: AssetUploader

  constructor(uploader: AssetUploader = uploadAsset) {
    this.uploader = uploader
  }

  async upload(file: File, type: AssetType, onProgress?: UploadProgress): Promise<UploadedAsset> {
    const key = `${type}:${fileKey(file)}`
    const hit = this.uploaded.get(key)
    if (hit) return hit
    const asset = await this.uploader(file, type, onProgress)
    this.uploaded.set(key, asset)
    return asset
  }

  /** 提交成功后调用：这些 URL 已被绑定到刚创建的内容，不能再给下一次投稿用。 */
  clear(): void {
    this.uploaded.clear()
  }
}

/** 按计划顺序上传，返回与计划**同序**的 URL 数组（图文的顺序就是 `sort` 1..n）。 */
async function uploadPlan(
  plan: ReadonlyArray<UploadPlanEntry>,
  pool: AssetUploadPool,
  onProgress?: UploadProgress,
): Promise<string[]> {
  const totalBytes = totalPlanBytes(plan)
  const urls: string[] = []
  let done = 0
  for (const entry of plan) {
    const size = entry.file.size
    const asset = await pool.upload(entry.file, entry.type, (loaded) => {
      // 单个文件的进度是"它自己传了多少"，换算成整批累计字节（夹住上界，multipart 开销会略微越界）
      onProgress?.(done + Math.min(loaded, size), totalBytes)
    })
    done += size
    onProgress?.(done, totalBytes)
    urls.push(asset.url)
  }
  return urls
}

async function createContent(payload: CreateContentPayload, idempotencyKey: string): Promise<number> {
  const data = await postJson('/api/v1/contents', payload, {
    headers: { [IDEMPOTENCY_HEADER]: idempotencyKey },
  })
  return contentIdOf(data)
}

/**
 * 发布视频：先传视频与封面，再提交 `type=VIDEO` 的清单。
 *
 * <p>只允许在 `validateVideoDraft` 返回空数组后调用（草稿不完整会在此抛错，不会发出半份清单）。
 */
export async function publishVideo(
  draft: VideoDraft,
  idempotencyKey: string,
  pool: AssetUploadPool,
  onProgress?: UploadProgress,
): Promise<number> {
  if (!draft.video || !draft.cover) {
    throw new ApiError(0, 'DRAFT_INCOMPLETE', '视频与封面未选择，不能提交')
  }
  const urls = await uploadPlan(videoUploadPlan(draft), pool, onProgress)
  const contentId = await createContent(videoPayload(draft, urls[0]!, urls[1]!), idempotencyKey)
  pool.clear()
  return contentId
}

/**
 * 发布图文：封面（可选）与图片按草稿顺序上传，再提交 `type=POST` 的清单。
 *
 * <p>`imageUrls` **数组顺序即详情页展示顺序**（后端按序写 sort 1..n）。
 */
export async function publishPost(
  draft: PostDraft,
  idempotencyKey: string,
  pool: AssetUploadPool,
  onProgress?: UploadProgress,
): Promise<number> {
  const hasCover = draft.cover !== null
  const urls = await uploadPlan(postUploadPlan(draft), pool, onProgress)
  const contentId = await createContent(
    postPayload(draft, hasCover ? urls[0]! : null, hasCover ? urls.slice(1) : urls),
    idempotencyKey,
  )
  pool.clear()
  return contentId
}
