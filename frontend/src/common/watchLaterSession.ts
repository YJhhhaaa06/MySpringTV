import { reactive } from 'vue'

/**
 * 稍后再看：**本次页面内**的"我刚加过/刚取消过"记忆。
 *
 * <h2>为什么不是"是否已在稍后再看"</h2>
 * 后端**刻意没有状态查询端点**（`favorite.md` §六 红线：不新增 status 端点、不塞进
 * `interaction`）——因为"在不在池里"不是互动维度，且清单本身已经是唯一读面。
 * 所以前端**不查、也不假装查**：卡片/播放页初始一律显示「稍后再看」，
 * 只有用户在**当前页面**里点过加入之后，才知道它在池里、才允许再点一次取消。
 *
 * <p>这个行为不是我们发明的：2026-10-26 用户实测 B 站网页版 —— **同一个页面**认识刚加入的视频、
 * 不刷新就能取消；**换了页面就不认识**，自然也无法在卡片上取消。
 *
 * <h2>★ URL 就是作用域（不写 localStorage / sessionStorage）</h2>
 * 记录的是"加入时的 `pathname + search`"。URL 一变，`isWatchLaterMarked` 自然返回 false；
 * 刷新（新文档）Map 也清空。这样既复刻了 B 站的"换页即忘"，又不用监听路由。
 * 存储口径见 `architecture/frontend/前端.md` §六：本地存储只有 `myspringtv.token` 与
 * `history:pending:{id}` 两把钥匙 —— 这里**不新增第三把**。
 *
 * <p>为什么用 `reactive(Map)`：卡片里的 `computed` 读 {@link isWatchLaterMarked}，
 * `set` / `delete` 要能触发重渲染（普通 Map 不会）。
 */
const marked = reactive(new Map<number, string>())

/** 当前文档的作用域；node（vitest）下没有 `window` ⇒ 空串（测试都显式传 scope）。 */
export function currentWatchLaterScope(): string {
  if (typeof window === 'undefined') return ''
  return `${window.location.pathname}${window.location.search}`
}

/** 该内容是否在**当前 URL** 下被本次会话标记为"已加入"。 */
export function isWatchLaterMarked(contentId: number, scope: string = currentWatchLaterScope()): boolean {
  return contentId > 0 && marked.get(contentId) === scope
}

/** 加入成功后调用：把该内容绑到当前 URL。 */
export function markWatchLaterAdded(contentId: number, scope: string = currentWatchLaterScope()): void {
  if (contentId > 0) marked.set(contentId, scope)
}

/** 取消成功后调用（同一内容在别的 URL 下的标记也一并清掉，避免陈旧项）。 */
export function markWatchLaterRemoved(contentId: number): void {
  marked.delete(contentId)
}

/** 单测 / 应用卸载用；不在组件里直接调。 */
export function resetWatchLaterSession(): void {
  marked.clear()
}
