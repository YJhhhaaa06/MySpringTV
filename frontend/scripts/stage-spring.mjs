/**
 * 把 `frontend/dist` 同步到 Spring 的静态资源根（`src/main/resources/static`）。
 *
 * <h2>为什么需要这一步</h2>
 * 生产形态是「Spring 直接服务打包产物」：`/`、`/search`、`/video/:id`、`/assets/**` 全部由
 * 8080 回答（history 转发见 `WebMvcConfig#addViewControllers`）。而 Vite 的产物在 `frontend/dist`，
 * Spring 默认只服务 classpath 下的 `static/` ⇒ 中间必须有一次**显式的搬运**。
 *
 * <h2>为什么不放进 `npm run build`</h2>
 * `build` 是门禁（`vue-tsc -b && vite build`）：它只该产出 `dist/`，不该写仓库里的另一个目录。
 * 搬运是**部署动作**，单独一条命令（`npm run stage`），出问题时能立刻指出是哪一步。
 *
 * <h2>为什么产物不入库</h2>
 * `src/main/resources/static/` 在 `.gitignore` 里（构建产物、每次 `vite build` 文件名带 hash）。
 * 代价：**新克隆的仓库在 `npm run stage` 之前，8080 的页面入口是 404**（不是白屏、不是旧页面）——
 * 由 `docs` 与 README 里的启动顺序承接。这比"提交一份会漂移的 dist"更诚实。
 *
 * <h2>★ 先校验后清理</h2>
 * 没有 `dist/index.html` 时直接失败退出，**绝不**先 `rm` 目标目录：
 * "构建没跑成功"与"把线上前端删掉"必须是两种结果，不能是同一种。
 */
import { access, cp, mkdir, rm } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const scriptDir = path.dirname(fileURLToPath(import.meta.url))
const frontendRoot = path.resolve(scriptDir, '..')
const repoRoot = path.resolve(frontendRoot, '..')

const source = path.join(frontendRoot, 'dist')
const target = path.join(repoRoot, 'src', 'main', 'resources', 'static')

const entry = path.join(source, 'index.html')
try {
  await access(entry)
} catch {
  console.error(`[stage] 找不到 ${path.relative(repoRoot, entry)}`)
  console.error('[stage] 先跑 `npm run build`，再跑 `npm run stage`（顺序不能反）')
  process.exit(1)
}

// 目标路径是写死的常量（不是命令行参数），清理范围 = 这个目录本身。
await rm(target, { recursive: true, force: true })
await mkdir(target, { recursive: true })
await cp(source, target, { recursive: true })

console.log(`[stage] ${path.relative(repoRoot, source)} → ${path.relative(repoRoot, target)}`)
console.log('[stage] 启动后端后访问 http://localhost:8080/（Spring 直接服务打包产物）')
