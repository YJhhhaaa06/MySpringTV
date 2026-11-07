import { createRouter, createWebHistory } from 'vue-router'
import PlaceholderPage from '../pages/PlaceholderPage.vue'
import LegacyFavoriteRedirect from '../pages/LegacyFavoriteRedirect.vue'

/**
 * 旧收藏页应用的 router（**软弃用**，`R-34`）。
 *
 * <p>收藏夹内容的唯一入口已收敛到 `/space/{uid}?tab=folders`；这里只保留
 * `/favorites` 与 `/watchlater` 两个薄重定向壳，承接旧书签 / 旧链接的 query 平移。
 * 新页稳定后再另窗删除这个 HTML 入口（连同 Vite / WebMvcConfig / 限流清单）。
 */
export const favoritesRouter = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/favorites', name: 'favorites', component: LegacyFavoriteRedirect },
    { path: '/watchlater', name: 'watchlater', component: LegacyFavoriteRedirect },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: PlaceholderPage },
  ],
  scrollBehavior: () => ({ top: 0 }),
})
