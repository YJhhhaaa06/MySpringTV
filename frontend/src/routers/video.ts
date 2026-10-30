import { createRouter, createWebHistory } from 'vue-router'
import PlaceholderPage from '../pages/PlaceholderPage.vue'
import VideoPage from '../pages/VideoPage.vue'

/** 播放页应用的 router：`/video/:id`（id 是本仓数据库自增，仿不了 BV 号 —— 口径见 §十二 末尾）。 */
export const videoRouter = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/video/:id(\\d+)', name: 'video', component: VideoPage },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: PlaceholderPage },
  ],
  scrollBehavior: () => ({ top: 0 }),
})
