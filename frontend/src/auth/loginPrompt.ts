import { reactive, readonly } from 'vue'
import { isAuthenticated } from './session'

/**
 * 登录闸门（**动作级**，不是页面级）。
 *
 * <p>口径：未登录**不隐藏页面**、也不跳登录页 —— B 站是"点下去才弹登录框"。
 * 所以每个需要身份的动作先 `await requireLogin()`：已登录立即 `true`，
 * 否则弹出登录框并**阻塞到用户给出结果**（登录成功 → `true`，关掉 → `false`）。
 *
 * <p>为什么用 promise 而不是"弹窗 + 回调"：动作代码是线性的
 * （`if (!(await requireLogin())) return`），回调会把一条流程劈成两段，
 * 中间态（点了没反应 / 登录后又忘了继续）正是这种劈法的典型 bug。
 *
 * <p>★ 并发的两次点击复用同一个 promise：否则叠两个浮层，第二次的 settle 会悬空。
 */

interface LoginPromptState {
  visible: boolean
}

const state = reactive<LoginPromptState>({ visible: false })

export const loginPromptState = readonly(state) as Readonly<LoginPromptState>

let resolver: ((ok: boolean) => void) | null = null
let pending: Promise<boolean> | null = null

/** 需要登录才能继续的动作先调它。 */
export function requireLogin(): Promise<boolean> {
  if (isAuthenticated()) return Promise.resolve(true)
  if (!pending) {
    pending = new Promise<boolean>((resolve) => {
      resolver = resolve
    })
    state.visible = true
  }
  return pending
}

/** 由 `LoginGate` 在登录框关闭 / 登录成功时调用（**唯一出口**）。 */
export function settleLogin(ok: boolean): void {
  const notify = resolver
  resolver = null
  pending = null
  state.visible = false
  notify?.(ok)
}
