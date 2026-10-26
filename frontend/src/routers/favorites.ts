import { createRouter, createWebHistory } from 'vue-router'
import PlaceholderPage from '../pages/PlaceholderPage.vue'
import FavoritesPage from '../pages/FavoritesPage.vue'

/**
 * 收藏页应用的 router：`/favorites`（应用内状态全部在 query：`?folder=&tab=later&page=`）。
 *
 * <p>为什么 state 放 query 而不是内存：刷新 / 分享 / 浏览器后退都要停在同一个夹同一页，
 * 与空间页、搜索页的口径一致。
 */
export const favoritesRouter = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/favorites', name: 'favorites', component: FavoritesPage },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: PlaceholderPage },
  ],
  scrollBehavior: () => ({ top: 0 }),
})
