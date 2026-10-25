import { reactive, readonly } from 'vue'
import { createSession, fetchMe } from '../api/account'
import { toCurrentUser } from '../api/adapters/user'
import { ApiError } from '../api/http'
import type { CurrentUserVM } from '../types/ui'
import { readToken, writeToken } from './token'

/**
 * 登录态（**不装 Pinia**：跨页共享真出现之前，一个模块级 reactive 就够 —— 见登记台 §一）。
 *
 * <p>口径：**页面之间不共享内存态**。这里的状态活在一个应用实例里
 * （首页 / 搜索 / 播放页各自一次），每次进应用都靠 `restoreSession()` 从 localStorage + `GET /users/me` 重建。
 */
export type SessionStatus = 'restoring' | 'anonymous' | 'authenticated'

interface SessionState {
  status: SessionStatus
  user: CurrentUserVM | null
}

const state = reactive<SessionState>({ status: 'restoring', user: null })

/** 只读视图：组件只许读，改状态必须走下面三个动作 */
export const sessionState = readonly(state) as Readonly<SessionState>

/**
 * 从 localStorage 恢复登录态。**幂等**：只在本应用首次挂载时调一次，重复调用直接返回。
 * token 失效（401）时清掉本地 token，退化为匿名 —— 不把用户卡在"看起来登录了但什么都点不了"。
 */
export async function restoreSession(): Promise<void> {
  if (state.status !== 'restoring') return
  if (!readToken()) {
    state.status = 'anonymous'
    return
  }
  try {
    state.user = toCurrentUser(await fetchMe())
    state.status = 'authenticated'
  } catch (error) {
    if (error instanceof ApiError && (error.status === 401 || error.status === 403)) writeToken(null)
    state.user = null
    state.status = 'anonymous'
  }
}

/** 登录：`POST /sessions` 拿 token → 写 localStorage → `GET /users/me` 拿用户。失败抛 `ApiError`。 */
export async function signIn(phone: string, password: string): Promise<CurrentUserVM> {
  const login = await createSession(phone, password)
  if (!login?.token) throw new ApiError(200, 'NO_TOKEN', '登录成功但没有返回 token')
  writeToken(login.token)
  const user = toCurrentUser(await fetchMe())
  state.user = user
  state.status = 'authenticated'
  return user
}

export function signOut(): void {
  writeToken(null)
  state.user = null
  state.status = 'anonymous'
}

/** 是否已登录（动作级闸门与组件都读它，别各自判断 `status === 'authenticated'`）。 */
export function isAuthenticated(): boolean {
  return state.status === 'authenticated' && state.user !== null
}

/**
 * 服务端回来的**权威余额**覆盖本地硬币数（投币 / 签到之后）。
 *
 * <p>为什么必须由服务端给：本地的 `coin - 1` 只是乐观展示；余额是会被
 * 签到 / 其它标签页改动的共享事实，前端自己算必然漂移。
 */
export function applyCoinBalance(balance: number | null | undefined): void {
  if (!state.user || typeof balance !== 'number' || !Number.isFinite(balance)) return
  state.user = { ...state.user, coin: balance }
}
