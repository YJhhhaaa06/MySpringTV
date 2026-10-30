import { createApp } from 'vue'
import '../styles/index.css'
import CreatorApp from '../apps/CreatorApp.vue'
import { restoreSession } from '../auth/session'
import { creatorRouter } from '../routers/creator'

createApp(CreatorApp).use(creatorRouter).mount('#app')

void restoreSession()
