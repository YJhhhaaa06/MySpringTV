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
 * 从 localStorage 恢复登录态。**幂等**：只在本应用首次挂载时真正发请求。
 *
 * <p>★ 并发调用复用**同一个 promise**：入口文件与页面组件都会调它，
 * 不合并的话一个页面装载会打两次 `GET /users/me`（同源、同 token，纯浪费一枚限流令牌）。
 * token 失效（401）时清掉本地 token，退化为匿名 —— 不把用户卡在"看起来登录了但什么都点不了"。
 */
export function restoreSession(): Promise<void> {
  if (state.status !== 'restoring') return Promise.resolve()
  if (!restoring) {
    restoring = doRestore().finally(() => {
      restoring = null
    })
  }
  return restoring
}

let restoring: Promise<void> | null = null

async function doRestore(): Promise<void> {
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

/** 当前用户 id（未登录 / 恢复中 ⇒ null）。adapter 的 `canDelete` 等判断用它。 */
export function currentUserId(): number | null {
  return state.user?.id ?? null
}

/**
 * 就地写「记录浏览历史」开关（拨动后调用；未登录 ⇒ no-op）。
 *
 * <h2>★ 为什么"乐观"且不落 localStorage</h2>
 * 唯一事实源是 `users.history_recording`；本函数只改**这个应用实例**的内存，
 * 下一次整页装载由 `restoreSession()` 重新读服务端。
 * 所以"断网时切到关闭"的结局是：界面变了、服务端没收到、**刷新后变回开** ——
 * 这正是 B站 实测口径（`R-25`），也是它**不**提示、**不**回弹的原因：
 * 服务端才是判据，本地只是先走一步。
 *
 * <p>⚠️ 不写 localStorage（前端只许两个 key 的纪律，见 `客户端静默机制.md` §3.10）：
 * 存一份就会造出"本地说关、服务端说开"的第二事实源，而那正是这套设计要避免的。
 */
export function applyHistoryRecording(recording: boolean): void {
  if (!state.user) return
  if (state.user.historyRecording === recording) return
  state.user = { ...state.user, historyRecording: recording }
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

/**
 * 签到成功的**状态迁移**（纯函数：`session.test.ts` 直接断言，不用起 DOM）。
 *
 * <h2>★ 任何一次 200 的签到回执都意味着「今天已签」</h2>
 * `credited` 只区分**是不是这一次入的账**（`true` = 本次 +1，`false` = 今天早先已签过），
 * 两者都证明服务端已经把「今天」记成签到日 ⇒ `signedToday` 一律置 `true`。
 * 把它写成 `signedToday = credited` 会让"点了但别人已签"重新点亮按钮
 * （多标签页 / 手机先签过时必然撞上），用户会看到一个永远点不灰的按钮。
 *
 * @param balance 服务端回带的新余额；缺失 / 非有限值 ⇒ 保留本地值
 *                （宁可显示旧余额，也不把 `undefined` 画成 `0` 枚）
 */
export function markSignedIn(user: CurrentUserVM | null, balance: number | null | undefined): CurrentUserVM | null {
  if (!user) return null
  const coin = typeof balance === 'number' && Number.isFinite(balance) ? balance : user.coin
  return { ...user, coin, signedToday: true }
}

/** 签到接口返回后调用：余额对齐服务端 + 今天置为已签（未登录 ⇒ no-op）。 */
export function applySignInResult(balance: number | null | undefined): void {
  if (!state.user) return
  state.user = markSignedIn(state.user, balance)
}
