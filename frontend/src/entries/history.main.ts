import { createApp } from 'vue'
import '../styles/index.css'
import HistoryApp from '../apps/HistoryApp.vue'
import { restoreSession } from '../auth/session'
import { historyRouter } from '../routers/history'

createApp(HistoryApp).use(historyRouter).mount('#app')

void restoreSession()
