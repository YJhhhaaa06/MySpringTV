import { createApp } from 'vue'
import '../styles/index.css'
import VideoApp from '../apps/VideoApp.vue'
import { restoreSession } from '../auth/session'
import { videoRouter } from '../routers/video'

createApp(VideoApp).use(videoRouter).mount('#app')

void restoreSession()
