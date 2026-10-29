import { describe, expect, it } from 'vitest'
import type { components } from '../types/api'
import {
  isDirty,
  swapImages,
  toEditableContent,
  toUpdatePayload,
  validateEdit,
} from './creatorEdit'

/**
 * 编辑页纯规则的契约测试。
 *
 * <p>期望值全部来自后端契约（`UpdateContentRequest` / `MediaManifest` 的注释与
 * `ContentMediaManifestService#normalize` 的硬校验），不是照抄实现：
 * - 视频清单必须**恰好**有视频 + 封面且不能带图片；
 * - 图文清单必须**没有** `videoUrl`，`imageUrls` 顺序即 sort；
 * - 简介上限按类型（视频 1000 / 图文 10000）；
 * - 后端空简介占位 `-` 在编辑框里必须显示为空。
 */

type ContentDetailVO = components['schemas']['ContentDetailVO']

function videoDetail(overrides: Partial<ContentDetailVO> = {}): ContentDetailVO {
  return {
    id: 92,
    authorId: 7,
    type: 1,
    title: '老标题',
    description: '老简介',
    categoryId: 4,
    coverUrl: '/upload/cover/old.png',
    videoUrl: '/upload/video/old.mp4',
    imageUrls: null,
    durationSeconds: 125,
    createTime: '2026-10-29T08:05:00',
    ...overrides,
  }
}

function postDetail(overrides: Partial<ContentDetailVO> = {}): ContentDetailVO {
  return {
    id: 93,
    authorId: 7,
    type: 2,
    title: '图文标题',
    description: '正文',
    categoryId: 2,
    coverUrl: null,
    videoUrl: null,
    imageUrls: ['/upload/image/1.jpg', '/upload/image/2.jpg'],
    durationSeconds: null,
    createTime: '2026-10-29T08:05:00',
    ...overrides,
  }
}

describe('详情 → 草稿', () => {
  it('视频：媒体与时长回填，图片区为空', () => {
    const draft = toEditableContent(videoDetail())
    expect(draft.isPost).toBe(false)
    expect(draft.videoUrl).toBe('/upload/video/old.mp4')
    expect(draft.coverUrl).toBe('/upload/cover/old.png')
    expect(draft.images).toEqual([])
    expect(draft.durationText).toBe('02:05')
  })

  it('后端空简介占位 `-` 显示为空（否则作者会真的把它保存进库）', () => {
    expect(toEditableContent(videoDetail({ description: '-' })).description).toBe('')
    // spec 里 description 是 string；运行时缺失只可能是空串，两条都按"没有简介"处理
    expect(toEditableContent(postDetail({ description: '' })).description).toBe('')
  })

  it('图文：图片顺序原样保留（顺序就是 sort），全部标记为"已保存"', () => {
    const draft = toEditableContent(postDetail())
    expect(draft.isPost).toBe(true)
    expect(draft.videoUrl).toBeNull()
    expect(draft.images.map((image) => image.url)).toEqual([
      '/upload/image/1.jpg',
      '/upload/image/2.jpg',
    ])
    expect(draft.images.every((image) => image.isNew === false)).toBe(true)
  })
})

describe('草稿 → 覆盖载荷', () => {
  it('视频：清单完整（视频 + 封面 + 空图片数组）', () => {
    const draft = toEditableContent(videoDetail())
    expect(toUpdatePayload(draft)).toEqual({
      title: '老标题',
      description: '老简介',
      categoryId: 4,
      media: {
        videoUrl: '/upload/video/old.mp4',
        coverUrl: '/upload/cover/old.png',
        imageUrls: [],
      },
    })
  })

  it('图文：不带 videoUrl 键（带空串也会被判"图文不能带视频"）；顺序 = 当前草稿顺序', () => {
    const draft = toEditableContent(postDetail())
    draft.images = swapImages(draft.images, 0, 1)
    const payload = toUpdatePayload(draft)
    expect(payload.media).toEqual({ imageUrls: ['/upload/image/2.jpg', '/upload/image/1.jpg'] })
    expect('videoUrl' in (payload.media ?? {})).toBe(false)
  })

  it('图文无封面 ⇒ 不带 coverUrl 键；文案去首尾空白但仍整体替换', () => {
    const draft = toEditableContent(postDetail({ description: '  正文  ' }))
    const payload = toUpdatePayload(draft)
    expect('coverUrl' in (payload.media ?? {})).toBe(false)
    expect(payload.description).toBe('正文')

    draft.description = ''
    expect(toUpdatePayload(draft).description).toBe('')
  })
})

describe('编辑校验', () => {
  it('视频缺封面 ⇒ 提示补封面（后端 normalize 也会拒）', () => {
    const draft = toEditableContent(videoDetail({ coverUrl: null }))
    expect(validateEdit(draft)).toEqual([{ field: 'coverUrl', message: '请选择封面图片' }])
  })

  it('视频缺视频源 ⇒ 提示重新选择（换源上传失败后草稿不会被写脏，但旧数据可能没有）', () => {
    const draft = toEditableContent(videoDetail({ videoUrl: null }))
    expect(validateEdit(draft).map((error) => error.field)).toEqual(['videoUrl'])
  })

  it('简介上限按内容类型：视频 1001 拒、图文 10001 拒、图文 10000 放行', () => {
    const video = toEditableContent(videoDetail({ description: 'a'.repeat(1001) }))
    expect(validateEdit(video)).toContainEqual({ field: 'description', message: '简介不得超过 1000 字' })

    const post = toEditableContent(postDetail({ description: 'a'.repeat(10_001) }))
    expect(validateEdit(post)).toContainEqual({ field: 'description', message: '简介不得超过 10000 字' })

    const longPost = toEditableContent(postDetail({ description: 'a'.repeat(10_000) }))
    expect(validateEdit(longPost)).toEqual([])
  })

  it('标题 / 分区与投稿同口径（同一组校验函数，不许漂移）', () => {
    const draft = toEditableContent(postDetail({ title: '   ', categoryId: 12 }))
    expect(validateEdit(draft).map((error) => error.field)).toEqual(['title', 'categoryId'])
  })
})

describe('长按换序的落点：swapImages', () => {
  it('交换两张；不改入参（Vue 才能感知变化）', () => {
    const source = ['a', 'b', 'c']
    const next = swapImages(source, 0, 2)
    expect(next).toEqual(['c', 'b', 'a'])
    expect(source).toEqual(['a', 'b', 'c'])
  })

  it('同一位置 / 越界 ⇒ 原样副本（拖动经过自己不该产生副作用）', () => {
    const source = ['a', 'b']
    expect(swapImages(source, 1, 1)).toEqual(['a', 'b'])
    expect(swapImages(source, -1, 1)).toEqual(['a', 'b'])
    expect(swapImages(source, 0, 5)).toEqual(['a', 'b'])
    expect(swapImages(source, 5, 5)).not.toBe(source)
  })
})

describe('脏判定（保存按钮可用性）', () => {
  it('原样 ⇒ 不脏；新增的"新"角标不算改动', () => {
    const original = toEditableContent(postDetail())
    const draft = toEditableContent(postDetail())
    draft.images[0]!.isNew = true
    expect(isDirty(original, draft)).toBe(false)
  })

  it('换序 / 换封面 / 改标题都算脏', () => {
    const original = toEditableContent(postDetail())
    const swapped = toEditableContent(postDetail())
    swapped.images = swapImages(swapped.images, 0, 1)
    expect(isDirty(original, swapped)).toBe(true)

    const reCovered = toEditableContent(postDetail({ coverUrl: null }))
    reCovered.coverUrl = '/upload/cover/new.png'
    expect(isDirty(original, reCovered)).toBe(true)

    const retitled = toEditableContent(postDetail())
    retitled.title = '新标题'
    expect(isDirty(original, retitled)).toBe(true)
  })

  it('只加首尾空白不算改动（提交前会 trim，服务端看到的是同一份内容）', () => {
    const original = toEditableContent(postDetail())
    const padded = toEditableContent(postDetail())
    padded.title = `  ${padded.title}  `
    expect(isDirty(original, padded)).toBe(false)
  })
})
