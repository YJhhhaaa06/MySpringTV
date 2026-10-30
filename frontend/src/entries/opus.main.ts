import { createApp } from 'vue'
import '../styles/index.css'
import OpusApp from '../apps/OpusApp.vue'
import { restoreSession } from '../auth/session'
import { opusRouter } from '../routers/opus'

createApp(OpusApp).use(opusRouter).mount('#app')

void restoreSession()
