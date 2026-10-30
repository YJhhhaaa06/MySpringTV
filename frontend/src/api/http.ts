import { readToken } from '../auth/token'
import type { components, paths } from '../types/api'
import { syncServerClock } from './serverClock'

/**
 * 极简请求层：**只为"类型来自 spec"这一件事存在**。
 *
 * <p>信封口径：成功 `{code:"OK", msg, data?}`；失败 `{code, msg, errors?}`（失败体没有 data）。
 * HTTP status 只表类别，业务码在 `code` 里 —— 所以这里两者都收：非 2xx 与 `code !== 'OK'` 都算失败。
 *
 * <p>不装 axios / openapi-fetch：本仓的动词只有 GET/POST/PUT/DELETE，多一层运行时依赖，
 * 换来的是"讲不清它替我做了什么"。路径与出入参的**类型**由生成层给，运行时就这几个函数。
 *
 * <h2>四件事只在这里做（各写一遍 = 必然漏）</h2>
 * <ol>
 *   <li><b>信封解包</b>：`data` 出栈、`code/msg/errors` 变 {@link ApiError}；</li>
 *   <li><b>Authorization</b>：token 只从 {@code auth/token.ts} 读（不反向依赖 session）；</li>
 *   <li><b>204 无体</b>：删除类端点的成功响应没有 body（`DELETE /contents/{id}/like` 等），
 *       不能当"返回了非 JSON"报错；</li>
 *   <li><b>`Retry-After`</b>：429 / 503 才有，播放进度上报要靠它降频（见
 *       `architecture/domain/history/history-客户端契约.md` §五）。</li>
 * </ol>
 *
 * <h2>★ 唯一例外：multipart 上传走 {@link postForm}</h2>
 * `fetch` 不提供上传进度，而投稿页的进度条是用户能看见的东西 ⇒ 上传用 `XMLHttpRequest`。
 * 但**信封解包 / Bearer / 204 / Retry-After 与上面是同一份实现**（{@link parseEnvelope}），
 * 不允许在页面里另写一套判信封。
 *
 * <h2>★ 写接口的幂等键是**调用方显式传**的</h2>
 * 后端只有 4 条 `KEYED` 端点（评论 / 投币 / 投稿视频 / 投稿图文）——它们的效果层非幂等，
 * 重试会多一条评论、多投一次币。键**不由本层自动生成**：自动加会掩盖"这个端点是否需要幂等"
 * 这个业务判断，也会让超时重试悄悄变成"同一个键的第二刀"。调用方按需传
 * {@code options.headers['Idempotency-Key']}（见 `api/idempotency.ts`）。
 */

/**
 * 成功响应的 data 类型。
 *
 * <p>`200 + JSON` 取信封里的 `data`；`204` 没有响应体 ⇒ `undefined`（删除类端点）。
 * <p>★ `ApiResponseVoid` 的 `data` 在 spec 里是空 schema ⇒ 生成成 `data?: unknown`
 * （`POST /contents/{id}/like`、`/history/flush` 这类"成功但没有值"的端点）。
 * 这里把它归一到 `undefined`，而不是让调用方拿到一个 `{}`（读它就是错的）。
 */
type JsonBody<R> = R extends { data?: infer D }
  ? unknown extends D
    ? undefined
    : NonNullable<D>
  : never
type OkData<Op> = Op extends { responses: { 200: { content: { 'application/json': infer R } } } }
  ? JsonBody<R>
  : Op extends { responses: { 204: unknown } }
    ? undefined
    : never

type OpFor<K extends keyof paths, M extends 'get' | 'post' | 'put' | 'delete'> = paths[K] extends Record<
  M,
  infer Op
>
  ? Op
  : never

type JsonRequestBody<Op> = Op extends { requestBody: { content: { 'application/json': infer B } } }
  ? B
  : never

/** 查询串取值：数组会展开成重复键（`contentIds=1&contentIds=2`，Spring 两种都认）。 */
export type QueryValue = string | number | boolean | null | undefined | ReadonlyArray<string | number>
export type QueryParams = Record<string, QueryValue>

export class ApiError extends Error {
  /** 0 = 网络层失败（没拿到 HTTP 响应） */
  readonly status: number
  readonly code: string
  readonly fields: components['schemas']['ApiFieldError'][]
  /** 仅 429 / 503 带：服务端给的"多久之后再来"（秒）。其余情况 null */
  readonly retryAfterSeconds: number | null

  constructor(
    status: number,
    code: string,
    message: string,
    fields: components['schemas']['ApiFieldError'][] = [],
    retryAfterSeconds: number | null = null,
  ) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.fields = fields
    this.retryAfterSeconds = retryAfterSeconds
  }

  /** 挂在某个输入框上的错误（登录 / 评论框用） */
  fieldMessage(field: string): string | null {
    return this.fields.find((f) => f.field === field)?.message ?? null
  }

  /** 身份类失败（token 过期 / 未登录 / 越权）——调用方据此清 token 或弹登录 */
  get isAuthError(): boolean {
    return this.status === 401 || this.status === 403
  }
}

const TIMEOUT_MS = 15_000

export interface RequestOptions {
  /** 路径模板参数，如 `/api/v1/contents/{id}` + `{ id: 92 }` */
  path?: Record<string, string | number>
  /** 查询参数（`undefined` / `null` / 空串会被丢掉） */
  query?: QueryParams
  /** 额外请求头（目前只有 `Idempotency-Key` 用它） */
  headers?: Record<string, string>
}

/** @deprecated 用 {@link RequestOptions}；保留别名是为了不改旧调用点 */
export type GetOptions = RequestOptions

function buildUrl(path: string, query?: QueryParams): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(query ?? {})) {
    if (value === null || value === undefined || value === '') continue
    if (Array.isArray(value)) {
      for (const item of value) {
        if (item !== null && item !== undefined && item !== '') search.append(key, String(item))
      }
      continue
    }
    search.set(key, String(value))
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

function retryAfterOf(response: Response): number | null {
  return retryAfterValue(response.headers.get('Retry-After'))
}

/** `Retry-After` 头 → 秒数（缺失 / 非数字 / 负数 ⇒ null，不瞎猜）。 */
function retryAfterValue(header: string | null): number | null {
  if (!header) return null
  const seconds = Number(header)
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : null
}

async function request<T>(url: string, init: RequestInit, headers: Headers): Promise<T> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS)
  headers.set('Accept', 'application/json')
  const token = readToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)

  let response: Response
  try {
    response = await fetch(url, { ...init, headers, signal: controller.signal })
  } catch {
    throw new ApiError(0, 'NETWORK_ERROR', '网络异常，请稍后重试')
  } finally {
    clearTimeout(timer)
  }

  // ★ 用服务器时间校准"现在"：`Date` 由服务器生成，与 create_time 同源。
  //   必须在 parseEnvelope 之前同步 —— 时间文本是在本函数返回之后才格式化的，
  //   晚一步就会让首屏用浏览器时钟渲染（用户改过系统时间时表现为"全部刚刚"）。
  syncServerClock(response.headers.get('Date'))

  return parseEnvelope<T>(response.status, await response.text(), retryAfterOf(response))
}

/**
 * 信封解包（fetch 与 XHR **共用这一份**）。
 *
 * <p>规则与原 `request` 逐条一致：204 无体是成功；非 2xx 或 `code !== 'OK'` 都是失败；
 * 失败体没有 `data`，只有 `code/msg/errors`。
 */
function parseEnvelope<T>(status: number, text: string, retryAfterSeconds: number | null): T {
  if (status === 204 && !text) {
    return undefined as T
  }

  let payload: unknown = null
  if (text) {
    try {
      payload = JSON.parse(text)
    } catch {
      throw new ApiError(status, 'BAD_RESPONSE', '服务端返回了非 JSON 响应')
    }
  }

  const envelope = payload as
    | { code?: string; msg?: string; data?: T; errors?: components['schemas']['ApiFieldError'][] }
    | null
  if (status < 200 || status >= 300 || envelope?.code !== 'OK') {
    throw new ApiError(
      status,
      envelope?.code ?? 'UNKNOWN',
      envelope?.msg ?? `请求失败（HTTP ${status}）`,
      envelope?.errors ?? [],
      retryAfterSeconds,
    )
  }
  return envelope.data as T
}

function mergeHeaders(options?: RequestOptions): Headers {
  const headers = new Headers()
  for (const [key, value] of Object.entries(options?.headers ?? {})) headers.set(key, value)
  return headers
}

/** GET：返回类型由 `paths` 推出，响应形状与 spec 永远同源。 */
export function getJson<K extends keyof paths>(
  path: K,
  options?: RequestOptions,
): Promise<OkData<OpFor<K, 'get'>>> {
  const url = buildUrl(expandPath(path as string, options?.path), options?.query)
  return request(url, { method: 'GET' }, mergeHeaders(options))
}

/** POST（JSON body）。无体的 POST 传 `undefined`（`/contents/{id}/play`、`/history/flush`）。 */
export function postJson<K extends keyof paths>(
  path: K,
  body?: JsonRequestBody<OpFor<K, 'post'>>,
  options?: RequestOptions,
): Promise<OkData<OpFor<K, 'post'>>> {
  const url = buildUrl(expandPath(path as string, options?.path), options?.query)
  const headers = mergeHeaders(options)
  const init: RequestInit = { method: 'POST' }
  if (body !== undefined) {
    headers.set('Content-Type', 'application/json')
    init.body = JSON.stringify(body)
  }
  return request(url, init, headers)
}

/** PUT（JSON body 可缺：`PUT /contents/{id}/like`、`/follows/{id}` 都没有 body）。 */
export function putJson<K extends keyof paths>(
  path: K,
  body?: JsonRequestBody<OpFor<K, 'put'>>,
  options?: RequestOptions,
): Promise<OkData<OpFor<K, 'put'>>> {
  const url = buildUrl(expandPath(path as string, options?.path), options?.query)
  const headers = mergeHeaders(options)
  const init: RequestInit = { method: 'PUT' }
  if (body !== undefined) {
    headers.set('Content-Type', 'application/json')
    init.body = JSON.stringify(body)
  }
  return request(url, init, headers)
}

/** DELETE（多数返回 204 无体；`DELETE /favorites` 用 query 传夹与内容） */
export function deleteJson<K extends keyof paths>(
  path: K,
  options?: RequestOptions,
): Promise<OkData<OpFor<K, 'delete'>>> {
  const url = buildUrl(expandPath(path as string, options?.path), options?.query)
  return request(url, { method: 'DELETE' }, mergeHeaders(options))
}

/**
 * 上传的超时是**独立的 10 分钟**，不套 JSON 请求的 15 秒。
 *
 * <p>理由：50MB 视频在慢网络下 15 秒必然传不完，而把 JSON 的超时也拉长又会让页面卡死等不到错误。
 * 超时后重试是安全的——投稿端点带幂等键，服务端若已落库会重放第一次的响应。
 */
export const UPLOAD_TIMEOUT_MS = 10 * 60 * 1000

export interface FormRequestOptions extends RequestOptions {
  /** 上传进度（`loaded/total` 含 multipart 开销；`lengthComputable` 为假时不回调） */
  onProgress?: (loaded: number, total: number) => void
}

/**
 * POST + `multipart/form-data`（投稿页专用；`fetch` 没有上传进度）。
 *
 * <p>文本字段也放进 `FormData`（不是 query）：图文简介上限 1 万字，拼进 URL 会撞请求行长度；
 * Spring 的 `@RequestParam` 在 multipart 请求里同样读 form field（旧前端就是这么发的）。
 *
 * <p>**不手动设 `Content-Type`**：boundary 必须由 XHR 自己生成。
 */
export function postForm<K extends keyof paths>(
  path: K,
  form: FormData,
  options?: FormRequestOptions,
): Promise<OkData<OpFor<K, 'post'>>> {
  const url = buildUrl(expandPath(path as string, options?.path), options?.query)
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('POST', url)
    xhr.timeout = UPLOAD_TIMEOUT_MS
    xhr.setRequestHeader('Accept', 'application/json')
    const token = readToken()
    if (token) xhr.setRequestHeader('Authorization', `Bearer ${token}`)
    for (const [name, value] of Object.entries(options?.headers ?? {})) xhr.setRequestHeader(name, value)

    if (options?.onProgress) {
      const onProgress = options.onProgress
      xhr.upload.onprogress = (event) => {
        if (event.lengthComputable) onProgress(event.loaded, event.total)
      }
    }

    xhr.onload = () => {
      try {
        syncServerClock(xhr.getResponseHeader('Date'))
        resolve(
          parseEnvelope(xhr.status, xhr.responseText, retryAfterValue(xhr.getResponseHeader('Retry-After'))),
        )
      } catch (error) {
        reject(error)
      }
    }
    xhr.onerror = () => reject(new ApiError(0, 'NETWORK_ERROR', '网络异常，请稍后重试'))
    xhr.ontimeout = () => reject(new ApiError(0, 'UPLOAD_TIMEOUT', '上传超时，请检查网络后重试'))
    xhr.onabort = () => reject(new ApiError(0, 'UPLOAD_ABORTED', '上传已取消'))
    xhr.send(form)
  })
}

/**
 * 把失败翻成**页面级**人话（字段级错误请用 {@link ApiError#fieldMessage}）。
 *
 * <p>为什么不让页面直接吃 `error.message`：服务端的 `msg` 是给日志/接口调试看的
 * （"请求过于频繁"、"输入不能为空"），在整页错误态里既不说明"该不该重试"，
 * 也不说明"等多久"。三个瞬态类别必须分开说，否则用户只能盲点重试。
 */
export function describeError(error: unknown, fallback = '请求失败，请稍后重试'): string {
  if (!(error instanceof ApiError)) return fallback
  if (error.status === 0) return '连不上后端（服务可能没启动），请稍后重试'
  if (error.status === 429) return `访问太频繁了，${error.retryAfterSeconds ?? 30} 秒后再试`
  if (error.status >= 500) return '服务端开小差了，请稍后重试'
  return error.message
}
