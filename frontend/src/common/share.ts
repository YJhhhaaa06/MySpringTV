import { showToast } from './toast'

/**
 * 分享 = **复制当前链接**（B 站也是复制；本仓没有转发域 ⇒ 不显示转发数）。
 *
 * <p>播放页与图文详情页共用这一份：两处行为必须一致，别各自写一份剪贴板逻辑。
 */
export async function shareCurrentPage(): Promise<void> {
  try {
    await navigator.clipboard.writeText(window.location.href)
    showToast('链接已复制，发给朋友吧')
  } catch {
    showToast('复制失败，请手动复制地址栏', 'error')
  }
}
