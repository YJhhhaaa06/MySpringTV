import { createApp } from 'vue'
import '../styles/index.css'
import HomeApp from '../apps/HomeApp.vue'
import { restoreSession } from '../auth/session'
import { homeRouter } from '../routers/home'

createApp(HomeApp).use(homeRouter).mount('#app')

// 登录态**每个入口重建一次**（§十二：页面之间不共享内存态）——失败不阻塞渲染
void restoreSession()
