import { createRouter, createWebHistory } from 'vue-router'
import PlaceholderPage from '../pages/PlaceholderPage.vue'
import SpacePage from '../pages/SpacePage.vue'

/**
 * 空间页应用的 router：`/space/:id`（应用内 tab 用 query `?tab=folders`）。
 *
 * <p>tab 放 query 而不是内存态：刷新 / 分享链接能停在同一个 tab（与 B 站一致）。
 */
export const spaceRouter = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/space/:id(\\d+)', name: 'space', component: SpacePage },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: PlaceholderPage },
  ],
  scrollBehavior: () => ({ top: 0 }),
})
