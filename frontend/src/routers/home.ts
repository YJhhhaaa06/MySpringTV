import { createRouter, createWebHistory } from 'vue-router'
import HomePage from '../pages/HomePage.vue'
import PlaceholderPage from '../pages/PlaceholderPage.vue'

/**
 * 首页应用的 router（**每个入口一个实例**）。
 *
 * <p>应用内路由：`/` 与 `/placeholder/:feature`。
 * 搜索 / 播放页是**另一个入口**（整页跳转），所以这里没有它们的路由。
 */
export const homeRouter = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: HomePage, meta: { overlay: true } },
    { path: '/placeholder/:feature', name: 'placeholder', component: PlaceholderPage },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: PlaceholderPage },
  ],
  scrollBehavior: () => ({ top: 0 }),
})
