import { createRouter, createWebHistory } from 'vue-router'
import PlaceholderPage from '../pages/PlaceholderPage.vue'
import SearchPage from '../pages/SearchPage.vue'

/** 搜索应用的 router：`/search?keyword=&page=`（**应用内**改 query，不整页重载）。 */
export const searchRouter = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/search', name: 'search', component: SearchPage },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: PlaceholderPage },
  ],
  scrollBehavior: () => ({ top: 0 }),
})
