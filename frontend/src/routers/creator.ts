import { createRouter, createWebHistory } from 'vue-router'
import CreatorUploadPage from '../pages/CreatorUploadPage.vue'
import PlaceholderPage from '../pages/PlaceholderPage.vue'

/**
 * 创作中心应用（`creator.html`）的 router。
 *
 * <p>本窗口只有 `/creator`（投稿）。内容管理 / 编辑在下一窗口加到同一入口
 * （URL 形状届时是 `/creator/manage`，Spring 转发与 Vite 映射表同步补）。
 *
 * <p>不渲染无域栏目：短剧 / 专栏 / UP 动画 / 互动视频 / 数据中心 / 粉丝管理…都没有后端域，
 * 既没有 tab 也没有左栏项。
 */
export const creatorRouter = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/creator', name: 'creator-upload', component: CreatorUploadPage },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: PlaceholderPage },
  ],
  scrollBehavior: () => ({ top: 0 }),
})
