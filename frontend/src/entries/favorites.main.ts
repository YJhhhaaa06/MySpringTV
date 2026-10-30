import { createApp } from 'vue'
import '../styles/index.css'
import FavoritesApp from '../apps/FavoritesApp.vue'
import { restoreSession } from '../auth/session'
import { favoritesRouter } from '../routers/favorites'

createApp(FavoritesApp).use(favoritesRouter).mount('#app')

void restoreSession()
