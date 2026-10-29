import { computed, ref, type ComputedRef, type Ref } from 'vue'
import { checkInToday } from '../api/interactions'
import { ApiError, describeError } from '../api/http'
import { applySignInResult, isAuthenticated, sessionState, signOut } from '../auth/session'
import { showToast } from '../common/toast'

/**
 * 「每日签到」的动作机（空间页硬币卡用）。
 *
 * <h2>★ 状态不落 localStorage，唯一来源是 `GET /users/me` 的 `signedToday`</h2>
 * 签到是"某账号在**上海自然日**里的一次"——这是**服务端**的事实（`users.last_sign_date`
 * 与 `SiteTime` 口径），不是"这个浏览器"的事实。放在本地会有三个立刻能复现的错：
 * <ol>
 *   <li><b>换号就失效</b>：A 签过 ⇒ B 登录后按钮还是灰的（除非登出清缓存）；</li>
 *   <li><b>跨设备撒谎</b>：手机签过、桌面没签 ⇒ 按钮亮着，点下去只拿到「今日已签到」；</li>
 *   <li><b>时钟不可信</b>：本机时间可改，且"今天"要在浏览器里重新实现一遍时区换算，
 *       而这条日历口径后端已经唯一收敛（`SiteTime.SITE_ZONE`）——前端再算一遍就是第二事实源。</li>
 * </ol>
 * 代价只是"进页那一次 `/users/me` 多带一列"，而那一次请求本来就在跑（`restoreSession()`）。
 *
 * <h2>为什么不需要幂等键</h2>
 * `POST /users/me/check-ins` 不是 `KEYED` 端点：服务端一条原子条件 `UPDATE` 判"今天签过没"，
 * 重复调用返回 200 + `credited=false`，余额不变。所以 `busy` 只防手抖，不承担正确性。
 *
 * <p>`credited` 只决定**提示文案**，不决定状态：两种回执都说明"今天已签"，
 * 见 `auth/session.ts#markSignedIn`。
 */
export interface CheckInState {
  /** 硬币余额（**一位小数**的原值，取整在展示处做） */
  balance: ComputedRef<number>
  /** 今天是否已签到（服务端权威值；未登录恒 false） */
  signedToday: ComputedRef<boolean>
  busy: Ref<boolean>
  submit: () => Promise<void>
}

export function useCheckIn(): CheckInState {
  const balance = computed(() => sessionState.user?.coin ?? 0)
  const signedToday = computed(() => sessionState.user?.signedToday === true)
  const busy = ref(false)

  async function submit(): Promise<void> {
    // 已签 / 在途直接忽略：按钮虽然 disabled，但键盘 + 慢网下双击仍可能连发
    if (busy.value || signedToday.value) return
    if (!isAuthenticated()) return
    busy.value = true
    try {
      const result = await checkInToday()
      // 余额用服务端回带值（本地不做 +1：签到 / 投币 / 别的标签页都在改这个数）
      applySignInResult(result?.balance)
      showToast(result?.credited ? '签到成功，硬币 +1' : '今天已经签到过了')
    } catch (error) {
      // 401 特判：token 在页面打开期间过期时，本地 session 仍以为已登录 ⇒ 清掉，下次点击重新弹登录框
      if (error instanceof ApiError && error.status === 401) {
        signOut()
        showToast('登录已过期，请重新登录', 'error')
        return
      }
      showToast(describeError(error), 'error')
    } finally {
      busy.value = false
    }
  }

  return { balance, signedToday, busy, submit }
}
