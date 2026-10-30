# 前端重制期遗留口径（Current Frontend · 上一期）

> ⚠️ **已收口（2026-10-30）**：本期入口 = [CURRENT_INTEGRATION.md](./CURRENT_INTEGRATION.md)（联调期）；本文件**不再是入口**。
> **定位**：重制期沉淀下来、**联调期仍然要用**的东西 —— 选型铁律 / 门禁 / 环境与坑 / 入口映射 / 客户端口径。
> **不在这里的**：GAP 差异清单 → [CURRENT_FRONTEND_GAPS.md](./CURRENT_FRONTEND_GAPS.md)；
> 应用层设计 → [前端.md](../architecture/frontend/前端.md)；视觉基准 → [视觉基准](../architecture/frontend/视觉基准.md)；
> 静默机制 → [客户端静默机制](../architecture/frontend/客户端静默机制.md)；后端期登记台 → [归档](../archive/backend-2026-10-30/README.md)。
> ★ **过程不进本文件**：逐窗口交付、逐支 E2E 的断言清单、每次反向注入的记录 —— 都在 git（2026-10-25 ~ 10-29）。

---

## 一、选型与两条铁律

- **栈**：Vue 3 + TypeScript + Vite + vue-router（**不装 Pinia**：跨页共享真出现再说）。
- **页面形态**：**混合** —— 外壳多入口（跨应用 = 新标签整页跳转）+ 每个入口一个 vue-router（应用内 SPA）。理由与 B 站实测见 [前端.md §一](../architecture/frontend/前端.md)。
- ★ **类型三层（铁律）**：
  ```
  openapi.yaml ──gen──▶ types/api.d.ts   生成，只读，入库（diff = 契约变更记录）
                        types/ui.ts        手写：页面期望形状，可含后端暂无的字段
                        api/adapters/      纯函数 api → ui，缺口在此显式 fallback
  ```
  **组件只吃 `ui.ts`，不许直接吃 `api.d.ts`**（纯透传表单可例外）。收益：后端补字段 ⇒ 只改 adapter + 删一个 fallback，组件零改动。
- ★ **假数据边界**：**一个 fixture 都没有**，占位只靠文案 + 图标。判据：界面上的数字要么来自真接口、要么来自占位组件，**不许从 fixture 流进真卡片**。
- ★ **"不做"要分清两种**：**永远不做**（无域的：弹幕 / 会员购 / 番剧 / 直播 / 游戏中心 / 热搜）⇒ 占位路由或灰态，**不进「等后端」清单**；
  **等后端**（有域缺端点 / 缺参数）⇒ 进 GAP 台账、`临时表现` 必填。逐条见 [GAP 台账](./CURRENT_FRONTEND_GAPS.md)。
- **契约改动只走"批次"**：前端撞到要后端补的 GAP ⇒ 先记账、攒到一页闭环或 3–5 条再一次做完
  → 重生成 `endpoint-inventory.tsv` / `openapi.yaml` → **review diff** → `npm run gen:api` → 删 fallback → 销账。
  纪律见 [接口契约-护栏与Spec](../architecture/tech/api/接口契约-护栏与Spec.md)。

---

## 二、门禁（都在 `frontend/` 下）

```bash
npm run gen:api && git diff --exit-code src/types/api.d.ts   # 类型落后于后端 ⇒ 红
npm run typecheck                                            # vue-tsc -b（类型硬门禁）
npm run test                                                 # vitest（node 环境，无 DOM）
npm run build                                                # 多入口产物 dist/*.html
npm run stage                                                # dist → ../src/main/resources/static（Spring 直接服务）
```

`npm run test` 断言落在**业务口径**上（不是"调用了哪个函数"）。**哪条用例钉住了什么**，改前端前对一眼：

| 文件 | 钉住的口径 |
|---|---|
| `api/adapters/format.test.ts` | 万/亿、`mm:ss`、相对时间、图文中文日期、**后端空简介占位 `-` 视为空** |
| `api/adapters/{videoCard,hero,search,videoDetail,user,interaction,comment,space}.test.ts` | 各页 VM 的缺口 fallback（`playCount` 缺失 ⇒ `null`、图文不显示计数、三态 `null ≠ false`、评论无时间 ⇒ 空串）；**POST → `/opus/{id}` / VIDEO → `/video/{id}` 分流** |
| `api/opusImages.test.ts` | 图文图片区：1/2/3+ 列数、>9 张 `+N` 截断、循环切图、旋转只留 0/90/180/270、输入控件不接快捷键 |
| `api/adapters/{favorites,history}.test.ts` | 夹 / 稍后再看卡（`addedTime` **不冒充**发布时间）；历史角标三态、按本地日历日分组 |
| `api/adapters/{feed,relation}.test.ts` | 动态卡 `likeCount/commentCount` 缺失 ⇒ `null`、0 是真值、`liked` 三态；关注卡 `isFollowed=false` **不是**"没查" |
| `api/http.test.ts` | 信封 / 204 / `Retry-After` / 幂等键 / 数组 query；multipart：进度回调、**不手设 `Content-Type`**、XHR 超时与网络错误的不同业务码 |
| `api/uploadDraft.test.ts` · `api/upload.test.ts` | 投稿校验（标题 ≤50、简介视频 1000 / 图文 10000、分区 0~9、**单文件** 50MB）、上传计划顺序、创建载荷形状、幂等键生命周期；`AssetUploadPool` 同文件第二次不发请求 |
| `api/creatorEdit.test.ts` · `api/coverCrop.test.ts` | 编辑页脏判定与整份覆盖载荷、`swapImages` 不改入参、越界原样；裁剪恒 16:9、锚点缩放不动点、缩放夹 1..4 |
| `common/watchLaterSession.test.ts` | 稍后再看"只记本次页面"：同 URL 记得、换 URL 忘、取消即忘 |
| `player/progressReporter.test.ts` | 45s 节奏、503 退避与**恢复**、停报条件、续播 `max`、本地补偿 TTL |

> 这些用例都做过**反向注入**验证（2026-10-25 ~ 10-29 共 18 次：把 `null` 画成 `0`、把三态归一成 `false`、去掉退避恢复分支、把总额当本页条数…），
> 每次都能精确变红、还原后全绿。逐次记录在 git；**结论已被用例本身承担**，不复述。

### 真浏览器 E2E（一次性脚本）

- 脚本放 `.tmp/*.mjs`，**不入库**；需 Chrome `--remote-debugging-port=9222`；**可以直接对 `8080`（dist 上 Spring）跑**，不必起 Vite。
- 断言对象 = **用户看得见的东西 + 浏览器实际发出的请求 URL**；每个脚本跑完**还原**（删夹具 / 取消点赞关注），脚本自己挑"不在池里"的目标。
- 两个必加的守门（都踩过）：`waitImages()` **必须带超时**（一张图不 load 会让整支静默挂住）；
  "列表里没有非法请求"这类断言要加 **"抓到请求数 > 0"**，否则 `every()` 在空数组上**恒真**（真空转）。

### 四个坑（会反复咬人）

1. ★ **headless 视口只有 900px**：页面下半部分按钮的 `rect` 在视口外，按 rect 派发鼠标事件 = 点空白（表现为"保存没反应"的假失败）⇒ 先 `scrollIntoView({block:'center'})`。
2. ★ **封面必须走 `POST /uploads/cover`**：拿一张 image 的 URL 顶上会被 `normalize` 判「封面地址不合法」400（媒体类型**按目录**校验）。
3. ★ **agent 沙箱写不了 dev 媒体根**（`D:/data/projects/MySpringTV/media` ⇒ `拒绝访问`）⇒ 跑真上传用 **`UPLOAD_ROOT=.tmp/e2e-media` 起后端**；生产默认值不变。
4. ★ **改了 `src/main/resources/static` 的产物后，jar 内的静态资源必须重新 `package`** —— Spring 直接服务 classpath 里的 `static/`，只 `npm run stage` 不重新打包，跑 jar 时看到的还是旧前端。

---

## 三、环境与中间件

- Node `v24.15.0` / npm `11.12.1` / registry 已是 npmmirror；pnpm 未装（`corepack enable`）。
- ★ 沙箱下**子进程建不了 TLS、Chrome 起不来** ⇒ `npm install` 与截图各需一次 `danger-full-access` 授权（建议合在一条命令里）。
- 端口：`3306` = 本机 MySQL（**不是**容器 `springbook-mysql`，别抢）；`6379` / `5672` = `docker start redis` / `rabbitmq`。起后端前确认三个都在。
- ★ **工具链唯一的坑**：`openapi-typescript@7.13` 的 peer 是 `typescript@^5.x`，而 create-vite 模板默认 TS 6.0.3 ⇒ `npm i -D openapi-typescript` **ERESOLVE**（`npx` 能跑通但掩盖问题）。修法：先 `npm i -D typescript@~5.9.0`。
- `src/main/resources/static/` 现在是**构建产物落点**（`npm run stage` 拷入，**产物不入库**）；旧 SPA 的 18 个文件已于 2026-10-26 删除，`/static/**` 不再存在。

---

## 四、入口 → 应用内路由 → 接口（**九个入口**）

| 应用（入口） | 应用内路由（vue-router，history） | 主要接口 |
|---|---|---|
| `index.html` | `/`、`/placeholder/:feature` | `GET /contents/recommended` |
| `video.html` | `/video/:id`（分 P 用 `?p=`） | 详情 + 互动 + 评论 + 进度 + `GET /users/{id}` |
| `opus.html` | `/opus/:id`（图片两态是页面内状态，无路由） | 详情 + 评论 + 点赞/收藏（**无投币**，GAP-26） |
| `search.html` | `/search?keyword=`（tab / 排序用 query） | `GET /contents?keyword=`（+ `type` / `order`，GAP-10） |
| `space.html` | `/space/:id`（tab / 翻页 / 夹 / 关注粉丝用 query） | `GET /users/{id}` + 收藏夹 + `/following|followers` |
| `favorites.html` | `/favorites`（`?folder=` / `?page=`）+ **`/watchlater`**（同入口的独立路由） | 收藏夹 / 收藏 / 稍后再看四端点 |
| `history.html` | `/history?page=` | `GET /history`（**只读**：无删除/清空，已知代价） |
| `dynamic.html` | `/dynamic?page=` | `GET /feed`（**需登录**）+ 关注/粉丝 |
| `creator.html` | `/creator`（投稿）· `/creator/manage` · `/creator/edit/:id` | `POST /uploads/{type}` + `POST /contents`（JSON 清单）· `GET /contents/{id}` 取回 · `PUT /contents/{id}` 整份覆盖 |

- **跨应用**一律 `<a href="/video/77" target="_blank" rel="noopener">`（新标签、可中键、可复制）。
  **唯一例外**：播放页相关推荐走同标签 SPA（`new-tab=false` + `router.push`）—— B 站就这么做。
- **应用内**每个入口一个 `createWebHistory` 实例，切 tab 只改 URL；页面之间**不共享内存状态**，登录态从 `localStorage` 读 `token`（后端是 `Authorization: Bearer`，不是 Cookie）。
- ⚠️ **history 模式要服务端 fallback**：`/video/77` 必须落到 `video.html`。生产用 **`WebMvcConfig#addViewControllers` + forward**（不是 redirect、不是 `@Controller` —— 后者会进端点登记簿 / spec，还要为默认拒绝开 `@Public`）；dev 侧是 `vite.config.ts` 的 `htmlHistoryFallback()`，挂在 `configureServer` **与** `configurePreviewServer` 上。
- ⚠️ **dev 代理要转发两段**：`/api` **和** `/upload`（封面是后端静态路径，只代理 `/api` 会让图片全 404）。
- **真实差异（接受）**：B 站是 `/video/BV1xx`，本仓是数据库自增 `/video/77`。路径形状可仿，id 语义仿不了；要短链 / 公开 id 得单独开 GAP。

---

## 五、新增入口时**必须同步的三处**（漏一处就"页面打不开"或"CSS 被限流"）

1. `WebMvcConfig#addViewControllers` —— 生产转发（forward）；
2. `vite.config.ts` 的 `htmlHistoryFallback()` —— dev / preview 同一张映射表；
3. `RateLimitFilter` 的跳过清单（`/assets/`、HTML 外壳；`/creator/` 这类前缀走 `SKIP_PREFIXES`）—— 否则 **429 掉 CSS**。

判据靠两个契约测试：`StaticForwardingContractTests`（断言 `forwardedUrl` 而不是"最终 200"）+ `RateLimitFilterContractTests` 的零扣费探针。
**删除时的红线**：不动 `db/migration/**`（逐字节冻结）、不动 `old-project/`（行为判据）。

---

## 六、客户端行为口径（跨页面复用，**联调时照这个判**）

> ⚠️ **这篇是"上一期"里最不该淘汰的一块**：它是页面级口径的**唯一汇总**。本节一旦随本文件归档，
> 必须**先迁进** [`architecture/frontend/前端.md`](../architecture/frontend/前端.md)（稳定层），否则散落成悬空引用。

| 口径 | 做法 | 反例（别这么写） |
|---|---|---|
| **写动词** | `api/http.ts` 的 `getJson/postJson/putJson/deleteJson`（multipart 用 `postForm`），类型由 spec 推导；`204` 是成功 | 每页自己 `fetch` / 自己判信封 |
| **multipart 上传** | XHR；**10 分钟**超时；`upload.onprogress`；信封 / Bearer / 204 / `Retry-After` 与四动词**共用 `parseEnvelope`**；文字进 `FormData`；**不手设 `Content-Type`** | 手拼 boundary / 把文本拼进 query |
| **幂等键** | 只有 4 条 `KEYED` 端点要；键在"按下按钮"时取，**同一草稿的重试复用**，草稿一改即作废（`SubmitKeyHolder`） | 每次新生成（重试 = 两条评论）；或请求层自动加 |
| **登录闸门** | **动作级**：`await requireLogin()`，弹浮层阻塞到用户给出结果；成功后**这次点击的意图继续执行** | 页面级跳登录页；浮层关掉就丢操作 |
| **页面级身份态** | 整页都属于"我"时先 `await restoreSession()`，匿名渲染「登录后查看 + 登录按钮」；**表单用 `v-show` 保持挂载** ⇒ 登录后草稿不丢 | 进页自动弹框 / 把 401 画成空数据 / `v-if` 卸载表单 |
| **稍后再看状态** | **只记本次页面**：`(contentId, pathname+search)` 记进模块级 `reactive(Map)`；换 URL 即忘 | 每卡回源查状态 / "不知道"画成"未加入" / 落 localStorage |
| **跨应用落点** | 唯一规则 `contentHref(id, isPost)`：`POST → /opus/{id}`、`VIDEO → /video/{id}`；只有播放页相关推荐同标签 | 各处散写 `videoHref` / 相关推荐也 `_blank` |
| **收藏夹图文**（方案 A） | `FavoriteItemVO` 没有 `type` ⇒ 卡片先落 `/video/{id}`，播放页发现 `isPost` 再 `location.replace('/opus/{id}')` | 每卡回源详情查 type / 把图文画成视频卡 |
| **纯文字图文卡** | 无封面 ⇒ 文字卡面用**真 `description`**（3 行）；没简介 → 中性「图文动态」 | 图标占位 / 编假文字 / 为卡面回源取首图 |
| **图文详情两态** | 默认九宫格；点图 → 放大态（工具条 + 大图 + 缩略图）；收起回网格；与入口无关 | 按来源做两套页面 / 图片态放路由参数 |
| **写后对齐** | 乐观更新 → 失败回滚 → 成功后用**服务端权威值**再对齐一次 | 只乐观不回滚；回滚后不回读 |
| **三态** | `null` = **本面不查**（≠ `false`）：`interaction` 整体 `null` 是匿名，字段 `null` 是这一维没查 | `?? false` 归一（关注按钮永远显示"关注"） |
| **429 / 瞬态** | 吃 `Retry-After` 说人话（"N 秒后再试"）；页面级错误态给「重试」按钮 | 静默重试 / 只给「回首页」 |
| **不该重试的** | 400 / 401 / 403 / 404 是**确定失败**：停手并说清楚 | 循环重试把 429 也刷出来 |
| **本地存储** | 只有两处：`myspringtv.token`、`history:pending:{id}`（崩溃补偿） | 把页面状态塞 localStorage 冒充缓存 |

---

## 七、已经关闭的两件后端期结转（留痕，不用再查）

- `F-15`（互动态维度常量）：**已落地** —— 后端返回的 `interaction` 本来就是正确契约，缺的是"哪个面读哪几维"；重制期已按面接上三态。表见 [favorite.md §六](../architecture/domain/favorite/favorite.md)。
- `T61`（旧 SPA 留作消费样例）：**已关闭**（2026-10-25 复核：旧前端简陋、接口规范化后不可用，无参考价值）⇒ 18 个文件 2026-10-26 删除。
