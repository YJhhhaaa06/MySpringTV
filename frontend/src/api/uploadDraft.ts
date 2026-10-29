import { DEFAULT_CATEGORY_ID, UPLOAD_CATEGORIES } from '../constants/categories'
import type { components } from '../types/api'
import { newIdempotencyKey } from './idempotency'

/** 创建载荷直接用生成类型（`POST /api/v1/contents` 请求体）：契约改动会被 typecheck 立刻抓到。 */
export type CreateContentPayload = components['schemas']['CreateContentRequest']

/**
 * 投稿草稿的**纯规则**（校验 / 上传计划 / 创建载荷 / 幂等键生命周期）。
 *
 * <p>放在 `api/` 而不是页面里，是因为这些规则必须能被单测复算：
 * 上限值来自后端 `UploadController` 与 `application.yaml`（`max-file-size: 50MB`），
 * 不是拍脑袋的数字。
 *
 * <p>★ 文本长度按**去掉首尾空白后**的值校验，并且发送的也是这个值 ——
 * 客户端放行的内容，后端一定也放行（两套上限只在一处对齐，不在页面里各写一遍）。
 *
 * <p>★ <b>2026-10-29 起"合计 100MB"这条上限不再存在</b>：投稿改成逐个文件上传
 * （`POST /uploads/{type}`）后，`max-request-size` 只约束单次请求、而单次请求最多一个文件
 * ⇒ 真实约束只剩 `max-file-size: 50MB`。"视频 + 封面合计 ≤99MB"是 multipart 时代的
 * 客户端余量规则，留着会把 60MB 视频 + 40MB 封面的合法投稿挡在门外。
 */
export const TITLE_MAX = 50
export const VIDEO_DESCRIPTION_MAX = 1000
export const POST_DESCRIPTION_MAX = 10_000
export const MAX_FILE_BYTES = 50 * 1024 * 1024

const VIDEO_SUFFIXES = ['.mp4'] as const
const IMAGE_SUFFIXES = ['.jpg', '.png', '.jpeg'] as const
const COVER_SUFFIXES = ['.jpg', '.png'] as const

export interface UploadFieldError {
  /** 与后端 `errors[].field` 同名，页面据此把错误挂到对应控件 */
  field: string
  message: string
}

export interface VideoDraft {
  title: string
  description: string
  categoryId: number
  video: File | null
  cover: File | null
}

export interface PostDraft {
  title: string
  description: string
  categoryId: number
  cover: File | null
  images: File[]
}

export function createVideoDraft(): VideoDraft {
  return { title: '', description: '', categoryId: DEFAULT_CATEGORY_ID, video: null, cover: null }
}

export function createPostDraft(): PostDraft {
  return { title: '', description: '', categoryId: DEFAULT_CATEGORY_ID, cover: null, images: [] }
}

/** 文件名后缀（含点、小写）；没有点 ⇒ 空串。与后端 `UploadType#isSuffixValid` 同口径。 */
export function suffixOf(fileName: string): string {
  const dot = fileName.lastIndexOf('.')
  return dot === -1 ? '' : fileName.slice(dot).toLowerCase()
}

/** 人读的文件大小（页面展示用）。 */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes}B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)}KB`
  return `${(bytes / 1024 / 1024).toFixed(1)}MB`
}

/** 上传进度百分比（0..100；loaded/total 不可用 ⇒ 0，不显示 NaN%）。 */
export function progressPercent(loaded: number, total: number): number {
  if (!Number.isFinite(loaded) || !Number.isFinite(total) || total <= 0) return 0
  return Math.min(100, Math.max(0, Math.round((loaded / total) * 100)))
}

function checkFile(
  file: File,
  allowed: ReadonlyArray<string>,
  label: string,
  suffixHint: string,
): string | null {
  if (!allowed.includes(suffixOf(file.name))) {
    return `${label}格式不支持（仅支持 ${suffixHint}）`
  }
  if (file.size <= 0) return `${label}是空文件`
  if (file.size > MAX_FILE_BYTES) return `${label}不能超过 50MB`
  return null
}

/**
 * 三个**文本字段**校验（`field` 与后端 `errors[].field` 同名）。
 *
 * <p>导出是因为编辑页复用同一套口径：投稿与编辑对标题 / 分区 / 简介的约束必须一致，
 * 两处各写一份迟早会漂移（后端本来就只有一个 `ContentService`）。
 */
export function checkTitle(title: string): UploadFieldError | null {
  const value = title.trim()
  if (!value) return { field: 'title', message: '标题不能为空' }
  if (value.length > TITLE_MAX) return { field: 'title', message: `标题不得超过 ${TITLE_MAX} 字` }
  return null
}

export function checkCategory(categoryId: number): UploadFieldError | null {
  const valid = UPLOAD_CATEGORIES.some((item) => item.id === categoryId)
  return valid ? null : { field: 'categoryId', message: '请选择分区' }
}

export function checkDescription(description: string, max: number): UploadFieldError | null {
  if (description.trim().length > max) {
    return { field: 'description', message: `简介不得超过 ${max} 字` }
  }
  return null
}

/** 视频投稿校验。返回空数组 = 可以提交。 */
export function validateVideoDraft(draft: VideoDraft): UploadFieldError[] {
  const errors: UploadFieldError[] = []
  const titleError = checkTitle(draft.title)
  if (titleError) errors.push(titleError)
  const categoryError = checkCategory(draft.categoryId)
  if (categoryError) errors.push(categoryError)
  const descriptionError = checkDescription(draft.description, VIDEO_DESCRIPTION_MAX)
  if (descriptionError) errors.push(descriptionError)

  if (!draft.video) {
    errors.push({ field: 'video', message: '请选择视频文件' })
  } else {
    const videoError = checkFile(draft.video, VIDEO_SUFFIXES, '视频', '.mp4')
    if (videoError) errors.push({ field: 'video', message: videoError })
  }
  if (!draft.cover) {
    errors.push({ field: 'cover', message: '请选择封面图片' })
  } else {
    const coverError = checkFile(draft.cover, COVER_SUFFIXES, '封面', '.jpg / .png')
    if (coverError) errors.push({ field: 'cover', message: coverError })
  }
  return errors
}

/** 图文投稿校验。返回空数组 = 可以提交。 */
export function validatePostDraft(draft: PostDraft): UploadFieldError[] {
  const errors: UploadFieldError[] = []
  const titleError = checkTitle(draft.title)
  if (titleError) errors.push(titleError)
  const categoryError = checkCategory(draft.categoryId)
  if (categoryError) errors.push(categoryError)
  const descriptionError = checkDescription(draft.description, POST_DESCRIPTION_MAX)
  if (descriptionError) errors.push(descriptionError)

  if (draft.cover) {
    const coverError = checkFile(draft.cover, COVER_SUFFIXES, '封面', '.jpg / .png')
    if (coverError) errors.push({ field: 'cover', message: coverError })
  }
  for (let index = 0; index < draft.images.length; index += 1) {
    const image = draft.images[index]!
    const imageError = checkFile(image, IMAGE_SUFFIXES, `图片 ${index + 1}`, '.jpg / .png / .jpeg')
    if (imageError) errors.push({ field: 'image', message: imageError })
  }
  return errors
}

// ==================== 媒体清单（2026-10-29：投稿切 upload + JSON 清单） ====================

/**
 * 单文件上传的类型（与后端 `POST /api/v1/uploads/{type}` 的路径段同名）。
 *
 * <p>★ 2026-10-29 起投稿不再发 multipart 表单：文件先逐个上传拿到**不可变相对 URL**，
 * 再由 `POST /api/v1/contents` 提交**整份媒体清单**。这条改动的意义在"重试"：
 * 上传与提交分离后，重试提交不必重传视频（详见 `architecture/frontend/前端.md` §四）。
 */
export type AssetType = 'video' | 'cover' | 'image'

export interface UploadPlanEntry {
  file: File
  type: AssetType
}

/**
 * 视频投稿的上传顺序：**先视频、后封面**。
 *
 * <p>顺序不是随意的：进度条按"已完成字节 + 当前文件已传字节"累计，把最大的文件放前面，
 * 用户在前半程就能看到连续推进；反过来会让进度条长时间停在 0 附近。
 */
export function videoUploadPlan(draft: VideoDraft): UploadPlanEntry[] {
  const plan: UploadPlanEntry[] = []
  if (draft.video) plan.push({ file: draft.video, type: 'video' })
  if (draft.cover) plan.push({ file: draft.cover, type: 'cover' })
  return plan
}

/** 图文投稿的上传顺序：**封面在前、图片按草稿顺序**（后端按 `imageUrls` 数组顺序写 sort 1..n）。 */
export function postUploadPlan(draft: PostDraft): UploadPlanEntry[] {
  const plan: UploadPlanEntry[] = []
  if (draft.cover) plan.push({ file: draft.cover, type: 'cover' })
  for (const image of draft.images) plan.push({ file: image, type: 'image' })
  return plan
}

/** 本次投稿要上传的总字节（进度条分母；后端 `max-request-size` 已不再适用，逐个文件各自 50MB）。 */
export function totalPlanBytes(plan: ReadonlyArray<UploadPlanEntry>): number {
  return plan.reduce((sum, entry) => sum + entry.file.size, 0)
}

/**
 * 视频创建载荷（`POST /api/v1/contents` 的 `type=VIDEO` 分支）。
 *
 * <p>清单**必须完整**：后端 `ContentMediaManifestService#normalize` 对视频要求
 * "恰好有视频 + 封面、且不能带图片"，少一个就 400（文案「请选择视频文件 / 请选择封面图片」）。
 */
export function videoPayload(
  draft: VideoDraft,
  videoUrl: string,
  coverUrl: string,
): CreateContentPayload {
  return {
    type: 'VIDEO',
    title: draft.title.trim(),
    description: draft.description.trim(),
    categoryId: draft.categoryId,
    media: { videoUrl, coverUrl, imageUrls: [] },
  }
}

/**
 * 图文创建载荷。`coverUrl` 可空（后端允许图文无显式封面），`imageUrls` **顺序即展示顺序**。
 *
 * <p>⚠️ 视频字段必须是 `undefined`（不是空串）：后端把"带 videoUrl 的图文"判成
 * 「图文不能带视频」并 400；空串同样会被当成"有值"。
 */
export function postPayload(
  draft: PostDraft,
  coverUrl: string | null,
  imageUrls: ReadonlyArray<string>,
): CreateContentPayload {
  return {
    type: 'POST',
    title: draft.title.trim(),
    description: draft.description.trim(),
    categoryId: draft.categoryId,
    media: {
      ...(coverUrl ? { coverUrl } : {}),
      imageUrls: [...imageUrls],
    },
  }
}

/**
 * 文件在**本页面会话内**的稳定标识：同一份草稿重试时据此命中已上传结果。
 *
 * <p>与 {@link draftSignature} 同一口径（`name:size:lastModified`）——那里用来判断
 * "草稿是否变了"，这里用来判断"这个文件是否已经传过"。两者若口径不同，
 * 会出现"幂等键复用了、但载荷变了"（服务端会按指纹冲突回 409）。
 */
export function fileKey(file: File): string {
  return `${file.name}:${file.size}:${file.lastModified}`
}
/**
 * 草稿指纹：**同一草稿的重试复用同一个幂等键**，草稿一改就换键。
 *
 * <p>文件用 `name:size:lastModified` 参与指纹 —— 换了一个文件但同名同大小同修改时间
 * 在现实中几乎不可能；而只比较文件名会让"换个同名文件"复用旧键（服务端重放旧响应）。
 */
export function draftSignature(draft: VideoDraft | PostDraft): string {
  const files: File[] =
    'video' in draft
      ? [draft.video, draft.cover].filter((file): file is File => file !== null)
      : [draft.cover, ...draft.images].filter((file): file is File => file !== null)
  return JSON.stringify({
    title: draft.title.trim(),
    description: draft.description.trim(),
    categoryId: draft.categoryId,
    files: files.map((file) => `${file.name}:${file.size}:${file.lastModified}`),
  })
}

/**
 * 幂等键生命周期（口径 = `architecture/frontend/前端.md` §八）：
 * 在**用户按下提交**时取、同一次意图的重试复用、成功后清空、草稿一改就作废。
 */
export class SubmitKeyHolder {
  private key: string | null = null
  private signature: string | null = null

  /** 当前草稿对应的键；草稿指纹变了就换一把新的。 */
  keyFor(signature: string): string {
    if (this.key === null || this.signature !== signature) {
      this.key = newIdempotencyKey()
      this.signature = signature
    }
    return this.key
  }

  /** 成功提交后调用：下一次投稿必须是新意图（新键）。 */
  reset(): void {
    this.key = null
    this.signature = null
  }
}
