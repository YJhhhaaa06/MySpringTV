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
> ★ **2026-10-27 更正**：`GAP-01/05/07` 已进入 `src/main` 约 30 处（作交叉引用），`GAP-10` 沿用 ——
> 编号可作"为什么有这段代码"的锚点，但**不得**据此新开编号空间（新编号只能加在本表）。

| ID | 类型 | 页面·组件 | 期望 | 现状 | 谁适配 | 临时表现 | 后端改动点 | 状态 |
|---|---|---|---|---|---|---|---|---|
| GAP-01 | M1 字段缺 | 首页 / VideoCard | 列表带 `playCount`（动态卡片另带 `likeCount` / `commentCount`） | `ContentVO` 已按面平铺三个可空计数 | ✅ 前后端均已接 | ✅ 封面左下播放量角标（图文不显示，见 GAP-05）；like/comment 由**动态页**（`/feed`）消费（2026-10-27） | ✅ 后端 2026-10-26 已交付（矩阵见 [缓存与数据一致性 §5.3](../architecture/tech/cache/缓存与数据一致性.md)）；前端 2026-10-27 全部接完 | **已销账** |
| GAP-02 | M3 端点缺 | 首页 / ChannelWall | 分区列表 | 无 `GET /categories` | 前端适配 | **常量表 18 项** | 无（不补） | 长期 |
| GAP-03 | M4 语义异 | 播放页 / DanmakuLayer | 弹幕流 | 无弹幕域（无表/无端点） | 前端适配 | **不做 + 占位角标** | 无 | 不做 |
| GAP-04 | M1 字段缺 | 首页 / VideoCard | 弹幕数角标 | 同 GAP-03 | 前端不做 | **不显示该数字** | 无 | 不做 |
| GAP-05 | M4 语义异 | 首页·播放页 / NumberText | 图文 `play_count` 语义 | 列叫 play，图文实为「浏览」，且对图文**结构性恒 0**（GAP-10） | 前端适配 | **图文不渲染计数**（画「浏览 0」＝把"没接管道"说成"没人看"）；视频按「播放」 | 无 | 长期 |
| GAP-06 | M3 端点缺 | 首页 / VideoGrid | 推荐流分页（无限加载） | `GET /contents/recommended` **固定 12 条、无 page 参数**；`GET /contents` 是**搜索专用**（`keyword` 必填，空白即 400 `输入不能为空`） | 后端补（**已拍板：等后端**） | 只渲染 12 条；**不做无限滚动** | 给 recommended 加分页参数，或新增推荐分页端点 | 待补（等后端） |
| GAP-07 | M2 形状异 | 全部页面 / `api/adapters/*` | 生成类型与运行时一致 | spec 已用 3.1 的 `anyOf: [原类型, {type: "null"}]` 标出已登记的可空字段（`coverUrl` / `durationSeconds` / `interaction` / 三态 / 失效占位等） | ✅ 前后端均已接 | adapter 仍按 `null` 兜底（运行时真会显式 null） | ✅ 已落地：`OpenApiConfig#nullableVoFieldsCustomizer` + `NULLABLE_VO_FIELDS` 注册表（只登记**确有 null 分支**的字段）；spec 快照已重生成 | **已销账（2026-10-27）** |
| GAP-08 | M1 字段缺 | 顶栏 / 播放页侧栏 / 头像 | 头像 URL | `UserInfoVO` / `ProfileVO` 都**没有头像字段**，也没有头像上传域 | 前端适配（不做头像域） | **昵称首字圆形头像**（渐变底，B 站观感近似） | 无（不补） | 长期 |
| GAP-09 | M3 端点缺 | 首页 / HeroBanner | 运营 banner 位（B 站首页大轮播是广告/活动位） | 无任何 banner/运营端点 | 前端适配 | 用 `recommended` 前 3 条**有封面的**做「推荐橱窗」轮播（真封面真标题，非假图）；无内容时不渲染轮播 | 无（不补） | 长期 |
| GAP-10 | M3 端点缺（参数） | 搜索页 / tabs·排序条 | `type` 筛选（视频/图文）+ 排序（播放/最新） | 原只有 `keyword/page/pageSize` | **前后端均已补** | —（已可筛可排） | ✅ 已落地（2026-10-27）：`?type=VIDEO\|POST&order=LATEST\|PLAY`，两个**可选枚举**（大小写敏感，非法值 400 + `errors[field]`）；类型过滤与 `count` **共用 mapper 片段**（total 同源）；`PLAY` = `play_count DESC, id DESC`。⚠️ 图文 `play_count` 结构性恒 0 ⇒ 前端**图文 tab 不渲染**「最多播放」，后端**不设特例**（照收不报错）。⚠️ 与 `recommended?type=1\|2` 的**数字**口径刻意不一致（§2.5 要求语义字符串；那套是 TV 遗留、前端未消费） | **已销账（2026-10-27）** |
| GAP-11 | M3 端点缺 | 首页 / ChannelWall「热门」 | 热门榜 | 没有排行榜端点（`recommended` 是推荐流，不是排行） | 前端不做 | 灰态占位 → `/placeholder/popular` | 无 | 不做 |
| GAP-12 | M1 字段缺 | 播放页 / 评论区 | 评论发布时间 | `CommentVO` 没有 `createTime`（只有 `commentId` 可推先后） | 后端补（P3） | **不显示时间**（不写"刚刚"顶上） | 评论 VO 加 `createTime` | 待补 |
| GAP-13 | M3 参数缺 | 播放页 / 评论排序 | 热度 / 时间排序 | `GET /contents/{id}/comments` 只有 `page/pageSize`，固定按 `comment_id` 升序 | 前端适配 | **不渲染排序条**；发布后跳到末页并高亮 | 加 `order` 参数 | 长期 |
| GAP-14 | M1 字段缺 | 空间页 / 头部 | banner 图 / 签名 / 生日 / 地区 | `ProfileVO` 只有 id、username、两个计数、`contentPage` | 前端适配（不做资料域） | **低饱和渐变 banner**；资料卡只放真计数 | 无 | 长期 |
| GAP-15 | M3 端点缺 | 空间页 / tabs | 动态 / 合集和系列 / 最近点赞 | 无对应端点（`/feed` 是「我关注的动态」，不是某人的） | 前端不做 | **不渲染这三个 tab**（不摆点不动的） | 无 | 不做 |
| GAP-16 | M1 字段缺 | 空间页 / 收藏条目卡 | 条目要带 `authorId` / `type` / `durationSeconds` / 发布时间 | `FavoriteItemVO` 只有 contentId/title/coverUrl/authorName/favoriteTime/invalid | 前端适配 | 作者渲染成**纯文本**（不做死链）、不显示时长与日期、`invalid` 打「已失效」灰角标 | 无 | 长期 |
| GAP-17 | M1 字段缺 | 空间页 / 收藏夹卡 | 收藏夹封面（B 站是四宫格缩略图） | `PublicFavoriteFolderVO` 只有 id/name/itemCount | 前端适配 | 纯文字卡片（名字 + 条目数） | 无 | 长期 |
| GAP-18 | M4 语义异 | 播放页 / 播放进度 | 匿名观看也计播放量（B 站口径） | `/play`、`/progress`、`/flush` **全部 `@RequiresLogin`** | 前端适配 | 未登录**整段不接**进度上报（不打一串 401） | 若要做需匿名计数端点 | 长期 |
| GAP-19 | M3 端点缺 | 历史页 / 卡片 | 删除单条历史 / 清空历史（B 站历史页有） | `HistoryController` 只有 list / position / flush / report / play **五条**（`F-27` ①：删除/清空本期刻意不做） | 前端不做 | **不渲染删除与清空按钮**（有按钮但点不动 = 撒谎） | 若要做：`DELETE /api/v1/history?contentIds=` + `DELETE /api/v1/history`（清空） | 待补（等后端） |
| GAP-20 | M3 参数缺 | 历史页 / 工具栏 | B 站历史页的 tabs（综合 / 视频 / 直播 / 专栏）+「搜索标题/up 主」+ 更多筛选 | `GET /history` 只有 `page/pageSize`；`HistoryItemVO` **连 `type` 都没有**（前端筛选也做不了） | 后端补（P3） | **不渲染 tabs / 搜索框 / 更多筛选**（只按最后观看倒序） | `/history` 加 `type`（视频/图文）与 `keyword`；⚠️ 直播 / 专栏**无对应域，永远不做** | 待补（等后端） |
| GAP-21 | M3 端点缺 | 历史页 / 记录开关 | 「记录浏览历史」开关（B 站可暂停记录） | 无用户设置域（无 `user_settings` 表 / 端点） | 前端不做 | **不渲染开关**（历史始终记录；不假装能关） | 若要支持：用户级开关端点 + 写路径跳过记录 | 长期 |
| GAP-22 | M3 端点缺 | 创作中心 / 图文编辑 | 「把原图文取回、改完**原位覆盖**」（尤其增删多张图） | 现有：`PUT /contents/{id}` 改标题/简介 ✅；`PUT /contents/{id}/media?type=2&sort=` **替换已有某张** ✅；`DELETE /contents/{id}/media?type=2&url=` 删一张 ✅。**没有追加图片 / 整份图片列表覆盖**，也没有草稿态；且删图会 `compactImageSort` 前移 ⇒ 不能靠 `sort` 稳定追加 | 后端补（创作中心暂不做） | **不渲染创作中心**；只记设计意图：原内容预填 + 原位覆盖（**不**新建内容 —— 新建会换 id、丢评论/点赞） | `PUT /contents/{id}/images`（整份列表）或 `POST /contents/{id}/images`（追加）；草稿态可选 | 待补（等后端，未排期） |
| GAP-23 | M3 参数缺 | 动态页 / tabs | 动态流按内容类型筛选（B 站：全部 / 视频投稿 / 追番追剧 / 专栏） | `GET /feed` 只有 `page/pageSize`；`ContentVO.type` 有，但端点不收 `type` | 后端补（P3） | **不渲染 tabs**（只按时间倒序；不拿"只筛当前页"冒充筛选） | `/feed?type=VIDEO\|POST`（与搜索的 GAP-10 同口径；追番/专栏无域，永远不做） | 待补（等后端） |
| GAP-24 | M3 端点缺 | 动态页 / 右栏 | 「bilibili 热搜」榜 + 社区中心运营位 | 没有热搜 / 运营端点（与 GAP-09 的 banner 同类） | 前端不做 | 右栏换成**我关注的人**（真接口 `/users/{me}/following`），顺带接上新交付的关注列表页 | 无（热搜属运营域，本仓不做） | 不做 |
| GAP-25 | M1 字段缺 | 空间·关注/粉丝 | 用户卡的**头像 / 签名 / 等级 / 分组** | `FollowUserVO` 只有 `userId / username / isFollowed / isSelf` | 前端适配（不做资料/头像域） | 首字头像 + 名字 + 关注按钮；**不渲染**签名 / 分组 / 搜索 / 排序 / 批量操作（B 站那句「这个人很神秘」是 B 站自己的默认签名，写上去就是编） | 无（头像域见 GAP-08） | 长期 |

> ★ **稍后再看没有"是否已在池中"的查询端点**（[favorite.md §六](../architecture/domain/favorite/favorite.md) 红线）：
> 本仓**不登记 GAP**、也不逐卡回源 —— 前端用 URL 作用域的内存记忆复刻 B 站实测行为（§十四）。
> 这是**刻意不做**，不是缺口。

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
| 历史「筛选 / 搜索 / 记录开关」 | 不做 | **不渲染** tabs / 搜索框 / 「记录浏览历史」开关（GAP-20 / GAP-21，摆了也点不动） |
| 动态页「发布框 / 转发 / 热搜 / 分区 tabs」 | 不做 | 发布框（投稿页暂缓）、转发（无转发域）、热搜与社区中心（GAP-24）、类型筛选 tabs（GAP-23）**都不渲染**；右栏用「我关注的人」真数据填位 |
| 稍后再看批量管理（多选 / 清空 / 移入收藏夹） | 不做 | 只做单条移出；端点有（`DELETE /watch-later`、`PUT /watch-later/{id}/folder`）但 UI 本窗口未认领（**不是 GAP**） |
| 创作中心 / 投稿 / 图文编辑 | **暂缓**（2026-10-26 用户拍板"复杂，暂不跟进"） | 顶栏「投稿」仍走 `/placeholder/upload`；「编辑图文 = 取回原稿原位覆盖」等后端补"追加图片"（GAP-22） |

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
npm run build                                                # 多入口产物：dist/{index,search,video,space,favorites,history,dynamic}.html
npm run stage                                                # dist → ../src/main/resources/static（Spring 直接服务）
```

`npm run test` 跑 `src/**/*.test.ts`（node 环境，**无 DOM 依赖**），断言落在**业务口径**上，
不是"调用了哪个函数"：

| 文件 | 钉住的口径 |
|---|---|
| `api/adapters/format.test.ts` | 万/亿、`mm:ss`、相对时间 |
| `api/adapters/{videoCard,hero,search,videoDetail,user,interaction,comment,space}.test.ts` | 各页 VM 的缺口 fallback（`playCount` 缺失 ⇒ `null`、图文不显示计数、三态 `null ≠ false`、评论无时间 ⇒ 空串…） |
| `api/adapters/{favorites,history}.test.ts` | 我的夹 / 稍后再看卡片（`addedTime` **不冒充**发布时间）；历史角标三态（`已看/总长` / `已看完` / 无总长只给「看到」）、按本地日历日分组 |
| `api/adapters/{feed,relation}.test.ts` | 动态卡：`likeCount`/`commentCount` 缺失 ⇒ `null`、0 是真值、图文不显示播放量、`liked` 三态；关注/粉丝卡：`isFollowed=false` **不是**"没查"、空名字 ⇒ 未知用户 |
| `api/http.test.ts` | 信封 / 204 / `Retry-After` / 幂等键 / 数组 query |
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

**真浏览器 E2E**（一次性脚本，放 `.tmp/`，**不入库**）：登录 / 互动 / 评论 / 进度 / 空间 /
**收藏 / 历史 / 稍后再看 / 动态·关注粉丝**各一支，必须真接口真写（`node .tmp/<name>-e2e.mjs`，需 Chrome `--remote-debugging-port=9222`）。
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

★ **静默机制**（进度上报 / 自动重试与退避 / 退出信号 / 崩溃补偿 / 幂等键 / 乐观更新 / 登录态时序）
的**实现口径、代价与覆盖矩阵**见 [客户端静默机制](../architecture/frontend/客户端静默机制.md) §五 ——
那里有一份**"明写没覆盖"清单**（幂等键复用、`restoreSession` 单飞、卸载收尾上报…），别当成已经验过。

---

## 七、环境前置（实测）

- Node `v24.15.0` / npm `11.12.1` / registry 已是 npmmirror；pnpm 未装（`corepack enable` 即可）。
- ★ 沙箱下**子进程建不了 TLS、Chrome 起不来** ⇒ `npm install` 与页面截图**各需一次 `danger-full-access` 授权**（建议合并在一条命令里）。
- 老前端：`src/main/resources/static/` 的 18 个文件已于 **2026-10-26 删除**（随 dist 上线同批，§十三）。
  该目录现在是**构建产物落点**（`npm run stage` 把 `frontend/dist` 拷进去，产物不入库），
  由 Spring 直接服务 `/`、`/search`、`/video/:id`、`/space/:id`、`/favorites`、`/watchlater`、`/history`、
  `/dynamic`、`/assets/**`。
  旧 SPA 已冻结过（2026-10-25），删除不影响任何活代码。

---

## 八、开工顺序（重制窗口照此走）

> **进度（详见 git log 与各窗口交接）**：
> ①–⑦ 脚手架 / 首页 / 搜索 / 播放页 / 占位与登录 / UP 空间 / 生产静态转发（2026-10-25 ~ 26）✅
> ⑧–⑫ 收藏 / 稍后再看 / 历史 / 2560 校准（首页·搜索·播放页）（2026-10-26）✅
> ⑬–⑭ GAP-10 搜索筛选 + GAP-01 播放量 / 时长回填（2026-10-27 第五窗口）✅
> **⑮–⑰（2026-10-27 第六窗口）**：动态页 `/dynamic`（`GET /feed`，消费 `likeCount/commentCount`）✅ ·
> 空间「关注/粉丝」面板（`?tab=following|followers`）✅ · 播放页侧栏 411 + 历史页 1160 校准 ✅
> **下一窗口候选**：① 动态详情页（B 站有独立文档；本仓无端点，需先开 GAP）·
> ② 空间「合集和系列 / 最近点赞」（无端点，GAP-15）· ③ 首页 / 空间页在 1707 的残余像素差（低优先）。
> **明确延后**：创作中心 / 投稿页（用户 2026-10-26 拍板"复杂，暂不跟进"）；
> 「编辑图文 = 取回原稿 → 原位覆盖」只记设计意图 + **GAP-22**（缺"追加图片"端点），不做页面。
> **设计沉淀**：前端应用口径在 [architecture/frontend/前端.md](../architecture/frontend/前端.md)；
> 视觉校准依据在 [视觉基准](../architecture/frontend/视觉基准.md)；
> 播放进度客户端行为**不在本篇**，以 [history-客户端契约](../architecture/domain/history/history-客户端契约.md) 为准。

1. `frontend/` 脚手架（Vue + TS + Vite + vue-router）→ 配 **多入口**（`vite.config.ts` 的 `build.rollupOptions.input`）+ 每个入口一个 router → `npm run gen:api` 出类型
2. 先做 `VideoCard` + `VideoGrid`（会立刻暴露 GAP-01）
3. 首页闭环 → 截一张 B 站首页对照 → 攒第一批契约批次
4. 播放页 → 搜索 → 用户空间 → 收藏 / 历史 → 动态 / 关注粉丝 →（可选）后台

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
| `search.html` | `/search?keyword=java`（tab / 排序用 query） | `GET /contents?keyword=` |
| `space.html` | `/space/:id`（tab / 翻页 / 夹 / 关注粉丝用 query） | `GET /users/{id}` + `GET /users/{id}/favorite-folders` + `GET /favorite-folders/{id}/items` + `GET /users/{id}/following|followers` |
| `favorites.html` | `/favorites`（`?folder=` / `?page=`）+ **`/watchlater`**（独立路由；旧 `?tab=later` 兼容） | `GET /favorite-folders` + `GET /favorites?folderId=` + `GET /watch-later` + `PUT /watch-later/{id}` + `DELETE /favorites`、`DELETE /watch-later` |
| `history.html` | `/history?page=` | `GET /history`（**只读**：无删除/清空端点，GAP-19） |
| `dynamic.html` | `/dynamic?page=` | `GET /feed`（**需登录**）+ `GET /users/{id}?pageSize=1` + `GET /users/{id}/following?pageSize=8` |
| `upload.html` | `/upload` | `POST /contents/videos`、`/contents/posts` |

> 2026-10-26 已落地 **六个入口**；**2026-10-27 第七个 `dynamic`** —— 现在共 **七个**：
> `index` / `search` / `video` / `space` / `favorites` / `history` / `dynamic`。
> `video` 已从"只读"补成完整页（互动 / 评论 / 进度）；`upload` 尚未建入口（灰态导航走 `/placeholder/*`）。
> `/watchlater` 是 `favorites.html` 内的**独立路由**（转发到同一入口），**不是**独立入口。

- **跨应用**一律 `<a href="/video/77" target="_blank" rel="noopener">` —— 新标签、可中键、可复制链接，与 B 站一致。
- **应用内**用 vue-router（每个入口一个实例，`createWebHistory`），切 tab 只改 URL、不重载。
- 页面之间**不共享内存状态**；`AppHeader` 等组件在每页各自 import（Vite 抽公共 chunk）。登录态从 `localStorage`
  读 `token`（后端是 `Authorization: Bearer`，不是 Cookie），每页自己请求 `GET /users/me` 渲染头像。
- ⚠️ **history 模式要服务端 fallback**：`/video/77` 必须落到 `video.html`。两条路 ——
  ① 加静态转发（URL 与 B 站同构）；② 退一步保留 `?id=`（零后端改动、URL 不像 B 站）。
  **决策：先走 ①**，被拦再退 ②。★ 它不是纯配置改动：转发走 `ViewController`（不进登记簿 / spec），
  dist 资源目录 `/static/` → `/assets/` 要同步进限流与静态白名单（见 §十三）。
  ★ **状态（2026-10-26）：① 已落地**（`WebMvcConfig#addViewControllers`，forward 不是 redirect）。`WebMvcConfig#addViewControllers` 用 **forward**（不是 redirect）
  把 `/`、`/search`、`/video/{id}`、`/space/{id}`、`/favorites`、**`/watchlater`**、`/history`、**`/dynamic`**、
  `/placeholder/{feature}` 转到入口 HTML；
  **不用 `@Controller`**（那会进端点登记簿 / spec，还要为默认拒绝开 `@Public` —— 它只是页面导航）；
  dist 由 `npm run stage` 落到 `src/main/resources/static/`（产物不入库）；`/assets/` 与 HTML 外壳进限流跳过清单。
  `mvnw clean` 后实测：七个入口 200、`/assets/*.js` 200、`/api/v1/**` 200、旧 `/static/**` 404。
- ✅ **dev 侧 fallback 已落地**：`vite.config.ts` 的 `htmlHistoryFallback()` 把 `/search` → `search.html`、
  `/video/<digits>` → `video.html`、`/space/<digits>` → `space.html`、`/favorites` → `favorites.html`、
  **`/watchlater` → `favorites.html`**、`/history` → `history.html`、**`/dynamic` → `dynamic.html`**、
  `/placeholder/*` → `index.html`，并挂在 `configureServer` **与** `configurePreviewServer` 上 ⇒ `npm run preview` 也能验打包产物。
  它只接管 `Accept: text/html` 的导航请求（`/api`、`/upload`、带扩展名的资源全部放行）。
  ★ **生产转发要补的正是这份映射表**（七入口 + `/watchlater`，已落在 `WebMvcConfig#addViewControllers`，两处必须同步改）。
- ⚠️ **dev 代理要转发两段**：`/api` **和** `/upload` —— 封面是后端静态路径（`/upload/cover/…`），只代理 `/api` 会让所有图片 404。
- **明确接受的代价**：跨应用整页刷新、公共请求（`/users/me`）每次进应用重复一次；**换来**与参考实现同构。

⚠️ **一个真实差异**：B 站是 `/video/BV1xx`，本仓内容 id 是数据库自增（`/video/77`）。路径形状可仿，
**id 语义仿不了**；重制期不改契约，将来若要短链 / 公开 id，单独开 GAP。

---

## 十三、迁移收尾：旧 SPA 删除（**已收口 2026-10-26**）

三个提交：`b79dfe1`（静态转发 + 白名单）· `a916959`（幂等过滤器安全方法直通）· `09ae36a`（dist 上 Spring + 删 18 文件）。
当前有效结论（细节以代码 + 契约测试为准，不再复述当时的前置清单）：

- dist 落 `src/main/resources/static`（产物不入库）；`/`、`/search`、`/video/:id`、`/space/:id`、
  `/favorites`、`/watchlater`、`/history`、**`/dynamic`（2026-10-27）**、`/assets/**` 全 200，旧 `/static/**` 404；
- 转发用 **`ViewController` + forward**（不进端点登记簿 / 不进 spec / 不需要 `@Public` / 地址栏 URL 不变）；
  dev 侧 `vite.config.ts` 是**同一张映射表**，两处必须同步改；
- `/assets/`、HTML 外壳在 `RateLimitFilter.SKIP_EXACT` / `SKIP_PREFIXES` 里（否则 **429 掉 CSS**）；
- `StaticForwardingContractTests`（断言 `forwardedUrl`，而非"最终 200"）+ `RateLimitFilterContractTests`
  的零扣费探针钉住上面两条；新增入口时**两处都要加**（2026-10-27 加 `/dynamic` 即如此）。

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
| **页面级身份态**（2026-10-26 新增，收藏 / 历史 / 稍后再看） | **整页内容都属于"我"**时（匿名请求必然 401、没有"匿名可见的一部分"）：先 `await restoreSession()`，匿名渲染「登录后查看」+ 一个登录按钮，按钮本身走 `requireLogin()`；登录成功后 watcher 自动装载 | 进页面**自动弹**登录框 / 把 401 画成"空数据"（用户以为自己的收藏没了） |
| **稍后再看状态**（2026-10-26 晚新增） | **只记本次页面**：加入成功时把 `(contentId, pathname+search)` 记进模块级 `reactive(Map)`；同一 URL 内再点=取消；刷新 / 换路由即忘（`common/watchLaterSession.ts`） | 每张卡片回源 `GET /watch-later` 查状态（N 次请求）/ 把初始"不知道"画成"未加入"并禁用 / 落 localStorage |
| **写后对齐** | 乐观更新 → 失败回滚 → 成功后用**服务端权威值**再对齐一次（详情 / 资料重读） | 只做乐观、不回滚；或回滚后不回读（并行请求会把状态写回旧值） |
| **三态** | `null` = **本面不查**（≠ `false`）：`interaction` 整体为 null 是匿名；字段为 null 是这一维没查 | `?? false` 归一（关注按钮永远显示"关注"） |
| **429** | 吃 `Retry-After` 说人话（"N 秒后再试"），别只说"请求过于频繁" | 静默重试 / 立即重试 |
| **瞬态错误** | 页面级错误态给「重试」按钮（429 / 网络 / 5xx 都是瞬态） | 只给「回首页」，逼用户重进 |
| **不该重试的** | 400 / 401 / 403 / 404 是**确定失败**：停手并说清楚 | 循环重试把 429 也刷出来 |
| **本地存储** | 只有两处：`myspringtv.token`（`auth/token.ts`）与 `history:pending:{id}`（崩溃补偿） | 把页面状态塞 localStorage 冒充缓存 |
