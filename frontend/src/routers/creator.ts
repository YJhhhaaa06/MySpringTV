import { createRouter, createWebHistory } from 'vue-router'
import CreatorEditPage from '../pages/CreatorEditPage.vue'
import CreatorUploadPage from '../pages/CreatorUploadPage.vue'
import CreatorWorksPage from '../pages/CreatorWorksPage.vue'
import PlaceholderPage from '../pages/PlaceholderPage.vue'

/**
 * 创作中心应用（`creator.html`）的 router。
 *
 * <p>本窗口三个页面：`/creator`（投稿）、`/creator/manage`（稿件管理）、
 * `/creator/edit/:id`（编辑）。深链刷新由 `WebMvcConfig` 的三条 ViewController 承接，
 * dev 侧由 `vite.config.ts` 的 `pageEntries` 承接 —— 两处同一张映射表，必须同步改。
 *
 * <p>不渲染无域栏目：短剧 / 专栏 / UP 动画 / 互动视频 / 数据中心 / 粉丝管理…都没有后端域，
 * 既没有 tab 也没有左栏项。
 */
export const creatorRouter = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/creator', name: 'creator-upload', component: CreatorUploadPage },
    { path: '/creator/manage', name: 'creator-works', component: CreatorWorksPage },
    { path: '/creator/edit/:id(\\d+)', name: 'creator-edit', component: CreatorEditPage },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: PlaceholderPage },
  ],
  scrollBehavior: () => ({ top: 0 }),
})
