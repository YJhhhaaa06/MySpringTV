import { DEFAULT_CATEGORY_ID, UPLOAD_CATEGORIES } from '../constants/categories'
import { newIdempotencyKey } from './idempotency'

/**
 * 投稿草稿的**纯规则**（校验 / FormData / 幂等键生命周期）。
 *
 * <p>放在 `api/` 而不是页面里，是因为这些规则必须能被单测复算：
 * 上限值来自后端 `UploadController` 与 `application.yaml`（`max-file-size: 50MB` /
 * `max-request-size: 100MB`），不是拍脑袋的数字。
 *
 * <p>★ 文本长度按**去掉首尾空白后**的值校验，并且发送的也是这个值 ——
 * 客户端放行的内容，后端一定也放行（两套上限只在一处对齐，不在页面里各写一遍）。
 */

export const TITLE_MAX = 50
export const VIDEO_DESCRIPTION_MAX = 1000
export const POST_DESCRIPTION_MAX = 10_000
export const MAX_FILE_BYTES = 50 * 1024 * 1024
export const MAX_REQUEST_BYTES = 100 * 1024 * 1024
/**
 * 一次投稿里**所有文件合计**的上限：后端 `max-request-size` 是 100MB，
 * 但 multipart 边界也占字节 ⇒ 给客户端留 1MB 余量，避免"刚好 100MB"被 413。
 */
export const MAX_COMBINED_BYTES = MAX_REQUEST_BYTES - 1024 * 1024

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

function checkTitle(title: string): UploadFieldError | null {
  const value = title.trim()
  if (!value) return { field: 'title', message: '标题不能为空' }
  if (value.length > TITLE_MAX) return { field: 'title', message: `标题不得超过 ${TITLE_MAX} 字` }
  return null
}

function checkCategory(categoryId: number): UploadFieldError | null {
  const valid = UPLOAD_CATEGORIES.some((item) => item.id === categoryId)
  return valid ? null : { field: 'categoryId', message: '请选择分区' }
}

function checkDescription(description: string, max: number): UploadFieldError | null {
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
  if (draft.video && draft.cover && draft.video.size + draft.cover.size > MAX_COMBINED_BYTES) {
    errors.push({ field: 'video', message: '视频与封面合计不能超过 99MB' })
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
  const total = (draft.cover?.size ?? 0) + draft.images.reduce((sum, image) => sum + image.size, 0)
  if (total > MAX_COMBINED_BYTES) {
    errors.push({ field: 'image', message: '封面与图片合计不能超过 99MB' })
  }
  return errors
}

/** 视频投稿的 multipart 体。只允许在 {@link validateVideoDraft} 返回空数组后调用。 */
export function buildVideoForm(draft: VideoDraft): FormData {
  if (!draft.video || !draft.cover) {
    throw new Error('视频与封面未选择，不能构建提交体')
  }
  const form = new FormData()
  form.append('title', draft.title.trim())
  form.append('description', draft.description.trim())
  form.append('categoryId', String(draft.categoryId))
  form.append('video', draft.video)
  form.append('cover', draft.cover)
  return form
}

/** 图文投稿的 multipart 体（封面可选；图片按选择顺序追加，后端按 part 顺序写 sort 1..n）。 */
export function buildPostForm(draft: PostDraft): FormData {
  const form = new FormData()
  form.append('title', draft.title.trim())
  form.append('description', draft.description.trim())
  form.append('categoryId', String(draft.categoryId))
  if (draft.cover) form.append('cover', draft.cover)
  for (const image of draft.images) form.append('image', image)
  return form
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
