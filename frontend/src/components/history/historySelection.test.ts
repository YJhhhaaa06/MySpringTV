import { describe, expect, it } from 'vitest'
import {
  isAllSelected,
  shouldStepBackPage,
  toggleSelectAll,
  toggleSelection,
} from './historySelection'

/**
 * 批量管理的选择规则（`R-26`）。
 *
 * <p>为什么值得单测：这三条都是"点错了界面不会报错"的规则 ——
 * 全选判据写错时，要么全选框永远不亮，要么**声称选了 259 条却只发出 20 个 id**（界面撒谎）；
 * 翻页回退写错时，删掉末页后用户停在空白页上，看起来像"历史全没了"。
 *
 * <h2>反向验证（注入 ⇒ 必红）</h2>
 * ① `isAllSelected` 改成 `ids.length === selected.size` ⇒ "只选了别的页的 id" 那条红
 *    （数量相等但内容不同）；② `shouldStepBackPage` 去掉 `page > 1` ⇒ 第 1 页删空那条红。
 */
describe('历史页批量选择', () => {
  it('勾选 / 取消勾选只影响目标那一条', () => {
    const first = toggleSelection(new Set<number>(), 7)
    expect([...first]).toEqual([7])

    const second = toggleSelection(first, 9)
    expect([...second]).toEqual([7, 9])

    const third = toggleSelection(second, 7)
    expect([...third]).toEqual([9])

    // 入参不被就地修改（调用方拿的是同一个 Set 时会踩"改了但没触发渲染"）
    expect([...second]).toEqual([7, 9])
  })

  it('★全选看的是「已加载的条目」，不是 total：数量相等但内容不同不算全选', () => {
    const loaded = [1, 2, 3]
    expect(isAllSelected(loaded, new Set([1, 2, 3]))).toBe(true)
    expect(isAllSelected(loaded, new Set([1, 2]))).toBe(false)
    // 数量相等、内容不同（比如上一页残留的 id）⇒ 不是全选
    expect(isAllSelected(loaded, new Set([1, 2, 4]))).toBe(false)
    // 空列表恒 false：全选框应禁用，而不是显示"已全选"
    expect(isAllSelected([], new Set())).toBe(false)
  })

  it('全选 / 取消全选二态切换', () => {
    expect([...toggleSelectAll([1, 2], new Set())]).toEqual([1, 2])
    expect([...toggleSelectAll([1, 2], new Set([1, 2]))]).toEqual([])
    // 只选了一部分 ⇒ 点"全选"是**补齐**，不是清空
    expect([...toggleSelectAll([1, 2, 3], new Set([2]))]).toEqual([1, 2, 3])
    // 空列表点什么都不该发生
    expect([...toggleSelectAll([], new Set())]).toEqual([])
  })

  it('★删空末页要回退一页；第 1 页删空就停在空状态', () => {
    expect(shouldStepBackPage(0, 3)).toBe(true)
    expect(shouldStepBackPage(0, 1)).toBe(false)
    expect(shouldStepBackPage(5, 3)).toBe(false)
  })
})
