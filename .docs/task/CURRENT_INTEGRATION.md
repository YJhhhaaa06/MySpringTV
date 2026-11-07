# 联调期登记台（Current Integration）

> **本期唯一入口**（2026-10-30 开窗）：前半场「照着 B 站写前端」已收口，后端期已归档，现在只做两件事 ——
> ① **前后端联调**（已交付的页面 × 端点逐条打通、验真）② **有条件地补回功能**（补回要有判据，不凭手感）。
>
> **上一期 / 历史**（默认不读）：
> 「前端重制期」遗留口径 [CURRENT_FRONTEND.md](./CURRENT_FRONTEND.md)（选型铁律 / 门禁 / 环境与坑 / 入口映射 / 客户端口径，
> 仍作背景参考，但**不再是入口**）＋ [GAP 台账](./CURRENT_FRONTEND_GAPS.md)（**仍是**唯一差异清单）；
> 「后端期」三件套 → [后端期登记台（归档）](../archive/backend-2026-10-30/README.md)（含 `I-27→F-28` 等改号映射）。
>
> **口径**：需求为什么要做 → `architecture/`；**执行中发现的问题** → [CURRENT_ISSUES](./CURRENT_ISSUES.md)；
> **本期不解决的**（结转 / 留池）→ [FURTHER_ISSUES](./FURTHER_ISSUES.md)；
> 本文件只记**执行态**（做什么、做到哪、怎么算过）。**编号纪律**见 `../INDEX.md` §五.4。

---

## 一、周期头（每窗开读）

* ★ **状态**：2026-10-30 开窗。本窗第一件事 = 补一次全量回归基线（见下）+ 起联调清单。
* ★ **编号**：任务 **`T76`** 起（`T71`–`T98` 已完结；**已用到 `T118`，下一编号 `T119`**）/
  决策 **`R-27`** 起（`R-25` = 记录开关、`R-26` = 历史可删 + per-day 口径；**已用到 `R-35`，下一编号 `R-36`**）/ 问题 **`I-33`** 起（`I-31`/`I-32` 已终结；**下一编号 `I-34`**）/ 结转 **`F-30`** 起（**已用到 `F-35`，下一编号 `F-36`**）。
  ⚠️ **编号不回收、不复用**：后端期已用的 `I-01`–`I-30` 随归档留在原地，联调期**从 `I-31` 继续**；
  `F-##` 同理（`F-01`–`F-35` 已用，见 [FURTHER_ISSUES](./FURTHER_ISSUES.md)）。
* ★ **回归基线**：默认集 **980**（2026-11-06，`DB_POOL_MAX_SIZE=4`，`R-33` 后；上一档 962）+ 前端 **383**（`R-32` 后；上一档 377）；
  `resilience` 组 **41** 按惯例单跑 ⇒ 全量口径 **1021**。★ 本次全量 **0 失败**（`Tests run: 1021`）。
  跑全量必须带 `DB_POOL_MAX_SIZE=4`，否则容器 MySQL `Too many connections`（**不是代码缺陷**）。
* ★ **门禁**：文档三道 —— `python tools/docs/doc_links.py` · `python tools/docs/doc_refs.py` · `python tools/docs/doc_stats.py`；
  前端 `npm run typecheck` / `npm run test` / `npm run build`（★ **先 `cd frontend`** —— 仓库根**没有** `package.json`，在根目录跑 `npm run build` 会 `ENOENT`，不是编译失败；跑起来与截图规矩见 `frontend/README.md`）。
* ★ **执行约定**：**commit 级不变式**（`G1`：可编译 / 可回滚 / 一事一 commit / 可回溯；**切分由执行者定**，
  不必与任务边界对齐）、验收用例 + 反向验证（`G5`）；
  `G#` / `C#` 的**定义**在 [`AGENTS.md`「跨期元约定」](../../AGENTS.md)，本文件不复述。

---

## 二、本期范围：两件事，两套判据

### 2.1 联调（默认全做）

* **清单**＝ [前端.md §四 页面 × 端点矩阵](../architecture/frontend/前端.md)（**不在这里复制**，矩阵一变就漂）。
* **差异清单**＝ [CURRENT_FRONTEND_GAPS.md](./CURRENT_FRONTEND_GAPS.md)（`GAP-##` 是唯一编号空间，不新开）。
* ★ **"联调完成"的判据**（四条全中才算，缺一条就是"看着像通了"）：
  1. 页面走**真实后端**（非 mock、非占位、非假数据）；
  2. 请求/响应形状与**生成的类型**对得上（`api.d.ts` 与运行时一致，含 `null` 三态）；
  3. **错误分支**有落点（400 / 401 / 403 / 404 / 409 / 429 / 503 各自界面行为说得出，见前端.md §六 降级约定）；
  4. **写路径**带幂等键、重试不重复（`Idempotency-Key` 覆盖面见 `tech/idempotency/写接口幂等.md`）。
* **反面**：不重做视觉、不为对齐 B 站新增无域页面；视觉偏差只按 `视觉基准.md` 的既有结论处理。

### 2.2 有条件补回（不是"想补就补"）

> 一个字：**判据**。本仓的价值在"知道什么时候不该做"，补回也要能说出凭什么现在补。

补回一项必须**同时**满足三条，缺一条就写进 `FURTHER_ISSUES.md` 而不是开任务：

1. **有前端消费方** —— 页面已经在用，或本期明确要上（不是"以后可能有用"）。
2. **不改契约形状** —— 只补缺的**字段 / 端点 / 参数**；要动状态码类别、错误信封这类**规则**，另开 `R-##` 拍板。
3. **有可验证的验收** —— 一条**会红**的用例，或一条可复算命令（`C-2` / `G5`）。

**当前候选**（只从 GAP 台账标「待补（等后端）」里挑，逐条去 GAP 表看上下文）：

| GAP | 缺什么 | 补回成本 / 注意 |
|---|---|---|
| `GAP-06` | `recommended` 无分页（固定 12 条）⇒ 首页做不了无限加载 | 与 `GET /contents`（搜索专用、`keyword` 必填）**不是**一回事，别顺手合并 |
| `GAP-20` | `/history` 无 `type` / `keyword` | 与 `GAP-10` 搜索筛选**同口径**（枚举字符串、大小写敏感）；图文 `play_count` 恒 0 的老问题不因它改变 |
| `GAP-23` | `/feed` 无 `type` | 同上；追番/专栏无域，**永远不做** |

> ~~已定不再讨论的：`GAP-19`（历史删除/清空）⇒ 已知代价~~ **2026-10-31 推翻**：`GAP-19` 由 `R-26` 重新开做（
> 前置条件"计过数人次的独立载体"随 `V10` 落地），见 §2.3。
> `GAP-28`（图文首图回填）2026-10-29 拍板本窗不做；标「不做 / 长期」的 GAP 不进候选。

### 2.3 本窗拍板（`R-##`）

| 编号 | 决策（2026-10-31 用户拍板） | 推翻条件 |
|---|---|---|
| `R-25` | **历史「记录浏览历史」开关**：① **用户级**（`users.history_recording`，不是设备级）；② **服务端不强制**（只在客户端拦写，写端点一律不校验）；③ 关闭期**连 `/play` 也不发** ⇒ 该期间的观看**不计播放量**；④ 只关写（`GET /progress` 续播仍可用）；⑤ 状态只在**整页装载**时随 `GET /users/me` 读一次，不轮询、不落 localStorage；⑥ 拨动是**乐观生效、失败不提示不回弹**（断网期间的切换不算数，刷新回原值） | ② 出现第二个客户端 / 公开 API ⇒ 必须改成服务端校验；③ 有人质疑"关掉开关就少计播放量" ⇒ 走"计过数人次的独立载体"（与 `GAP-19` 同一前置）；⑤ 需要跨设备实时 ⇒ 先有推送通道 |
| `R-26` | **历史可删 + 播放量改「每人·每内容·每自然日一次」**（2026-10-31 用户拍板，推翻 `R-18` 的"一人一次"与"历史不可删"）：① 去重标记搬出 `watch_history`，落独立账本 `content_play_dedup(user_id, content_id, play_date)`（PK 即三列；`play_date` = **业务时区 `Asia/Shanghai`** 的日历日，**Java 算日期后传参**，不用 `CURDATE()` —— 它取 MySQL 时区，容器常是 UTC，差 8 小时）；② 账本**不回填**旧数据（`DATE(update_time)` 是最后上报时间、不是计数日，回填即写假数据）⇒ `play_count == COUNT(账本)` 只对 **V10 后新建**的内容绝对成立，老内容退化为增量等式；③ 账本**不清理**（行数 = 用户×内容×活跃天），真要清走 `tools/devclean/` 运维脚本，**不引入常驻定时任务**；④ `watch_history.play_counted` 与 `idx_content_id` 在 `V10` 一并 DROP（前者成死列、后者失去唯一用途）；⑤ 删除端点 `DELETE /api/v1/history`：`?contentIds=`（≤ **500**，缺参 400）或 `?all=true` 清空（两者互斥），**幂等 204**（行不存在也成功）、**不校验内容存在性**（失效占位必须能删）、**不动 `play_count`**；⑥ 删除同时清该用户的 Redis 上报缓冲 + 前端清 `history:pending:{contentId}`（不清 ⇒ `/flush` / 轮询兜底把行写回来） | ① 业务时区变更（出海 / 多区域）⇒ `play_date` 口径重定；② 有人要求播放量**跨版本可比** ⇒ 必须先回填并接受假日期；⑤ 出现"按时间删 / 按条数删"的需求 ⇒ 重开一轮；④ 回滚到"永久一次"时账本仍可当"人次"账本用，不必删表 |
| `R-27` | **自定义头像 + 默认头像切换**（2026-11-01 用户拍板，推翻 `GAP-08` 原先"本窗不做头像域"）：① 头像媒体落 **`/upload/avatar/`**，不复用 `image/`（`media_clean.py` 会把未被 `content_media` 引用的 `image` 文件判孤儿）；② DB 只存 **`users.avatar_ref`**（`default:1`…`default:6` 或 `/upload/avatar/...`），`UserInfoVO` / `ProfileVO` 统一回带**生效 `avatarUrl`**（`NULL` ⇒ 按 `userId % 6 + 1` 选默认编号 1..6）；③ 六张默认头像移到 `frontend/public/avatars/default-1..6.svg`，顺序不变（sunrise→1 … cloud→6）；④ 写端点：`PUT /users/me/avatar`（multipart，服务端收裁剪产物，**类型 + ≤2MB**）、`PUT /users/me/avatar/default`（`avatarId` 1..6）；⑤ 作者快照面（评论 / 动态 / 关注列表 / 图文作者）**不把头像进缓存**，走公开 `GET /users/avatars?userIds=`（≤100，按 userId 实时解析）；⑥ 设置入口 = **自己的空间页点头像弹窗**；⑦ 裁剪 **180×180 JPEG**，原图 ≤10MB，**客户端裁剪，服务端不重编码 / 不强制尺寸** | ① 需要跨端 / 公开 API 的头像缓存 ⇒ 再评"进缓存 + 失效"；② 服务端要强制 180×180 / 去 EXIF ⇒ 加 ImageIO 重编码；③ 头像孤儿需要自动回收 ⇒ 扩 `media_clean.py` 把 `users.avatar_ref` 当引用源 |
| `R-28` | **首页推荐流可无限翻页**（2026-11-02 用户拍板；设计篇 = [content-推荐流](../architecture/domain/content/content-推荐流.md)）：① 顺序 = `(seed, id)` 的**纯函数**（splitmix64 finalizer），服务端**零状态**；② 游标 = 不透明 `v1.<seed>.<lastId>`，锚在 **id** 上（末条被删仍精确），非法游标静默归一到第一页；③ 响应改信封 `{list, nextCursor}`，`pageSize` 缺省 **20** / 上限 **50**；④ 池子复用 `content:index:*`，**不做启动预热、不做每用户私有队列**（理由：顺序是正确性，缓存是加速器）；⑤ 新内容**不加权**（与其它内容同权随机）；⑥ 视频页侧栏改为"取第一页 + **`?type=1` 服务端过滤** + 排除自己"（2026-11-02 前端口径）；同分区优先**不做** —— 技术上用既有 `categoryId` 即可、不必新开端点，真·相关（多信号）才另开端点 | ① 池子 > 5×10⁴ 或 P99 > 20 ms ⇒ 预排序分桶；② "发布后 X 分钟内首页可见"成为硬需求 ⇒ 首屏"最新"独立区块 + 冻结时间水位；③ 顺序必须被冻结（运营插位 / A/B 分组 / 跨设备不重复）⇒ 另开编排层，不改造随机序 |
| `R-29` | **首页无限滚动的交互口径**（2026-11-02 用户拍板；实现见 `T91`）：① 到底文案 = **会话级**「这次的推荐到底了」，不承诺"全站刷完"（`nextCursor=null` 有四因、无 `total`）；**首屏空 ≠ 到底**（走「暂时没有内容 / 刷新试试」，主操作是刷新、不引导投稿）；② 两个换一换：内联（右上，随内容滚走）**原地换一批、不回顶**；浮层（右下，仅内联不可见时出现）**先回顶再刷新**；两者不同时出现，中间断档不补；浮层 = 刷新内容（hover 展开 pill）+ 顶部，**不做「...」**（无菜单内容）；③ 侧栏**维持随机** + `?type=1`，不足 6 条接受少，不做同分区（`categoryId` 可用但刻意不做）；④ 图片未到**只盖封面位**（shimmer + 150ms 淡入），整卡骨架只用于"数据未到"；⑤ 续页骨架 10 个、失败行内重试**不自动重试**、429 说清等多久；⑥ 刷新失败：**有旧内容 → toast + 保留**，无内容 → 页面级错误 | ① 出现"必须显示全站总数 / 进度"的需求 ⇒ 后端加 `total`（与推荐流 §四.5 冲突，需重开）；③ "相关推荐"名不副实被用户质疑 ⇒ 先用 `categoryId`，再谈 `/related`；④ 弱网下仍觉得"灰块一张张蹦" ⇒ 才考虑 LQIP / 后端缩略图 |
| `R-30` | **顶栏账号卡片按 B 站版式重做**（2026-11-03 拍板；实现 `T92`）：① 桌面 hover 开卡片、头像点击仍进空间；触屏 `hover: none` 点击头像开卡片；② 卡片 = 头像 + 昵称 + 硬币 + 关注/粉丝/动态（均新标签页）+ 个人中心/投稿管理/退出；③ **动态数 = 投稿总数** `contentPage.total`（本仓无动态域，"动态 = 投稿时间线"）；④ 不渲染等级/成长值、大会员、B币、主题、推荐服务；⑤ 退出 = `signOut()` + 回首页整页加载，无二次确认；⑥ 计数懒加载 `GET /users/{id}?pageSize=1`，失败降级 `-` | ① 出现真动态域 ⇒ 改读动态数；② 触屏交互失效 ⇒ 重议；③ 要保留当前页位置 ⇒ 就地退出 |
| `R-31` | **动态页补齐：三种形态 + 图文呈现 + 内联评论**（2026-11-03 拍板；推翻 `F-32` 的两条裁剪）：① **个人动态 = 投稿时间线**（`R-30` 延伸）：作者维度**不走** `/feed` 加参，直接读 `GET /users/{id}` 的 `contentPage`；`/feed` 一字不动。② **空间三视图 + 收藏**：主页（最新 3 大卡 + 网格预览 6 + 查看全部，不分页）/ 动态（全量时间线 + 分页）/ 投稿（全量网格 + 分页）/ 收藏（前三者是本轮新增的三视图）；默认主页，原 `works` 改叫投稿；主页与投稿是"预览 vs 全量"，不是两套数据。③ **图文卡**：正文优先 + 图集（≤9，第 9 张 `+N`）+ 超长折叠/展开；无 media = 纯文字动态（meta「发布了动态」），有 media = 「发布了图文」；视频卡版式不动。④ **评论内联**：复用 `CommentSection`，懒加载、同时只开一个、计数回写；详情页评论不动。⑤ **字段**：`ContentVO` 透出 `imageUrls`（`videoUrl` 继续忽略；缓存 DTO 本来就有，零额外读）；`/users/{id}` 的 `contentPage` 补 `likeCount/commentCount`（同一端点服务网格 + 时间线，按面合约随契约跟随）。⑥ **标题**：图文投稿新增「不使用标题」，勾选提交 `title = "「无标题」"`（**含直角括号**，5 字符）；编辑页按同一魔串回填；动态卡 / opus 详情 trim 后全等则跳过标题；网格 / 搜索 / 历史仍显示该串。⑦ **登录门**：`/dynamic` 仍是页面级登录门；作者模式沿用登录门，匿名分享链接给「去 TA 的空间看动态」公开入口（不把公开数据说成"只属于账号"） | ① 出现独立纯文字 / 转发动态域 ⇒ 重定个人动态数据源；② 列表 payload 因图集变大成瓶颈 ⇒ 摘要 / 分页另案；③ 评论内联让首屏或滚动明显变慢 ⇒ 改抽屉；④ 空间三 tab 无点击或被质疑"同一套数据" ⇒ 撤 tab 或合回主页；⑤ `「无标题」` 与真实标题冲突成为问题 ⇒ 加 `title_optional` 布尔字段（不动 `title` 语义）；⑥ 需要按类型筛选（视频 / 图文）⇒ `/users/{id}` 加 `type` + `ProfileVO.worksCount`（另开 `R-##`，本窗不做，见 `GAP-32`） |
| `R-32` | **动态页翻页改无限滚动 + 轮询式「有新动态」**（2026-11-06 拍板；服务端**不做推送**）：① `/dynamic` 全部动态与作者模式都改无限滚动（12/页、哨兵提前 800px、续页骨架、行内重试；到底 = `page >= totalPages`，文案「这次的动态到底了」）；② 后端只加**可选** `beforeContentId` 快照锚点：第 2 页起只看 `id ≤ 首屏首条 id`，`total` = 锚点内条数 ⇒ 新内容插到头部不会让 offset 漂（不去重、不漏条）；**不加端点、不改响应形状、不加迁移**；③ 「有新动态」= **1 分钟轮询** `GET /feed?page=1&pageSize=3`（不带锚点）比首条 id，**无计数**；仅标签页可见时跑、回前台立刻补一次、单飞、401 停、429 退避；点击 = 回顶 + 重拉第 1 页（整批替换，epoch 淘汰在途续页，失败 toast + 保留旧列表）；④ 标题行「刷新」按钮同语义；内联横幅 + 浮层「有新动态」胶囊互斥（横幅不在视口时才出浮层，照 `R-29` 口径）；⑤ 右下「回到顶部」（`scrollY > 800`）；⑥ 不落 localStorage；作者模式不轮询；空列表不轮询 | ① 真实 QPS 下轮询占比过高 / 要未读数 ⇒ 加 head 端点或推送通道；② 要"刷新不丢位置 / 多端同步 / 精确增量" ⇒ 上完整游标信封；③ 空间动态 tab 与作者模式体验割裂被质疑 ⇒ 下一窗接同一套；④ 窗口重建 / 删稿导致"跳 1 条"成为可感问题 ⇒ 改服务端会话快照 |
| `R-33` | **收藏夹封面**（2026-11-06 用户拍板；推翻 `GAP-17` 长期与 `FURTHER_ISSUES` 的封面留池）：① 三态互斥 = 自定义 / 自动 / 灰底 TV 占位；自动封面 = **夹内最新一条收藏**（视频或图文都算），能经 content 装载契约取到且 `coverUrl` 非空就用它，否则（失效 / 无封面 / 空夹）**直接占位，不回退**；② 自定义封面只在 **`/favorites`** 提供上传：可反复更换、可「恢复自动」、默认夹与私密夹都可设；创建收藏夹弹窗**不提供上传**；`FavoriteDialog` 不显示封面；③ 展示面 = **空间「主页」tab 新增收藏区块（最多 10 个公开夹、两行、查看更多）+ `/favorites` 选中夹头部封面**；空间页 `?tab=folders` 本期不动（合并登记 `F-35`）；④ 接口：`PUT /api/v1/favorite-folders/{id}/cover`（multipart，服务端收 16:9 裁剪产物，**类型 + ≤2MB**）、`DELETE /api/v1/favorite-folders/{id}/cover`（恢复自动，204）；`GET /favorite-folders` / `GET /users/{id}/favorite-folders` 回带生效 `coverUrl`（我的夹另带 `hasCustomCover`）；⑤ 落盘 **`/upload/favorite-cover/`**（不混入 `cover/`：`media_clean.py` 会把未被 `content_media` 引用的 `cover` 文件判孤儿）；旧自定义文件在替换 / 恢复 / 删夹后清理；⑥ **不缓存 folders**：封面读取时现算（一条最新收藏查询 + 一趟 content 批量读）；**不改 `favorite_item` 写路径、不加 `content_type`**；⑦ 前端裁剪固定 **16:9 / 1280×720 JPEG**，原图 ≤10MB、jpg/png/jpeg；占位复用灰 TV 图标；本轮**后端优先**，前端另窗按工作量决定 | ① 要求「最新视频」而非「最新一条收藏」 ⇒ 需给 `favorite_item` 增 `content_type`（或回扫），重开；② 封面需要进缓存 / 列表热路径被质疑慢 ⇒ 再评缓存与失效；③ 需要创建时上传 / 空间收藏 tab 直接编辑 ⇒ 重定入口；④ 服务端要强制尺寸 / 去 EXIF / 内容审核 ⇒ 加 ImageIO 重编码；⑤ 封面孤儿需自动回收 ⇒ 扩 `media_clean.py` 把 `favorite_folder.cover_ref` 当引用源 |
| `R-34` | **收藏夹查看入口收敛到个人空间收藏 tab**（2026-11-06 用户拍板；`/favorites` 软弃用）：① 唯一查看收藏夹内容的入口 = **`/space/{uid}?tab=folders`**；`/favorites` / `/watchlater` 不再作为独立页面，只保留**薄重定向壳**（旧 query 映射：`folder→folder`、`page→itemPage`、watchlater→`view=later`），新页稳定后另窗清理 HTML 入口 / 转发 / 限流 / 测试；② 顶栏「收藏」直连 **`/space/me?tab=folders`**，解析登录态后 `replace` 成数字 uid；匿名走页面级登录；③ self 走 `GET /favorite-folders` + `GET /favorites?folderId=`（含私密夹），别人走 `GET /users/{id}/favorite-folders` + `GET /favorite-folders/{id}/items`（公开），**绝不混用**；身份判定在 `restoreSession()` 之后用 `currentUserId()`，登录/退出/换号必须清空 folders / items / 选中夹；④ 稍后再看 = 收藏 tab **左栏特殊项**，右栏复用同一套网格 / 分页 / 移出；URL `?tab=folders&view=later&itemPage=N`；只 self 可见、不计入“收藏 N”和夹数；⑤ 封面本窗**只展示**（选中夹头部 + 主页夹卡；`coverUrl=null` 灰 TV 占位、破图 fallback；左栏与 `FavoriteDialog` 不显示）；**不做上传 / 恢复**，`hasCustomCover` 的 UI 留到上传那步；⑥ 空间主页 tab 收藏区块：self 含私密夹并带私密标记、别人只有公开夹；最多 10 张 + 查看更多；无夹不渲染、失败块内联重试；⑦ 不做收藏夹 CRUD / 私密开关 / 批量管理 / 排序 tabs / “播放全部” | ① `/space/{uid}` 无法安全承载 self 私密夹 ⇒ 改独立页；④ 稍后再看需要独立 URL / 独立 tab ⇒ 重开；⑤ 上传 / 恢复提前 ⇒ 重定入口（原“只在 `/favorites` 上传”口径随页面弃用失效）；⑥ 主页 10 张被质疑 ⇒ 调整上限 |
| `R-35` | **收藏夹信息编辑入口落在空间收藏 tab**（2026-11-07 用户拍板；落地 `R-33` ⑤ / `R-34` ⑤ 的入口口径）：① 仅 self 面左栏每个夹 **hover / focus 出现 ⋮**，菜单只放「编辑信息」（删除夹另开任务）；② 弹窗 = **封面**（点封面选图 → 复用 16:9 / 1280×720 的 `CoverCropper` → 裁剪确认即上传）+ **名称**（≤20，与新建弹窗一致；后端实际允许 100）+ **公开开关**（`isPrivate` 取反）+ **简介只读禁用占位**（`favorite_folder.description` 列 V2 已存在，实体 / VO / DTO 未接；本窗不接后端、不做"可输入但丢弃"）；③ **恢复自动封面做**，仅在 `hasCustomCover=true` 时显示；`hasCustomCover` 补进 `LibraryFolderVM`（公开面 VO 无此字段 ⇒ false）；④ 默认夹可编辑（名称 / 公开 / 封面），**不提供删除**；⑤ 不改后端 / 不加迁移 / 不动 `FavoriteDialog` 与主页夹卡（只读）；⑥ 旧 `favorites.html` 入口与转发 / 限流 / 契约清理**下窗**做；验收 = self / public / anonymous 三态 + 真浏览器 | ① B站实测封面是"保存时一起提交"而非选图即上传 ⇒ 改 pending 模式（取消 = 放弃封面）；② 简介必须可编辑 ⇒ 补后端（列已存在，无需迁移）；③ 要求菜单同时有删除 ⇒ 另开任务；④ 空间收藏 tab 不再承担编辑入口 ⇒ 重定入口 |

> 用户 2026-10-31 在 Chrome 重放上报请求实测：**B站 是服务端强制的**。本仓**刻意不跟**，理由与代价见
> [history-已知代价](../architecture/domain/history/history-已知代价.md)（不是"没做到"）。

---

## 三、任务（`T##`）

> 每任务＝**目标 / 红线 / 验收入口 / ≤3 句回写**（`G4`）；只给方向，不写死实现步骤（`G13`）。
> 状态取值：待执行 / 执行中 / 已完成 / 搁置（搁置必须注明去向）。

| 编号 | 目标 | 红线 | 验收 | 状态 |
|---|---|---|---|---|
| `T71` | 补回 `GAP-12`：评论发布时间（后端 `createTime` + 前端 **web 端口径** + 服务器时钟基准） | 只动评论，**不改** `formatRelativeDate`（首页卡/动态卡是另一套口径）；不新增迁移；不把展示逻辑塞进后端（缓存会把相对时间固化） | 后端 `CommentReadTests#评论带发布时间`（独立 oracle 直查 DB，主楼/children/cursor 三处）；前端 `format.test.ts` + `serverClock.test.ts`（`TZ=UTC` 复跑；反向注入：去 +08:00 / 日界改 24 小时 / `Date.now` 做锚） | **已完成（2026-10-31）** |
| `T72` | 时间显示口径收口（原 `I-31`）：全部出口按 +08:00 解析、按**浏览器本地时区**呈现；"现在"统一服务器时钟；卡片补未来分支 | 不改传输形状（不给时间串加偏移，§2.7 不动）；不合并 `formatRelativeDate` 与 `formatCommentTime`；**不动后端**；JVM/MySQL 时区前提不处理（用户已拍板不登记） | 前端 `format.test.ts`（跨时区 / 未来 / 日界 / 观看时刻，`TZ=UTC`）+ `history.test.ts` + `videoDetail.test.ts` + 跨适配器接线（`videoCard.test.ts`）+ 端点透传（`endpoints.test.ts`）；反向注入 5 条：去 +08:00（**8 文件**红）/ 删未来分支（**2** 红）/ 日界忽略显式时区（**4** 红）/ `loadFeed` 回退 `new Date()`（1 红）/ 偏移改"此刻"（**0 红，只能靠 review**） | **已完成（2026-10-31）** |
| `T73` | 补回 `GAP-21` 的后端面（`R-25`）：`V9` 加 `users.history_recording`、`GET /users/me` 回带、`PUT /users/me/history-recording` 写入 | **不在任何写端点校验开关**（刻意，见已知代价）；不建 `user_settings` 表；不加 JSON 设置列；不改 `/users/me` 的其它字段形状 | `UserHistoryRecordingTests` **7 例全绿**（默认开 / 关 / 再开 / 401 / 缺字段 400 / 非布尔 400 / **刻意不强制**，独立 oracle 直查 DB + 对照用户）+ `SecurityContractTests` 新路由 + 两处迁移锚点 + 端点总数 69→70；反向注入 2 条：① mapper 去掉 `where id=?` ⇒「关掉只影响自己」红（对照用户变 0）；② `/report` 加一道拒绝 ⇒「关闭后写端点仍放行」红（404→400） | **已完成（2026-10-31）** |
| `T74` | 前端接 `R-25`：历史页标题行开关（关闭需确认）+ 播放页只关写 | 关闭时**不发** `/play`/`/progress`/`/flush`、不写本地 pending、不补报；`GET /progress` 的续播**保留**；不落 localStorage（第 3 个 key 仍然不许）；拨动不做失败提示 / 回弹 | `historyTracking.test.ts` **4 例**（匿名 skip / 关掉 read-only / 开着 track / 缺字段按开）+ `typecheck` / `test`（296）/ `build` 全绿；反向注入 2 条：`=== true` ⇒「缺字段按开」红；恒 `true` ⇒「关掉 read-only」红。★ **DOM 接线（真的一个请求都不发）无自动化覆盖**（不装 jsdom，E2E 也没造"关着播 5 分钟"）⇒ 只能靠 review，别当已验 | **已完成（2026-10-31）** |
| `T75` | 文档收口：客户端契约 / 静默机制 / 接口契约 / `GAP-21` 销账 | 不新开编号空间；`history.md` 只增不改号（`src` 里有裸 `§x` 引用） | 三道门禁 0 违规；`grep -rn "GAP-21" src` 的交叉引用仍指向**已销账**行 | **已完成（2026-10-31）** |
| `T76` | `R-26` 后端口径：`V10` 建 `content_play_dedup`（+ DROP `play_counted`/`idx_content_id`）、`/play` 改 `INSERT IGNORE` 账本判首播、oracle 改 `COUNT(账本)` | 账本**不回填**、**不清理**；`play_date` 必须 Java 算（业务时区）+ 传参，**不用 `CURDATE()`**；`play_count` 仍只增；迁移**新开 V10**（V1–V9 逐字节冻结） | `HistoryWriteTests` **11 例**（首播/同日重复/并发/日界/不变量）+ `PlayDayTests` **2 例**（`2026-10-31T16:30Z → 11-01`）+ `HistorySchemaTests` **7 例**（账本 PK / 索引全集 / 列集 / 1..10）+ 三处迁移锚点。反向注入 2 条：`ZONE` 改 UTC ⇒ PlayDay 2 红；账本主键语义由结构用例守 | **已完成（2026-10-31）** |
| `T77` | `R-26` 后端能力：`DELETE /api/v1/history`（`contentIds` ≤500 / `all=true`，204 幂等）+ 清 Redis 缓冲 | 不校验内容存在性（失效占位要能删）；不动 `play_count`；缺参 400 与"没有这条记录"204 **必须分开**；缓冲清理失败只记 WARN（接受复活） | `HistoryDeleteTests` **10 例**（作用域 / 幂等 / 三种 400 / 两种批量写法 / 清空 / 失效可删 / 401 / 删后同日不计次日再计 / 删后 flush 不复活）+ 四处锚点（端点 70→71、非 GET 42→43、`IdempotencyScope` 登记、表数 19→20）+ 契约产物重生成。★ 反向注入 3 条：删 SQL 去掉 `user_id` ⇒ 2 红；去掉 `forgetBuffered` ⇒ 2 红；`/play` 只在首播时建行 ⇒ 1 红 | **已完成（2026-10-31）** |
| `T78` | `R-26` 前端：历史页工具栏「清空历史 / 批量管理」、管理态（全选=已加载、已选择 N 条、删除、退出管理）、单卡垃圾桶、确认框、删后清 `history:pending:{contentId}` | **不渲染** tabs / 搜索 / 更多筛选（`GAP-20` 仍不做）；管理态不落 localStorage；确认框归属入口（管理态删除、清空）而非条数；末页删空要回退一页 | `historySelection.test.ts` **4 例** + `api/history.test.ts` **3 例**；`typecheck` / `test`（**304**）/ `build` 全绿。★ 反向注入 2 条：`clearHistory` 不传 `all` ⇒ **2** 红；`isAllSelected` 改比数量 ⇒ 1 红 | **已完成（2026-10-31）** |
| `T79` | `R-26` 文档收口：`history.md`（§〇/§一/§二/§三/§四/§五/§七/§八/§十/§十一/附录）、`history-需求`（§一/§二/§四/§六/§七）、`history-已知代价`（作废"不可删"、新增①②③④）、`history-客户端契约`（§二 + 新增 §十二）、`history-术语表`、`接口契约` §2.4、`前端.md`、`ARCHITECTURE`、`GAP-19` 销账 | 只增不改号；`doc_refs`/`doc_links` 0 违规；`doc_stats` 把 `history.md` 超 40KB 登记为 `F-30`（拆篇要动章节号 ⇒ 先给 `src` 的裸 `§x` 加文件限定） | 三道门禁（`doc_links` 悬空 0 / `doc_refs` 0 违规 / `doc_stats` 仅 1 条 advisory）；`grep -rn "GAP-19" src` 的交叉引用指向**已销账**行 | **已完成（2026-10-31）** |
| `T80` | `R-27` 后端：`V11` 加 `users.avatar_ref`；`UploadType.AVATAR` + `/upload/avatar`；`AvatarService`；`PUT /users/me/avatar`（multipart）、`PUT /users/me/avatar/default`、公开 `GET /users/avatars?userIds=`；`UserInfoVO` / `ProfileVO` 回带生效 `avatarUrl` | 头像**不进**评论 / feed / 内容缓存；`/uploads/{type}` **不新增** avatar；`V1`–`V10` 逐字节冻结；旧自定义文件只删 `/upload/avatar/` 前缀；不新增 domain 包（`ArchitectureTests.DOMAINS` 不动）；服务端只校验类型 + ≤2MB，**不重编码 / 不强制 180×180** | `AvatarWriteTests`（自定义上传 / 替换删旧 / 切默认删旧 / 401 / 非图片 400 / >2MB 400 / 另一用户不受影响）+ `AvatarBatchTests`（公开 / ≤100 / 缺 id / 未选头像回落 / userIds 非法 400）+ 契约锚点（端点 71→74、非 GET 43→45、限流与幂等登记、spec 重生成）。反向注入：去掉旧文件清理 ⇒ 旧文件仍在；default id 映射错 ⇒ URL 断言红；批量去掉 `user_id` 过滤 ⇒ 跨用户断言红 | **已完成（2026-11-01）** |
| `T81` | 前端头像基础：六张 SVG 迁 `frontend/public/avatars/default-1..6.svg`；`defaultAvatarUrl` / `defaultAvatarForUser`；`UserAvatar` 接受 `avatarUrl`；`CurrentUserVM` / `AuthorCardVM` / adapter / session 就地更新；`putForm` + 头像 API + `useUserAvatars`（≤100 自动分块） | 不落 localStorage（仍只两个 key）；默认头像顺序不变（sunrise→1 … cloud→6）；批量失败回落 `userId` 默认；不把头像塞进评论 / 内容 VM 的缓存形状 | `avatar.test.ts`（默认 URL / userId 回落 / 顺序）+ `http.test.ts`（`putForm` 信封 / Bearer）+ `user.test.ts` / 空间 adapter；`typecheck` 全绿。反向注入：忽略 `avatarUrl` ⇒ 自定义头像断言红；默认顺序改 ⇒ 映射断言红 | **已完成（2026-11-01）** |
| `T82` | 空间页自己的头像可点 → `AvatarSettingsDialog`：六宫格默认切换 + 自定义上传 → 1:1 `AvatarCropper`（180×180、圆形实时预览、拖动 / 缩放 / 重新选择）；成功后就地更新 `header` 与 session | 只在 `header.isSelf` 可点；原图 ≤10MB、仅 jpg/png/jpeg；产物 180×180 JPEG；不写 localStorage；**不动封面裁剪的 16:9 行为** | `avatarCrop.test.ts`（1:1 不越界 / 拖动与锚点缩放等式 / 180 产物）+ `typecheck` / `build`；真浏览器验收见 `T85`。反向注入：aspect 改回 16:9 ⇒ 1:1 断言红；产物尺寸改 512 ⇒ 断言红 | **已完成（2026-11-01）** |
| `T83` | 全站接线：评论主楼 / 楼中楼、动态卡、关注 / 粉丝列表、动态右栏、图文作者全部接批量头像；匿名评论也可读 | **不把 `avatarUrl` 加进 `CommentCacheDTO` / `ContentCacheDTO` / `FollowUserVO`**；不改 feed / comment 缓存载荷；批量按页一次、id 去重；缺 id 回落默认 | `useUserAvatars.test.ts`（去重 / 分块 / 竞态 / 失败回落）+ 评论 / 动态 / 空间 / 图文四面的接线断言；匿名请求可读。反向注入：删批量调用 ⇒ 列表头像回落 info 红；map 错配 ⇒ 指定用户断言红 | **已完成（2026-11-01）** |
| `T84` | 文档收口：`接口契约`（3 端点 + `avatarUrl` 字段）、`前端.md`（页面 × 端点矩阵 / 头像口径）、`GAP-08` / `GAP-25` 销账、`CURRENT_INTEGRATION` 回写；三道门禁 + subagent review | 不新开编号；不写死实现步骤；代码注释只引用 `architecture/**` / `archive/**`，不得引用登记台别名；`doc_stats` 不新增超限文档 | `doc_links` 悬空 0 / `doc_refs` 0 违规 / `doc_stats` 无新增 advisory；`grep -rn "GAP-08" src` 指向已销账行；subagent review 报告归档在回写 | **已完成（2026-11-01）** |
| `T85` | 真浏览器验收（`UPLOAD_ROOT=.tmp/e2e-media` 起后端）：空间页头像入口 → 默认切换 / 自定义裁剪预览 / 更新 → 刷新保持 → 评论 / 动态 / 关注列表头像一致 | 不碰真实媒体根；`.tmp` 不入库；失败先登记 `I-##` 再修 | 六步全过：① 自己空间头像可点、别人不可点；② 选默认 N 后顶栏 / 空间 / 评论一致；③ 上传自定义 → 180×180 圆形预览 → 更新；④ 刷新后仍是自定义；⑤ 切回默认后旧自定义文件消失；⑥ 匿名看评论区头像可显示 | **已完成（2026-11-01）** |
| `T86` | `R-28` 纯函数：`RecommendOrder`（splitmix64 排序键 + `v1.<seed>.<lastId>` 编解码） | 不用 `hashCode` / `Random` 当排序键；非法游标**静默归 null**（不 400）；不引入锁与缓存 | `RecommendOrderTests` 4 例（基准值 / 1e5 连续 id 无碰撞 / 换 seed 换排列 / 编解码与非法格式） | **已完成（2026-11-02）** |
| `T87` | `R-28` 读路径：`ContentCache#getRecommendPage`（池读 → 去重 → rank 过滤排序 → 分块批量读 → 取 pageSize+1 判定到底） | 池读失败降级空页（不 500）；不新增 Redis key；不写缓存；空页只可能出现在候选耗尽时 | `ContentCacheTests` 既有索引用例 + 新增取页单测；`ContentIndexCooldownTests` 跟随新签名 | **已完成（2026-11-02）** |
| `T88` | `R-28` 契约层：`RecommendPageVO` + `StartController` 新增 `cursor`/`pageSize` + `OpenApiConfig` 可空登记 + spec 重生成 + 13 处数组断言跟随 | 端点不新增（仍 74 条）；`type`/`categoryId` 数字口径不动；`limit` 继续忽略 | spec 快照一致；`OpenApiSpecExportTests` 全绿；`ContentReadTests` 三处（列表 / 过滤 / 互动态）改读 `list`；登记簿 TSV 改 `ApiResponse<RecommendPageVO>` | **已完成（2026-11-02）** |
| `T89` | `R-28` 集成测试 + 反向验证：翻完全部页 == 池子全集、相邻页无交集、游标指向已删内容、脏 id 跳过仍填满、空池 / 池读失败 / pageSize 归一 / 非法游标 / 同游标两次一致 | 期望值按业务口径推导；夹具带对照（另一条内容 + 另一人）；不按"短页"判到底 | `RecommendFeedTests` **10 例**全绿；反向注入 3 条（去掉游标过滤 ⇒ 5 红 / `pageSize+1` 退化 ⇒ 6 红 / 排序键不看 seed ⇒ 3 红，明细见设计篇 §六） | **已完成（2026-11-02）** |
| `T90` | `R-28` 文档收口：设计篇按实现校准（复杂度 / 不变量 / "多取一条"）、`GAP-06` 后端状态、登记台回写；subagent review | 不新开编号；不改 `§2.6` 既有编号；代码注释只引 `architecture/**` | 三道门禁 0 违规；review 的 2 条 P0 + 4 条 P1/P2 全部已修（见回写）；设计篇按实现校准（分块 / 整除边界 / 判别力实测） | **已完成（2026-11-02）** |
| `T91` | **首页无限滚动前端落地**（`R-29`，消费 `R-28` 的游标信封）：D1–D11 一次落地，拆见 `T91-1`…`T91-7` | 不改后端契约；不新增端点；不动 5 列视觉基准；代码注释只引 `architecture/**` | 子任务全绿 + 弱网 / 真浏览器手动验收 + subagent review P0/P1 全修 | **已完成（2026-11-02；弱网/真浏览器验收未做，见回写）** |
| `T91-1` | 推荐流**纯状态机**：`src/feed/recommendFeed.ts`（items / cursor / phase / error / epoch + 七条迁移 + `feedView`） | 不依赖 Vue / DOM；不引状态库；epoch 必须能淘汰"刷新时在途的续页" | `recommendFeed.test.ts` 覆盖 append / 到底 / 失败 / retry / 旧 epoch / 刷新失败保留旧内容 / 八种视图态；反向注入：去掉 epoch 判断 ⇒ 竞态用例红 | **已完成（2026-11-02）** |
| `T91-2` | 端点层：`loadHome` 回带 `nextCursor` + `loadHomeMore(cursor)`；两者共用 `pageSize=20`；侧栏加 `?type=1` | 只有第一页 `splitHero`；`type` 用**数字 1**（不是 `VIDEO`）；`limit` 仍不识别 | `recommendEndpoints.test.ts` 补 4 例（无 cursor / 带 cursor / 只拆第一页 / 侧栏 `type:1`）；反向注入：续页也拆 hero ⇒ 红；去掉 `type` ⇒ 红 | **已完成（2026-11-02）** |
| `T91-3` | 首页接线：哨兵（`rootMargin 800px`）+ 单飞 + 连续补页、10 个续页骨架、到底文案、行内重试（429 倒计时、不自动重试）、空态改文案、刷新失败 toast / 页面错误 | 到底 ≠ 首屏空；替换不追加；有旧内容时刷新失败不丢列表；不引虚拟滚动 | `typecheck` / `test` / `build`；断言在状态机层（DOM 接线无 jsdom；composable 的 toast 分支 / 429 冷却也无自动化覆盖 —— 两层都靠 review + 手动，**明写不当已验**） | **已完成（2026-11-02）** |
| `T91-4` | 两态换一换：内联（原地）+ 右下浮层（先回顶再刷新，hover 展开「刷新内容」）+ 顶部；显隐由内联按钮可见性决定 | 两者不同时出现；浮层不摆「...」；内联刷新不回顶；浮层刷新先回顶 | `typecheck` / `build`；显隐与 hover 手动验收（无 DOM 测试，明写） | **已完成（2026-11-02）** |
| `T91-5` | 封面占位：数据已到、图未到只盖封面位（`.skeleton`）+ 加载完 150ms 淡入 | 不遮标题 / 作者 / 播放量；`coverUrl` 变化重置；破图仍 fallback；`reduced-motion` 关动效 | `typecheck` / `build` + DevTools 弱网手动验收（视觉无单测） | **已完成（2026-11-02）** |
| `T91-6` | 文档收口：`GAP-06` 销账、`前端.md`（§三 / §四 / §六 / §七）、`content-推荐流.md` §五 更正、`CURRENT_INTEGRATION` 回写 | 只增不改号；不新开编号空间；代码注释只引 `architecture/**` | 三道门禁 0 违规；`grep -rn "GAP-06" src` 指向已销账行 | **已完成（2026-11-02）** |
| `T91-7` | 回归 + subagent review：前端全量 + 代码 / 文档审查，P0/P1 全修 | 不改实现来"变绿"；发现问题先登记 `I-##` 或停下问 | `typecheck` / `test`（**354**）/ `build` 全绿；review 报告与修复留痕 | **已完成（2026-11-02；2 条 P1 全修 + 5 条 P2 修，2 条 P2 刻意不做）** |
| `T92` | 顶栏账号卡片（`R-30`）：抽 `UserMenu.vue` + `menuHover.ts`，修"4px 死区 + `display:none` 无宽限 ⇒ 唯一退出入口点不到" | 不动后端/不加端点；不编无域数据；不引 jsdom；代码注释只引 `architecture/**` | 子任务全绿 + 真浏览器验收 + review P0/P1 全修 | **已完成（2026-11-03）** |
| `T92-1` | 纯逻辑 `menuHover.ts`（open/scheduleClose/cancelClose/close/dispose + onChange） | 不依赖 Vue/DOM；180ms 可配；`dispose` 清定时器 | `menuHover.test.ts` **7 例**；反向注入 4 条（延时 0 / `cancelClose` / `dispose` / `open` 不清 ⇒ 各红） | **已完成（2026-11-03）** |
| `T92-2` | 抽 `UserMenu.vue`（头像放大、透明桥、三列、菜单、退出）；`AppHeader` 收敛 | hover + 触屏点击；`opacity/visibility`；focus/Esc/点外部；`reduced-motion` | `typecheck`/`build`；交互无 DOM 自动化（明写） | **已完成（2026-11-03）** |
| `T92-3` | 退出：`signOut()` + `replace('/')` | 无二次确认、无 toast、不新增 localStorage | 手动：回首页 + 登录按钮 | **已完成（2026-11-03）** |
| `T92-4` | 文档：`前端.md` §五/§六、`F-32`、回写 | 只增不改号；`前端.md` 已超线（`F-31`） | 三道门禁 0 违规 | **已完成（2026-11-03）** |
| `T92-5` | 回归 + review + 真浏览器（overlay/窄屏/触屏/键盘） | 发现问题先登记；上传媒体用 `.tmp/e2e-media` | 361 例 + typecheck/build；review P0/P1 全修 | **已完成（2026-11-03）** |
| `T93` | `R-31` 后端字段：`ContentVO` 透出 `imageUrls`（`videoUrl` 继续忽略）+ OpenApi nullable 注册；`/users/{id}` 补 `likeCount/commentCount` | 不加端点、不加迁移、不改信封 / 状态码；`imageUrls` 零额外读；计数只补不改语义 | `ContentReadTests` / `FeedReadTests` / `CoinReadTests` 独立 oracle（直查 `content_media` / 计数 key）+ 反向注入；spec 快照重生成 | **已完成（2026-11-04）**：`FeedReadTests` 12（+1 图集 / `videoUrl` 屏蔽）、`ContentReadTests` 21（+1 跨面图集）、`CoinReadTests` 7 / `ContentListCountTests` 9 跟随按面合约；spec 仅 +`ContentVO.imageUrls`。反向注入：去掉 profile 的 LIKE/COMMENT ⇒ 2 红（两处用例）；`toContentVO` 漏拷 `imageUrls` 时 `FeedReadTests` 红（先红后修） |
| `T94` | `R-31` 前端：`DynamicCardVM` / 适配器加 `imageUrls`（纯文字 meta、无标题跳过）；图文卡正文 + 图集 + 折叠；评论内联；抽 `useDynamicActions` | 只改时间线面；视频卡版式不动；DOM 接线无 jsdom 明写"未自动化" | adapter / 状态机单测 + `typecheck` / `test` / `build`；反向注入（去图集 / 双开评论） | **已完成（2026-11-04）**：图文/视频双形态、展开/收起、评论单开；前端 **375** 全绿 + typecheck/build。★ 真浏览器抓到「展开按钮被图集挤到首屏外」并修（按钮移到正文与图集之间）；DOM 接线无 jsdom（明写） |
| `T95` | `R-31` 作者模式：`/dynamic?author=` 走 `loadSpace` + `toDynamicPage`；顶部「全部 + 关注的人」头像行；作者头 / 空态 / 404 | 不改 `/feed`；作者模式 total = 作者全部投稿；翻页保留 `author` | 路由 / 分页 / 404 用例 + 反向注入（丢 `author`） | **已完成（2026-11-04）**：`dynamicQuery` 保留 author（feed 测试 13 例）；CDP 实测作者页第 2 页仍 `?author=1&page=2`、页头 / 选中态正确；404 未单独自动化（真浏览器 + 接口路径） |
| `T96` | `R-31` 空间三 tab：主页（3 + 预览 6 + 查看全部）/ 动态（时间线）/ 投稿（网格）；默认主页 | 同一 `contentPage` 两种呈现；类型子筛选不做（`GAP-32`）；翻页保留 `tab` | 三 tab 切换 / 默认页 / 分页 + `typecheck` / `build` | **已完成（2026-11-04）**：主页 / 动态 / 投稿 / 收藏，默认主页；旧无 tab 的 `?page=N` 回落到投稿（兼容）；CDP 实测三 tab、`?tab=works&page=2` / `?tab=dynamic&page=2` 均保留 tab；页面级无 jsdom（明写） |
| `T97` | `R-31` 标题：投稿页「不使用标题」+ 编辑页魔串回填 + 动态卡 / opus 跳过 `「无标题」` | 后端零改动；魔串**含直角括号**、trim 后全等；网格 / 搜索 / 历史保留 | `uploadDraft` / `creatorEdit` 单测 + 反向注入（魔串少括号不触发） | **已完成（2026-11-04）**：投稿 / 编辑「不使用标题」+ 魔串（含直角括号）回填 / 跳过；`uploadDraft` 26 / `creatorEdit` 20；反向断言：少括号的「无标题」不触发；opus h1 / `document.title` 均跳过 |
| `T98` | 收口：文档（`R-31` 回写 / `F-32` / `GAP-15` / `GAP-28` / `GAP-32` / `前端.md` / `视觉基准`）+ 三道门禁 + subagent review + 真浏览器 | `UPLOAD_ROOT=.tmp/e2e-media`；P0/P1 全修；`doc_stats` 新 advisory 必须登记（登记台预计 >40KB） | 前端 361+ 全绿 / typecheck / build；后端全量（`DB_POOL_MAX_SIZE=4`）；review 报告留痕 | **执行中** |
| `T99` | 动态卡图文**点图放大**（2026-11-06 用户反馈）：点图片不再跳详情，改为原卡内进入放大态（复用 `OpusImageGallery`）；标题 / 正文仍跳详情 | 只改点击行为 —— 视频封面仍跳播放页；九宫格沿用动态卡版式（2–4 张两列、单图 4:3），不把详情页"最多 3 列"搬进 feed；查看器不另写一份 | `opusImages.test.ts` 的 `feedGridColumns`（4 张 2 列 / 5 张 3 列 / 与 `gridColumns` 分开）+ 真浏览器：点图后 URL 不变、工具条 + 大图 + 缩略图、查看大图 / Esc / 收起、正文链接仍 `href=/opus/{id}` | **已完成（2026-11-06）** |
| `T100` | `R-32` 后端：`GET /feed` 加可选 `beforeContentId`（窗口 + 纯拉两条路径过滤，`total` 随锚点） | 不改响应形状 / 端点 74 / 迁移 / 限流表；非法参数静默忽略（与 `page` 同口径）；不带参数行为逐字不变 | `FeedReadTests` +4（锚点不重复 / total 随锚点 / 纯拉同样生效 / 非法参数忽略），**必须带"不带锚点会重复"的对照**；spec 重生成 | **已完成（2026-11-06）**：`FeedReadTests` 16（+4，含窗口外对照）；spec 仅 +1 参数；反向注入 2 条。 |
| `T101` | 列表状态机通用化：`feed/recommendFeed.ts` 提成与数据源无关（游标当不透明 token），动态页把 `page` 编成 token；空页文案参数化 | 纯函数、无 Vue / DOM；首页行为与既有断言不变；不引状态库 | 状态机既有 17 例全绿 + 动态页新增 token / 空页用例；反向注入（去 epoch ⇒ 红） | **已完成（2026-11-06）**：`listState.ts` 通用化 + `clearedFeed`（+1 例）；`dynamicPaging` 5 例；首页 17 例零语义改动。 |
| `T102` | `/dynamic` 全部动态无限滚动：哨兵 800px + 连续补页 + 续页骨架 + 行内重试 + 到底文案；移除 `Pagination` 与 `?page=` 读取 | 12/页；`page >= totalPages` 才到底；首屏空 ≠ 到底；不改后端；旧 `?page=` 落到第 1 页 | `dynamicFeed` / 适配器单测 + `typecheck` / `test` / `build`；真浏览器滚动到底 | **已完成（2026-11-06）**：12/页 + 哨兵 800px + 3 张续页骨架 + 行内重试 + 到底；`?page=` 不读；真浏览器 38 张全唯一。 |
| `T103` | 新动态提示：1 分钟轮询（不带锚点）+ 内联横幅 + 浮层胶囊 + 标题行「刷新」；点击回顶刷新 | 仅可见时跑、回前台补一次、单飞、401 停、429 退避、不落 localStorage；作者模式 / 空列表不轮询；失败 toast + 保留旧列表 | 轮询纯逻辑用例（fake timers）+ 真浏览器（切后台/回前台、点横幅） | **已完成（2026-11-06）**：1 分钟轮询 + 横幅/胶囊互斥 + 标题行刷新；真浏览器轮询/点击/回顶全过；失败 toast。 |
| `T104` | 右下「回到顶部」：`scrollY > 800` 出现，平滑回顶 | 只 `/dynamic`；`prefers-reduced-motion` 关动画；不遮内容列 | 真浏览器 + `typecheck` / `build` | **已完成（2026-11-06）**：`scrollY>800` 出现、点击回 0；reduced-motion 关动画。 |
| `T105` | 作者模式 `?author=` 无限滚动：走既有 `contentPage`，追加 + 按 id 去重；切作者清空并回顶 | 不改 `/users/{id}`；翻页 / 切作者都保留 `author`；total = 作者全部投稿 | 用例（去重 / 切作者 / 到底 / 404）+ CDP 实测 | **已完成（2026-11-06）**：作者模式 12→30 张全唯一、URL 保留 author、返回全部正常。 |
| `T106` | 收口：`feed.md` §3.4 / `前端.md` §四 / `接口契约` 分页表注；三道门禁 + 全量回归 + subagent review | 只增不改号；代码注释只引 `architecture/**` / `archive/**`；review P0/P1 全修 | 门禁 0 违规；后端全量（`DB_POOL_MAX_SIZE=4`）+ 前端全绿；review 报告留痕 | **已完成（2026-11-06）**：三道门禁 0 违规；后端 962 / 前端 383；review 4 P1 + 3 P2 全修。 |
| `T107` | `R-32` 延伸（用户 2026-11-06 点名）：空间页「动态」tab 改无限滚动，与 `/dynamic?author=` **同一思路**（复用 `useDynamicFeed` 作者模式 + `dynamicPaging`） | 不改后端 `/users/{id}`；不动主页 / 投稿 / 收藏三视图；动态 tab 不再读写 `?page=`（旧链接落到第 1 页）；不引「有新动态」轮询（个人页没有这个语义） | `typecheck` / `test` / `build` + 真浏览器：12→30 张全唯一、URL 不带 `page`、切 tab 不串页 | **已完成（2026-11-06）**：复用作者模式；12→30 全唯一、无 Pagination、URL 无 page；投稿 Page / 旧链接归一 / 切页回第 1 页均过。 |
| `T108` | 空间页动态 tab 右下「回到顶部」（用户 2026-11-06）；抽 `BackToTop.vue` 与 `/dynamic` 共用 | 只在 `tab === 'dynamic'` 渲染；`scrollY > 800` 才出现；`prefers-reduced-motion` 关平滑；不动主页 / 投稿 / 收藏 | `typecheck` / `test` / `build` + 真浏览器：动态 tab 出现 / 点击回 0 / 切投稿消失 / 回动态再出现；`/dynamic` 不回归 | **已完成（2026-11-06）**：4031 → 0；切投稿不渲染；回动态再出现；`/dynamic` 仍正常（0 console error）。 |
| `T109` | `R-33` 后端读路径：`V12` 加 `favorite_folder.cover_ref`；`FavoriteFolder` / 两个 folder VO / mapper 读回；`GET /favorite-folders` 与 `GET /users/{id}/favorite-folders` 返回**生效 `coverUrl`**（自定义优先，否则最新一条收藏的 `coverUrl`，取不到即 `null` 占位）；我的夹另带 `hasCustomCover` | `V1`–`V11` 逐字节冻结；**不加 `content_type` / 不改收藏写路径**；收藏域 SQL **不 JOIN content**；封面读取时现算、**不进 folders 缓存**；公开面只多 `coverUrl`，不加 `isPrivate` / `isDefault` | `FavoriteFolderCoverReadTests`（独立 oracle：自定义优先 / 最新一条 / 失效不回退 / 空夹 / 公开面键集 / 公开面自定义 / 另一用户另一夹干扰）+ 反向注入（去掉自定义优先 / 去掉最新一条 / 公开面多漏字段）| **已完成（2026-11-06）**：`FavoriteFolderCoverReadTests` **8 例**全绿；反向注入「去掉自定义优先」⇒ 1 例红（期望 custom、实际空串）。 |
| `T110` | `R-33` 后端写路径：`UploadType.FOLDER_COVER` + `/upload/favorite-cover/`；`PUT|DELETE /api/v1/favorite-folders/{id}/cover`；权限 / 类型 / ≤2MB / 旧文件在替换、恢复、删夹后清理 | 不改创建收藏夹端点；不写 `content_media`；旧文件只删 `/upload/favorite-cover/` 前缀；替换/恢复的 DB 写与文件清理顺序照 `AvatarService`（失败删新文件、成功后删旧文件）；删夹清理走 `AfterCommit` | `FavoriteFolderCoverWriteTests`（上传落盘+DB+读侧 / 替换删旧 / 恢复删旧 + 回自动 / 删夹删文件 + 另一夹封面存活 / 401 / 403 / 404 / 空文件 / 坏后缀 / >2MB / 另一用户不受影响）+ `FavoriteTransactionTests` 的删夹回滚不删封面 + `UploadType` 非法目录断言 + 反向注入（去掉归属校验 / 去掉旧文件清理 / 去掉大小校验）| **已完成（2026-11-06）**：`FavoriteFolderCoverWriteTests` **8 例**、`FavoriteTransactionTests` **3 例**全绿；反向注入「去掉归属校验」⇒ 403 变 200（1 红）、「去掉替换删旧文件」⇒ 旧文件仍在（1 红）。 |
| `T111` | `R-33` 契约与文档收口：`OpenApiConfig` 可空登记、spec 快照、端点清单 74→76 / 非 GET 45→47、`IdempotencyScope`、限流权重表；`favorite.md` / `favorite-需求.md` / `接口契约.md` / `前端.md` / `GAP-17` / `FURTHER_ISSUES`（`F-35`）/ 已知代价；`CURRENT_INTEGRATION` 回写 | 只增不改号；代码注释只引 `architecture/**` / `archive/**`；`GAP-17` 同步修正“四宫格”为“单封面”；前端任务另窗，**不写成已完成** | 三道文档门禁 0 违规；后端全量（`DB_POOL_MAX_SIZE=4`）0 失败；subagent review 的 P0/P1 全修或留痕 | **已完成（2026-11-06）**：文档门禁 0 违规；后端全量 **1021** / 0 失败；review 0 P0 / 2 P1 全修 + 6 P2 修 5 / 接受 1（归属校验复制）。前端未做，`GAP-17` 标“前端待接”。 |
| `T112` | `R-34` 空间收藏 tab 成为唯一浏览器：抽 `FolderRail` / `FolderCover` / `FolderCard` / 选中夹头部 / 条目网格；self/public 数据源、私密夹、稍后再看左栏视图、封面展示 | 不改后端 / 不加端点；**别人的页面绝不调用我的端点**；退出 / 换号清空私密数据；封面只读；不做 CRUD / 排序 / 批量；`FavoriteDialog` 不加封面 | `favoriteLibrary` 适配器 / 数据源用例（self 含私密、public 不含）+ `typecheck` / `test` / `build`；真浏览器 self / 别人 / 匿名三态 | **已完成（2026-11-06）** |
| `T113` | `/space/me` + 顶栏「收藏」直连 + `/favorites`、`/watchlater` 软弃用重定向壳（旧 query 映射） | 不硬删 `favorites.html` 入口；不改后端转发 / 限流清单（`/space/me` 走既有 `/space/{id}` 转发）；旧链接不 404；跨应用整页跳转用 `location.replace` | `/space/me` 登录 / 匿名 / 已登录、旧 URL 映射用例 + 真浏览器；`typecheck` / `build` | **已完成（2026-11-06）** |
| `T114` | 空间主页 tab 收藏区块：`收藏 N` + 最多 10 张夹卡 + 封面 + 查看更多；self 含私密（带标记），别人公开 | 不动投稿预览 / 动态 / 投稿；夹列表失败不拖垮主页；无夹不渲染；封面只读 | 真浏览器（3 夹卡 / 私密标记 / 空夹占位 / 查看更多）；⚠️ `slice(0,10)` 上限与 `isSelf` 判定**无 DOM 单测**（明写，不冒充已验）+ `typecheck` / `build` | **已完成（2026-11-06）** |
| `T115` | 收口：`GAP-17` / `前端.md` 页面矩阵 / `CURRENT_FRONTEND` 入口映射 / `favorite.md` §十一 口径 / `CURRENT_INTEGRATION` 回写；三道文档门禁 + 前端全量 + subagent review + 真浏览器（需要时 `UPLOAD_ROOT=.tmp\e2e-media`） | 只增不改号；代码注释只引 `architecture/**` / `archive/**`；旧入口删除另窗；发现问题先登记 `I-##` 或停下问 | 三道门禁 0 违规；前端全绿；review P0/P1 全修；真浏览器六步（self / 别人 / 匿名、私密消失、封面占位、稍后再看、旧链接重定向、主页区块） | **已完成（2026-11-06）**：门禁 0 违规；前端 394 / typecheck / build；E2E 40 项；review 1 P1 已修 + P2 修 2。 |
| `T116` | `R-35` 前端数据面：`updateMyFolder` / `uploadMyFolderCover` / `resetMyFolderCover`；`hasCustomCover` 进 `LibraryFolderVM`（self / public 两适配器）；纯函数 `folderEdit`（名称 trim / ≤20 / 空值、只提交变化字段、公开→`isPrivate`） | 不改后端 / 不加迁移；公开面 VO 没有 `hasCustomCover` ⇒ 一律 `false`，不拿它做编辑判断；只提交用户真正改过的字段（两个都没改 ⇒ 不发请求） | `folderEdit` 单测（trim / 空 / 超长 / 只改名 / 只改公开 / 两者 / 无变化）+ 适配器测试（`true` / `false` / 缺省）+ `typecheck`；反向注入：去掉 trim 或去掉 20 上限各红一条 | **已完成（2026-11-07）**：3 个 API + `hasCustomCover` 两适配器；`folderEdit` 纯函数（含"名字没改不校验"的旧长名守卫）；单测 **11** 例，前端 **405** 全绿；typecheck 绿。 |
| `T117` | `R-35` 交互面：`FolderRail` self 左栏 hover / focus ⋮ + 菜单（仅「编辑信息」）；`FolderEditDialog`（封面点击选图 → 复用 `CoverCropper` 16:9 → 裁剪确认即上传；恢复自动封面仅 `hasCustomCover`；名称 / 公开；简介只读禁用占位；错误 / 429 内联）；`SpacePage` 接线（改名 / 公开就地更新、封面后重拉 folders、保持选中夹、public / 匿名不渲染） | 不按钮套按钮；不改 `FavoriteDialog` / 主页夹卡 / 左栏「稍后再看」；封面成功后必须重拉 `GET /favorite-folders`（上传响应无 URL）；嵌套裁剪层要处理 `body overflow` 与 Escape，别让关裁剪层后编辑弹窗背后可滚 / 一起关掉；取消不改名称与公开，但**已上传的封面不回滚**（弹窗内明写） | 真浏览器 self / public / anonymous：hover 出 ⋮、菜单、改名 / 公开、默认夹可编辑、封面上传 → 裁剪 → 头部 / 主页夹卡更新、恢复自动、失败 / 429 提示、0 console error + `typecheck` / `test` / `build`；⚠️ DOM 交互没有 `@vue/test-utils`，回写明写"无 DOM 单测" | **已完成（2026-11-07）**：⋮ + 菜单（键盘焦点回收）/ 编辑弹窗 / 封面裁剪上传 + 恢复 / 默认夹可编辑；`foldersMutationVersion` 守卫封面后重拉；真浏览器 **50/50**（self / public / anonymous、取消不回滚封面、非图片拦截、默认夹、0 console error）；`typecheck` / `test` **405** / `build` 绿。⚠️ 菜单与弹窗 DOM 交互**无 DOM 单测**。 |
| `T118` | 收口：`GAP-17` / `favorite.md` §十一 / `前端.md` 页面矩阵 / `CURRENT_INTEGRATION` 回写；三道文档门禁 + 前端全量 + subagent review + 真浏览器 | 只增不改号；代码注释只引 `architecture/**` / `archive/**`；review P0/P1 全修；发现问题先登记 `I-##` 或停下问 | 门禁 0 违规；前端全绿；review 报告留痕；`GAP-17` 状态改为"已销账（编辑 / 封面上传 / 恢复）" | **已完成（2026-11-07）**：`GAP-17` 销账 / `favorite.md` §十一 / `favorite-需求.md` / `前端.md` / `F-35` 行为面更新；三道门禁 0 违规；review **0 P0 / 0 P1 / 9 P2**，修 6（版本守卫、旧自定义 URL 占位、嵌套 overflow 计数锁、菜单焦点、旧长名、测试 oracle + 规则复用），2 条接受（`R-35` 注释与仓内先例一致；"恢复失败入口消失"不成立），登记台时序已修。 |
| — | *（本窗第一件事：跑基线 + 列联调清单，见 §一 / §2.1）* | — | — | — |

> **R-27 回写（2026-11-01）**：`T80`–`T85` 已完成。后端 `V11` + 3 端点 + 生效 `avatarUrl` + 公开批量读；前端默认头像稳定 URL、空间页弹窗 + 1:1 裁剪、评论 / 动态 / 关注 / 图文作者批量接线。
> subagent review 抓到的 2 条 P1（去重用例假绿 / `/avatars/` 未进静态跳过清单）与 3 条 P2（头像 Content-Type / 预览尺寸 / spec 必填登记）已修；
> 实机验收覆盖默认切换、自定义裁剪上传、空间页与评论区头像。已知代价（不重编码 / 崩溃孤儿 / 头像不进缓存）见 `R-27` 推翻条件。

> **R-28 回写（2026-11-02）**：`T86`–`T90` 完成（**后端 + 前端形状适配**）。`recommended` 改游标分页
> （`{list,nextCursor}`、随机序、翻页不重不漏）；**前端只读第一页，无限滚动未做**（行为另窗讨论）。
> 端点不新增（仍 74 条）。反向注入 3 条实测：去掉游标过滤 ⇒ 5 红；`pageSize+1` 退化 ⇒ 6 红；
> 排序键不看 seed ⇒ 3 红。subagent review 抓到 2 条 P0（前端消费方未跟随契约、脏 id 用例是假绿）
> 与 4 条 P1/P2（位次游标判别力不足、缺整除边界、limit 判别力为零、分块过取），均已修。

> **R-29 / T91 回写（2026-11-02）**：`T91-1`–`T91-7` 完成。首页接 `recommended` 游标无限滚动
> （`src/feed/recommendFeed.ts` 纯状态机 **17 例** + 端点 **4 例**；`epoch` 淘汰在途续页 / 到底 = `cursor=null` /
> 首屏空 ≠ 到底）；两态「换一换」（内联原地 / 浮层先回顶再刷新，互斥）、封面只盖封面位 + 150ms 淡入、
> 续页 10 个整卡骨架、行内重试 + 429 倒计时、刷新失败有旧内容走 toast。前端 **354** 例全绿，
> typecheck / build 绿，`doc_links` / `doc_refs` 0 违规。**反向注入 4 条实测**：去 `receiveMore` 的 epoch ⇒
> 「刷新淘汰在途续页」红；`loadHomeMore` 也拆 hero ⇒「续页不拆轮播」红；首屏空页保留旧 items ⇒
> 「已到底后刷新遇到空页」红；去 `failMore` 的 epoch ⇒「旧失败晚到」红。
> subagent review 抓 **2 条 P1**（首屏空页被误判成续页错误 ⇒ `loadingMore` 卡死 / `failMore` 的 epoch 用例假绿）
> + **7 条 P2**；P1 全修，P2 修 5 条（`nextCursor` 空串归一 / 重试后补预取 / 轮播计入"有无内容" /
> 卸载后不弹 toast / 文档小账），**刻意不做** 2 条（刷新 429 冷却：没有倒计时可看的禁用更糟；
> composable 单测：要先把 `onBeforeUnmount` 解耦，属另一层重构，已明写不当已验）。
> ★ **未验**：弱网 / 真浏览器（滚动、hover 变形、1440 下的浮层）**没做** —— 本机 3306/6379/5672 未起，
> 未起服务、未上传媒体（若做，`UPLOAD_ROOT=.tmp/e2e-media`）；`useRecommendFeed` 的 toast / 冷却分支
> 无自动化覆盖。两条都按"没验"记，**不当已验**；封面 / 浮层几何**未逐像素标定**（见 `视觉基准` §2.8）。

> **R-30 / T92 回写（2026-11-03）**：根因不是手速 —— 旧 `.user-chip` 高 40、卡片 `top: 44px` 留 **4px 真空**，
> 且 `display: none` 无宽限，指针穿缝即断。现在 `menuHover.ts`（**7 例**）+ 透明外壳 `padding-top: 8px` + 180ms 宽限；
> 桌面 hover / 触屏点击（`:focus-visible` 区分键盘）/ Esc / 点外部；卡片以**头像中心**展开（rect 实测 chip 与卡片中心同为 1065）。
> **反向注入 4 条**：延时 0 ⇒ 3 红；`cancelClose` / `dispose` / `open` 不清 ⇒ 各 1 红；还原后 7 绿；前端 **361** 例全绿。
> **CDP 实测**：1418×802 慢速 14 步跨缝全程开；离开 120ms 仍开、380ms 关；Esc 两条路径都关；
> 390px 触屏点击开卡片且 URL 不变；375 高横屏 panel 可滚、退出按钮入视口；退出后回 `/`、登录按钮出现、token 清空。
> ★ **抓到并修 1 个真 bug**：触屏 `focusin` 先开 → `click` toggle 又关，改 `onFocusIn` 只认 `:focus-visible`。
> **review 1 轮**：0 P0；4 P1 全修（Esc 改 document 级 / focusin 加 `relatedTarget` 守卫 / 矮视口 `max-height+scroll` /
> 补 `open()` 清计时器用例）；P2 修 8 条（`CREATOR_HREF`、`aria-controls`、`pointerdown`、窄屏贴住 chip、`replace` 等），
> 1 条不做（卡片开着时计数不轮询，已声明失败降级）。
> **未验**：真机 / Safari、`prefers-reduced-motion` 观感。起服务 `UPLOAD_ROOT=.tmp/e2e-media`，未上传媒体；
> 测试账号 `id=247` 已删。
> **P2 不做 1 条**：计数在卡片**开着**时失败不重试（已声明"失败降级 `-`"，加轮询反而打请求）。

> **R-31 / T93–T98 回写（2026-11-04）**：动态页补齐。后端 `ContentVO.imageUrls` 透出（`videoUrl` 仍屏蔽）
> + `/users/{id}` 三维计数；前端图文卡正文 / 图集 / 展开 + 评论内联 + 作者模式 + 空间三视图 + 「不使用标题」。
> **测试**：前端 **375** 全绿（上一档 361）；后端全量 **999**（上一档 997；+1 `FeedReadTests` 图集 / `videoUrl`、
> +1 `ContentReadTests` 跨面图集），`DB_POOL_MAX_SIZE=4`。反向注入：profile 去 LIKE/COMMENT ⇒ 2 红；
> `toContentVO` 漏拷 `imageUrls` ⇒ `FeedReadTests` 红（先红后修）。
> **真浏览器（CDP，headless 1685×834）**：12 卡 = 2 图文 + 10 视频；无标题图文卡无标题、meta「发布了动态」；
> 长文展开 / 收起；评论卡内单开；作者模式 `?author=1` 第 2 页仍保留 author；空间三 tab 默认主页、动态 2 卡、
> 投稿 `?tab=works`、两 tab 第 2 页均保留 tab；opus 无标题 h1 为 null。★ **抓到并修 1 个真 bug**：
> 展开按钮放在图集之后被挤出首屏（移到正文与图集之间）。
> **subagent review**：1 P0（切作者 / 翻页请求竞态 ⇒ `requestSeq` + 切作者先清空）+ **7 P1**
> （旧 `?page=N` 链接掉回主页 / 作者模式匿名文案 / 列表契约扩散无断言 / `videoUrl` 无反向断言 /
> 缓存矩阵与 GAP 台账过时 / T 台账验收口径）全部修；P2 修 7 条（视频评论数重复、opus `document.title`、
> 空间时间线头像多打一趟、提交期间草稿快照 + 控件禁用、图集 oracle 逆序插入、越界页空态、过期注释），
> **接受 1 条**（`hasMoreWorks` 用 DB `total`：跳过无效条目时可能误显示按钮，属边界；已在本段写明）。
> **登记**：`doc_stats` 登记台 **45.8 KB** / `前端.md` **46.0 KB** ⇒ `F-33` / `F-31`（不静默放过）。
> **未验**：真机 / Safari、1707 逐像素、评论发表 / 点赞写入（只验展开与读取）；既有 dev 视频封面在
> `UPLOAD_ROOT=.tmp/e2e-media` 下 404（目录切换预期，不是回归）。

> **R-32 / T100–T106 回写（2026-11-06）**：动态页翻页改无限滚动。
> **后端**：`GET /feed` 加**可选** `beforeContentId`（窗口先过滤 `id<=锚点` 再切页、`total` = 锚点内条数；
> 纯拉 count / 当页同条件；缺省 / 非法 / ≤0 静默忽略）。响应形状 / 端点 **74** / 迁移 / 限流表全不动，
> spec 仅 +1 个 query 参数。`FeedReadTests` **16**（+4），其中"锚点不重复"用例带**双重对照**：
> 不带锚点会重复（c3 重现）、窗口外内容（c0 不在收件箱）被排除 ⇒ 退化到纯拉时 `total` 会变 5。
> **反向注入 2 条**：窗口过滤去掉 ⇒ 锚点用例红；纯拉计数去掉条件 ⇒ `total` 5≠4。
> **前端**：`feed/recommendFeed.ts` → `feed/listState.ts`（令牌不透明、推荐流 / 动态流共用；新增 `clearedFeed`
> 保留 `epoch`）+ `feed/dynamicPaging.ts`（令牌 = 下一页页码 / 按 id 去重）+ `useDynamicFeed` + `useNewDynamics`。
> 全部动态 12/页、哨兵 800px、续页 3 张骨架、行内重试、`page >= totalPages` 到底（文案「这次的动态到底了」）；
> 作者模式 `contentPage` 追加 + id 去重、`?author=` 保留；旧 `?page=` 不读。
> 「有新动态」= **1 分钟**轮询 `page=1&pageSize=3`（不带锚点）比首条 id：仅可见 / 回前台补一次 / 单飞 / 401 停 /
> 429 退避 / 不落 localStorage / 作者模式与空列表不跑；内联横幅 + 浮层胶囊互斥（只属于全部动态），
> 点击回顶 + 重拉第 1 页；标题行「刷新」；右下「回到顶部」（`scrollY>800`）。刷新失败（有旧内容）**toast + 保留列表**；
> `superseded / busy` 不停轮询（否则会把胜出方的轮询关掉）。
> **真浏览器（headless 1600×1000，dev DB）**：首屏 12 卡 / 无 Pagination；发布 301 后第 2/3/4 页请求均带
> `beforeContentId=301`，24 张 24 唯一，滚到 38 张仍 38 唯一 + 到底文案；轮询 `pageSize=3` 出现后
> 横幅 + 胶囊可断言（pill 在 scrollY 10773 可见）；点胶囊 ⇒ scrollY 0、新动态在首位、横幅消失；
> 回顶在 scrollY 3503 出现、点击后 0；作者模式 `?author=1` 12→30 张全唯一、URL 保留 author、返回全部正常；
> 无 console error。★ **抓到并修 1 个真 bug**：哨兵在登录门之后才渲染，`onMounted` 里 `restoreSession`
> 之后 DOM 未 flush ⇒ `sentinel.value` 仍 null，observer 从未 observe（滚到底不加载）；改 `watch(sentinel)`。
> **subagent review**：0 P0 / **4 P1 全修** —— ① 作者头在 epoch 校验前写（慢响应会把 A 的名字盖到 B 的列表）；
> ② `stop()` 不清横幅（切作者 / 退出后横幅残留，点它永远消不掉）；③ `refresh()` 的 `false` 被当"停轮询"且失败静默
> （改三态结果 + toast，`superseded / busy` 不停）；④ 文档 5 件未提交。**P2 修 3 条**（`前端.md` 的
> `recommendFeed` 残留 / 登记台编号 / 窗口路径判别力 / id 序≠时间序的前提写进 `feed.md`），**不做 2 条**：
> 作者模式整页重复会误报空页（需 12 条新内容挤在同一页，极端）、`useUserAvatars` 续页重取已加载 id
> （≤300 条时每页 ≤3 次批量，接受；真成问题再改增量）。
> **登记**：`F-34`（空间「动态」tab 仍翻页，与 `?author=` 同一时间线两套翻法；下期零后端接线）。
> **未验**：真机 / Safari / 触屏、1707 逐像素；轮询的 401/429/后台切换只靠 review（无 jsdom / 未造故障）；
> E2E 夹具（账号 251/252、内容 301/302、follow/inbox）已清理，1/3 的 `follower_count` 回滚到 9/6。

> **T107 追加（2026-11-06，`R-32` 延伸）**：空间页「动态」tab 也改无限滚动（用户点名；`F-34` 兑现）。
> 直接复用 `useDynamicFeed` 作者模式 —— 12/页、哨兵 800px、续页骨架、行内重试、到底文案；
> 主页 / 投稿 / 收藏仍走各自分页。动态 tab 的 `authorHeader` 补空间头部；切入 `refresh`、切出 `reset`；
> 旧 `?tab=dynamic&page=2` 静默 replace 掉；从动态切主页 / 投稿时补一次 `loadWorks`
> （防"首屏直接落动态 ⇒ `works` 还是 null"或"投稿页码已变"）；`toggleFollow` / 登录态变化按当前 tab 分派重载。
> **真浏览器**：`/space/1?tab=dynamic` 12→30 张全唯一 + 到底；无 `.pagination`；URL 无 `page`；
> 切投稿 Page 正常（第 1 页）；回动态重置为 12；切主页 `featured` 正常；旧 `page=2` 链接归一；
> 「投稿 page=2 → 动态 → 投稿」回第 1 页；无 console error。前端 **383** / typecheck / build 全绿。
> **subagent review**：0 P0 / **2 P1**（换人后切投稿拿上一个人的 works / 首屏刷新在途时关注态被旧快照盖回）
> + **4 P2**（切入失败整页错误、`reset` 不淘汰在途首屏、`loadWorks` 晚到覆盖 header、`replace` 丢 query）全修。
> 补测：CDP 拦 `/api/v1/users/1` ⇒ 动态面板单独出错、四个 tab / 头部仍在、面板内「重试」，解除后 12 张恢复；
> SPA 换人（`pushState + popstate`，组件复用）实测头部变「基米」、动态 / 投稿分页与内容均为 3 号（P1-1 的判别）。
> **未验**：真机 / 触屏；动态 tab 的点赞 / 评论写入（只验读取与渲染）。

> **T108（2026-11-06）**：空间动态 tab 右下加「回到顶部」；抽出 `BackToTop.vue` 与 `/dynamic` 共用
> （`scroll` 监听 / 按钮 / 样式收进组件，`v-if` 挂载 + 卸载时移除监听）。真浏览器：
> `scrollY 4031 → 0`、切投稿不渲染、回动态再出现；`/dynamic` 仍正常；0 console error。

> **R-33 / T109–T111 回写（2026-11-06）**：收藏夹封面**后端**交付。
> **数据**：`V12` 只加 `favorite_folder.cover_ref`（不引入 `content_type`）；自动封面 = 夹内**最新一条收藏**
> （窗口查询 `create_time DESC, id DESC` + 一趟 `ContentService.loadContentVOs`），失效 / 无封面 / 空夹 ⇒
> `coverUrl=null` 前端占位、**不回退**；自定义优先；公开面只多 `coverUrl`，我的夹另带 `hasCustomCover`。
> **端点**：`PUT|DELETE /api/v1/favorite-folders/{id}/cover`（落 `/upload/favorite-cover/`，≤2MB；
> 替换 / 恢复 / 删夹删旧文件；`DELETE` 204 幂等）。folders **不缓存**，收藏域 SQL **不 JOIN content**。
> **测试**：`FavoriteFolderCoverReadTests` **8** + `FavoriteFolderCoverWriteTests` **8** +
> `FavoriteTransactionTests` **3**（含"删夹回滚不删封面"）；反向注入 3 条实测：去自定义优先 ⇒ 1 红、
> 去归属校验 ⇒ 403→200 1 红、去替换删旧 ⇒ 旧文件仍在 1 红。契约：端点 74→76 / 非 GET 45→47，
> spec 与 `api.d.ts` 重生成，幂等 / 限流 / 可空登记全跟随；`OpenApiSpecExportTests` 9 例绿。
> **subagent review**：0 P0 / **2 P1**（删夹回滚无契约用例、删夹用例无干扰行）+ 6 P2
> （公开面自定义零覆盖、`UploadType` 非法目录无红灯、`isCustom` 脏值、base-url 不一致、
> `hasCustomCover` 缺键假绿、归属校验复制）⇒ P1 全修；P2 修 5 条，**接受 1 条**
> （归属校验两处复制：同域小方法，抽公共组件属另一层重构）。
> **前端未做**（按用户 2026-11-06 约定：后端工作量大则留给下一窗）：空间「主页」tab 收藏区块 +
> `/favorites` 选中夹头部封面 / 上传 / 恢复；`GAP-17` 已标"后端已交付、前端待接"。
> **回归**：后端全量 **1021** / 0 失败（`DB_POOL_MAX_SIZE=4`，2026-11-06）。
> **前端**：`npm run gen:api` 重生成 `api.d.ts` + `npm run typecheck` 绿；**未做前端功能**。
> **未验**：真浏览器 / 真实上传（未起服务；若起用 `UPLOAD_ROOT=.tmp/e2e-media`）；
> 封面文件清理的并发 / 崩溃窗口只靠 code review。

> **R-34 / T112–T115 前端回写（2026-11-06）**：收藏夹查看入口收敛到空间收藏 tab，`/favorites` 软弃用。
> **T112**：抽 `components/favorite/*`（`FolderRail` / `FolderCover` / `FolderCard` / `FavoriteFolderHeader` /
> `FavoriteItemGrid` / `FolderHomeSection`）；`api/favoriteLibrary.ts` 按 `isSelf` 分流 self / public 端点，
> **绝不混用**；`SpacePage` 收藏 tab 改全宽「左栏 + 选中夹/稍后再看 + 网格/分页/移出」；身份用
> `restoreSession` 后的 `currentUserId()`，退出 / 换号 / 换人清空 folders + items + 选中夹；封面只展示
> （`coverUrl=null` 灰 TV 占位、破图 fallback）。
> **T113**：`/space/me`（登录态解析后 `replace` 数字 uid）+ 顶栏「收藏」直连；`/favorites`、`/watchlater`
> 变薄重定向壳（`legacyFavoriteTarget` 纯函数 + 单测），旧 query 平移；**未硬删** HTML 入口。
> **T114**：主页 tab「收藏 N」+ 最多 10 张夹卡 + 查看更多；self 含私密（带标记），别人公开；无夹不渲染，
> 失败只块内联重试；修了"首屏 works 装载先置 loading ⇒ 收藏区块永不装载"的时序坑。
> **测试**：前端 **394**（+11：数据源 7 / 旧 URL 映射 3 / 封面映射 1）全绿；`typecheck` / `build` 绿。
> **真浏览器 E2E**（`UPLOAD_ROOT=.tmp\e2e-media`，Chrome 1600×1000）：**40 项全过、0 console error** —
> self 左栏含私密 + 稍后再看 2、选中夹头部封面、私密夹/稍后再看/空夹（灰 TV + 空文案）、主页 3 夹卡 +
> 私密标记 + 查看更多、匿名只剩公开夹且 crafted view=later 归一、旧 `/favorites`/`/watchlater` 重定向、
> 顶栏 `/space/me`、夹内 / 稍后再看单条移除；截图 `.tmp/r34-{self,home,anonymous}-folders.png`。
> 生产 8080 冒烟：`/space/me?tab=folders`、`/space/256?tab=folders` 200，`/favorites`、`/watchlater` 200。
> **未做 / 另窗**：封面**上传 / 恢复**（GAP-17 只销展示）；旧 `favorites.html` 入口与转发 / 限流 / 测试清理；
> 窄屏 / 触屏未验；E2E 的测试用户 256 因无删除用户端点留在 dev 库（夹具夹 / 稍后再看已清）。
> **subagent review**：0 P0 / **1 P1**（`refreshItemsSilently` 绕过 `itemsRequestId`：退出 / 换人后在途 self 面响应可把私密条目写回公共面）+ 3 P2（失败移出切走后不回滚、主页多发一次 `/watch-later`、T114 验收把"上限"写成有用例）。
> P1 已修：`refreshItemsSilently` 共用 `itemsRequestId` + `isSelf/userId/folder/view` 四重校验；P2 修 2（失败移出用服务端重读、主页不取稍后再看计数），T114 验收改为**明写无 DOM 单测**。另补：自动选夹的 `router.replace` 也带 `ownerId + isSelf` 守卫（在途换人不会把上一用户的夹 id 带进新页面）。修复后 `typecheck` / `test` **394** / `build` 绿；**未重跑全量 E2E**（夹具夹已清理，改动是防护性分支，正常路径由上一轮 40 项覆盖）。T115 完成。

> **R-35 / T116–T118 回写（2026-11-07）**：收藏夹信息编辑落空间收藏 tab self 左栏。
> **入口**：hover / focus ⋮ → 菜单仅「编辑信息」；弹窗 = 封面 / 名称（≤20）/ 公开 / 简介只读占位，默认夹可编辑；
> `FavoriteDialog` 与主页夹卡不动，删除收藏夹不在本窗。
> **封面**：点封面 → 复用 `CoverCropper` 16:9 / 1280×720 → 裁剪确认**即上传**；`hasCustomCover` 进 VM，
> 仅自定义时显示「恢复自动封面」；取消不撤销已上传封面（弹窗明写）。**同步**：改名 / 公开就地更新；
> 封面后重拉 `GET /favorite-folders`（响应无 URL），用 `foldersMutationVersion` 淘汰旧列表；
> 替换旧自定义封面时先置 `null` 占位（旧文件已被服务端删，避免 `<img>` 打 404）；嵌套弹层改按层计数锁 body。
> **验证**：前端 **405** 全绿 / `typecheck` / `build`；真浏览器 **50/50**（`localhost:5173` + 运行中的 8080 后端；
> self / public / anonymous、hover ⋮、改名+公开双向、默认夹、封面裁剪上传+恢复、取消不回滚、非图片拦截、0 console error）。
> **review**：0 P0 / 0 P1 / 9 P2；修 6（版本守卫、旧自定义 URL 占位、嵌套 overflow 计数锁、菜单键盘焦点、
> 旧长名不卡死、测试 oracle + 规则复用），2 条接受（`R-35` 注释与仓内 52 处 R/T 先例一致；
> "恢复失败入口消失"不成立 —— `customCover=false` 只在 await 成功后执行），登记台时序已修。
> **未做 / 下窗**：删除收藏夹（另开）；旧 `favorites.html` 入口与 WebMvcConfig / 限流 / 契约测试清理（`F-35` 剩余项）。
> E2E 用户 257（无删除用户端点）及其默认夹留在 dev 库；夹具夹 / 自定义封面已删（上传→恢复会删旧文件）。

---

## 四、结转与留池

* **反面清单的唯一事实源** = [FURTHER_ISSUES.md](./FURTHER_ISSUES.md)（现 12 行：`F-10` / `F-14` / `F-18` /
  `F-21` / `F-26` / `F-28` / `F-30` / `F-31` / `F-32` / `F-33` / `F-34` / `F-35`），每行带**推翻条件**；开窗时逐行核销一次，不许静默跳过。
* **已核销只留一行摘要**（含短版推翻条件）：旧池全文（`F-01`–`F-27`）已归档到 `archive/backend-2026-10-30/`，
  新池的「已核销」表只为"代码 / 文档引用的编号"保一条可解析的摘要（链：编号 → 摘要 → 已知代价台账）。
* 需要拍板的 `R-##` → 本文件；**执行中发现的问题** → [CURRENT_ISSUES](./CURRENT_ISSUES.md)（`I-##`）；
  **本期不解决的** → [FURTHER_ISSUES](./FURTHER_ISSUES.md)（`F-##`）。三个号各归各位，不混。

---

## 五、"想过但否决"的方案留痕去哪里（**2026-10-30 起生效**）

> 旧做法是塞进 `FURTHER_ISSUES` 的 `F-##` 行（`F-21` 因此涨到 5 KB，2026-10-30 已压缩到 1 KB）。
> 新做法：**落该域 / 横切面的 `*-已知代价.md`**，但**两个前提**——否则连"否决"都不值得留：

1. **方案成熟**：能写全三样 —— 方案原貌 / 否决理由（逐条可复核）/ 推翻条件（出现什么事实该重考虑）。
2. **预计流量大时现状真会成为严重瓶颈**：这是它进台账而**不是**进"随手记"的理由。

不满足 ⇒ **只留 git**，不落文档（`architecture/已知代价-模板.md` 里有面向台账的写法）。
⚠️ 台账措辞是"**已拍板接受的代价**"——被否决的方案是"**没被选中**"，两条都算台账条目，但**标签不同**：
接受类写现状的代价，否决类写"**为什么没走那条路**"。

---

## 六、边界与去向

| 不在本篇的内容 | 去向 |
|---|---|
| 页面 × 端点矩阵 / 类型三层 / 请求层约定 | [architecture/frontend/前端.md](../architecture/frontend/前端.md) |
| 用户看不见的客户端行为（上报 / 重试 / 退出信号 / 乐观更新） | [客户端静默机制](../architecture/frontend/客户端静默机制.md) |
| 视觉基准 / 截图复算 / 2560 校准 | [视觉基准](../architecture/frontend/视觉基准.md) |
| 端点契约（状态码 / 错误码 / 入参出参形状） | [接口契约](../architecture/tech/api/接口契约.md) |
| 跑起来 / 门禁 / 环境前置 | `frontend/README.md` · [CURRENT_FRONTEND.md](./CURRENT_FRONTEND.md)「门禁」「环境与中间件」（上一期沉淀，仍然有效） |
| 编号是什么（`T#` / `R-##` / `F-##`） | 各专项活速查（`favorite.md` / `coin.md` / `history.md` / `写接口幂等.md` 附录） |
| 跨期元约定 `G#` / `C#` | [`AGENTS.md`「跨期元约定」](../../AGENTS.md) |
