import { createApp } from 'vue'
import '../styles/index.css'
import DynamicApp from '../apps/DynamicApp.vue'
import { restoreSession } from '../auth/session'
import { dynamicRouter } from '../routers/dynamic'

createApp(DynamicApp).use(dynamicRouter).mount('#app')

void restoreSession()
