import { describe, expect, it } from 'vitest'
import {
  POST_DESCRIPTION_MAX,
  SubmitKeyHolder,
  createPostDraft,
  createVideoDraft,
  draftSignature,
  fileKey,
  formatBytes,
  postPayload,
  postUploadPlan,
  progressPercent,
  suffixOf,
  totalPlanBytes,
  validatePostDraft,
  validateVideoDraft,
  videoPayload,
  videoUploadPlan,
} from './uploadDraft'

/**
 * 投稿草稿规则的契约测试。
 *
 * <p>期望值全部来自**后端契约**：`UploadController` 的标题 ≤50 / 视频简介 ≤1000 /
 * 图文简介 ≤10000 / 分区 0~9、`UploadType` 的扩展名（视频 .mp4、图片 .jpg/.png/.jpeg、
 * 封面 .jpg/.png）、`application.yaml` 的 50MB 单文件 / 100MB 单请求。
 * 不是抄实现里的数字：改实现不改契约时，这些断言必须变红。
 */

const MB = 1024 * 1024

/** 造一个指定 `size` 的 File（不真的分配那么多内存：size 是只读 getter，用自有属性覆盖）。 */
function fakeFile(name: string, size: number, lastModified = 1): File {
  const file = new File([new Uint8Array(1)], name, { type: 'application/octet-stream', lastModified })
  Object.defineProperty(file, 'size', { value: size })
  return file
}

function videoDraft(overrides: Partial<ReturnType<typeof createVideoDraft>> = {}) {
  return {
    ...createVideoDraft(),
    title: '标题',
    video: fakeFile('a.mp4', 1024),
    cover: fakeFile('c.png', 1024),
    ...overrides,
  }
}

function postDraft(overrides: Partial<ReturnType<typeof createPostDraft>> = {}) {
  return { ...createPostDraft(), title: '标题', ...overrides }
}

describe('视频投稿校验', () => {
  it('空草稿只报缺标题与两个文件（分区默认 0 合法、简介可空）', () => {
    const errors = validateVideoDraft(createVideoDraft())
    expect(errors.map((error) => error.field)).toEqual(['title', 'video', 'cover'])
  })

  it('标题 51 字 / 简介 1001 字 / 分区 10 都拒绝', () => {
    const errors = validateVideoDraft(
      videoDraft({ title: 'a'.repeat(51), description: 'a'.repeat(1001), categoryId: 10 }),
    )
    expect(errors).toEqual(
      expect.arrayContaining([
        { field: 'title', message: '标题不得超过 50 字' },
        { field: 'description', message: '简介不得超过 1000 字' },
        { field: 'categoryId', message: '请选择分区' },
      ]),
    )
  })

  it('扩展名不在白名单（视频只认 .mp4、封面只认 .jpg/.png）⇒ 两个字段各自报错', () => {
    const errors = validateVideoDraft(
      videoDraft({ video: fakeFile('a.avi', 1024), cover: fakeFile('c.webp', 1024) }),
    )
    expect(errors).toEqual(
      expect.arrayContaining([
        { field: 'video', message: '视频格式不支持（仅支持 .mp4）' },
        { field: 'cover', message: '封面格式不支持（仅支持 .jpg / .png）' },
      ]),
    )
  })

  it('单文件超过 50MB 拒绝；空的 .mp4 也拒绝', () => {
    const errors = validateVideoDraft(
      videoDraft({ video: fakeFile('a.mp4', 50 * MB + 1), cover: fakeFile('c.png', 0) }),
    )
    expect(errors).toEqual(
      expect.arrayContaining([
        { field: 'video', message: '视频不能超过 50MB' },
        { field: 'cover', message: '封面是空文件' },
      ]),
    )
  })

  it('每个文件各自 ≤50MB；"合计 99MB"这条 multipart 余量规则已随 upload 迁移退役', () => {
    // 对照：单个文件 51MB 仍然拒绝（后端 max-file-size: 50MB）
    const tooBig = validateVideoDraft(
      videoDraft({ video: fakeFile('a.mp4', 51 * MB), cover: fakeFile('c.png', 1024) }),
    )
    expect(tooBig).toContainEqual({ field: 'video', message: '视频不能超过 50MB' })

    // 两个 50MB 文件在逐个上传的形态下是合法的（旧实现会报"合计不能超过 99MB"）
    const bothMax = validateVideoDraft(
      videoDraft({ video: fakeFile('a.mp4', 50 * MB), cover: fakeFile('c.png', 50 * MB) }),
    )
    expect(bothMax).toEqual([])
  })

  it('合法草稿没有任何错误（对照组：上面的红不是"永远红"）', () => {
    expect(validateVideoDraft(videoDraft())).toEqual([])
  })
})

describe('图文投稿校验', () => {
  it('只要标题就能发纯文字图文（后端 allow 0 图 0 封面）', () => {
    expect(validatePostDraft(postDraft())).toEqual([])
  })

  it('简介 10001 字拒绝（图文上限与视频不同，是 10000）', () => {
    const errors = validatePostDraft(postDraft({ description: 'a'.repeat(POST_DESCRIPTION_MAX + 1) }))
    expect(errors).toEqual([{ field: 'description', message: '简介不得超过 10000 字' }])
  })

  it('图片后缀：.gif 拒绝；多张各自 ≤50MB 时合计不再受限（逐个上传）', () => {
    const badSuffix = validatePostDraft(postDraft({ images: [fakeFile('a.gif', 1024)] }))
    expect(badSuffix).toEqual([{ field: 'image', message: '图片 1格式不支持（仅支持 .jpg / .png / .jpeg）' }])

    const big = validatePostDraft(
      postDraft({ images: [fakeFile('a.jpg', 50 * MB), fakeFile('b.jpg', 50 * MB)] }),
    )
    expect(big).toEqual([])
  })

  it('封面可选，但给了就必须是 .jpg/.png 且 ≤50MB', () => {
    const errors = validatePostDraft(postDraft({ cover: fakeFile('c.webp', 1024) }))
    expect(errors).toEqual([{ field: 'cover', message: '封面格式不支持（仅支持 .jpg / .png）' }])
  })
})

describe('上传计划与创建载荷（upload + JSON 清单）', () => {
  it('视频先传视频、后传封面（进度条前半程要能连续推进）', () => {
    const video = fakeFile('a.mp4', 30 * MB)
    const cover = fakeFile('c.png', 1024)
    const plan = videoUploadPlan(videoDraft({ video, cover }))
    expect(plan.map((entry) => entry.type)).toEqual(['video', 'cover'])
    expect(plan.map((entry) => entry.file)).toEqual([video, cover])
    expect(totalPlanBytes(plan)).toBe(30 * MB + 1024)
  })

  it('图文：封面在前、图片按草稿顺序（顺序就是 sort 1..n）', () => {
    const cover = fakeFile('c.png', 5)
    const first = fakeFile('1.jpg', 10)
    const second = fakeFile('2.png', 20)
    const plan = postUploadPlan(postDraft({ cover, images: [first, second] }))
    expect(plan.map((entry) => entry.type)).toEqual(['cover', 'image', 'image'])
    expect(plan.map((entry) => entry.file)).toEqual([cover, first, second])

    // 无封面时计划里不能出现 cover 占位（后端 uploads 端点没有"空封面"这一说）
    expect(postUploadPlan(postDraft({ images: [first] })).map((entry) => entry.type)).toEqual(['image'])
  })

  it('视频创建载荷：清单完整（视频 + 封面，无图片），文本去首尾空白', () => {
    const payload = videoPayload(
      videoDraft({ title: '  标题  ', description: '  简介  ', categoryId: 4 }),
      '/upload/video/a.mp4',
      '/upload/cover/c.png',
    )
    expect(payload).toEqual({
      type: 'VIDEO',
      title: '标题',
      description: '简介',
      categoryId: 4,
      media: { videoUrl: '/upload/video/a.mp4', coverUrl: '/upload/cover/c.png', imageUrls: [] },
    })
  })

  it('图文创建载荷：无封面时不带 coverUrl 键（不是空串）；videoUrl 必须缺席', () => {
    const payload = postPayload(postDraft({ title: '标题' }), null, ['/upload/image/1.jpg'])
    expect(payload.type).toBe('POST')
    expect(payload.media).toEqual({ imageUrls: ['/upload/image/1.jpg'] })
    expect('coverUrl' in payload.media).toBe(false)
    expect('videoUrl' in payload.media).toBe(false)
  })

  it('图文创建载荷：imageUrls 顺序 = 传进来的顺序（长按换序后这就是新的 sort）', () => {
    const payload = postPayload(postDraft(), '/upload/cover/c.png', [
      '/upload/image/3.jpg',
      '/upload/image/1.jpg',
      '/upload/image/2.jpg',
    ])
    expect(payload.media.imageUrls).toEqual([
      '/upload/image/3.jpg',
      '/upload/image/1.jpg',
      '/upload/image/2.jpg',
    ])
  })

  it('文件标识只看 name/size/lastModified：改了任一项就是"另一个文件"', () => {
    const file = fakeFile('a.mp4', 100, 7)
    expect(fileKey(file)).toBe(fileKey(fakeFile('a.mp4', 100, 7)))
    expect(fileKey(file)).not.toBe(fileKey(fakeFile('a.mp4', 101, 7)))
    expect(fileKey(file)).not.toBe(fileKey(fakeFile('a.mp4', 100, 8)))
    expect(fileKey(file)).not.toBe(fileKey(fakeFile('b.mp4', 100, 7)))
  })
})

describe('幂等键生命周期', () => {
  it('同一草稿的重试复用同一个键（这是幂等的全部价值）', () => {
    const holder = new SubmitKeyHolder()
    const signature = draftSignature(videoDraft())
    expect(holder.keyFor(signature)).toBe(holder.keyFor(signature))
  })

  it('草稿一改就换键（换文件也算改）', () => {
    const holder = new SubmitKeyHolder()
    const original = videoDraft()
    const first = holder.keyFor(draftSignature(original))
    const changedTitle = holder.keyFor(draftSignature({ ...original, title: '另一个标题' }))
    expect(changedTitle).not.toBe(first)
    const changedFile = holder.keyFor(draftSignature({ ...original, video: fakeFile('b.mp4', 1024) }))
    expect(changedFile).not.toBe(changedTitle)
  })

  it('成功提交后 reset ⇒ 下一次是新意图（新键）', () => {
    const holder = new SubmitKeyHolder()
    const signature = draftSignature(videoDraft())
    const first = holder.keyFor(signature)
    holder.reset()
    expect(holder.keyFor(signature)).not.toBe(first)
  })

  it('键满足后端 [A-Za-z0-9._-]{1,64}', () => {
    expect(new SubmitKeyHolder().keyFor('x')).toMatch(/^[A-Za-z0-9._-]{1,64}$/)
  })
})

describe('展示与边界的小工具', () => {
  it('进度百分比：正常取整、total 不可用不产生 NaN、越界被夹住', () => {
    expect(progressPercent(50, 100)).toBe(50)
    expect(progressPercent(0, 0)).toBe(0)
    expect(progressPercent(Number.NaN, 100)).toBe(0)
    expect(progressPercent(200, 100)).toBe(100)
    expect(progressPercent(-1, 100)).toBe(0)
  })

  it('文件大小文案', () => {
    expect(formatBytes(500)).toBe('500B')
    expect(formatBytes(1536)).toBe('1.5KB')
    expect(formatBytes(1.5 * MB)).toBe('1.5MB')
  })

  it('后缀取最后一个点且小写；无点 ⇒ 空串', () => {
    expect(suffixOf('A.MP4')).toBe('.mp4')
    expect(suffixOf('no-dot')).toBe('')
    expect(suffixOf('a.b.jpg')).toBe('.jpg')
  })
})
