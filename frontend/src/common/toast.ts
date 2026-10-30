import { reactive, readonly } from 'vue'

/**
 * 轻量提示条（B 站那种"已点赞"、"硬币不足"小提示）。
 *
 * <p>为什么不用 `alert`：阻塞式对话框会打断播放；为什么不引 UI 库：
 * 只有一个形态（一行文字 + 2.6s 自动消失），一个模块级数组就够。
 *
 * <p>★ 只承载**动作结果**，不承载业务状态：失败信息里必须有"为什么"
 * （来自 `ApiError.message`），不能只写"操作失败"。
 */
export type ToastKind = 'info' | 'success' | 'error'

export interface ToastItem {
  id: number
  text: string
  kind: ToastKind
}

const items = reactive<ToastItem[]>([])

/** 只读视图：组件只渲染，弹提示必须走 {@link showToast} */
export const toasts = readonly(items) as Readonly<ToastItem[]>

const DURATION_MS = 2600
let sequence = 0

export function showToast(text: string, kind: ToastKind = 'success', durationMs = DURATION_MS): void {
  const id = ++sequence
  items.push({ id, text, kind })
  setTimeout(() => {
    const index = items.findIndex((item) => item.id === id)
    if (index >= 0) items.splice(index, 1)
  }, durationMs)
}

/** 单测 / 页面卸载用（不在组件里直接调，避免把"清空"写成隐式约定） */
export function clearToasts(): void {
  items.splice(0, items.length)
}
