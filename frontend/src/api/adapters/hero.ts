import type { components } from '../../types/api'
import type { HeroSlideVM } from '../../types/ui'
import { CONTENT_TYPE_POST } from '../contentTypes'
import { contentHref } from './links'

export interface HeroSplit {
  /** 大轮播位用的（有封面的）前 N 条 */
  slides: HeroSlideVM[]
  /** 剩下的进网格 —— **同一内容不会既在轮播又在网格**（B 站的轮播是运营位，不占内容池） */
  rest: components['schemas']['ContentVO'][]
}

/**
 * 拆出首页大轮播 + 网格。
 *
 * <p>★ 为什么是"推荐流前 3 条"而不是运营 banner：本仓没有广告/运营位端点（GAP 台账）。
 * 用真封面 + 真标题做"推荐橱窗"，是**可解释的降级**；编一张活动图才是假数据。
 */
export function splitHero(
  items: ReadonlyArray<components['schemas']['ContentVO']>,
  limit = 3,
): HeroSplit {
  const slides: HeroSlideVM[] = []
  const rest: components['schemas']['ContentVO'][] = []
  for (const item of items) {
    const coverUrl = item.coverUrl?.trim()
    if (coverUrl && item.id !== undefined && slides.length < limit) {
      slides.push({
        id: item.id,
        title: item.title?.trim() || '无标题',
        // 轮播位也可能收到有封面的图文（本仓推荐流含图文）⇒ 与网格同一套落点规则
        href: contentHref(item.id, item.type === CONTENT_TYPE_POST),
        coverUrl,
        authorName: item.authorName?.trim() || '未知UP主',
      })
    } else {
      rest.push(item)
    }
  }
  return { slides, rest }
}
