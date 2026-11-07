import type { FolderEditPatch, LibraryFolderVM } from '../../types/ui'

/**
 * 收藏夹信息编辑的纯口径（`R-35`）。
 *
 * <p>组件只负责"把输入喂给这些函数、把结果画出来"；名称怎么算有效、
 * 哪些字段算"真的改了"，都在这里用单测钉住 —— 弹窗里不写第二套判断。
 */
export const FOLDER_NAME_MAX = 20

/** 后端存的是 trim 后的名字（新建弹窗也按同一口径提交）。 */
export function normalizeFolderName(raw: string): string {
  return raw.trim()
}

/** 空名字 / 超长返回给用户看的错误；合法返回空串。 */
export function folderNameError(raw: string): string {
  const name = normalizeFolderName(raw)
  if (!name) return '收藏夹名称不能为空'
  if (name.length > FOLDER_NAME_MAX) {
    return `收藏夹名称不能超过 ${FOLDER_NAME_MAX} 个字符`
  }
  return ''
}

/**
 * 只有名字**真的改了**才校验。
 *
 * <p>后端允许 100 字符；前端产品上限是 20。既有夹名可能是 21–100 字（经 API 建的），
 * 用户只想改公开开关时不能因为旧名超长把保存卡死 —— 这时提交的 patch 不含 `name`，
 * 后端也不会改它。
 */
export function folderNameChangeError(currentName: string, rawName: string): string {
  if (normalizeFolderName(rawName) === currentName) return ''
  return folderNameError(rawName)
}

/**
 * 只提交变化字段：两个字段都没改时返回空对象（调用方**不要**发请求）。
 *
 * <p>后端是部分更新：`name` 缺省 = 不改名、`isPrivate` 缺省 = 不动可见性；
 * 两个都缺会 400。所以"没改"必须在这里被识别出来，不能靠后端兜。
 */
export function folderEditPatch(
  folder: Pick<LibraryFolderVM, 'name' | 'isPrivate'>,
  rawName: string,
  isPrivate: boolean,
): FolderEditPatch {
  const patch: FolderEditPatch = {}
  const name = normalizeFolderName(rawName)
  if (name !== folder.name) patch.name = name
  if (isPrivate !== folder.isPrivate) patch.isPrivate = isPrivate
  return patch
}

/** 有没有要提交的变化（保存按钮的禁用条件之一）。 */
export function hasFolderEditChanges(patch: FolderEditPatch): boolean {
  return patch.name !== undefined || patch.isPrivate !== undefined
}
