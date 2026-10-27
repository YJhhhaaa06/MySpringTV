import { createRouter, createWebHistory } from 'vue-router'
import DynamicPage from '../pages/DynamicPage.vue'
import PlaceholderPage from '../pages/PlaceholderPage.vue'

/**
 * 动态页应用的 router：`/dynamic?page=2`。
 *
 * <p>只有一个真路由（没有应用内 tab —— `GET /feed` 没有 type 筛选，不摆点不动的 tab）。
 * 分页放 query：刷新 / 分享能停在同一页（与搜索 / 历史同口径）。
 */
export const dynamicRouter = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/dynamic', name: 'dynamic', component: DynamicPage },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: PlaceholderPage },
  ],
  scrollBehavior: () => ({ top: 0 }),
})
