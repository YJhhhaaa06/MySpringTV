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

> **已独立成篇**：[CURRENT_FRONTEND_GAPS.md](./CURRENT_FRONTEND_GAPS.md)（2026-10-27 拆出；登记台让出 40 KB 线）。
> 新增 GAP 只加在那里，ID 不回收；本文件只留「缺口状态指针」与门禁。
>
> 本窗口新增：**GAP-26** 图文详情不投币（M4/不做）· **GAP-27**「赞与转发」无端点（M3/不做）·
> **GAP-28** 列表图文无封面只能用首图（M1/等后端）· **GAP-29** 本仓首页推荐含图文（M4/有意为之）。

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
| 历史「筛选 / 搜索 / 记录开关」 | 不做 | **不渲染** tabs / 搜索框 / 「记录浏览历史」开关（GAP-20 / GAP-21，摆了也点不动） |
| 动态页「发布框 / 转发 / 热搜 / 分区 tabs」 | 不做 | 发布框（投稿页暂缓）、转发（无转发域）、热搜与社区中心（GAP-24）、类型筛选 tabs（GAP-23）**都不渲染**；右栏用「我关注的人」真数据填位 |
| 稍后再看批量管理（多选 / 清空 / 移入收藏夹） | 不做 | 只做单条移出；端点有（`DELETE /watch-later`、`PUT /watch-later/{id}/folder`）但 UI 本窗口未认领（**不是 GAP**） |
| 创作中心 / 投稿 / 图文编辑 | **已交付（2026-10-29）**：投稿（upload + JSON 清单）· 稿件管理 · 编辑（取回 → 覆盖 + 图文长按换序）· **封面裁剪 + 预览（2026-10-29 晚）** | 只渲染「视频投稿 / 图文投稿」两个 tab；4 个无域 tab 与标签 / 创作声明 / 活动 / 定时发布 / 合集 / 商业推广 / 草稿一律不渲染；稿件列表不渲染状态列（无审核域）与批量操作 |

- ★ 本仓**目前一个 fixture 都没有**：占位组件只靠文案 + 图标，不需要假数据。
  若将来真需要，只许放 `frontend/src/mock/*.fixture.ts`，且仅占位组件可 import（用 ESLint `no-restricted-imports` 拦死）。
- 判据：界面上任何数字，要么来自真接口，要么来自占位组件，**不许从 fixture 流进真卡片**。

---

## 六、门禁（四条命令 + 一条部署命令，都是 `frontend/` 下的 npm script）

```bash
cd frontend
npm run gen:api && git diff --exit-code src/types/api.d.ts   # 类型落后于后端 ⇒ 红
npm run typecheck                                            # vue-tsc -b（类型硬门禁）
npm run test                                                 # vitest：适配器 fallback + 请求层 + 进度上报规则 + 图文图片两态 + 投稿草稿规则
npm run build                                                # 多入口产物：dist/{index,search,video,opus,space,favorites,history,dynamic,creator}.html
npm run stage                                                # dist → ../src/main/resources/static（Spring 直接服务）
```

`npm run test` 跑 `src/**/*.test.ts`（node 环境，**无 DOM 依赖**），断言落在**业务口径**上，
不是"调用了哪个函数"：

| 文件 | 钉住的口径 |
|---|---|
| `api/adapters/format.test.ts` | 万/亿、`mm:ss`、相对时间、**图文详情的中文日期 `YYYY年M月D日 HH:mm`**、**后端空简介占位 `-` 视为空** |
| `api/adapters/{videoCard,hero,search,videoDetail,user,interaction,comment,space}.test.ts` | 各页 VM 的缺口 fallback（`playCount` 缺失 ⇒ `null`、图文不显示计数、三态 `null ≠ false`、评论无时间 ⇒ 空串…）；**POST → `/opus/{id}` / VIDEO → `/video/{id}` 的落点分流**；`toOpusDetail` 只换日期口径；空简介占位 `-` 视为空 |
| `api/opusImages.test.ts` | 图文详情图片区纯规则：**1/2/3+ 张的列数、>9 张的 +N 截断、循环切图、旋转只保留 0/90/180/270、输入控件不接快捷键** |
| `api/adapters/{favorites,history}.test.ts` | 我的夹 / 稍后再看卡片（`addedTime` **不冒充**发布时间）；历史角标三态（`已看/总长` / `已看完` / 无总长只给「看到」）、按本地日历日分组 |
| `api/adapters/{feed,relation}.test.ts` | 动态卡：`likeCount`/`commentCount` 缺失 ⇒ `null`、0 是真值、图文不显示播放量、`liked` 三态；关注/粉丝卡：`isFollowed=false` **不是**"没查"、空名字 ⇒ 未知用户 |
| `api/http.test.ts` | 信封 / 204 / `Retry-After` / 幂等键 / 数组 query；**multipart：`postForm` 的进度回调、不手设 `Content-Type`、XHR 超时 / 网络错误的不同业务码** |
| `api/uploadDraft.test.ts` | 投稿校验（标题 ≤50、视频简介 ≤1000、图文 ≤10000、分区 0~9、视频 .mp4 / 封面 .jpg·.png / 图片 .jpg·.png·.jpeg、**单文件** 50MB ——"合计 99MB"随 upload 迁移退役）；**上传计划顺序**（视频先于封面 / 图文封面在前、图片按草稿顺序）；**创建载荷**（视频清单完整；图文不带 `videoUrl`、无封面不带键、`imageUrls` 顺序即 sort）；`fileKey` 口径；**幂等键生命周期** |
| `api/upload.test.ts` | `AssetUploadPool`：**同一文件第二次不发请求且 URL 不变**（幂等指纹含 body，重传换 URL 会 409）、换文件/换角色/成功 `clear()` 后重新上传 |
| `api/creatorEdit.test.ts` | 编辑页纯规则：详情 → 草稿（`-` 视为空简介、图片顺序原样）、草稿 → **整份覆盖载荷**（视频恰好视频+封面、图文无 `videoUrl`）、文本校验与投稿同源（视频 1000 / 图文 10000）、`swapImages`（不改入参 / 越界原样）、脏判定（`isNew` 不算改动、只加首尾空白不算改动） |
| `api/coverCrop.test.ts` | 封面裁剪几何：`zoom=1` 取源图内最大 16:9 矩形 / 裁剪窗**恒 16:9 不越界** / `stageLayout` 铺满舞台 / 拖动 = 窗口反向移 `dx/scale` / **锚点缩放不动点** / 缩放夹 1..4 / 产物名幂等 |
| `api/adapters/creator.test.ts` | 稿件行：视频给时长与播放量、**图文播放量恒 `null`（行里画 —）**、落点 `/video` vs `/opus`、编辑入口恒 `/creator/edit/{id}`、分页 `total` 用契约值 |
| `common/watchLaterSession.test.ts` | 稍后再看页面内记忆：**同一 URL 记得、换 URL 不记得**、取消即忘、不同内容互不影响 |
| `player/progressReporter.test.ts` | 播放进度契约：45s 节奏、503 退避与**恢复**、停报条件、续播 `max`、本地补偿 TTL |

★ **能变红**是判据：2026-10-25 三次 mutation 实测 —— ①把列表 `playCountText` 编成 `'0'` ⇒ 卡片那条红；
②把三态 `followed` 归一成 `false` ⇒ 三态那条红；③删掉进度上报的"收到 200 回到 45s"分支
⇒ 退避恢复那条红。
**2026-10-26 再来三次**：④把历史 `progressPercent` 改成恒 `100` ⇒ 2 条红；⑤把稍后再看的
`dateText` 设成 `addedTime`（时间冒充发布日期）⇒ 1 条红；⑥把历史角标从「已看/总长」改成只显示总时长
⇒ 3 条红。还原后 122/122 绿。
**2026-10-26 晚再来一次**：⑦把 `isWatchLaterMarked` 改成只看 `marked.has(id)`（无视 URL 作用域）
⇒ **2 条红**（同 URL/换 URL 两条），还原后 **127/127** 绿。
**2026-10-27 再来一次**：⑧把 `playCountText` 改回恒 `null` ⇒ `videoCard.test.ts` **红 1 条**
（只跑该类：12 选中 1 红）；还原后全量 **141/141** 绿。
**2026-10-27 晚（动态 / 关注粉丝）**：⑨把动态卡的 `likeCount` 改成 `item.likeCount ?? 0`（`null` 被伪装成 0）
⇒ `feed.test.ts` **红 1 条**；⑩把关注列表的 `total` 改成 `list.length`（本页条数冒充总数）
⇒ `relation.test.ts` **红 1 条**；还原后全量 **158/158** 绿。
**2026-10-27（图文详情 / 落点）**：⑪ 把 `contentHref` 改成恒 `videoHref`（不再按 POST 分流）
⇒ `videoCard` / `feed` / `hero` **红 3 条**（`/video/92` vs `/opus/92` 等），其余 172 绿；
⑫ 把 `gridPreview` 改成"不截断、overflow 恒 0" ⇒ `opusImages.test.ts` **红 1 条**；还原后全量 **175/175** 绿。
（review 后补的 `isEditableTarget` / `cleanDescription` 4 条测试计入 175。）
**2026-10-28（创作中心投稿页）**：⑬ 把 `SubmitKeyHolder.keyFor` 改成"每次新生成"（不再按草稿指纹复用）
⇒ `uploadDraft.test.ts` **红 1 条**（同一草稿的重试复用）；还原后全量 **199/199** 绿。
**2026-10-29（编辑页 / 稿件列表）**：⑭ 把 `swapImages` 改成"只返回副本、不交换"
⇒ `creatorEdit.test.ts` **红 3 条**（交换 / 载荷顺序 / 脏判定）；⑮ 把稿件行的 `playCountText` 对图文也调
`formatCount`（`null` 被画成 `0`）⇒ `adapters/creator.test.ts` **红 1 条**；两次都还原后全量 **227/227** 绿。
**2026-10-29 晚（封面裁剪）**：⑯ `dragBy` 位移方向取反 ⇒ `coverCrop.test.ts` **红 2 条**（位移换算 / 拖到边缘夹住）；
⑰ `zoomAround` 改成"缩放后回原中心"（忽略锚点）⇒ **红 1 条**（锚点下像素不动）；
⑱ `COVER_ASPECT` 改成 `16/10` ⇒ **红 5 条**（内接矩形 / 居中 / 铺满舞台 / 锚点 / 导出比例）；三次还原后 **240/240** 绿。
★ 浏览器层也实测能红：初版 `CoverCropper` 漏写 `<img ref="image">` ⇒ 点「使用这张封面」无反应，
E2E 红 2 条（弹层不关 / 封面框无 blob 图）—— **真 bug**、非注入；修好后 `confirm` 补了"图未解码"的显式报错。

**真浏览器 E2E**（一次性脚本，放 `.tmp/`，**不入库**）：登录 / 互动 / 评论 / 进度 / 空间 /
**收藏 / 历史 / 稍后再看 / 动态·关注粉丝 / 图文详情 / 创作中心投稿**各一支，必须真接口真写（`node .tmp/<name>-e2e.mjs`，需 Chrome `--remote-debugging-port=9222`）。
★ 2026-10-26 起也可以直接对 **8080**（dist 上 Spring）跑，不必起 Vite —— 顺带验证转发与静态产物。
收藏页那支还覆盖写路径：点「取消收藏」→ 卡片 2→1、侧栏计数 −1、toast、**刷新后仍 1**、API 恢复后回 2。
稍后再看那支（`watchlater-e2e.mjs`）覆盖：匿名点卡片 → 登录浮层 → **登录后同一次点击续做** → 加入/取消 →
`/watchlater` 独立页出现 → 页面级移出 → 刷新后仍不在 → 播放页 ⋮ 菜单加入/取消 → 刷新后记忆重置；
跑完池子恢复原状（脚本挑"不在池中"的卡片，避免误删既有条目）。
⚠️ 脚本里的"撞上限流 ⇒ 等 8s 重试"是**真实现**，不是脚本噪声：2026-10-25 实测时限流确实咬到了正常操作
（当时登录桶 `20 / 2`，缓存 miss `×3`；**2026-10-26 用户已提到 `200 / 10`**，数值以 `application.yaml` 为准）。
脚本仍保留重试分支 —— 它同时验证了前端的 429 文案（`操作太频繁了，N 秒后再试`，吃 `Retry-After`）。

★ **2026-10-27 新增一支：搜索（GAP-10）** `search-gap10-e2e.mjs`（28 条断言，匿名即可跑）。
钉的是"用户能看见的东西 + 浏览器**实际发出的请求 URL**"：tab 激活态 / 结果数文案 / 卡片 id 与**顺序** /
排序条在不在 / URL 里有没有 `type`·`order` / 请求里有没有把非法值或 `POST+PLAY` 带出去。
期望值的 oracle 来自 **dev 库直查**（两组关键词的 latest 序与 play 序**刻意不同**）。
★ **变红实测**：把 `normalizeSearchFilters` 改成"图文不丢 PLAY" ⇒ 精确红 2 条（D 的 URL+请求、H 的请求），
其余 26 条不受影响；还原后 28/28 绿。⚠️ 首版有 3 条请求断言是**真空转**（`every()` 在空数组上恒真）⇒ 已加
"抓到请求数 > 0"的守门。

★ **2026-10-27 晚新增一支：动态页 + 空间关注/粉丝** `dynamic-relation-e2e.mjs`（登录态；Vite 或 8080 都能跑）。
钉的同样是"用户能看见的东西 + 浏览器实际发出的请求"：feed 请求只有 `page/pageSize`、12 张卡 / 第 2 页 8 张、
点赞后 `PUT /contents/93/like` + `GET …/like-count` 回读、关注按钮 `PUT /follows/3`、粉丝列表 11 人、
关注 tab 1 人、两处匿名态是「登录后查看」而不是 401 / 空列表；**跑完把点赞与关注都还原**（dev 库复查
`like_count=0`、233 只关注 1）。⚠️ `waitImages()` 必须加超时，否则一张图不 load 就让整支脚本静默挂住。

★ **2026-10-27 晚新增：图文详情 + 落点统一**（`.tmp/opus-explore.mjs` / `.tmp/opus-actions-e2e.mjs`，对 8080 dist 跑）：
钉的是两种图片态与"从任何入口都落同一页"：6 图九宫格 3 列（193.3×3）/ 点图进放大态（工具条
收起·查看大图·左/右旋转 + 大图 + 6 缩略图）/ lightbox / 收起回九宫格；纯文字详情无图片区；
搜索纯文字卡 `href=/opus/60` + `target=_blank`；首页无封面图文文字卡 `/opus/{id}`；收藏夹卡片仍
`/video/265`（GAP-16）→ 访问后**重定向 `/opus/265`**；播放页相关推荐 6 张均 `target=null`，点击后
URL→`/video/...` 且 CDP target 数不变（同标签 SPA）；图文详情点赞 0→1→0、评论发布（评论区与右侧
操作栏计数 1→0）、收藏弹层打开/关闭 —— **9/9 PASS**、无 console error，跑完还原。
⚠️ 多图夹具（测试 POST 260–265）跑完已删；E2E 依赖 `.tmp/media`（dev 媒体根的临时副本）+
`UPLOAD_ROOT`，**不在仓库**。
★ **review 后补**（未再跑 E2E）：评论框内 ↑↓←→/Esc 不被图库劫持（`isEditableTarget` 单测）；
lightbox 打开聚焦 + Tab 陷阱 + 关闭还焦点；缩略图切换 `scrollIntoView`；`/opus/{videoId}` 反向重定向
`/video/{id}`；lightbox 与既有弹层同层（遮罩不盖顶栏，非新回归）。

★ **2026-10-28 新增：创作中心投稿**（`.tmp/creator-e2e.mjs`，**68/68 PASS**；登录态；对 8080 dist 跑）：
钉的是匿名「登录后才能投稿」、只有视频/图文两个 tab、无域字段一律不渲染、分区 0~9、
真上传视频（7.4MB mp4 + 封面）与图文（2 图 + 封面）→ `/video/{id}` / `/opus/{id}`、请求带
`Idempotency-Key` + multipart boundary、文字字段不在 URL、跑完 DELETE 还原（详情 404）。
⚠️ 本机后端写 dev 媒体根 `D:/data/projects/MySpringTV/media` 被环境拒绝（`拒绝访问`）⇒ 该支用
`UPLOAD_ROOT=.tmp/e2e-media` 起后端；**生产默认值不变**（口径见 [前端](../architecture/frontend/前端.md) §八）。

★ **2026-10-29 新增三支：编辑闭环 / 稿件列表 / 视频编辑分支**（对 8080 dist 跑，headless Chrome 亦可）：
`creator-edit-e2e.mjs`（**15/15**）—— 真建 3 图图文 → 打开 `/creator/edit/{id}` 取回（标题 / 正文 / 图片顺序都对）
→ **长按 560ms**（真 `Input.dispatchMouseEvent`）第一张 ⇒ `aria-pressed=true` + 提示条、且此时**不交换**
→ 点第三张 ⇒ 第一/三张互换 → 「保存修改」→ 出现「修改已生效」→ **详情 API 复算新顺序**
→ 重进页面仍是新顺序 → 删除夹具。`creator-works-e2e2.mjs`（**7/7**）—— 列表行渲染、编辑链接形状、图文播放量画「—」、
点编辑真进编辑页且 id 对得上。`creator-edit-video.mjs`（**6/6**）—— 视频分支 `<video>` 预览 + 封面 + 标题回填 +
未改动时保存禁用 + 不渲染图片墙。另有 API 级 `edit-e2e.mjs`（**14/14**）：换序 / 文案整体替换 / 删图后物理文件 404 /
未改 URL 不换名 / 删除后 404。
⚠️ 两个坑（都踩过）：① **headless 视口只有 900px**，页面下半部分的按钮 `rect` 在视口外 ⇒ 直接按 rect 派发鼠标事件
等于点在空白处（表现为"保存没反应"的假失败）⇒ 必须先 `scrollIntoView`；② **封面必须走 `/uploads/cover`**，
拿一张 image 的 URL 顶上会被 `normalize` 判「封面地址不合法」400（媒体类型按目录校验）。

★ **2026-10-29 晚：封面裁剪 / 预览**（`.tmp/creator-cover-e2e.mjs`，**39/39**）：选封面自动进裁剪、拖动/缩放不露白边、
预览三格 16:9/16:9/16:10、真上传后**线上封面 = 1280×720**；编辑页重裁已上传封面后 URL 换新仍 1280×720；图文分支同；
跑完 DELETE 还原。⚠️ 后端要 `UPLOAD_ROOT=.tmp/e2e-media`（§七），jar 内静态资源须**重新 package**。

★ **静默机制**（进度上报 / 自动重试与退避 / 退出信号 / 崩溃补偿 / 幂等键 / 乐观更新 / 登录态时序）
的**实现口径、代价与覆盖矩阵**见 [客户端静默机制](../architecture/frontend/客户端静默机制.md) §五 ——
那里有一份**"明写没覆盖"清单**（幂等键复用、`restoreSession` 单飞、卸载收尾上报…），别当成已经验过。

---

## 七、环境前置（实测）

- Node `v24.15.0` / npm `11.12.1` / registry 已是 npmmirror；pnpm 未装（`corepack enable` 即可）。
- ★ 沙箱下**子进程建不了 TLS、Chrome 起不来** ⇒ `npm install` 与页面截图**各需一次 `danger-full-access` 授权**（建议合并在一条命令里）。
- 老前端：`src/main/resources/static/` 的 18 个文件已于 **2026-10-26 删除**（随 dist 上线同批，§十三）。
  该目录现在是**构建产物落点**（`npm run stage` 把 `frontend/dist` 拷进去，产物不入库），
  由 Spring 直接服务 `/`、`/search`、`/video/:id`、`/opus/:id`、`/space/:id`、`/favorites`、`/watchlater`、
  `/history`、`/dynamic`、`/creator`、`/assets/**`。
  旧 SPA 已冻结过（2026-10-25），删除不影响任何活代码。
- ⚠️ **agent 沙箱下后端写 dev 媒体根会被拒绝**（`D:/data/projects/MySpringTV/media` ⇒ `拒绝访问`）：
  跑真上传 E2E 时用 `UPLOAD_ROOT=.tmp/e2e-media` 起后端；**用户正常起后端不受影响**（媒体根默认值不变）。

---

## 八、开工顺序（重制窗口照此走）

> **进度（详见 git log 与各窗口交接）**：
> ①–⑦ 脚手架 / 首页 / 搜索 / 播放页 / 占位与登录 / UP 空间 / 生产静态转发（2026-10-25 ~ 26）✅
> ⑧–⑫ 收藏 / 稍后再看 / 历史 / 2560 校准（首页·搜索·播放页）（2026-10-26）✅
> ⑬–⑭ GAP-10 搜索筛选 + GAP-01 播放量 / 时长回填（2026-10-27 第五窗口）✅
> **⑮–⑰（2026-10-27 第六窗口）**：动态页 `/dynamic`（`GET /feed`，消费 `likeCount/commentCount`）✅ ·
> 空间「关注/粉丝」面板（`?tab=following|followers`）✅ · 播放页侧栏 411 + 历史页 1160 校准 ✅
> **⑱（2026-10-27 第七窗口）**：图文详情 `/opus/:id`（第 8 个入口；九宫格 ↔ 放大缩小平铺 + 评论）✅ ·
> `contentHref` 落点统一（POST→opus / VIDEO→video；收藏夹方案 A 重定向）✅ ·
> 纯文字图文网格卡（真简介；无简介 → 中性「图文动态」）✅ · 相关推荐同标签 SPA ✅
> **⑲（2026-10-28 第八窗口）**：创作中心投稿页 `/creator`（第 9 个入口；视频/图文真提交 + 上传进度 +
> 幂等键；4 个无域 tab 与无域字段一律不渲染；E2E 68/68）✅
> **后端契约批次 ✅（2026-10-29）**：① `POST /api/v1/uploads/{type}` 单文件上传（只落盘、不写库）；
> ② `POST /api/v1/contents` JSON 媒体清单创建；③ `PUT /contents/{id}` 的 `media` 整份覆盖
> （加图 / 删图 / 重排 / 补封面 / 清空；缺省 = 不动媒体）；④ 简介上限按类型（视频 1000 / 图文 10000，GAP-31）。
> 旧 multipart 发布端点保留为**兼容面**，业务逻辑与清单校验只有一套。
> **⑳（2026-10-29 第九窗口）**：**投稿页迁到 upload + JSON 清单**（重试不重传、进度按累计字节）✅ ·
> **稿件管理 `/creator/manage`**（借用 `GET /users/{id}.contentPage`；视频给播放量、图文画 —）✅ ·
> **编辑页 `/creator/edit/{id}`**（取回 → 换源 / 增删图 / 长按换序 → 整份覆盖；脏判定驱动保存）✅ ·
> `/creator/**` 深链转发 + 限流跳过 ✅ · 单测 199 → **227**；E2E 三支全绿（编辑 15/15、列表 7/7、视频分支 6/6）+ API 级 14/14。
> **㉑（2026-10-29 晚）**：**封面裁剪 + 预览**（投稿视频 / 投稿图文 / 编辑三处入口）✅ —— 选封面即进 16:9 裁剪
> （拖动 / 缩放 / 还原 → 1280×720 JPEG），预览按**真实展示位**三格（播放页 16:9 · 卡片 16:9 · 侧栏 16:10）；
> 编辑页可对**已上传封面**重裁。单测 227 → **240**；E2E `creator-cover-e2e` **39/39**。
> **明确不做**：动态详情页（本仓没有动态实体/转发域；图文详情已由 `/opus` 承接，视频动态仍进播放页）·
> 标签 / 创作声明 / 活动 / 定时发布 / 合集 / 商业推广 / 草稿与审核态（本仓无域或无语义）·
> **稿件删除**（`DELETE /contents/{id}` 是软删且不可恢复，要单独设计二次确认；本窗只用它清 E2E 夹具）。
> **其他候选**：空间「合集和系列 / 最近点赞」（无端点，GAP-15）· 首页 / 空间页 1707 残余像素差（低优先）·
> GAP-28 首图回填（**2026-10-29 拍板本窗不做**；保持文字卡面，不采用"上传时复制首图为封面"的写侧降级）。
> **设计沉淀**：前端应用口径在 [architecture/frontend/前端.md](../architecture/frontend/前端.md)；
> 视觉校准依据在 [视觉基准](../architecture/frontend/视觉基准.md)；
> 播放进度客户端行为**不在本篇**，以 [history-客户端契约](../architecture/domain/history/history-客户端契约.md) 为准。

1. `frontend/` 脚手架（Vue + TS + Vite + vue-router）→ 配 **多入口**（`vite.config.ts` 的 `build.rollupOptions.input`）+ 每个入口一个 router → `npm run gen:api` 出类型
2. 先做 `VideoCard` + `VideoGrid`（会立刻暴露 GAP-01）
3. 首页闭环 → 截一张 B 站首页对照 → 攒第一批契约批次
4. 播放页 → 搜索 → 用户空间 → 收藏 / 历史 → 动态 / 关注粉丝 → **创作中心投稿** →（下窗）编辑 / 后台

---

## 九、后端期结转给本专项的两件事（原登记台已冻结，摘在此）

| 来源 | 事项 | 处置 |
|---|---|---|
| `F-15`（2026-10-15 清出） | **互动态维度常量**：后端返回的 `interaction` 是正确契约，缺的是前端「哪个面读哪几维」 | 重制时**先把常量加回**：`/contents/recommended` → `{关注}`；`/contents`（搜索）→ **0 维**；`/feed` 与 `/users/{id}` → `{赞}`；详情 → 三维。表见 [favorite.md §六](../architecture/domain/favorite/favorite.md)。⚠️ 三态：`null` = **本面不查**（≠ `false`）；匿名时整个 `interaction` 才是 `null` |
| `T61`（`R-23` 搁置） | 旧 SPA 是否留作「接口消费样例」 | **已关闭**（2026-10-25 用户复核：旧前端简陋、功能未跟进、接口规范化后完全不可用，**无参考价值**）⇒ 不留样例；**18 个文件已于 2026-10-26 删除**（§十三）。`T61` 不再排期 |

---

## 十、视觉基准（**已独立成篇**，2026-10-26）

> 见 **[architecture/frontend/视觉基准.md](../architecture/frontend/视觉基准.md)**：
> B 站 2026-10-25 匿名实拍 + 2026-10-26 登录态 2560 参考图矩阵（**150% 缩放 = 1707 CSS 视口**）、
> 2026-10-26 晚**逐页实测与校准规则**（首页 1408 / 搜索 1500 六列 / 播放页 1565）、
> **2026-10-27 晚补**（播放页侧栏 350→411 来自 B 站线上 CSS、历史页 1160、动态页 1336 = 78vw）、
> 我们的产物对照图、复算命令与 11 条截图坑。
> 图不入库（`.screenshots/` 在 `.gitignore`）；本文件只留指针，避免登记台撞 40 KB 线。

---

## 十一、工具链（**已落地为 `frontend/`**；版本与脚本以 `frontend/package.json` + README 为准）

- Vue 3.5.44 · Vite 8.3.5 · vue-router **4.x（不升 5：未勘察的新 major，peer 还挂 pinia）** ·
  typescript **5.9.3（必须钉 5.x）** · vitest 5.0.5 · openapi-typescript 7.13.0。
- ★ **唯一的坑（必踩）**：`openapi-typescript@7.13` 的 peer 是 `typescript@^5.x`，create-vite 模板默认 TS 6.0.3
  ⇒ `npm i -D openapi-typescript` **ERESOLVE**（`npx` 能跑通但掩盖问题）。修法：先 `npm i -D typescript@~5.9.0`。
- **中间件**：`3306` 是本机 MySQL（**不是**容器 `springbook-mysql`，别去抢端口）；`6379` / `5672` 是
  `docker start redis` / `rabbitmq` 拉起的容器 —— 起后端前确认三个端口都在。

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
| `opus.html` | `/opus/:id`（图文详情；图片两态是页面内状态，无路由） | `GET /contents/{id}` + 评论 + 点赞/收藏（**无投币**，GAP-26） |
| `search.html` | `/search?keyword=java`（tab / 排序用 query） | `GET /contents?keyword=` |
| `space.html` | `/space/:id`（tab / 翻页 / 夹 / 关注粉丝用 query） | `GET /users/{id}` + `GET /users/{id}/favorite-folders` + `GET /favorite-folders/{id}/items` + `GET /users/{id}/following|followers` |
| `favorites.html` | `/favorites`（`?folder=` / `?page=`）+ **`/watchlater`**（独立路由；旧 `?tab=later` 兼容） | `GET /favorite-folders` + `GET /favorites?folderId=` + `GET /watch-later` + `PUT /watch-later/{id}` + `DELETE /favorites`、`DELETE /watch-later` |
| `history.html` | `/history?page=` | `GET /history`（**只读**：无删除/清空端点，GAP-19） |
| `dynamic.html` | `/dynamic?page=` | `GET /feed`（**需登录**）+ `GET /users/{id}?pageSize=1` + `GET /users/{id}/following?pageSize=8` |
| `creator.html` | `/creator`（投稿）· `/creator/manage`（稿件管理）· `/creator/edit/:id`（编辑） | `POST /uploads/{type}` + `POST /contents`（JSON 清单）· `GET /users/{id}?page=`（稿件列表）· `GET /contents/{id}`（取回）· `PUT /contents/{id}`（整份覆盖） |

> 2026-10-26 已落地 **六个入口**；**2026-10-27 第七个 `dynamic`、第八个 `opus`**；
> **2026-10-28 第九个 `creator`** —— 现在共 **九个**：
> `index` / `search` / `video` / `opus` / `space` / `favorites` / `history` / `dynamic` / `creator`。
> `video` 已从"只读"补成完整页（互动 / 评论 / 进度）；创作中心顶栏「投稿」与「创作中心」现在都指向 `/creator`。
> `/watchlater` 是 `favorites.html` 内的**独立路由**（转发到同一入口），**不是**独立入口。
> `/opus` 是**独立入口**（不是 `video.html` 内的路由）：B 站图文是独立文档、独立新标签。

- **跨应用**一律 `<a href="/video/77" target="_blank" rel="noopener">` —— 新标签、可中键、可复制链接，与 B 站一致。
  **唯一例外**：播放页相关推荐同标签 SPA（`new-tab=false` + `router.push`，见 §十四）。
- **应用内**用 vue-router（每个入口一个实例，`createWebHistory`），切 tab 只改 URL、不重载。
- 页面之间**不共享内存状态**；`AppHeader` 等组件在每页各自 import（Vite 抽公共 chunk）。登录态从 `localStorage`
  读 `token`（后端是 `Authorization: Bearer`，不是 Cookie），每页自己请求 `GET /users/me` 渲染头像。
- ⚠️ **history 模式要服务端 fallback**：`/video/77` 必须落到 `video.html`。两条路 ——
  ① 加静态转发（URL 与 B 站同构）；② 退一步保留 `?id=`（零后端改动、URL 不像 B 站）。
  **决策：先走 ①**，被拦再退 ②。★ 它不是纯配置改动：转发走 `ViewController`（不进登记簿 / spec），
  dist 资源目录 `/static/` → `/assets/` 要同步进限流与静态白名单（见 §十三）。
  ★ **状态（2026-10-26）：① 已落地**（`WebMvcConfig#addViewControllers`，forward 不是 redirect）。`WebMvcConfig#addViewControllers` 用 **forward**（不是 redirect）
  把 `/`、`/search`、`/video/{id}`、**`/opus/{id}`**、`/space/{id}`、`/favorites`、**`/watchlater`**、`/history`、
  **`/dynamic`**、**`/creator`**（含 2026-10-29 的 `/creator/manage`、`/creator/edit/{id}`）、`/placeholder/{feature}` 转到入口 HTML；
  **不用 `@Controller`**（那会进端点登记簿 / spec，还要为默认拒绝开 `@Public` —— 它只是页面导航）；
  dist 由 `npm run stage` 落到 `src/main/resources/static/`（产物不入库）；`/assets/` 与 HTML 外壳进限流跳过清单。
  `mvnw clean` 后实测：九个入口 200、`/assets/*.js` 200、`/api/v1/**` 200、旧 `/static/**` 404。
- ✅ **dev 侧 fallback 已落地**：`vite.config.ts` 的 `htmlHistoryFallback()` 把 `/search` → `search.html`、
  `/video/<digits>` → `video.html`、**`/opus/<digits>` → `opus.html`**、`/space/<digits>` → `space.html`、
  `/favorites` → `favorites.html`、
  **`/watchlater` → `favorites.html`**、`/history` → `history.html`、**`/dynamic` → `dynamic.html`**、
  **`/creator` → `creator.html`**、`/placeholder/*` → `index.html`，并挂在 `configureServer` **与** `configurePreviewServer` 上 ⇒ `npm run preview` 也能验打包产物。
  它只接管 `Accept: text/html` 的导航请求（`/api`、`/upload`、带扩展名的资源全部放行）。
  ★ **生产转发要补的正是这份映射表**（九入口 + `/watchlater`，已落在 `WebMvcConfig#addViewControllers`，两处必须同步改）。
- ⚠️ **dev 代理要转发两段**：`/api` **和** `/upload` —— 封面是后端静态路径（`/upload/cover/…`），只代理 `/api` 会让所有图片 404。
- **明确接受的代价**：跨应用整页刷新、公共请求（`/users/me`）每次进应用重复一次；**换来**与参考实现同构。

⚠️ **一个真实差异**：B 站是 `/video/BV1xx`，本仓内容 id 是数据库自增（`/video/77`）。路径形状可仿，
**id 语义仿不了**；重制期不改契约，将来若要短链 / 公开 id，单独开 GAP。

---

## 十三、迁移收尾：旧 SPA 删除（**已收口 2026-10-26**）

三个提交：`b79dfe1`（静态转发 + 白名单）· `a916959`（幂等过滤器安全方法直通）· `09ae36a`（dist 上 Spring + 删 18 文件）。
当前有效结论（细节以代码 + 契约测试为准，不再复述当时的前置清单）：

- dist 落 `src/main/resources/static`（产物不入库）；`/`、`/search`、`/video/:id`、**`/opus/:id`（2026-10-27）**、
  `/space/:id`、`/favorites`、`/watchlater`、`/history`、**`/dynamic`（2026-10-27）**、**`/creator`（2026-10-28）**、
  **`/creator/manage` 与 `/creator/edit/:id`（2026-10-29）**、`/assets/**` 全 200，旧 `/static/**` 404；
- 转发用 **`ViewController` + forward**（不进端点登记簿 / 不进 spec / 不需要 `@Public` / 地址栏 URL 不变）；
  dev 侧 `vite.config.ts` 是**同一张映射表**，两处必须同步改；
- `/assets/`、HTML 外壳在 `RateLimitFilter.SKIP_EXACT` / `SKIP_PREFIXES` 里（否则 **429 掉 CSS**）；
- `StaticForwardingContractTests`（断言 `forwardedUrl`，而非"最终 200"）+ `RateLimitFilterContractTests`
  的零扣费探针钉住上面两条；新增入口时**两处都要加**（2026-10-27 加 `/dynamic`、`/opus`；2026-10-28 加 `/creator`；
  2026-10-29 加 `/creator/manage`、`/creator/edit/{id}` 即如此 —— 后者在限流侧靠 `SKIP_PREFIXES` 的 `/creator/` 一举覆盖）。

**删除时不做的事**：不动 `src/main/resources/db/migration/**`（逐字节冻结）、不动 `old-project/`（行为判据）。

---

## 十四、客户端行为口径（2026-10-25 第二窗口沉淀；下个窗口照此写，别重新发明）

这一节只放**跨页面复用**的约定。页面级视觉细节进 [architecture/frontend/前端.md](../architecture/frontend/前端.md)，
播放进度的节奏 / 退避 / 崩溃补偿进 [history-客户端契约](../architecture/domain/history/history-客户端契约.md)。

| 口径 | 做法 | 反例（别这么写） |
|---|---|---|
| **写动词** | `api/http.ts` 的 `getJson/postJson/putJson/deleteJson`（multipart 用 **`postForm`**），返回类型由 spec 推导；`204` 是成功 | 每个页面自己 `fetch` / 自己判信封 |
| **multipart 上传**（2026-10-28） | 投稿走 `postForm`（XHR；**10 分钟**超时；`upload.onprogress`）；信封 / Bearer / 204 / `Retry-After` 与四动词**共用 `parseEnvelope`**；文字字段进 `FormData`（1 万字简介不进 URL）；**不手设 `Content-Type`** | 手拼 boundary / 把文本字段拼进 query / 页面自己判信封 |
| **幂等键** | 只有 4 条 `KEYED` 端点需要；键在"用户按下按钮"时取，**同一草稿的重试复用**，草稿一改就作废（投稿页用 `SubmitKeyHolder`） | 每次请求新生成（重试 = 两条评论）；或让请求层自动加（掩盖"这个端点要不要幂等"） |
| **登录闸门** | **动作级**：`await requireLogin()`，弹浮层并阻塞到用户给出结果；登录成功后**这一次点击的意图继续执行** | 页面级跳登录页；浮层关掉就丢了这次操作 |
| **页面级身份态**（2026-10-26 新增；投稿页 2026-10-28） | **整页内容都属于"我"**时（匿名请求必然 401、没有"匿名可见的一部分"）：先 `await restoreSession()`，匿名渲染「登录后查看 / 登录后投稿」+ 一个登录按钮，按钮本身走 `requireLogin()`；登录成功后 watcher 自动装载。**表单用 `v-show` 保持挂载** ⇒ 登录后草稿不丢 | 进页面**自动弹**登录框 / 把 401 画成"空数据"（用户以为自己的收藏没了）/ 用 `v-if` 卸载表单导致草稿丢 |
| **稍后再看状态**（2026-10-26 晚新增） | **只记本次页面**：加入成功时把 `(contentId, pathname+search)` 记进模块级 `reactive(Map)`；同一 URL 内再点=取消；刷新 / 换路由即忘（`common/watchLaterSession.ts`） | 每张卡片回源 `GET /watch-later` 查状态（N 次请求）/ 把初始"不知道"画成"未加入"并禁用 / 落 localStorage |
| **跨应用落点**（2026-10-27） | 唯一规则 `contentHref(id, isPost)`：`POST → /opus/{id}`、`VIDEO → /video/{id}`；卡片默认 `target="_blank"`；**只有播放页相关推荐**传 `new-tab=false` + `router.push`（同标签 SPA） | 各处散写 `videoHref`（图文落播放页）/ 相关推荐也 `_blank` / 把 `/opus` 做成 `/video` 的应用内 tab |
| **收藏夹图文**（2026-10-27，方案 A） | `FavoriteItemVO` 没有 `type` ⇒ 卡片先落 `/video/{id}`；播放页取到详情发现 `isPost` ⇒ `location.replace('/opus/{id}')` | 每张收藏卡回源详情查 type（N 次请求）/ 把图文画成视频卡还假装正常 |
| **纯文字图文卡**（2026-10-27） | 无封面 ⇒ 文字卡面用**真 `description`**（3 行）；没简介 → 中性「图文动态」；有图但无封面也只能文字（GAP-28，不逐卡回源） | 用 TV 图标占位 / 编一段假文字 / 为卡面回源详情取首图 |
| **图文详情两态**（2026-10-27） | 默认九宫格（1/2/3+ 列）；点图 → 放大态（工具条 + 大图 + 缩小平铺缩略图）；收起回网格；同页可切换，**与入口无关** | 按来源做两套页面 / 图片态放路由参数 / 放假图或空图块 |
| **写后对齐** | 乐观更新 → 失败回滚 → 成功后用**服务端权威值**再对齐一次（详情 / 资料重读） | 只做乐观、不回滚；或回滚后不回读（并行请求会把状态写回旧值） |
| **三态** | `null` = **本面不查**（≠ `false`）：`interaction` 整体为 null 是匿名；字段为 null 是这一维没查 | `?? false` 归一（关注按钮永远显示"关注"） |
| **429** | 吃 `Retry-After` 说人话（"N 秒后再试"），别只说"请求过于频繁" | 静默重试 / 立即重试 |
| **瞬态错误** | 页面级错误态给「重试」按钮（429 / 网络 / 5xx 都是瞬态） | 只给「回首页」，逼用户重进 |
| **不该重试的** | 400 / 401 / 403 / 404 是**确定失败**：停手并说清楚 | 循环重试把 429 也刷出来 |
| **本地存储** | 只有两处：`myspringtv.token`（`auth/token.ts`）与 `history:pending:{id}`（崩溃补偿） | 把页面状态塞 localStorage 冒充缓存 |
