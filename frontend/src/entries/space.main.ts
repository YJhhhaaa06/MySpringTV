import { createApp } from 'vue'
import '../styles/index.css'
import SpaceApp from '../apps/SpaceApp.vue'
import { restoreSession } from '../auth/session'
import { spaceRouter } from '../routers/space'

createApp(SpaceApp).use(spaceRouter).mount('#app')

void restoreSession()
