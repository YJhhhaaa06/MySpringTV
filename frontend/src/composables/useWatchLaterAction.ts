import { computed, ref, unref, type ComputedRef, type MaybeRef, type Ref } from 'vue'
import { useRoute } from 'vue-router'
import { addWatchLater, removeWatchLater } from '../api/favorites'
import { ApiError, describeError } from '../api/http'
import { requireLogin } from '../auth/loginPrompt'
import { signOut } from '../auth/session'
import { showToast } from '../common/toast'
import {
  isWatchLaterMarked,
  markWatchLaterAdded,
  markWatchLaterRemoved,
} from '../common/watchLaterSession'

/**
 * 「稍后再看」的动作机（卡片 hover 与播放页「更多」菜单共用）。
 *
 * <h2>状态只有一个来源：本次页面内是否点过</h2>
 * 后端没有状态查询端点（`favorite.md` §六 红线），所以：
 * - 初始 `added === false`（**不知道 ≠ 不在池里**，但界面只能从"加入"开始）；
 * - 加入成功 → 记到 `watchLaterSession`（作用域 = `route.fullPath`）→ `added === true`；
 * - 取消成功 → 清掉 → `added === false`；
 * - 刷新 / 换路由 ⇒ 作用域变了 ⇒ 自动回到"加入"。B 站 2026-10-26 实测同此。
 *
 * <h2>为什么不做乐观更新</h2>
 * 两个写端点都只回 204，没有权威状态可回读；乐观改了失败还得回滚，
 * 而"点了之后按钮立刻变"的收益并不值得多一条回滚分支。失败时保持原状态 + toast 说清原因。
 *
 * <p>401 特判：`requireLogin()` 只认本地 session；token 在页面打开期间过期时它会以为已登录。
 * 此时写接口 401 ⇒ 清本地 session，下一次点击才会重新弹登录框（否则会一直 401 卡住）。
 */
export interface WatchLaterAction {
  /** 当前 URL 下是否"刚加入过"（初始恒 false —— 没有状态查询端点） */
  added: ComputedRef<boolean>
  busy: Ref<boolean>
  toggle: () => Promise<void>
}

export function useWatchLaterAction(contentId: MaybeRef<number>): WatchLaterAction {
  const route = useRoute()
  const id = computed(() => unref(contentId))
  const scope = computed(() => route.fullPath)
  const added = computed(() => isWatchLaterMarked(id.value, scope.value))
  const busy = ref(false)

  async function toggle(): Promise<void> {
    if (id.value <= 0 || busy.value) return
    if (!(await requireLogin())) return
    const target = !added.value
    busy.value = true
    try {
      if (target) {
        await addWatchLater(id.value)
        markWatchLaterAdded(id.value, scope.value)
        showToast('已加入稍后再看')
      } else {
        await removeWatchLater([id.value])
        markWatchLaterRemoved(id.value)
        showToast('已取消稍后再看')
      }
    } catch (error) {
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

  return { added, busy, toggle }
}
