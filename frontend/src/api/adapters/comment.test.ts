import { describe, expect, it } from 'vitest'
import type { components } from '../../types/api'
import { toComment, toCommentPage, toPublishedComment, toReplyPage } from './comment'

type CommentVO = components['schemas']['CommentVO']
type PageResultCommentVO = components['schemas']['PageResultCommentVO']
type CursorResultCommentVO = components['schemas']['CursorResultCommentVO']

/** 夹具按真实响应形状造：children 运行时是带 isLiked 的 CommentVO（spec 声明的 CommentCacheDTO 是缩水版） */
function comment(fields: Partial<Record<keyof CommentVO, unknown>> = {}): CommentVO {
  return {
    commentId: 14,
    contentId: 65,
    userId: 4,
    username: '五个字神人',
    content: '不是你们网站是交不起网费吗',
    parentId: null,
    likeCount: 3,
    isLiked: false,
    replyCount: 3,
    children: [],
    replyToUserId: null,
    replyToUsername: null,
    ...fields,
  } as CommentVO
}

describe('toComment', () => {
  it('基础映射：首字头像、点赞态、楼中楼预览', () => {
    const vm = toComment(
      comment({
        children: [
          { commentId: 32, userId: 11, username: '我需要医疗兵', content: '是这样的', parentId: 14, likeCount: 0, isLiked: true },
        ],
      }),
      4,
    )
    expect(vm.id).toBe(14)
    expect(vm.initial).toBe('五')
    expect(vm.likeCount).toBe(3)
    expect(vm.liked).toBe(false)
    expect(vm.replies).toHaveLength(1)
    expect(vm.replies[0]?.liked).toBe(true) // ★ children 的点赞态是真的（spec 少声明了）
    expect(vm.replies[0]?.parentId).toBe(14)
  })

  it('★ 后端没有 createTime ⇒ timeText 恒空串（不编"刚刚"）', () => {
    expect(toComment(comment(), null).timeText).toBe('')
  })

  it('canDelete 只给自己的评论（匿名一律 false）', () => {
    expect(toComment(comment({ userId: 4 }), 4).canDelete).toBe(true)
    expect(toComment(comment({ userId: 4 }), 5).canDelete).toBe(false)
    expect(toComment(comment({ userId: 4 }), null).canDelete).toBe(false)
  })

  it('楼中楼的「回复 @xxx」来自 replyToUsername；主楼为 null', () => {
    expect(toComment(comment({ parentId: 14, replyToUsername: '哈中王' }), null).replyToName).toBe('哈中王')
    expect(toComment(comment(), null).replyToName).toBeNull()
  })

  it('昵称缺失 ⇒ 匿名用户 + U 首字（不渲染空头像）', () => {
    const vm = toComment(comment({ username: undefined }), null)
    expect(vm.authorName).toBe('匿名用户')
    expect(vm.initial).toBe('匿')
  })

  it('replyCount 是回复总数，children 只是前 2 条预览（两者不可互相替代）', () => {
    const vm = toComment(comment({ replyCount: 7, children: [comment({ commentId: 1 })] }), null)
    expect(vm.replyCount).toBe(7)
    expect(vm.replies).toHaveLength(1)
  })
})

describe('toCommentPage', () => {
  it('total 是主楼条数（不是评论总数）', () => {
    const result: PageResultCommentVO = {
      list: [comment(), comment({ commentId: 17 })],
      total: 6,
      page: 1,
      pageSize: 10,
      totalPages: 1,
    }
    const vm = toCommentPage(result, null)
    expect(vm.items).toHaveLength(2)
    expect(vm.total).toBe(6)
    expect(vm.totalPages).toBe(1)
  })

  it('空 / 缺省信封 ⇒ 空页（评论区关闭时后端就回这个）', () => {
    expect(toCommentPage(undefined, null)).toEqual({ items: [], total: 0, page: 1, pageSize: 0, totalPages: 0 })
  })
})

describe('toReplyPage', () => {
  it('游标：nextCursor 为 null ⇒ 到底', () => {
    const result: CursorResultCommentVO = {
      list: [comment({ commentId: 32, parentId: 14 })],
      total: 3,
      pageSize: 10,
      nextCursor: 32,
    }
    const vm = toReplyPage(result, null)
    expect(vm.items).toHaveLength(1)
    expect(vm.nextCursor).toBe(32)
    expect(vm.total).toBe(3)
    expect(toReplyPage({ list: [], total: 3, pageSize: 10 }, null).nextCursor).toBeNull()
  })
})

describe('toPublishedComment', () => {
  it('刚发布的评论：id 来自服务端，作者来自登录态，可删', () => {
    const vm = toPublishedComment({
      id: 501,
      parentId: null,
      content: '好活',
      viewer: { id: 233, name: 'frontend_demo', initial: 'F', admin: false, coin: 0, signedToday: false, spaceHref: '/space/233' },
    })
    expect(vm.id).toBe(501)
    expect(vm.authorName).toBe('frontend_demo')
    expect(vm.initial).toBe('F')
    expect(vm.canDelete).toBe(true)
    expect(vm.likeCount).toBe(0)
    expect(vm.timeText).toBe('') // 与旧评论一致：都没有时间
  })

  it('回复：带上被 @ 的人与主楼 id', () => {
    const vm = toPublishedComment({ id: 502, parentId: 14, content: '同意', viewer: null, replyToName: '哈中王' })
    expect(vm.parentId).toBe(14)
    expect(vm.replyToName).toBe('哈中王')
    expect(vm.authorName).toBe('我')
  })
})
