import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig, type Connect, type Plugin } from 'vite'

/**
 * 前端重制：**MPA 外壳 + 应用内 SPA**（口径见 `.docs/architecture/frontend/前端.md` §一）。
 *
 * <p>跨应用 = 整页跳转（每个入口一个 HTML + 一个 vue-router 实例）；
 * 应用内 = `createWebHistory`，切 tab 只改 URL、不整页重载。
 *
 * <p>★ 多入口（`appType: 'mpa'`）不会把 `/search` 映射到 `search.html` ——
 * dev / preview 由下面的中间件补；**生产由 Spring 的静态转发承担**（尚未落地，见交接文档）。
 */
const backend = process.env.BACKEND_ORIGIN ?? 'http://127.0.0.1:8080'

/** 路径 → 入口 HTML。只列「URL 形状与文件名不同」的；`/` 由 Vite 自动落 index.html。 */
const pageEntries: ReadonlyArray<readonly [RegExp, string]> = [
  [/^\/search\/?$/, '/search.html'],
  [/^\/video\/\d+\/?$/, '/video.html'],
  [/^\/opus\/\d+\/?$/, '/opus.html'],
  [/^\/space\/\d+\/?$/, '/space.html'],
  [/^\/favorites\/?$/, '/favorites.html'],
  [/^\/watchlater\/?$/, '/favorites.html'],
  [/^\/history\/?$/, '/history.html'],
  [/^\/dynamic\/?$/, '/dynamic.html'],
  // 创作中心（2026-10-28）：**刻意不用 `/upload`** —— `/upload/**` 是后端媒体静态路径
  // （dev 侧 `/upload` 代理到后端、生产侧由 ResourceHandler 服务），页面路由撞上去会互相吃掉。
  // 2026-10-29 起含应用内路由（`/creator/manage`、`/creator/edit/{id}`），与 WebMvcConfig 同步。
  [/^\/creator(\/.*)?$/, '/creator.html'],
  [/^\/placeholder(\/.*)?$/, '/index.html'],
]

function htmlHistoryFallback(): Plugin {
  const middleware: Connect.NextHandleFunction = (req, _res, next) => {
    const url = req.url ?? '/'
    const accept = req.headers.accept ?? ''
    // 只接管「浏览器地址栏导航」：静态资源 / 模块图 / API 与媒体一律放行。
    if (!accept.includes('text/html')) return next()
    const [pathname = '/', search = ''] = url.split('?')
    if (pathname.startsWith('/api') || pathname.startsWith('/upload')) return next()
    if (/\.[A-Za-z0-9]+$/.test(pathname)) return next()
    for (const [pattern, target] of pageEntries) {
      if (pattern.test(pathname)) {
        req.url = search ? `${target}?${search}` : target
        break
      }
    }
    next()
  }

  return {
    name: 'myspringtv:html-history-fallback',
    configureServer(server) {
      server.middlewares.use(middleware)
    },
    configurePreviewServer(server) {
      server.middlewares.use(middleware)
    },
  }
}

export default defineConfig({
  appType: 'mpa',
  plugins: [vue(), htmlHistoryFallback()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    proxy: {
      // 只代理 /api 会让封面（后端静态路径 /upload/cover/...）全部 404 —— 两段都要转发。
      '/api': { target: backend, changeOrigin: true },
      '/upload': { target: backend, changeOrigin: true },
    },
  },
  preview: {
    proxy: {
      '/api': { target: backend, changeOrigin: true },
      '/upload': { target: backend, changeOrigin: true },
    },
  },
  build: {
    rollupOptions: {
      input: {
        index: fileURLToPath(new URL('./index.html', import.meta.url)),
        search: fileURLToPath(new URL('./search.html', import.meta.url)),
        video: fileURLToPath(new URL('./video.html', import.meta.url)),
        opus: fileURLToPath(new URL('./opus.html', import.meta.url)),
        space: fileURLToPath(new URL('./space.html', import.meta.url)),
        favorites: fileURLToPath(new URL('./favorites.html', import.meta.url)),
        history: fileURLToPath(new URL('./history.html', import.meta.url)),
        dynamic: fileURLToPath(new URL('./dynamic.html', import.meta.url)),
        creator: fileURLToPath(new URL('./creator.html', import.meta.url)),
      },
    },
  },
})
