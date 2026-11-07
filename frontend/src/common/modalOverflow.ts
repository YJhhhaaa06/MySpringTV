/**
 * 弹层期间的 `body` 滚动锁，按层计数。
 *
 * <p>为什么不是每个弹层自己"记进入前的值、卸载时还原"：弹层可以嵌套，
 * 且 Vue 卸载时**父组件先于子组件**执行 `onBeforeUnmount`。父先把 overflow 还原成 `''`、
 * 子再还原它进入时快照里的 `'hidden'` ⇒ 页面永久锁滚。按层计数后，
 * 只有最外层释放时才真正还原进入前的值，父子卸载顺序不再影响结果。
 */
let lockCount = 0
let savedOverflow = ''

export function lockBodyOverflow(): void {
  if (typeof document === 'undefined') return
  if (lockCount === 0) savedOverflow = document.body.style.overflow
  lockCount += 1
  document.body.style.overflow = 'hidden'
}

export function unlockBodyOverflow(): void {
  if (typeof document === 'undefined') return
  if (lockCount === 0) return
  lockCount -= 1
  if (lockCount === 0) document.body.style.overflow = savedOverflow
}
