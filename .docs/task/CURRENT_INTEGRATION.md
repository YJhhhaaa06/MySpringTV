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
* ★ **编号**：任务 **`T76`** 起（`T71`/`T72` 原有，`T73`–`T75` = 历史记录开关，`T76`–`T79` = `R-26` 见 §三）/
  决策 **`R-27`** 起（`R-25` = 记录开关、`R-26` = 历史可删 + per-day 口径）/ 问题 **`I-33`** 起（`I-31`/`I-32` 已终结）/ 结转 **`F-30`** 起。
  ⚠️ **编号不回收、不复用**：后端期已用的 `I-01`–`I-30` 随归档留在原地，联调期**从 `I-31` 继续**；
  `F-##` 同理（`F-01`–`F-29` 已用，见 [FURTHER_ISSUES](./FURTHER_ISSUES.md)）。
* ★ **回归基线**：默认集 **928**（2026-10-31，`DB_POOL_MAX_SIZE=4`，`T79` 后全绿；上一档 907）+ 前端 **304**（`T78` 后）；
  `resilience` 组仍是旧登记 **41**（2026-10-25，本轮未复跑，需时另跑 `--group resilience`）。
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
| — | *（本窗第一件事：跑基线 + 列联调清单，见 §一 / §2.1）* | — | — | — |

---

## 四、结转与留池

* **反面清单的唯一事实源** = [FURTHER_ISSUES.md](./FURTHER_ISSUES.md)（现 8 行：`F-10` / `F-14` / `F-18` /
  `F-21` / `F-26` / `F-28` / `F-29` / `F-30`），每行带**推翻条件**；开窗时逐行核销一次，不许静默跳过。
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
