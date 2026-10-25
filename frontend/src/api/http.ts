import { readToken } from '../auth/token'
import type { components, paths } from '../types/api'

/**
 * 极简请求层：**只为"类型来自 spec"这一件事存在**。
 *
 * <p>信封口径：成功 `{code:"OK", msg, data?}`；失败 `{code, msg, errors?}`（失败体没有 data）。
 * HTTP status 只表类别，业务码在 `code` 里 —— 所以这里两者都收：非 2xx 与 `code !== 'OK'` 都算失败。
 *
 * <p>不装 axios / openapi-fetch：本仓只有 GET 与一个登录 POST，多一层运行时依赖，
 * 换来的是"讲不清它替我做了什么"。路径与出入参的**类型**由生成层给，运行时就这几行。
 */

type JsonOk<Op> = Op extends { responses: { 200: { content: { 'application/json': infer R } } } } ? R : never
type UnwrapData<R> = R extends { data?: infer D } ? NonNullable<D> : never
type GetOp<K extends keyof paths> = paths[K] extends { get: infer Op } ? Op : never
type PostOp<K extends keyof paths> = paths[K] extends { post: infer Op } ? Op : never
type PostBody<Op> = Op extends { requestBody: { content: { 'application/json': infer B } } } ? B : never

export type QueryParams = Record<string, string | number | boolean | null | undefined>

export class ApiError extends Error {
  /** 0 = 网络层失败（没拿到 HTTP 响应） */
  readonly status: number
  readonly code: string
  readonly fields: components['schemas']['ApiFieldError'][]

  constructor(status: number, code: string, message: string, fields: components['schemas']['ApiFieldError'][] = []) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.fields = fields
  }

  /** 挂在某个输入框上的错误（登录表单用） */
  fieldMessage(field: string): string | null {
    return this.fields.find((f) => f.field === field)?.message ?? null
  }
}

const TIMEOUT_MS = 15_000

export interface GetOptions {
  query?: QueryParams
  /** 路径模板参数，如 `/api/v1/contents/{id}` + `{ id: 92 }` */
  path?: Record<string, string | number>
}

function buildUrl(path: string, query?: QueryParams): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(query ?? {})) {
    if (value !== null && value !== undefined && value !== '') search.set(key, String(value))
  }
  const qs = search.toString()
  return qs ? `${path}?${qs}` : path
}

function expandPath(template: string, params?: Record<string, string | number>): string {
  return template.replace(/\{([^}]+)\}/g, (_match, name: string) => {
    const value = params?.[name]
    if (value === undefined) throw new ApiError(0, 'MISSING_PATH_PARAM', `缺少路径参数 ${name}`)
    return encodeURIComponent(String(value))
  })
}

async function request<T>(path: string, init: RequestInit): Promise<T> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS)
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  const token = readToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)

  let response: Response
  try {
    response = await fetch(path, { ...init, headers, signal: controller.signal })
  } catch {
    throw new ApiError(0, 'NETWORK_ERROR', '网络异常，请稍后重试')
  } finally {
    clearTimeout(timer)
  }

  const text = await response.text()
  let payload: unknown = null
  if (text) {
    try {
      payload = JSON.parse(text)
    } catch {
      throw new ApiError(response.status, 'BAD_RESPONSE', '服务端返回了非 JSON 响应')
    }
  }

  const envelope = payload as { code?: string; msg?: string; data?: T; errors?: components['schemas']['ApiFieldError'][] } | null
  if (!response.ok || envelope?.code !== 'OK') {
    throw new ApiError(
      response.status,
      envelope?.code ?? 'UNKNOWN',
      envelope?.msg ?? `请求失败（HTTP ${response.status}）`,
      envelope?.errors ?? [],
    )
  }
  return envelope.data as T
}

/** GET：返回类型由 `paths` 推出，响应形状与 spec 永远同源。 */
export function getJson<K extends keyof paths>(
  path: K,
  options?: GetOptions,
): Promise<UnwrapData<JsonOk<GetOp<K>>>> {
  const url = buildUrl(expandPath(path as string, options?.path), options?.query)
  return request(url, { method: 'GET' })
}

/** POST（只用于 `POST /sessions` 这类 JSON body 端点；multipart 不在本层）。 */
export function postJson<K extends keyof paths>(
  path: K,
  body: PostBody<PostOp<K>>,
): Promise<UnwrapData<JsonOk<PostOp<K>>>> {
  return request(path as string, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}
