import { createRouter, createWebHistory } from 'vue-router'
import OpusPage from '../pages/OpusPage.vue'
import PlaceholderPage from '../pages/PlaceholderPage.vue'

/**
 * 图文详情应用的 router：`/opus/:id`（B 站图文类的标准链接形状；id 本仓仍是自增内容 id）。
 *
 * <p>应用内没有 tab / 分页：正文、图片两态、评论都在同一页（滚动 + 锚点）。
 */
export const opusRouter = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/opus/:id(\\d+)', name: 'opus', component: OpusPage },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: PlaceholderPage },
  ],
  scrollBehavior: () => ({ top: 0 }),
})
