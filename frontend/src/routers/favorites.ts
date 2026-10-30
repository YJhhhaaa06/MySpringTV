import { createRouter, createWebHistory } from 'vue-router'
import PlaceholderPage from '../pages/PlaceholderPage.vue'
import FavoritesPage from '../pages/FavoritesPage.vue'

/**
 * 收藏页应用的 router：`/favorites`（应用内状态全部在 query：`?folder=` / `?page=`）
 * + `/watchlater`（独立路由，复用同一个 `FavoritesPage`，`mode=later`）。
 *
 * <p>为什么 state 放 query 而不是内存：刷新 / 分享 / 浏览器后退都要停在同一个夹同一页，
 * 与空间页、搜索页的口径一致。
 *
 * <p>★ 稍后再看为什么是独立路由而不是 `?tab=later`：B 站网页版有独立页面（`/watchlater/`），
 * 用户在卡片 / 播放页点完「稍后再看」后需要一个可收藏、可刷新的地址；旧链接
 * `/favorites?tab=later` 仍兼容（页面按 query 仍判 later）。
 */
export const favoritesRouter = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/favorites', name: 'favorites', component: FavoritesPage },
    { path: '/watchlater', name: 'watchlater', component: FavoritesPage },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: PlaceholderPage },
  ],
  scrollBehavior: () => ({ top: 0 }),
})
