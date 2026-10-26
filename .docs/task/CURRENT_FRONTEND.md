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
| GAP-07 | M2 形状异 | 全部页面 / `api/adapters/*` | 生成类型与运行时一致 | spec 里 `coverUrl` / `durationSeconds` / `interaction` 等只标「可缺省」，**实际匿名响应返回显式 `null`** ⇒ `api.d.ts` 是 `string \| undefined`，与运行时不符 | 后端补（spec 生成侧标 nullable） | adapter 一律按 `null` 兜底（`?.trim()` / `?? null`）；测试夹具 `as` 掉类型谎言 | `OpenApiConfig` 给可空字段标 nullable → 重生成 spec | 待补 |
| GAP-08 | M1 字段缺 | 顶栏 / 播放页侧栏 / 头像 | 头像 URL | `UserInfoVO` / `ProfileVO` 都**没有头像字段**，也没有头像上传域 | 前端适配（不做头像域） | **昵称首字圆形头像**（渐变底，B 站观感近似） | 无（不补） | 长期 |
| GAP-09 | M3 端点缺 | 首页 / HeroBanner | 运营 banner 位（B 站首页大轮播是广告/活动位） | 无任何 banner/运营端点 | 前端适配 | 用 `recommended` 前 3 条**有封面的**做「推荐橱窗」轮播（真封面真标题，非假图）；无内容时不渲染轮播 | 无（不补） | 长期 |
| GAP-10 | M3 端点缺（参数） | 搜索页 / tabs·排序条 | `type` 筛选（视频/专栏）+ 排序（播放/最新） | `GET /contents` 只有 `keyword/page/pageSize` | 后端补（P3） | 只留「综合」tab；视频/专栏**灰态禁用**；**不渲染排序条**（不假装有） | `/contents` 加 `type`、`order` 参数 | 待补 |
| GAP-11 | M3 端点缺 | 首页 / ChannelWall「热门」 | 热门榜 | 没有排行榜端点（`recommended` 是推荐流，不是排行） | 前端不做 | 灰态占位 → `/placeholder/popular` | 无 | 不做 |
| GAP-12 | M1 字段缺 | 播放页 / 评论区 | 评论发布时间 | `CommentVO` 没有 `createTime`（只有 `commentId` 可推先后） | 后端补（P3） | **不显示时间**（不写"刚刚"顶上） | 评论 VO 加 `createTime` | 待补 |
| GAP-13 | M3 参数缺 | 播放页 / 评论排序 | 热度 / 时间排序 | `GET /contents/{id}/comments` 只有 `page/pageSize`，固定按 `comment_id` 升序 | 前端适配 | **不渲染排序条**；发布后跳到末页并高亮 | 加 `order` 参数 | 长期 |
| GAP-14 | M1 字段缺 | 空间页 / 头部 | banner 图 / 签名 / 生日 / 地区 | `ProfileVO` 只有 id、username、两个计数、`contentPage` | 前端适配（不做资料域） | **低饱和渐变 banner**；资料卡只放真计数 | 无 | 长期 |
| GAP-15 | M3 端点缺 | 空间页 / tabs | 动态 / 合集和系列 / 最近点赞 | 无对应端点（`/feed` 是「我关注的动态」，不是某人的） | 前端不做 | **不渲染这三个 tab**（不摆点不动的） | 无 | 不做 |
| GAP-16 | M1 字段缺 | 空间页 / 收藏条目卡 | 条目要带 `authorId` / `type` / `durationSeconds` / 发布时间 | `FavoriteItemVO` 只有 contentId/title/coverUrl/authorName/favoriteTime/invalid | 前端适配 | 作者渲染成**纯文本**（不做死链）、不显示时长与日期、`invalid` 打「已失效」灰角标 | 无 | 长期 |
| GAP-17 | M1 字段缺 | 空间页 / 收藏夹卡 | 收藏夹封面（B 站是四宫格缩略图） | `PublicFavoriteFolderVO` 只有 id/name/itemCount | 前端适配 | 纯文字卡片（名字 + 条目数） | 无 | 长期 |
| GAP-18 | M4 语义异 | 播放页 / 播放进度 | 匿名观看也计播放量（B 站口径） | `/play`、`/progress`、`/flush` **全部 `@RequiresLogin`** | 前端适配 | 未登录**整段不接**进度上报（不打一串 401） | 若要做需匿名计数端点 | 长期 |
| GAP-19 | M3 端点缺 | 历史页 / HistoryRow | 删除单条历史 / 清空历史（B 站历史页有） | `HistoryController` 只有 list / position / flush / report / play **五条**（`F-27` ①：删除/清空本期刻意不做） | 前端不做 | **不渲染删除与清空按钮**（有按钮但点不动 = 撒谎） | 若要做：`DELETE /api/v1/history?contentIds=` + `DELETE /api/v1/history`（清空） | 待补（等后端） |

> 实测补充（2026-10-25 起后端 curl，非文档推断）：
> ① `GET /api/v1/contents?page=1&pageSize=3` → **400 `PARAM_INVALID / 输入不能为空`** —— 该端点只服务关键词搜索，不是通用列表；
> ② `coverUrl` 是**相对路径**（`/upload/cover/xxx.png`），前端按同源拼即可，**别写死 localhost**；
> ③ 匿名请求 `interaction: null`（不是 `{}`、也不是四个 `false`）；
> ④ **spec 漏标 nullable**：匿名响应里 `coverUrl` / `durationSeconds` / `interaction` 会出现**显式 `null`**，
> 而 spec 只声明"可缺省" ⇒ `api.d.ts` 生成 `string | undefined`，与运行时不一致（GAP-07）。
> 前端已按 `null` 兜底，但**别拿生成类型当运行时真相**。
> ⑤ ★ `CommentVO.children` 运行时是**带 `isLiked` 的 `CommentVO`**（spec 声明成缩水的 `CommentCacheDTO`）
> ⇒ 楼中楼点赞态可以直接用；按 spec 写会以为"回复没有点赞态"。
> ⑥ ★ 三态是**字段级**的：`GET /users/{id}` 的 `contentPage.list[].interaction` 返回
> `{coin:null, isFavorited:null, isFollowed:null, isLiked:false}` —— 只有整块 `null` 才表示"匿名、本面不查"。
> ⑦ 限流会**真的咬到正常操作**：2026-10-25 实测"首屏 + 连续三个写操作就 429"（当时登录桶 `20 枚 / 2 枚每秒`，
> 缓存 miss `×3` 放大单次成本）；**2026-10-26 用户已把登录桶提到 `200 / 10`、匿名 `600 / 30`**
> （数值以 `application.yaml` 的 `video.rate-limit.user.*` 为准，本表不复制配置）。前端的 429 处理
> （吃 `Retry-After` 说清等多久 / 页面级「重试」/ **不**自动重试）不因数值变化而失效；
> 前端必须能说清"等多久"（吃 `Retry-After`），**这不是 bug**。

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
| 评论排序（热度/时间） | 不做 | **不渲染排序条**（后端无排序参数，GAP-13） |
| 空间「动态 / 合集和系列 / 最近点赞」 | 不做 | **不渲染 tab**（无端点，GAP-15） |
| 历史「删除单条 / 清空」 | 不做 | **不渲染删除与清空按钮**（无端点，GAP-19） |

- ★ 本仓**目前一个 fixture 都没有**：占位组件只靠文案 + 图标，不需要假数据。
  若将来真需要，只许放 `frontend/src/mock/*.fixture.ts`，且仅占位组件可 import（用 ESLint `no-restricted-imports` 拦死）。
- 判据：界面上任何数字，要么来自真接口，要么来自占位组件，**不许从 fixture 流进真卡片**。

---

## 六、门禁（四条命令 + 一条部署命令，都是 `frontend/` 下的 npm script）

```bash
cd frontend
npm run gen:api && git diff --exit-code src/types/api.d.ts   # 类型落后于后端 ⇒ 红
npm run typecheck                                            # vue-tsc -b（类型硬门禁）
npm run test                                                 # vitest：适配器 fallback + 请求层 + 进度上报规则
npm run build                                                # 多入口产物：dist/{index,search,video,space,favorites,history}.html
npm run stage                                                # dist → ../src/main/resources/static（Spring 直接服务）
```

`npm run test` 跑 `src/**/*.test.ts`（node 环境，**无 DOM 依赖**），断言落在**业务口径**上，
不是"调用了哪个函数"：

| 文件 | 钉住的口径 |
|---|---|
| `api/adapters/format.test.ts` | 万/亿、`mm:ss`、相对时间 |
| `api/adapters/{videoCard,hero,search,videoDetail,user,interaction,comment,space}.test.ts` | 各页 VM 的缺口 fallback（列表无计数 ⇒ `null`、三态 `null ≠ false`、评论无时间 ⇒ 空串…） |
| `api/adapters/{favorites,history}.test.ts` | 我的夹 / 稍后再看卡片（`addedTime` **不冒充**发布时间）、历史进度（无总时长只给已看时长、0 进度不写「看到 00:00」）、按本地日历日分组 |
| `api/http.test.ts` | 信封 / 204 / `Retry-After` / 幂等键 / 数组 query |
| `player/progressReporter.test.ts` | 播放进度契约：45s 节奏、503 退避与**恢复**、停报条件、续播 `max`、本地补偿 TTL |

★ **能变红**是判据：2026-10-25 三次 mutation 实测 —— ①把列表 `playCountText` 编成 `'0'` ⇒ 卡片那条红；
②把三态 `followed` 归一成 `false` ⇒ 三态那条红；③删掉进度上报的"收到 200 回到 45s"分支
⇒ 退避恢复那条红。
**2026-10-26 再来两次**：④把历史 `progressPercent` 改成恒 `100` ⇒ 2 条红；⑤把稍后再看的
`dateText` 设成 `addedTime`（时间冒充发布日期）⇒ 1 条红。还原后 **121/121** 绿。

**真浏览器 E2E**（一次性脚本，放 `.tmp/`，**不入库**）：登录 / 互动 / 评论 / 进度 / 空间 /
**收藏 / 历史**各一支，必须真接口真写（`node .tmp/<name>-e2e.mjs`，需 Chrome `--remote-debugging-port=9222`）。
★ 2026-10-26 起也可以直接对 **8080**（dist 上 Spring）跑，不必起 Vite —— 顺带验证转发与静态产物。
收藏页那支还覆盖写路径：点「取消收藏」→ 卡片 2→1、侧栏计数 −1、toast、**刷新后仍 1**、API 恢复后回 2。
⚠️ 脚本里的"撞上限流 ⇒ 等 8s 重试"是**真实现**，不是脚本噪声：2026-10-25 实测时限流确实咬到了正常操作
（当时登录桶 `20 / 2`，缓存 miss `×3`；**2026-10-26 用户已提到 `200 / 10`**，数值以 `application.yaml` 为准）。
脚本仍保留重试分支 —— 它同时验证了前端的 429 文案（`操作太频繁了，N 秒后再试`，吃 `Retry-After`）。

★ **静默机制**（进度上报 / 自动重试与退避 / 退出信号 / 崩溃补偿 / 幂等键 / 乐观更新 / 登录态时序）
的**实现口径、代价与覆盖矩阵**见 [客户端静默机制](../architecture/frontend/客户端静默机制.md) §五 ——
那里有一份**"明写没覆盖"清单**（幂等键复用、`restoreSession` 单飞、卸载收尾上报…），别当成已经验过。

---

## 七、环境前置（实测）

- Node `v24.15.0` / npm `11.12.1` / registry 已是 npmmirror；pnpm 未装（`corepack enable` 即可）。
- ★ 沙箱下**子进程建不了 TLS、Chrome 起不来** ⇒ `npm install` 与页面截图**各需一次 `danger-full-access` 授权**（建议合并在一条命令里）。
- 老前端：`src/main/resources/static/` 的 18 个文件已于 **2026-10-26 删除**（随 dist 上线同批，§十三）。
  该目录现在是**构建产物落点**（`npm run stage` 把 `frontend/dist` 拷进去，产物不入库），
  由 Spring 直接服务 `/`、`/search`、`/video/:id`、`/space/:id`、`/assets/**`。
  旧 SPA 已冻结过（2026-10-25），删除不影响任何活代码。

---

## 八、开工顺序（重制窗口照此走）

> **进度（2026-10-26 第三窗口交付）**：
> ① 脚手架 ✅ ② `VideoCard` + `VideoGrid` + **首页闭环** ✅ ③ 搜索页 ✅（真 Page 分页）
> ④ 播放页 ✅（**只读 → 完整**：点赞/投币/收藏/关注 + 评论区 + 播放进度）
> ⑤ 占位路由 + 登录真接口 ✅ ⑥ **UP 空间页** ✅（`space.html`：资料 / 投稿分页 / 公开收藏夹）
> ⑦ ★ **生产静态转发 + dist 上 Spring + 删老 SPA** ✅（§十三 收口；`mvnw clean` 后
>    `/`、`/search`、`/video/:id`、`/space/:id`、`/favorites`、`/history`、`/assets/**` 全 200，旧 `/static/**` 404）
> ⑧ ★ **收藏页** ✅（`favorites.html`：我的收藏夹 + 稍后再看 + 单条取消/移出）
> ⑨ ★ **历史页** ✅（`history.html`：按天分组 + 进度 + 失效占位；删除/清空等后端，GAP-19）
> **下一窗口**：投稿页（multipart —— `postJson` 不覆盖，要另写 `postForm`）→（可选）后台页
> → 契约批次（GAP-01 / 07 / 10）。
> **设计沉淀**：前端应用口径在 [architecture/frontend/前端.md](../architecture/frontend/前端.md)；
> 播放进度客户端行为**不在本篇**，以 [history-客户端契约](../architecture/domain/history/history-客户端契约.md) 为准。

1. `frontend/` 脚手架（Vue + TS + Vite + vue-router）→ 配 **多入口**（`vite.config.ts` 的 `build.rollupOptions.input`）+ 每个入口一个 router → `npm run gen:api` 出类型
2. 先做 `VideoCard` + `VideoGrid`（会立刻暴露 GAP-01）
3. 首页闭环 → 截一张 B 站首页对照 → 攒第一批契约批次
4. 播放页 → 搜索 → 用户空间 → 收藏 / 历史 →（可选）后台

---

## 九、后端期结转给本专项的两件事（原登记台已冻结，摘在此）

| 来源 | 事项 | 处置 |
|---|---|---|
| `F-15`（2026-10-15 清出） | **互动态维度常量**：后端返回的 `interaction` 是正确契约，缺的是前端「哪个面读哪几维」 | 重制时**先把常量加回**：`/contents/recommended` → `{关注}`；`/contents`（搜索）→ **0 维**；`/feed` 与 `/users/{id}` → `{赞}`；详情 → 三维。表见 [favorite.md §六](../architecture/domain/favorite/favorite.md)。⚠️ 三态：`null` = **本面不查**（≠ `false`）；匿名时整个 `interaction` 才是 `null` |
| `T61`（`R-23` 搁置） | 旧 SPA 是否留作「接口消费样例」 | **已关闭**（2026-10-25 用户复核：旧前端简陋、功能未跟进、接口规范化后完全不可用，**无参考价值**）⇒ 不留样例；**18 个文件已于 2026-10-26 删除**（§十三）。`T61` 不再排期 |

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

> **本窗口（2026-10-25）拍我们自己的页面时新增两条**：
> 4. ★ `--screenshot` 必须给**绝对路径**。相对路径会报 `Failed to write file …: 0x3`，**退出码仍是 0** ——
>    看起来"跑过了"，其实没产物。
> 5. ★ `--user-data-dir` 不能复用旧 profile（复用会 `exit 21` 且**无任何输出**）。每次在 `%TEMP%` 下建新目录；
>    必要时补 `--no-sandbox --disable-dev-shm-usage`。命令模板：

```powershell
$chrome = "C:\Program Files\Google\Chrome\Application\chrome.exe"
$out = (Resolve-Path ".screenshots\frontend-<日期>").Path
$prof = Join-Path $env:TEMP ("chrome-shot-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $prof | Out-Null
& $chrome --headless=new --no-sandbox --disable-gpu --disable-dev-shm-usage --hide-scrollbars `
  --user-data-dir="$prof" --window-size=1600,1000 --virtual-time-budget=15000 `
  --screenshot="$out\home-1600.png" "http://localhost:5173/"
```

**重制产物对照图**（同样图不入库）在 `.screenshots/frontend-2026-10-25/`：
`home-1600` · `home-full`（整页）· `search-1600` · `video-1600` · `video-comments-full`（整页：播放器 + 操作栏 + 主楼 + 楼中楼）·
`video-interactions-anon-1600` / `-logged-in-1600` / `-final-1600` / `-full` · `video-coin-dialog-1600` ·
`video-favorite-dialog-1600` · `video-progress-resume-1600`（续播提示）· `space-anon-1600` · `space-folders-1600` ·
`placeholder-1600` · `home-logged-in-1600`。
⚠️ 推荐流顺序**每次请求都不同**（后端随机），所以两次截图的卡片集合不同是正常的、不是 bug。

**2026-10-26 新增**（`.screenshots/frontend-2026-10-26/`）：
`favorites-1600`（我的收藏夹 + 卡片网格）· `favorites-later-1600`（稍后再看）· `favorites-anon-1600`（登录后查看）·
`history-1600`（按天分组 + 「看到 mm:ss」）· `history-anon-1600`。
B 站对照：`.screenshots/bilibili-2026-10-26/history-anon-1600.png`（匿名 = 「历史记录」标题 + 居中登录空态，
本仓照抄这个页面级身份态）；`favlist-1600.png` 匿名访问会被 B 站落到空间主页（收藏页本身要登录，**拿不到匿名对照**）。

> 6. ★ **拍我们自己的页面时，`--screenshot` + `--virtual-time-budget` 会在懒加载图片解码前收工** ⇒
>    卡片封面截成**灰块**（不是 404，用 CDP 查 `img.complete/naturalWidth` 全是 true）。
>    可信的产物图要走 CDP：等 `img.complete` 全真再 `Page.captureScreenshot`（本窗口脚本 `.tmp/shots.mjs`，
>    temp）；或临时把 `loading="lazy"` 去掉再拍。

---

## 十一、工具链实测（2026-10-25；**本窗口已落地为 `frontend/`**）

| 项 | 结果 |
|---|---|
| `npm create vite@latest … -- --template vue-ts` | ✅ create-vite **v9.3.1** |
| 模板自带版本 | `vue 3.5.44` · `vite 8.3.5` · `@vitejs/plugin-vue 6.0.10` · `vue-tsc 3.3.13` · **`typescript ~6.0.3`** |
| `npm run typecheck`（`vue-tsc -b`） | ✅ exit 0（钉 TS 5.9.3 后仍绿） |
| `openapi-typescript` 读本仓 spec | ✅ 3529 行 / ~116 ms → `src/types/api.d.ts` 入库 |
| 装完的实际版本 | `typescript 5.9.3` · `vue-router 4.6.4` · `vitest 5.0.5` · `openapi-typescript 7.13.0` |
| `npm install` | ✅ 100 包 / ~5 s（npmmirror） |

★ **唯一的坑（必踩）**：`openapi-typescript@7.13` 的 peer 是 `typescript@^5.x`，模板默认 TS 6.0.3
⇒ `npm i -D openapi-typescript` **ERESOLVE 报错**（`npx` 兜底能跑通，但**掩盖了问题**，将来 CI 会红）。
**修法（已实测）**：把 TS 钉到 5.9 —— `npm i -D typescript@~5.9.0`，再装 `openapi-typescript` 即 0 错误，
`vue-tsc` 仍绿。

★ **vue-router 钉 4.x 不升 5**：npm 上 `vue-router@5.4.0` 已是最新，但它是**未勘察的新 major**
（peer 里还挂了 pinia）。脚手架阶段拿未验证主版本当底座，等于把"跑不起来"混进"写页面"里。
要升单独开一个窗口做，别在功能窗口里顺手升。

实际的 `package.json` 脚本（路径**相对 `frontend/`**，不是仓库根）：

```json
{
  "scripts": {
    "dev": "vite",
    "build": "vue-tsc -b && vite build",
    "preview": "vite preview",
    "gen:api": "openapi-typescript ../src/test/resources/api/openapi.yaml -o src/types/api.d.ts",
    "typecheck": "vue-tsc -b",
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
| `video.html` | `/video/:id`（分 P 用 `?p=`） | `GET /contents/{id}` + 互动 + 评论 + 进度 + `GET /users/{id}` |
| `search.html` | `/search?keyword=java`（tab / 排序用 query） | `GET /contents?keyword=` |
| `space.html` | `/space/:id`（tab / 翻页 / 夹用 query） | `GET /users/{id}` + `GET /users/{id}/favorite-folders` + `GET /favorite-folders/{id}/items` |
| `favorites.html` | `/favorites`（`?folder=` / `?tab=later` / `?page=`） | `GET /favorite-folders` + `GET /favorites?folderId=` + `GET /watch-later` + `DELETE /favorites`、`DELETE /watch-later` |
| `history.html` | `/history?page=` | `GET /history`（**只读**：无删除/清空端点，GAP-19） |
| `upload.html` | `/upload` | `POST /contents/videos`、`/contents/posts` |

> 2026-10-26 已落地 **六个入口**：`index` / `search` / `video` / `space` / `favorites` / `history`。
> `video` 已从"只读"补成完整页（互动 / 评论 / 进度）；`upload` 尚未建入口（灰态导航走 `/placeholder/*`）。

- **跨应用**一律 `<a href="/video/77" target="_blank" rel="noopener">` —— 新标签、可中键、可复制链接，与 B 站一致。
- **应用内**用 vue-router（每个入口一个实例，`createWebHistory`），切 tab 只改 URL、不重载。
- 页面之间**不共享内存状态**；`AppHeader` 等组件在每页各自 import（Vite 抽公共 chunk）。登录态从 `localStorage`
  读 `token`（后端是 `Authorization: Bearer`，不是 Cookie），每页自己请求 `GET /users/me` 渲染头像。
- ⚠️ **history 模式要服务端 fallback**：`/video/77` 必须落到 `video.html`。两条路 ——
  ① **加静态转发**（Spring 视图映射 / 资源 resolver fallback）⇒ URL 与 B 站同构；**待验**：默认拒绝过滤器对
  「非 `@RequestMapping` 的转发路径」是否放行（大概率放行，开工时 `curl /video/1` 一条验）；
  ② 退一步：入口保留 `?id=`（`/video.html?id=77`），router 只管应用内 tab ⇒ 零后端改动，但 URL 不像 B 站。
  **决策：先走 ①**，被拦再退 ②。
  ★ **补充（2026-10-25 复查）**：它**不触发端点登记簿 / spec**，但**不是纯配置改动** ——
  转发路由必须标 `@Public`（默认拒绝）、静态/HTML 入口是否进限流跳过清单要明确决定，
  且 dist 的资源目录从 `/static/` 变成 `/assets/` ⇒ 白名单要一起改（见 §十三）。
  ★ **状态（2026-10-26）：① 已落地**。`WebMvcConfig#addViewControllers` 用 **forward**（不是 redirect）
  把 `/`、`/search`、`/video/{id}`、`/space/{id}`、`/favorites`、`/history`、`/placeholder/{feature}`
  转到入口 HTML；
  **不用 `@Controller`**（那会进端点登记簿 / spec，还要为默认拒绝开 `@Public` —— 它只是页面导航）；
  dist 由 `npm run stage` 落到 `src/main/resources/static/`（产物不入库）；`/assets/` 与 HTML 外壳进限流跳过清单。
  `mvnw clean` 后实测：六入口 200、`/assets/*.js` 200、`/api/v1/**` 200、旧 `/static/**` 404。
- ✅ **dev 侧 fallback 已落地**：`vite.config.ts` 的 `htmlHistoryFallback()` 把 `/search` → `search.html`、
  `/video/<digits>` → `video.html`、`/space/<digits>` → `space.html`、`/favorites` → `favorites.html`、
  `/history` → `history.html`、`/placeholder/*` → `index.html`，
  并挂在 `configureServer` **与** `configurePreviewServer` 上 ⇒ `npm run preview` 也能验打包产物。
  它只接管 `Accept: text/html` 的导航请求（`/api`、`/upload`、带扩展名的资源全部放行）。
  ★ **生产转发要补的正是这份映射表**（六个入口，已落在 `WebMvcConfig#addViewControllers`，两处必须同步改）。
- ⚠️ **dev 代理要转发两段**：`/api` **和** `/upload` —— 封面是后端静态路径（`/upload/cover/…`），只代理 `/api` 会让所有图片 404。
- **明确接受的代价**：跨应用整页刷新、公共请求（`/users/me`）每次进应用重复一次；**换来**与参考实现同构。

⚠️ **一个真实差异**：B 站是 `/video/BV1xx`，本仓内容 id 是数据库自增（`/video/77`）。路径形状可仿，
**id 语义仿不了**；重制期不改契约，将来若要短链 / 公开 id，单独开 GAP。

---

## 十三、迁移收尾：旧 SPA 删除（**已冻结 2026-10-25；删除随 dist 上线同批**）

**决策**：旧 SPA **不再同步、不再修**（`T61` 已关闭，见 §九）。
**删除时点 = 「dist 上 Spring + 静态转发」那一个提交**（与部署、静态白名单、引用清扫同批）——
不提前（`/` 会 404 到替代品上线），也不等重制完（活文档会继续把它当"实现位置"，规则会继续逼你为死代码同步）。
**兜底**：若静态转发被拖过一个窗口，按下面同一清单**立即删**、接受 `:8080/` 404 —— 坏页面比 404 更坏。

**前置条件（缺一不可）**

> ✅ **执行记录（2026-10-26，本项已收口）**：`b79dfe1`（静态转发 + 白名单）· `a916959`（幂等过滤器安全方法直通）·
> `09ae36a`（dist 上 Spring + 删 18 文件）。下面五条是**当时的前置清单**，保留作判据；逐条结果：
>
> | # | 结果 |
> |---|---|
> | 1 替代品可验证 | `npm run build && npm run stage` 后 `mvnw clean spring-boot:run`：`/`、`/search`、`/video/65`、`/space/233`、`/assets/*.js` 全 200（`/api/v1/contents/recommended` 仍 200） |
> | 2 白名单 | `SKIP_PREFIXES`：`/static/` → `/assets/`，**并补 HTML 外壳与 `/video/ /space/ /placeholder/`**（外壳是静态文件，页面里的 `/api/v1/**` 照常过闸）；`STATIC_PREFIXES` 同步；★ **转发用 ViewController，不需要 `@Public`**（没有 `@Controller` 端点，见 §十二） |
> | 3 测试同步 | `RateLimitFilterContractTests` 探针改 `/assets/` 并补四个 HTML 外壳「零扣费」；`FrameworkErrorContractTests` 探针改 `/assets/`；**新增 `StaticForwardingContractTests`**（MockMvc 断言 `forwardedUrl`，不依赖产物是否存在；也钉住不是 redirect） |
> | 4 引用清扫 | 活文档改指新前端（`INDEX` / `前端.md` / 限流 / 安全与鉴权 / 接口契约 / 幂等 / favorite / history）；历史裁决**只加状态行不改写** |
> | 5 验收 | `git ls-files src/main/resources/static` 为空 + 四条路径 200 + `mvnw -B clean verify` 绿 + 三条文档门禁 0 |

1. **替代品可验证**：dist 已上 Spring，`/`、`/search`、`/video/:id`、`/assets/*` 全 200。
2. **静态白名单一起改**（否则新站首屏资源逐枚吃匿名令牌 ⇒ **429 掉 CSS**）：
   `RateLimitFilter.SKIP_PREFIXES` 的 `/static/` → `/assets/`，并明确 `/search`、`/video/*` 这些 HTML 入口是否跳过；
   `GlobalExceptionHandler.STATIC_PREFIXES` 同步；转发路由标 `@Public`（默认拒绝）。
3. **测试同步**：`RateLimitFilterContractTests` 的 `/static/css/common.css` 用例与"前端 18 文件"措辞；
   补一条 `/assets/*` 零扣费断言（**要能变红**：去掉白名单应红）。
4. **引用清扫**（活文档改指新前端；**历史裁决保留 + 只加状态行，不改写**）：
   `INDEX.md` §一① · 本文件 §七/§九/§十二 · `favorite.md` 的 `T7`/`T19`/`I-08` 锚点 ·
   `history-术语表.md` 的"页面 ↔ 端点矩阵" · `history.md` / `history-需求.md` 的"`static/` 不动" ·
   `接口契约.md` 的 `R-23`/`T61` · `FURTHER_ISSUES.md` 的 `F-27`。
5. **验收**：`static/static/js` 零残留 + 第 1 条的四条路径全 200 + `mvnw -B clean verify` 绿 + 三条文档门禁 0。

**删除时不做的事**：不动 `src/main/resources/db/migration/**`（逐字节冻结）、不动 `old-project/`（行为判据）。

---

## 十四、客户端行为口径（2026-10-25 第二窗口沉淀；下个窗口照此写，别重新发明）

这一节只放**跨页面复用**的约定。页面级视觉细节进 [architecture/frontend/前端.md](../architecture/frontend/前端.md)，
播放进度的节奏 / 退避 / 崩溃补偿进 [history-客户端契约](../architecture/domain/history/history-客户端契约.md)。

| 口径 | 做法 | 反例（别这么写） |
|---|---|---|
| **写动词** | `api/http.ts` 的 `getJson/postJson/putJson/deleteJson`，返回类型由 spec 推导；`204` 是成功 | 每个页面自己 `fetch` / 自己判信封 |
| **幂等键** | 只有 4 条 `KEYED` 端点需要；键在"用户按下按钮"时取，**同一草稿的重试复用**，草稿一改就作废 | 每次请求新生成（重试 = 两条评论）；或让请求层自动加（掩盖"这个端点要不要幂等"） |
| **登录闸门** | **动作级**：`await requireLogin()`，弹浮层并阻塞到用户给出结果；登录成功后**这一次点击的意图继续执行** | 页面级跳登录页；浮层关掉就丢了这次操作 |
| **页面级身份态**（2026-10-26 新增，收藏 / 历史） | **整页内容都属于"我"**时（匿名请求必然 401、没有"匿名可见的一部分"）：先 `await restoreSession()`，匿名渲染「登录后查看」+ 一个登录按钮，按钮本身走 `requireLogin()`；登录成功后 watcher 自动装载 | 进页面**自动弹**登录框 / 把 401 画成"空数据"（用户以为自己的收藏没了） |
| **写后对齐** | 乐观更新 → 失败回滚 → 成功后用**服务端权威值**再对齐一次（详情 / 资料重读） | 只做乐观、不回滚；或回滚后不回读（并行请求会把状态写回旧值） |
| **三态** | `null` = **本面不查**（≠ `false`）：`interaction` 整体为 null 是匿名；字段为 null 是这一维没查 | `?? false` 归一（关注按钮永远显示"关注"） |
| **429** | 吃 `Retry-After` 说人话（"N 秒后再试"），别只说"请求过于频繁" | 静默重试 / 立即重试 |
| **瞬态错误** | 页面级错误态给「重试」按钮（429 / 网络 / 5xx 都是瞬态） | 只给「回首页」，逼用户重进 |
| **不该重试的** | 400 / 401 / 403 / 404 是**确定失败**：停手并说清楚 | 循环重试把 429 也刷出来 |
| **本地存储** | 只有两处：`myspringtv.token`（`auth/token.ts`）与 `history:pending:{id}`（崩溃补偿） | 把页面状态塞 localStorage 冒充缓存 |
