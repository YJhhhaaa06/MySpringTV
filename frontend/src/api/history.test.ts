import { beforeEach, describe, expect, it, vi } from 'vitest'
import { clearHistory, deleteHistory } from './history'
import { deleteJson } from './http'

/**
 * 删除历史的端点层（`R-26`）。
 *
 * <p>为什么值得单测：这两个函数的**全部价值就在"发出什么参数"**上 ——
 * 界面照样能点、删除照样"成功"，但清空写成"少传 contentIds"会被服务端 400，
 * 批量写成"一条一条删"会变成 N 个请求（还会在删到一半失败时留下半成品状态）。
 *
 * <h2>反向验证（注入 ⇒ 必红）</h2>
 * ① `clearHistory` 改成 `deleteJson('/api/v1/history')`（不传 all）⇒ 第一条红
 * （服务端会当"缺参"回 400，且"清空"靠的是**显式意图**，不是"少给一个参数"）；
 * ② `deleteHistory` 改成循环单条删除 ⇒ 第二条红（断言的是**一次**调用带全部 id）。
 */
vi.mock('./http', () => ({
  deleteJson: vi.fn(() => Promise.resolve(undefined)),
  getJson: vi.fn(),
  postJson: vi.fn(),
  putJson: vi.fn(),
}))

const mockedDelete = vi.mocked(deleteJson)

describe('删除历史端点', () => {
  beforeEach(() => {
    mockedDelete.mockClear()
  })

  it('★清空必须显式 all=true（不能靠"少传 contentIds"表达清空）', async () => {
    await clearHistory()

    expect(mockedDelete).toHaveBeenCalledTimes(1)
    expect(mockedDelete).toHaveBeenCalledWith('/api/v1/history', { query: { all: true } })
  })

  it('★批量删除是一次请求带全部 id（不是循环单条）', async () => {
    await deleteHistory([3, 9, 11])

    expect(mockedDelete).toHaveBeenCalledTimes(1)
    expect(mockedDelete).toHaveBeenCalledWith('/api/v1/history', {
      query: { contentIds: [3, 9, 11] },
    })
  })

  it('批量删除与清空走的是同一条路由的两种形态（互斥）', async () => {
    await deleteHistory([1])
    await clearHistory()

    const [, first] = mockedDelete.mock.calls[0] as [string, { query: Record<string, unknown> }]
    const [, second] = mockedDelete.mock.calls[1] as [string, { query: Record<string, unknown> }]
    expect(first.query.contentIds).toEqual([1])
    expect(first.query.all).toBeUndefined()
    expect(second.query.all).toBe(true)
    expect(second.query.contentIds).toBeUndefined()
  })
})
