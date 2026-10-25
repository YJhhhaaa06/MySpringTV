# 前端重制期登记台（Current Frontend）

> **定位**：**前端重制专项的唯一入口**。2026-10-25 起生效。
> **后端期登记台**（`CURRENT_NEEDS.md` / `CURRENT_TASKS.md` / `CURRENT_ISSUES.md`）**已收口冻结，本专项不读它们**；
> 跨期结转池仍是 `FURTHER_ISSUES.md`（后端遗留账，前端不必读）。
> **边界**：本文件记「做什么 / 缺口 / 门禁」；**设计细则**在重制期开工后按 SOP 迁入 `architecture/`。
> **后端改动**：所有「要后端适配」的条目先在本文件 §三 登记，**不直接动后端**（见 §四）。

---

## 一、一屏结论（2026-10-25 拍板）

> **本专项目标（用户口径，两句话）**：**尽量像 B 站网页版**；**有接口就调真接口，调不到就先降级/占位、记账等后端**
> （记账 = §三 GAP 台账，`临时表现` 必填）。**永远不适配的功能 = 永远占位**，不进「等后端」清单，也不阻塞重制（见 §五）。

| 决策 | 结论 |
|---|---|
| 框架 | **Vue 3 + TypeScript + Vite + Vue Router**（Pinia 暂不装，等跨页共享真出现） |
| 页面形态 | ★ **混合**：**外壳多入口**（跨应用整页 / 新标签跳转）+ **每个入口一个 vue-router**（应用内 SPA，见 §十二） |
| 列表计数 | **P1：列表 VO 直接带计数**（B 站行为优先）。不推翻 T43 的**缓存**口径（计数已是独立 key），代价降级为**读放大**，靠一次 pipeline MGET 摊平 |
| 类型 | **三层**：`openapi.yaml` → 生成 `api.d.ts` → 手写 `ui.ts` → `adapters/` 映射 |
| 不做的功能 | **占位**：弹幕 / 会员购 / 番剧 / 直播 / 游戏中心（见 §五） |
| 后端适配 | **攒批**（契约批次），不逐字段改（见 §四） |

---

## 二、类型三层（铁律）

```
openapi.yaml ──gen──▶ frontend/src/types/api.d.ts     生成，只读，入库（diff = 契约变更记录）
                       frontend/src/types/ui.ts        手写：页面期望形状，允许含后端暂无的字段
                       frontend/src/api/adapters/      纯函数 api → ui，缺口在此显式 fallback
```

**铁律：组件只吃 `ui.ts` 的类型，不许直接吃 `api.d.ts`。**

- 例外：纯透传页（如 `/users/me` 的表单）可直接用生成类型，别为教条写 adapter。
- 收益：后端补字段时只改 adapter + 删一个 fallback，**组件零改动**。

---

## 三、GAP 台账（**唯一**接口差异清单）

`类型` 四分类：`M1 字段缺` / `M2 形状异` / `M3 端点缺` / `M4 语义异`。
`临时表现` **必填**（隐藏？占位？假数据？）—— 缺这格，日后没人分得清「没做」还是「假装做了」。
定位用 `文件#函数`（如 `frontend/src/api/adapters/videoCard.ts#toVideoCard`），**GAP 编号不进代码注释**
（不新开无人管辖的编号空间）。

| ID | 类型 | 页面·组件 | 期望 | 现状 | 谁适配 | 临时表现 | 后端改动点 | 状态 |
|---|---|---|---|---|---|---|---|---|
| GAP-01 | M1 字段缺 | 首页 / VideoCard | 列表带 `playCount` | `ContentVO` 无任何计数（T43） | 后端补（P1） | **隐藏播放量角标** | 列表 VO 加 5 个计数 + 批量 MGET + spec 重生成 | 待补 |
| GAP-02 | M3 端点缺 | 首页 / ChannelWall | 分区列表 | 无 `GET /categories` | 前端适配 | **常量表 18 项** | 无（不补） | 长期 |
| GAP-03 | M4 语义异 | 播放页 / DanmakuLayer | 弹幕流 | 无弹幕域（无表/无端点） | 前端适配 | **不做 + 占位角标** | 无 | 不做 |
| GAP-04 | M1 字段缺 | 首页 / VideoCard | 弹幕数角标 | 同 GAP-03 | 前端不做 | **不显示该数字** | 无 | 不做 |
| GAP-05 | M4 语义异 | 首页·播放页 / NumberText | 图文 `play_count` 语义 | 列叫 play，图文实为「浏览」 | 前端适配 | 按 `type` 切文案 | 无 | 长期 |
| GAP-06 | M3 端点缺 | 首页 / VideoGrid | 推荐流分页（无限加载） | `GET /contents/recommended` **固定 12 条、无 page 参数**；`GET /contents` 是**搜索专用**（`keyword` 必填，空白即 400 `输入不能为空`） | 后端补（**已拍板：等后端**） | 只渲染 12 条；**不做无限滚动** | 给 recommended 加分页参数，或新增推荐分页端点 | 待补（等后端） |

> 实测补充（2026-10-25 起后端 curl，非文档推断）：
> ① `GET /api/v1/contents?page=1&pageSize=3` → **400 `PARAM_INVALID / 输入不能为空`** —— 该端点只服务关键词搜索，不是通用列表；
> ② `coverUrl` 是**相对路径**（`/upload/cover/xxx.png`），前端按同源拼即可，**别写死 localhost**；
> ③ 匿名请求 `interaction: null`（不是 `{}`、也不是四个 `false`）。

> 只**做一页列一页**，不预演全站；ID 不回收。台账超过约 40 KB 再拆独立文件。

---

## 四、契约批次（后端适配前端的唯一通道）

1. 前端撞到要后端补的 GAP → **只记台账，不动后端**
2. 攒到「一页闭环」或 3–5 条 → 一次做完：改 VO/端点 → 重生成 `endpoint-inventory.tsv` + `openapi.yaml`
   → **review diff** → 与代码同批提交（纪律见 [接口契约-护栏与Spec](../architecture/tech/api/接口契约-护栏与Spec.md)）
3. 前端 `npm run gen:api` → 删对应 fallback → 台账状态改「已销账」

理由：spec 的 diff 就是「这次契约变了什么」的记录；逐字段改会让 diff 变噪声、review 失效。

---

## 五、占位与假数据边界

| 功能 | 状态 | 表现 |
|---|---|---|
| 番剧 / 国创 / 电影…等分区 | 不做 | 导航项保留但灰态；点击进应用内占位路由 `/placeholder/:feature` |
| 会员购 / 直播 / 游戏中心 | 不做 | 同上，走同一个占位路由（不单独建应用） |
| 弹幕 | 不做 | 播放页无弹幕层；卡片**不显示弹幕数**（假数字一问就穿帮） |
| 分区列表 | 前端常量 | 18 项硬编码（无 `GET /categories`） |

- fixture 只在 `frontend/src/mock/*.fixture.ts`，**仅占位组件可 import**，用 ESLint `no-restricted-imports` 拦死。
- 判据：界面上任何数字，要么来自真接口，要么来自占位组件，**不许从 fixture 流进真卡片**。

---

## 六、门禁（三条命令）

```bash
npm run gen:api && git diff --exit-code src/types/api.d.ts   # 类型落后于后端 ⇒ 红
npx vue-tsc --noEmit                                          # 类型硬门禁
npx vitest run src/api/adapters                               # 缺口 fallback 行为测试（要能变红）
```

---

## 七、环境前置（实测）

- Node `v24.15.0` / npm `11.12.1` / registry 已是 npmmirror；pnpm 未装（`corepack enable` 即可）。
- ★ 沙箱下**子进程建不了 TLS、Chrome 起不来** ⇒ `npm install` 与页面截图**各需一次 `danger-full-access` 授权**（建议合并在一条命令里）。
- 老前端：`src/main/resources/static/`（18 文件，原生 JS，接口重构后已失效）→ 重制时整体替换为 `dist` 产物。

---

## 八、开工顺序（重制窗口照此走）

1. `frontend/` 脚手架（Vue + TS + Vite + vue-router）→ 配 **多入口**（`vite.config.ts` 的 `build.rollupOptions.input`）+ 每个入口一个 router → `npm run gen:api` 出类型
2. 先做 `VideoCard` + `VideoGrid`（会立刻暴露 GAP-01）
3. 首页闭环 → 截一张 B 站首页对照 → 攒第一批契约批次
4. 播放页 → 搜索 → 用户空间 → 收藏 / 历史 →（可选）后台

---

## 九、后端期结转给本专项的两件事（原登记台已冻结，摘在此）

| 来源 | 事项 | 处置 |
|---|---|---|
| `F-15`（2026-10-15 清出） | **互动态维度常量**：后端返回的 `interaction` 是正确契约，缺的是前端「哪个面读哪几维」 | 重制时**先把常量加回**：`/contents/recommended` → `{关注}`；`/contents`（搜索）→ **0 维**；`/feed` 与 `/users/{id}` → `{赞}`；详情 → 三维。表见 [favorite.md §六](../architecture/domain/favorite/favorite.md)。⚠️ 三态：`null` = **本面不查**（≠ `false`）；匿名时整个 `interaction` 才是 `null` |
| `T61`（`R-23` 搁置） | 旧 SPA 是否留作「接口消费样例」 | **本专项重定**；默认随重制整体删除，不为将死代码续命 |

---

## 十、视觉基准（2026-10-25 实拍；**图不入库，命令可复算**）

产物在 `.screenshots/bilibili-2026-10-25/`（已在 `.gitignore` 中忽略，约 5.3 MB）：

| 文件 | 页面 | 对照什么 |
|---|---|---|
| `home-1440.png` / `home-1600.png` | 首页 | 顶栏高度、频道墙、大轮播、卡片网格 |
| `search-1600.png` | 搜索（`keyword=java`） | 搜索框、结果 tab、排序条、结果卡片 |
| `video-1600.png` | 播放页 | 播放器、弹幕输入、右侧操作栏、选集 / 推荐 |
| `space-1600.png` | UP 空间 | banner、tab、代表作、视频网格、公告 / 资料卡 |
| `popular-1600.png` | 热门 | 列表型布局（备用） |

复算命令（⚠️ **必须在 `danger-full-access` 下跑**，Chrome 在沙箱里起不来）：

```powershell
$out = ".screenshots\bilibili-<日期>"
$chrome = "C:\Program Files\Google\Chrome\Application\chrome.exe"
& $chrome --headless=new --disable-gpu --hide-scrollbars --no-first-run `
  --user-data-dir="$out\_profile" --window-size=1600,1000 --virtual-time-budget=10000 `
  --screenshot="$out\home-1600.png" "https://www.bilibili.com/"
```

三个实测坑：
1. **别把 Chrome 调用封成 PowerShell 函数 + `*>` 静默重定向** —— 会**静默失败**（无产物、退出码仍为 0）；用直接 `& $chrome …`，并检查 PNG 大小（真页面 ≥ 500 KB，404/验证码页只有几十 KB）。
2. **BV / UID 从搜索结果页 `--dump-dom` 里取**（`/video/(BV[0-9A-Za-z]{10})`、`space\.bilibili\.com/(\d+)`）；从首页正则取会捞到广告壳的假号 ⇒ 拍出 404。
3. 登录浮层是**未登录态常态**，对照时看浮层底下的真实布局。

---

## 十一、工具链实测（2026-10-25，临时工程验证后已删除）

| 项 | 结果 |
|---|---|
| `npm create vite@latest … -- --template vue-ts` | ✅ 生成成功 |
| 模板自带版本 | `vue 3.5.44` · `vite 8.3.4` · `@vitejs/plugin-vue 6.0.10` · `vue-tsc 3.3.13` · **`typescript ~6.0.3`** |
| `vue-tsc --noEmit` | ✅ exit 0 |
| `openapi-typescript` 读本仓 spec | ✅ 3529 行 / ~106 ms |

★ **唯一的坑（必踩）**：`openapi-typescript@7.13` 的 peer 是 `typescript@^5.x`，模板默认 TS 6.0.3
⇒ `npm i -D openapi-typescript` **ERESOLVE 报错**（`npx` 兜底能跑通，但**掩盖了问题**，将来 CI 会红）。
**修法（已实测）**：把 TS 钉到 5.9 —— `npm i -D typescript@~5.9.0`，再装 `openapi-typescript` 即 0 错误，
`vue-tsc` 仍绿。

建议的 `package.json` 脚本（路径**相对 `frontend/`**，不是仓库根）：

```json
{
  "scripts": {
    "gen:api": "openapi-typescript ../src/test/resources/api/openapi.yaml -o src/types/api.d.ts",
    "typecheck": "vue-tsc --noEmit",
    "test": "vitest run"
  }
}
```

**中间件现状**（2026-10-25 20:45 实测）：
- `3306` **在监听** —— 本地 MySQL（**不是**容器；容器 `springbook-mysql` 是 Exited，别再起它抢端口）；
- `6379` / `5672` 曾未监听，容器 `redis` / `rabbitmq` 已 `docker start` 拉起（起后端前先确认这三个端口都在）。

---

## 十二、页面形态：MPA 外壳 + 应用内 SPA（**混合**，以 B 站网页版实际为准）

**B 站的实际形态**（2026-10-25 首页 DOM 实测 + 用户复核）：
- **跨应用 = 整页跳转**：首页 DOM 有 **149 处 `target="_blank"`**；视频卡片指向 `www.bilibili.com/video/BV…`、
  UP 主指向 `space.bilibili.com/<uid>` —— **不同子域 = 不同文档**，点开即新标签、整页加载。
- **应用内 = 客户端路由（SPA）**：播放页分 P、空间页 tab、搜索页 tab/排序，是 URL 变化而**不整页重载**。

⇒ 我们照抄这个**混合**结构，而不是二选一。

**怎么落地**：

| 应用（入口） | 应用内路由（vue-router，history） | 主要接口 |
|---|---|---|
| `index.html` | `/`、`/placeholder/:feature` | `GET /contents/recommended` |
| `video.html` | `/video/:id`（分 P 用 `?p=`） | `GET /contents/{id}` + 评论 + 互动 |
| `search.html` | `/search?keyword=java`（tab / 排序用 query） | `GET /contents?keyword=` |
| `space.html` | `/space/:id`（tab 用 query） | `GET /users/{id}` |
| `upload.html` | `/upload` | `POST /contents/videos`、`/contents/posts` |

- **跨应用**一律 `<a href="/video/77" target="_blank" rel="noopener">` —— 新标签、可中键、可复制链接，与 B 站一致。
- **应用内**用 vue-router（每个入口一个实例，`createWebHistory`），切 tab 只改 URL、不重载。
- 页面之间**不共享内存状态**；`AppHeader` 等组件在每页各自 import（Vite 抽公共 chunk）。登录态从 `localStorage`
  读 `token`（后端是 `Authorization: Bearer`，不是 Cookie），每页自己请求 `GET /users/me` 渲染头像。
- ⚠️ **history 模式要服务端 fallback**：`/video/77` 必须落到 `video.html`。两条路 ——
  ① **加静态转发**（Spring 视图映射 / 资源 resolver fallback）⇒ URL 与 B 站同构；**待验**：默认拒绝过滤器对
  「非 `@RequestMapping` 的转发路径」是否放行（大概率放行，开工时 `curl /video/1` 一条验）；
  ② 退一步：入口保留 `?id=`（`/video.html?id=77`），router 只管应用内 tab ⇒ 零后端改动，但 URL 不像 B 站。
  **决策：先走 ①**，被拦再退 ②。此改动属**静态路由**，不涉接口契约（不触发登记簿 / spec）。
- ⚠️ **dev 侧也要 fallback**：Vite 多入口（`appType: 'mpa'`）不会自动把 `/video/77` 映射到 `video.html`，
  需要一条中间件 rewrite；生产由 Spring 承担。
- ⚠️ **dev 代理要转发两段**：`/api` **和** `/upload` —— 封面是后端静态路径（`/upload/cover/…`），只代理 `/api` 会让所有图片 404。
- **明确接受的代价**：跨应用整页刷新、公共请求（`/users/me`）每次进应用重复一次；**换来**与参考实现同构。

⚠️ **一个真实差异**：B 站是 `/video/BV1xx`，本仓内容 id 是数据库自增（`/video/77`）。路径形状可仿，
**id 语义仿不了**；重制期不改契约，将来若要短链 / 公开 id，单独开 GAP。
