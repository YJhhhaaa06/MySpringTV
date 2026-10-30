import type { components } from '../types/api'
import { cleanDescription, formatDuration } from './adapters/format'
import { CONTENT_TYPE_POST } from './contentTypes'
import {
  POST_DESCRIPTION_MAX,
  VIDEO_DESCRIPTION_MAX,
  checkCategory,
  checkDescription,
  checkTitle,
} from './uploadDraft'
import type { UploadFieldError } from './uploadDraft'

/**
 * 编辑页的**纯规则**：详情 → 可编辑草稿、草稿 → 覆盖载荷、草稿校验、换序、脏判定。
 *
 * <h2>为什么单独一层</h2>
 * 与投稿草稿同一理由：这些是**业务口径**（哪些字段能改、清单必须完整、顺序即 sort），
 * 写在 `.vue` 里就只能靠截图回归。这里全是纯函数，单测能按契约先写断言。
 *
 * <h2>★ 保存永远提交**整份**媒体清单</h2>
 * `PUT /contents/{id}` 用"`media` 对象在不在"区分"改不改媒体"，对象一出现就是**整份覆盖**：
 * 清单里没有的旧图删除、数组顺序即 sort 1..n。
 * 所以"只改标题"也不许省略 `media` —— 省略会让用户刚做的换序 / 删图**静默不生效**，
 * 而界面上看起来已经改了（这是最难查的一类 bug）。
 * 反过来，视频的清单必须**恰好**是"视频 + 封面"，图文的必须**没有** `videoUrl`：
 * 后端 `ContentMediaManifestService#normalize` 对两者都硬校验。
 */
export interface EditImage {
  /** upload-only 端点返回的相对 URL（不可变） */
  url: string
  /** 本轮新上传（界面打「新」角标；保存后即普通图片） */
  isNew: boolean
}

/** 可编辑草稿：视频与图文共用一个形状，用 `isPost` 区分。 */
export interface EditableContent {
  id: number
  isPost: boolean
  title: string
  description: string
  categoryId: number
  coverUrl: string | null
  /** 仅视频有值 */
  videoUrl: string | null
  /** 仅图文有值；**数组顺序即展示顺序** */
  images: EditImage[]
  /** 详情里的时长（只用于预览展示，不参与提交） */
  durationText: string | null
}

export type UpdateContentPayload = components['schemas']['UpdateContentRequest']

/**
 * `ContentDetailVO` → 草稿。
 *
 * <p>★ 简介走 `cleanDescription`：后端把"没写简介"落成 `-`，
 * 编辑框里显示 `-` 会让作者以为原稿正文就是一个横杠，一保存就真的写进库。
 */
export function toEditableContent(detail: components['schemas']['ContentDetailVO']): EditableContent {
  const isPost = detail.type === CONTENT_TYPE_POST
  return {
    id: detail.id ?? 0,
    isPost,
    title: detail.title ?? '',
    description: cleanDescription(detail.description),
    categoryId: detail.categoryId ?? 0,
    coverUrl: detail.coverUrl?.trim() || null,
    videoUrl: isPost ? null : detail.videoUrl?.trim() || null,
    images: isPost
      ? (detail.imageUrls ?? []).map((url) => ({ url, isNew: false }))
      : [],
    durationText: isPost ? null : formatDuration(detail.durationSeconds),
  }
}

/**
 * 草稿 → `PUT /contents/{id}` 载荷。
 *
 * <p>`description` 原样传（可能是空串）：后端对文案字段是**整体替换**，
 * 传空 = 清空 —— 作者把简介删干净就该真的清掉（不是"缺省不变"）。
 */
export function toUpdatePayload(draft: EditableContent): UpdateContentPayload {
  return {
    title: draft.title.trim(),
    description: draft.description.trim(),
    categoryId: draft.categoryId,
    media: draft.isPost
      ? {
          ...(draft.coverUrl ? { coverUrl: draft.coverUrl } : {}),
          imageUrls: draft.images.map((image) => image.url),
        }
      : {
          ...(draft.videoUrl ? { videoUrl: draft.videoUrl } : {}),
          ...(draft.coverUrl ? { coverUrl: draft.coverUrl } : {}),
          imageUrls: [],
        },
  }
}

/**
 * 编辑校验：文本口径与投稿**同源**（同一组 `checkTitle/checkCategory/checkDescription`），
 * 媒体侧只要求"视频必须有视频 + 封面"，图文没有条数限制（与发布口径一致）。
 */
export function validateEdit(draft: EditableContent): UploadFieldError[] {
  const errors: UploadFieldError[] = []
  const titleError = checkTitle(draft.title)
  if (titleError) errors.push(titleError)
  const categoryError = checkCategory(draft.categoryId)
  if (categoryError) errors.push(categoryError)
  const descriptionError = checkDescription(
    draft.description,
    draft.isPost ? POST_DESCRIPTION_MAX : VIDEO_DESCRIPTION_MAX,
  )
  if (descriptionError) errors.push(descriptionError)

  if (!draft.isPost) {
    if (!draft.videoUrl) errors.push({ field: 'videoUrl', message: '请重新选择视频文件' })
    if (!draft.coverUrl) errors.push({ field: 'coverUrl', message: '请选择封面图片' })
  } else if (draft.images.some((image) => !image.url)) {
    // 理论上不可达（换源失败不会写回草稿），留一条明确报错比静默提交空串好
    errors.push({ field: 'image', message: '有图片还没上传完成，请先重试' })
  }
  return errors
}

/**
 * 交换两张图片（长按换序的落点）。**纯函数**：返回新数组，不改入参。
 *
 * <p>越界 / 同一个位置 ⇒ 原样副本（拖动时的"经过自己"不该产生任何副作用）。
 * 顺序本身就是 `sort`，因此在保存前不需要任何"重排接口"。
 */
export function swapImages<T>(items: ReadonlyArray<T>, from: number, to: number): T[] {
  const next = [...items]
  if (from === to) return next
  if (from < 0 || to < 0 || from >= next.length || to >= next.length) return next
  const a = next[from]!
  const b = next[to]!
  next[from] = b
  next[to] = a
  return next
}

/**
 * 是否与取回时不同（"保存"按钮的可用性）。
 *
 * <p>比较的是**将要提交的内容**，不是草稿对象：图片只用 URL 与顺序（`isNew` 是展示态，
 * 不影响提交）。作者换了封面又换回原图 ⇒ 不算改动，保存按钮保持禁用。
 */
export function isDirty(original: EditableContent, draft: EditableContent): boolean {
  if (draft.title.trim() !== original.title.trim()) return true
  if (draft.description.trim() !== original.description.trim()) return true
  if (draft.categoryId !== original.categoryId) return true
  if ((draft.coverUrl ?? null) !== (original.coverUrl ?? null)) return true
  if ((draft.videoUrl ?? null) !== (original.videoUrl ?? null)) return true
  if (draft.images.length !== original.images.length) return true
  return draft.images.some((image, index) => image.url !== original.images[index]?.url)
}
