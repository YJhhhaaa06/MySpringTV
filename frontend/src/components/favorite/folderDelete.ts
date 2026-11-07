import type { LibraryFolderVM } from '../../types/ui'

/**
 * 收藏夹删除的纯口径（`R-36`）。
 *
 * <p>组件只负责"把列表喂给这些函数、把结果画出来"；默认夹能不能删、
 * 删完该落到哪个夹，都在这里用单测钉住 —— 菜单与 `SpacePage` 不各写一套判断。
 */
export function canDeleteFolder(folder: Pick<LibraryFolderVM, 'isDefault'>): boolean {
  // self 面 `isDefault` 一定是布尔；公开面是 `null`（不知道，也不该删）
  // ⇒ 只有**明确知道不是默认夹**才给入口，缺省/未知一律不给。
  return folder.isDefault === false
}

/**
 * 当前选中的夹被删后，右栏应该落到哪个夹。
 *
 * <p>与 B 站同序：先默认夹（存在就一定选它），否则列表里的第一个；
 * 一个都不剩返回 `null`（右栏回到"选择一个收藏夹"）。
 * 不修改入参，调用方先移除再传或直接传都得到同一结果。
 */
export function nextFolderAfterDelete(
  folders: LibraryFolderVM[],
  deletedId: number,
): LibraryFolderVM | null {
  const remaining = folders.filter((folder) => folder.id !== deletedId)
  return remaining.find((folder) => folder.isDefault === true) ?? remaining[0] ?? null
}

/**
 * 删除回包时，被删的夹是否**仍是用户当前正在看的那一面**。
 *
 * <p>必须用回包之后的当前路由状态重算，不能用点确认那一刻的快照：
 * 请求期间用户可能已切到别的夹 / 稍后再看 / 主页，此时只能从列表移除，
 * 不能把用户拉回被删夹的替代夹。`tab` 用 string 是为了让调用方直接传路由值，
 * 纯函数不依赖 `SpaceTab` 类型。
 */
export function isDeletedFolderStillActive(
  tab: string,
  view: 'folder' | 'later',
  activeFolderId: number,
  deletedId: number,
): boolean {
  return tab === 'folders' && view === 'folder' && activeFolderId === deletedId
}
