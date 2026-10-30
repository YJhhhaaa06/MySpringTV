import { createRouter, createWebHistory } from 'vue-router'
import PlaceholderPage from '../pages/PlaceholderPage.vue'
import HistoryPage from '../pages/HistoryPage.vue'

/** 历史页应用的 router：`/history?page=N`（分页放 query，刷新停在同一页）。 */
export const historyRouter = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/history', name: 'history', component: HistoryPage },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: PlaceholderPage },
  ],
  scrollBehavior: () => ({ top: 0 }),
})
