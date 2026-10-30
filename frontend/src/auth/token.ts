/**
 * 登录凭据的**唯一落点**（localStorage）。
 *
 * <p>为什么不是 Cookie：后端鉴权是 `Authorization: Bearer`（`JwtAuthFilter`），不是会话 Cookie。
 * 单独一层是为了让 `http.ts` 能读 token 而**不反向依赖** `session.ts`（否则循环依赖）。
 */
const STORAGE_KEY = 'myspringtv.token'

export function readToken(): string | null {
  try {
    return globalThis.localStorage?.getItem(STORAGE_KEY) ?? null
  } catch {
    return null
  }
}

export function writeToken(token: string | null): void {
  try {
    if (token) globalThis.localStorage?.setItem(STORAGE_KEY, token)
    else globalThis.localStorage?.removeItem(STORAGE_KEY)
  } catch {
    // 隐私模式 / 存储被禁用：退化为「本次会话内内存态」，不抛错打断页面
  }
}
