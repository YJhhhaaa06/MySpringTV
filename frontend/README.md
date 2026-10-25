# MySpringTV 前端（仿哔哩哔哩网页版）

> 口径与 GAP 台账在 `.docs/task/CURRENT_FRONTEND.md`（**唯一入口**）；
> 应用层设计（入口 / 路由 / 类型三层 / 端点矩阵 / 降级约定 / 请求层约定）在
> `.docs/architecture/frontend/前端.md`。本文件只写"怎么跑"。

```bash
npm install
npm run gen:api     # openapi.yaml → src/types/api.d.ts（生成物入库，diff = 契约变更记录）
npm run dev         # http://localhost:5173（/api、/upload 已代理到 127.0.0.1:8080）
npm run typecheck   # vue-tsc -b —— 类型硬门禁
npm run test        # vitest —— 适配器 fallback + 请求层 + 播放进度规则
npm run build       # dist/{index,search,video,space}.html
npm run preview     # 验打包产物（同一套 history fallback + 代理）
```

> ⚠️ 用 `localhost:5173`，**不要用 `127.0.0.1:5173`**（Vite 8 默认只监听 `::1`）。

## 结构

```
index.html / search.html / video.html / space.html   ← 四个入口（MPA 外壳，跨应用整页跳转）
src/entries/*.main.ts         ← 每个入口一个 app + 一个 vue-router
src/types/api.d.ts            ← 生成，只读
src/types/ui.ts               ← 手写：页面期望形状（计数一律原始数字）
src/api/adapters/*            ← 纯函数 api → ui（缺口在这里显式 fallback）
src/api/*.ts                  ← 端点封装（返回生成类型）
src/api/http.ts               ← 四动词 + 信封 + Bearer + Retry-After
src/auth/                     ← token（唯一落点）/ 模块级登录态 / 动作级登录闸门
src/composables/              ← useVideoInteractions（互动态）、usePlaybackProgress（进度接线）
src/player/progressReporter.ts ← 播放进度纯逻辑（节奏 / 退避 / 续播 / 崩溃补偿，可单测）
src/components/...            ← 组件只吃 ui.ts
```

## 后端

先起后端（`./mvnw.cmd spring-boot:run`，8080；需要 3306 / 6379 / 5672 都在听）。
封面等媒体走 `/upload/**`，所以 dev 代理必须同时转发 `/api` **和** `/upload`
—— 只代理 `/api` 会让所有图片 404。
