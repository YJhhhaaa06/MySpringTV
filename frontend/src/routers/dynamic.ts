import { createRouter, createWebHistory } from 'vue-router'
import DynamicPage from '../pages/DynamicPage.vue'
import PlaceholderPage from '../pages/PlaceholderPage.vue'

/**
 * 动态页应用的 router：`/dynamic`（作者模式 `/dynamic?author=7`）。
 *
 * <p>只有一个真路由（没有应用内 tab —— `GET /feed` 没有 type 筛选，不摆点不动的 tab）。
 * `R-32` 起列表是无限滚动，**页码不再进 URL**；只有作者筛选留在 query（刷新 / 分享不丢）。
 * 旧 `?page=` 链接仍能打开（忽略页码，落到第 1 页）。
 */
export const dynamicRouter = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/dynamic', name: 'dynamic', component: DynamicPage },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: PlaceholderPage },
  ],
  scrollBehavior: () => ({ top: 0 }),
})
