import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, deleteJson, getJson, postJson, putJson } from './http'
import { IDEMPOTENCY_HEADER, newIdempotencyKey } from './idempotency'

/**
 * 请求层的契约测试（不是"调用了 fetch"的实现测试）。
 *
 * <p>断言的全是**跨端契约**：Authorization 怎么带、204 怎么算成功、
 * `Retry-After` 怎么变成可用的退避信号、数组 query 怎么展开。
 * 这些规则任一条写错，页面层是看不出来的（表现都是"偶发失败/偶发 401"）。
 */

interface Recorded {
  url: string
  init: RequestInit
}

const calls: Recorded[] = []

function stubFetch(handler: (call: Recorded) => Response): void {
  vi.stubGlobal('fetch', (url: string, init: RequestInit) => {
    const call = { url, init }
    calls.push(call)
    return Promise.resolve(handler(call))
  })
}

function jsonResponse(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  })
}

function headerOf(call: Recorded, name: string): string | null {
  const headers = new Headers(call.init.headers)
  return headers.get(name)
}

beforeEach(() => {
  calls.length = 0
  // token 的唯一落点是 localStorage（node 环境没有 ⇒ 给一个最小替身）
  vi.stubGlobal('localStorage', {
    store: new Map<string, string>(),
    getItem(key: string) {
      return (this.store as Map<string, string>).get(key) ?? null
    },
    setItem(key: string, value: string) {
      ;(this.store as Map<string, string>).set(key, value)
    },
    removeItem(key: string) {
      ;(this.store as Map<string, string>).delete(key)
    },
    clear() {
      ;(this.store as Map<string, string>).clear()
    },
  } satisfies Partial<Storage>)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('信封与身份', () => {
  it('成功体只把 data 交出去（信封不外泄给页面）', async () => {
    stubFetch(() => jsonResponse({ code: 'OK', msg: 'success', data: { progress: 12 } }))
    await expect(getJson('/api/v1/contents/{id}/progress', { path: { id: 65 } })).resolves.toEqual({
      progress: 12,
    })
    expect(calls[0]?.url).toBe('/api/v1/contents/65/progress')
  })

  it('有 token 才带 Authorization（匿名请求不许出现空 Bearer）', async () => {
    stubFetch(() => jsonResponse({ code: 'OK', data: [] }))
    await getJson('/api/v1/users/me')
    expect(headerOf(calls[0]!, 'Authorization')).toBeNull()

    localStorage.setItem('myspringtv.token', 'tok-123')
    await getJson('/api/v1/users/me')
    expect(headerOf(calls[1]!, 'Authorization')).toBe('Bearer tok-123')
  })

  it('失败体 → ApiError（业务码 + 字段错误 + 人读文案都能拿到）', async () => {
    stubFetch(() =>
      jsonResponse(
        {
          code: 'PARAM_INVALID',
          msg: '输入不能为空',
          errors: [{ code: 'PARAM_INVALID', field: 'message', message: '评论不能为空' }],
        },
        400,
      ),
    )
    const error = await postJson('/api/v1/comments', { contentId: 65, message: '' }).catch((e) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(400)
    expect(error.code).toBe('PARAM_INVALID')
    expect(error.fieldMessage('message')).toBe('评论不能为空')
    expect(error.isAuthError).toBe(false)
  })
})

describe('写动词', () => {
  it('PUT 带 JSON body 与 Content-Type；无 body 的 PUT 不许伪造空对象', async () => {
    stubFetch(() => jsonResponse({ code: 'OK', msg: 'success' }))
    await putJson('/api/v1/contents/{id}/progress', { progress: 42 }, { path: { id: 65 } })
    expect(calls[0]!.init.method).toBe('PUT')
    expect(calls[0]!.init.body).toBe('{"progress":42}')
    expect(headerOf(calls[0]!, 'Content-Type')).toBe('application/json')

    await putJson('/api/v1/contents/{id}/like', undefined, { path: { id: 65 } })
    expect(calls[1]!.init.body).toBeUndefined()
    expect(headerOf(calls[1]!, 'Content-Type')).toBeNull()
  })

  it('204 无体是成功（删除类端点），不是"非 JSON 响应"', async () => {
    stubFetch(() => new Response(null, { status: 204 }))
    await expect(deleteJson('/api/v1/comments/{id}', { path: { id: 9 } })).resolves.toBeUndefined()
    expect(calls[0]!.init.method).toBe('DELETE')
  })

  it('幂等键由调用方显式带上（本层不自动生成）', async () => {
    stubFetch(() => jsonResponse({ code: 'OK', data: { commentId: 1 } }))
    const key = newIdempotencyKey()
    await postJson('/api/v1/comments', { contentId: 65, message: 'hi' }, { headers: { [IDEMPOTENCY_HEADER]: key } })
    expect(headerOf(calls[0]!, IDEMPOTENCY_HEADER)).toBe(key)

    await postJson('/api/v1/comments', { contentId: 65, message: 'hi' })
    expect(headerOf(calls[1]!, IDEMPOTENCY_HEADER)).toBeNull()
  })

  it('幂等键必须满足后端 [A-Za-z0-9._-]{1,64}，否则 400 白挨一刀', () => {
    expect(newIdempotencyKey()).toMatch(/^[A-Za-z0-9._-]{1,64}$/)
  })
})

describe('重试信号与网络失败', () => {
  it('503 的 Retry-After 变成秒数（进度上报靠它降频）', async () => {
    stubFetch(() =>
      jsonResponse({ code: 'SYSTEM_ENDPOINT_NOT_FOUND', msg: '服务暂不可用' }, 503, { 'Retry-After': '30' }),
    )
    const error = await putJson('/api/v1/contents/{id}/progress', { progress: 5 }, { path: { id: 65 } }).catch(
      (e) => e,
    )
    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(503)
    expect(error.retryAfterSeconds).toBe(30)
  })

  it('没有 Retry-After 时为 null（不许瞎猜一个数）', async () => {
    stubFetch(() => jsonResponse({ code: 'COIN_INSUFFICIENT', msg: '硬币不足' }, 400))
    const error = await postJson('/api/v1/contents/{id}/coins', { count: 2 }, { path: { id: 65 } }).catch(
      (e) => e,
    )
    expect(error.retryAfterSeconds).toBeNull()
  })

  it('网络层失败 → status 0（页面据此区分"后端没起"与"业务拒绝"）', async () => {
    vi.stubGlobal('fetch', () => Promise.reject(new TypeError('fetch failed')))
    const error = await getJson('/api/v1/contents/{id}', { path: { id: 1 } }).catch((e) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(0)
    expect(error.code).toBe('NETWORK_ERROR')
  })
})

describe('查询串', () => {
  it('数组展开成重复键，空值被丢掉（搜索页 / 收藏删除都用得到）', async () => {
    stubFetch(() => jsonResponse({ code: 'OK', data: {} }))
    await getJson('/api/v1/contents', { query: { keyword: '测试', page: 2, pageSize: undefined } })
    expect(calls[0]!.url).toBe('/api/v1/contents?keyword=%E6%B5%8B%E8%AF%95&page=2')

    await deleteJson('/api/v1/favorites', { query: { folderId: 14, contentIds: [65, 66] } })
    expect(calls[1]!.url).toBe('/api/v1/favorites?folderId=14&contentIds=65&contentIds=66')
  })
})
