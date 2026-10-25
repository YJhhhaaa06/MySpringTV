import { describe, expect, it } from 'vitest'
import type { components } from '../../types/api'
import { toVideoDetail } from './videoDetail'

type ContentDetailVO = components['schemas']['ContentDetailVO']

function detail(overrides: Partial<Record<keyof ContentDetailVO, unknown>> = {}): ContentDetailVO {
  // 同 videoCard.test：nullable 未声明，夹具按真实响应形状造
  return {
    id: 92,
    title: '这期具有教育意义🐧',
    description: '🐧#mygo',
    authorId: 1,
    authorName: '一号员工',
    createTime: '2026-05-09T19:43:40',
    coverUrl: '/upload/cover/a.png',
    videoUrl: '/upload/video/a.mp4',
    durationSeconds: null,
    type: 1,
    commentEnabled: true,
    playCount: 0,
    likeCount: 12_345,
    coinCount: 0,
    favoriteCount: 0,
    commentCount: 0,
    ...overrides,
  } as ContentDetailVO
}

describe('toVideoDetail', () => {
  it('详情侧五个计数都是真数字（与列表侧相反）', () => {
    const vm = toVideoDetail(detail())
    expect(vm.stats).toEqual({ play: '0', like: '1.2万', coin: '0', favorite: '0', comment: '0' })
  })

  it('计数缺失 ⇒ `-`（不假装是 0）', () => {
    const vm = toVideoDetail(detail({ playCount: undefined, likeCount: undefined }))
    expect(vm.stats.play).toBe('-')
    expect(vm.stats.like).toBe('-')
  })

  it('视频：带播放源与封面', () => {
    const vm = toVideoDetail(detail())
    expect(vm.videoUrl).toBe('/upload/video/a.mp4')
    expect(vm.coverUrl).toBe('/upload/cover/a.png')
    expect(vm.isPost).toBe(false)
    expect(vm.dateText).toBe('2026-05-09 19:43')
  })

  it('图文：即使回填了 videoUrl 也不当视频播，改用图片列表', () => {
    const vm = toVideoDetail(
      detail({ type: 2, videoUrl: '/upload/video/a.mp4', imageUrls: ['/upload/image/1.png', '  ', '/upload/image/2.png'] }),
    )
    expect(vm.isPost).toBe(true)
    expect(vm.videoUrl).toBeNull()
    expect(vm.images).toEqual(['/upload/image/1.png', '/upload/image/2.png']) // 空白 URL 被过滤
  })

  it('durationSeconds 为 null ⇒ 不渲染时长', () => {
    expect(toVideoDetail(detail()).durationText).toBeNull()
  })

  it('commentEnabled 只有显式 false 才算关（缺省按开）', () => {
    expect(toVideoDetail(detail()).commentEnabled).toBe(true)
    expect(toVideoDetail(detail({ commentEnabled: false })).commentEnabled).toBe(false)
  })
})
