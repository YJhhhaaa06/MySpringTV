import { createApp } from 'vue'
import '../styles/index.css'
import SearchApp from '../apps/SearchApp.vue'
import { restoreSession } from '../auth/session'
import { searchRouter } from '../routers/search'

createApp(SearchApp).use(searchRouter).mount('#app')

void restoreSession()
