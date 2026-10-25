# MySpringTV 前端（仿哔哩哔哩网页版）

> 口径与 GAP 台账在 `.docs/task/CURRENT_FRONTEND.md`（**唯一入口**）；本文件只写"怎么跑"。

```bash
npm install
npm run gen:api     # openapi.yaml → src/types/api.d.ts（生成物入库，diff = 契约变更记录）
npm run dev         # http://localhost:5173（/api、/upload 已代理到 127.0.0.1:8080）
npm run typecheck   # vue-tsc -b —— 类型硬门禁
npm run test        # vitest —— adapters 的 fallback 行为
npm run build       # dist/（多入口 index.html + search.html）
```

## 结构

```
index.html / search.html      ← 两个入口（MPA 外壳，跨应用整页跳转）
src/entries/*.main.ts         ← 每个入口一个 app + 一个 vue-router
src/types/api.d.ts            ← 生成，只读
src/types/ui.ts               ← 手写：页面期望形状
src/api/adapters/*            ← 纯函数 api → ui（缺口在这里显式 fallback）
src/api/*.ts                  ← 端点封装（返回生成类型）
src/components/...            ← 组件只吃 ui.ts
```

## 后端

先起后端（`./mvnw.cmd spring-boot:run`，8080）。封面等媒体走 `/upload/**`，
所以 dev 代理必须同时转发 `/api` **和** `/upload` —— 只代理 `/api` 会让所有图片 404。
