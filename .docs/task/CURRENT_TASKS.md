# 当期任务（Current Tasks）

> **定位**：登记**当期正在执行的任务**及其执行态（四要素 + 三句以内回写）。
> 计划态（分期、骨架设计、验收口径）在 [收藏功能-分期与设计.md](收藏功能-分期与设计.md)；
> **本期范围与反面清单**在 [CURRENT_NEEDS.md](CURRENT_NEEDS.md) §四。**本文件只记执行态**，不复制计划。
> **需求来源**：`CURRENT_NEEDS.md`（N# / R-##）；**执行中发现的问题** → `CURRENT_ISSUES.md`。
>
> ★ **膨胀控制（2026-10-11 起执行）**：任务完成即标注，**期收尾时清出本文件**（归宿 = git commit）。
> 清出前**必须先把该编号收进** [收藏功能-编号索引.md](收藏功能-编号索引.md) ——
> 代码 / mapper / 测试注释里有**数百处** `T#` / `R-##` / `I-##` / `G#` 引用，那份索引是它们**唯一**的查证入口。
> **编号不回收**（下期顺延）；**登记台文件复用、不改名**（改名会让外部引用悬空）。

---

## 周期头（每窗开读）

* **方向**：**第 3 张清单 —— 跨域性能与契约收口**（`CURRENT_NEEDS.md` §三 **R-12 已拍板** 2026-10-13；**本期纳入 / 反面清单见其 §四**）：
  **T13** 列表互动态形态统一（**R-13**）✅｜**T14** 评论读路径有界化（**F-20 ②**）✅｜**T15** 收藏缓存层重判（**F-17**）✅ **三题全部交付（2026-10-14）**。
  ⚠️ **限流不做** —— 已补登记 `FURTHER_ISSUES.md` **F-22**，与 **F-01 / F-02** 同属"上线准备度"**专项**（理由见 §四 反面清单）。
* **历史**：收藏一期（有无）✅ T1–T8（2026-10-08 → 2026-10-11）｜收藏二期（性能 + R-10 契约演进）✅ 已收尾 T9–T12（2026-10-11 → 2026-10-12）
* **本周期**：`2026-10-13` 起 · **第 3 张清单**（编号 **T13 起**，**不回收 T1–T12**）
* **收尾 checklist**（下一期开窗时逐条打勾）：☐ 回归基线全绿 · ☐ 完成态已清出（记入 git）· ☐ 问题已结转（`FURTHER_ISSUES.md`）· ☐ 工作树干净
* **回归基线**：`python tools/run_tests.py` → **445 例全绿**（T15 交付后实测 2026-10-14，440 + 5 新增）；`--group resilience` → **34 例**全绿
  > 📌 ★ **基线更正（2026-10-14 T15 交付时核出）**：本文件此前记的 **439** 少算了 1 —— T14 的 `c63d1ed`（补 `page` 忽略用例）
  > 加了一条测试但没同步总数。双重印证：① `@Test` 计数 471（T14-1）→ 472（T14-2）→ 477（T15 后，+5）；
  > ② `INDEX.md` §一 已按 **440**（436 + 4 = `CommentReadTests` 10→14）记账。⇒ **本张清单的基线 = 440**，与 INDEX 一致。

---

## 运行约定（只保留当前仍然有效的）

| 号 | 约定 |
|----|------|
| G1 | 默认一任务一窗口一 commit；★ **但允许执行者自拆** —— 任务可能是连贯的、要深入调查后才知怎么拆，执行者可自行拆子任务与多个 commit（**每 commit 仍须可编译**，message 带任务号），拆法写进回写 |
| G2 | **证据可复核**：入口与验收能定位（文件:行 或 可复现命令），不接受纯文字断言 |
| G3 | **疑问分级**：可自答的 → 任务回写留一句；须裁决的 → **停下问用户**，不擅自拍板、不硬扛、不超范围 |
| G4 | 一任务 = 四要素 + 三句以内回写；探索过程 / 失败尝试 / 中间稿一律走 git，不进本文件 |
| G5 | **声明式机制必须配契约测试**：断言通过 ≠ 机制生效；写「证明某机制」的用例前先自问「去掉那行实现会红吗」（详见仓库根 `AGENTS.md`「测试纪律」节） |
| G6 | 改 schema 走 Flyway **最高版本 +1**（V1 冻结、V2 / **V3 已应用** ⇒ 下一次是 **V4**）；⚠️ **已应用的迁移文件逐字节冻结**（**连注释都不能改**，见 `architecture/tech/数据与Schema演进.md` §2.4）；测试用 DB 时钟造时间窗、关键计数走独立 oracle 查列 |
| G8 | ★ **跨期约定（升格保留）**：一期的测试必须只断言可观察行为（HTTP 状态 + body + DB 终态）—— 二期换实现时测试**不许改**；改了就说明测试在抄实现（纪律①）。⚠️ **例外有且只有两个**：**R-10**（有意契约演进）与 **I-12**（读切列 ⇒ 夹具改走真实写路径，业务断言一字未改）—— 见[收藏功能-编号索引.md](收藏功能-编号索引.md)A.4 |
| G13 | ★ **跨期约定（升格保留）**：清单只给方向与红线，不写死实现步骤 —— 有些问题**只有执行期才看得见**。把执行者当**勤快的外包**。执行中发现清单前提不成立 / 与既有实现冲突 / 有更好的拆法 ⇒ **自行决断或停下登记 `CURRENT_ISSUES.md` I-##**，不硬扛、也不擅自扩大范围 |
| G14 | ★ **每个任务都显式标注「可质疑」（2026-10-11 用户要求；**同日校准口径**）**：**任务可被质疑**（前提不成立 / 需求已被既有实现覆盖 / 执行时机不对）；**设计也可被质疑**（弊大于利 / 有更合适的方案 / 更便宜的拆法）。⚠️ **但本项目是学习项目、无真实流量**（见 `AGENTS.md`「项目定位与优化口径」）⇒ **质疑成立 ≠ 停做**：**先把代价写进回写**；若仍做，注明"**已知代价后选择做**"（理由可以是"展示决策"）。**只有质疑指向真实缺陷**（与既有实现冲突 / 会引入数据错误 / 真会成环）**才必须停下回报** |

> **一期 / 二期专属约定已随各自收尾清出**（收编进 [编号索引](收藏功能-编号索引.md) **A.4**）：
> `G7` 一期不写缓存 / 不做冗余计数增量维护（✅ **二期已解除**：T11 / T12）；`G9` `favorite` 必须登记进 `ArchitectureTests.DOMAINS`（已落地）；
> `G10` 🚫 收藏不得接 feed（**长期红线**，代码直接引用该号）；`G11` `/favorite/remove` 不得校验内容存在性（**长期红线**）；
> `G12` 夹内列表分页按 `favorite_item` 行（**长期红线**）。
> ★ **二期给后来者的两条**（经验，未编号）：① **跨域聚合边会形成装配期 Bean 环** —— 新域聚合他域时先看传递闭包，断环用 `@Lazy` 单点（见 I-10 / F-18）；
> ② **"批量语义"要在契约层就位** —— 逐条调用 N 次是这类改动最常见的退化形态。

---

## 任务

> 每任务必含**四要素 + 回写**；状态取值：待执行 / 执行中 / 已完成 / 搁置（搁置**必须**注明去向）。
> ★ **每个任务开头的「先质疑」是约定（G14），不是客套** —— 认真回答它。
>
> ⚠️ **读法（G14 于 2026-10-11 校准）**：块里列的是**已知代价 / 反对理由**，**不是"请自行否决"** ——
> 出现"停下回报"字样时按 G14 理解：**先记代价、再决定**；**只有指向真实缺陷才必须停**。
> 本项目**无真实流量**（见仓库根 `AGENTS.md`「项目定位与优化口径」）⇒ 这些代价**本身就是学习与展示的对象**：
> 能说清"我付了什么代价、换到什么、什么条件下不值得"，就是收益。

### T13 列表互动态形态统一（来源 R-13）【已完成】

> ⚠️ **先质疑（G14）**：
> - **任务**：R-13 已拍板（2026-10-13）；4 个列表面**前端零消费** ⇒ 本次破坏**前端代价 = 0**。⚠️ 但收益仍是"**决策展示**"（本仓无流量），**不是"更快"** —— 别写成性能改善。
> - **设计**：它是**原子改动** —— 只统一形态、不给"三态 + 维度集合"，会落在**两头不靠**的中间态（破了契约却仍留 `false` 歧义）；只做"维度选择"不动形态，则 `feed`/`profile` **无法表达"本面不查"**，只能继续填 `false`（**换个地方撒谎**）。反向方案（只删浪费不动契约 = 甲）**已被否决**，理由见 `CURRENT_NEEDS.md` §三 R-13。

* **入口线索**：`content/model/vo/ContentVO.java`（删顶层两字段、加 `interaction`）· `content/model/vo/ContentDetailVO.java`（**形态样板，不改**）· `interaction/model/vo/InteractionStatusVO.java`（`boolean` → `Boolean`）· `interaction/service/InteractionService.java#batchGetStatus`（加维度集合重载）· `content/service/ContentStatusFiller.java` · `feed/service/FeedService.java#renderPage` · `content/service/ProfileService.java#getProfile`。骨架 = 设计篇 **§3.6** 的「列表侧形态统一（R-13）」节
* **目标**：① `ContentVO` **删顶层** `isLiked`/`isFollowed`、改内嵌 `interaction`（与 `ContentDetailVO` 同形）；② `InteractionStatusVO` 三维改 **`Boolean` 三态**（`null` = **本面不查该维**）；③ 互动域**只给批量契约**加维度集合（`getStatus` 单条**不动**）；④ 调用点传**编译期常量**：`/start` = `{关注}`、**`/search/keywordSearch` = 空集（0 维）**、`/feed` = `{赞}`、`/profile` = `{赞}`、详情 = 三维；⑤ `feed`/`profile` **改走互动域** ⇒ 兑现 **F-16**
  > ★ **④ 的三条口径已于 2026-10-13 由用户拍板**（见 `CURRENT_NEEDS.md` §三 R-13）：维度按 **B站** 的实际展示面取（首页只显示「已关注」、搜索结果卡片什么都不显示、动态流与个人主页只显示点赞）；**搜索页取 0 维**，表示 = **对象在、三维全 `null`**（不是 `interaction: null`，那会与匿名不可区分）。
  > ⚠️ **原任务书此处与验收③ / 反面清单互相打架**（目标④+建议① 写 `/start`=`{关注}`、`/search`=`{赞}`；而收益①只点了"收藏集"那趟、验收③写"3→2"、反面清单写"不减少返回内容"）⇒ 已登记 `CURRENT_ISSUES.md` **I-13**；最终按用户拍板（B站 口径）执行，验收数字随之改为 **`/start` 3→1、`/search` 3→0**。
* **红线边界**：① ⚠️ `interaction` **只挂 `ContentVO`（子类），绝不进 `ContentCacheDTO`** —— 那是缓存形状，进去 = 互动态**被缓存** = **跨用户污染**；② ⚠️ **不加 `@JsonInclude(NON_NULL)`**（本仓三态惯例 = "`Boolean` + **显式 `null`**"，且详情用例断言匿名时 `interaction` **键必须在**）；③ ⚠️ **0 维仍必须调用互动域** —— 拿到"**对象在、三维全 `null`**"；**跳过调用会得到 `interaction: null`，与"匿名"不可区分**（★ 这是 2026-10-13 对原红线"不许选 0 维"的**解禁条件**：解的是**字面**，红线括号里的**理由**仍有效）；④ `authorId <= 0` **保持 `false`** ⇒ **详情 JSON 零变化**；⑤ `getStatus` 单条契约 / `ContentDetailVO` 结构 / 类级鉴权**都不得动**
* **强制探索**：① 复算"列表字段前端零消费"（`grep -rn "isLiked\|isFollowed\|interaction" src/main/resources/static/static/js/` ⇒ 只有 `detail.js` 读 `interaction`；`user.js` 读的是 `ProfileVO.isFollowed` 这**另一个字段**）；② 确认 `ContentCache.toContentVO` 产出的 `interaction` **恒为 `null`**（互动态**只能在请求期**由 filler / Service 填）；③ `ContentReadTests` 两条要改的用例改成**更强**的断言（"列表顶层两键必须在 / 匿名两键为 false" ⇒ "匿名 ⇒ 每条 `interaction` 为 `null`"）
* **验收**：① ✅ 4 个列表面新形状（顶层无 `isLiked`/`isFollowed`、有 `interaction`）；② ✅ 匿名 ⇒ 每条 `"interaction": null`（不是 `{}`、不是三 `false`）；③ ✅ ★ **批读趟数按维度常量收敛**（`/start` 3→**1**、`/search` 3→**0**；`@MockitoSpyBean` 数 like/favorite/follow 三域**被调次数**；★ **反向注入"改回全量维度"实测变红**）；④ ✅ **详情响应 JSON 逐字不变**（含匿名 `interaction: null`）—— 既有的详情用例回归通过作证；⑤ ✅ `feed`/`profile` 仍**一趟批量**（不退化）
* **回写**：① 形态与维度全部落地（`InteractionDimension` 枚举 + `batchGetStatus` 换三参签名 + `ContentVO` 内嵌 `interaction` + 四个面的编译期常量），`feed`/`profile` 改走互动域后 `LikeService` 直连被移除 ⇒ **F-16 兑现**，`/feed` 条目的 `isFollowed` 由"恒 `false`（谎言）"变为 `null`。② 新增 8 例（互动域维度 3 / `ContentReadTests` 批读趟数 1 / `FeedReadTests` 2 / `ProfileReadTests` 2），**三条反向注入实测变红并还原**（全量维度 ×2、0 维跳过填充、feed 改逐条调单条）；全量回归 **436 例全绿**（428 基线 + 8）。③ 三条连带处置已登记：**I-13**（任务书内部口径打架）、**F-15** 补注（首页/搜索页将来要做前端展示须先加回维度）、设计篇 §3.6 红线③ 的解禁条件。

### T14 `/comment/replies` 游标化（来源 F-20 ②）【已完成】

> ⚠️ **先质疑（G14）**：
> - **任务**：本项**破 `page` 契约**（用户 2026-10-13 已明确接受）。⚠️ 但**必须先查前端**：`detail.js` 现在怎么翻楼中楼 —— 若前端也在用 `page`，改契约就要**同步改前端 + `api.js`**。
> - **设计**：`LIMIT offset, n` 仍是 **O(offset)** 扫描 ⇒ 只有 **keyset** 能做到"负载 ∝ 本页"；代价是响应要给 `nextCursor`。**"保持 page 语义"无法解决 O(offset)** —— 这正是接受破坏契约的理由。

* **入口线索**：`comment/service/CommentService.java#getRepliesForRoot`（现传 `afterCommentId=0L` + `limit = page*pageSize` + 私有 `slicePage`/`toLimit`/`MAX_REPLY_WINDOW`）· `mapper/CommentMapper.xml#getRepliesInTreeByRoot`（**已支持 keyset**：`c.comment_id > #{afterCommentId} ... ORDER BY c.comment_id LIMIT #{limit}`）· `comment/controller/CommentController.java` · 前端 `static/js/views/detail.js`
* **目标**：入参 `page` → `cursor`（上一页末条 `commentId`），响应加 `nextCursor`；SQL **原样用 keyset**（不再传 `0`）；**删掉**内存切片与 `MAX_REPLY_WINDOW` 兜底；`total` 语义**不变**（仍是主楼 `reply_count`）
* **红线边界**：① **信封形状变更必须同步前端**（契约差异按 `.docs/INDEX.md` §一 ① 的规矩记录）；② `total` 与 `list` 顺序语义（`comment_id` 升序、页间不重不漏）**不得变**；③ **无 schema 变更** —— 本任务不许碰 `db/migration/**`；④ 不得顺手改 `likeCount` / 点赞态填充（**那部分已批量，无需动**）
* **强制探索**：① ★ **先按 C-6 造基线**（造 2 万条回复，扫"末页"用时与 **DB 返回行数**，证明现状 ∝ 页码；优化后应"平"）；② 契约破面清单（谁在传 `page`：前端 / 旧 pytest / 测试）；③ `shortPageMeansEnd:false` 这类末页判据是否受影响
* **验收**：① ✅ **独立 oracle 复算**页间不重不漏（游标逐条翻页的 DB 行集 vs 接口回显集；**夹具带"他楼 / 软删"对照行**）；② ✅ ★ **基线对照**：优化后"DB 行数 ∝ 页码"消失（基线 DB 返 2 / 20000 行 → 改造后 ≤ `pageSize+1`；HTTP 21 / 68ms → 20 / **8ms**，第 1 页本就须从楼头起扫故持平）；③ ✅ 末页 / 越界 / 非法 `cursor` 兜底用例；④ ✅ **前端改动同批交付**（`detail.js` 改游标循环；无独立联调环境 ⇒ 改由接口级对照 + 信封字段断言钉住）。
* **回写**：① **改了什么**：`/comment/replies` 由 offset 改为 **keyset 游标** —— 入参 `page` → `cursor`（= 上一页末条 `commentId`）、出参 `PageResult` → 新增 `CursorResult{list,total,pageSize,nextCursor}`；`CommentService#getRepliesForRoot` 删 `afterCommentId=0` / 内存切片 `slicePage` / `toLimit` / `MAX_REPLY_WINDOW`，改 `LIMIT pageSize+1` **一次判到底**；前端 `detail.js#ensureRootExpanded` 改游标循环。**未动** `CommentMapper.xml`（SQL 早已支持 keyset）/ 点赞态填充 / schema。
  ② **验证结果**：`CommentReadTests` 10 → **14** 例（新增：独立 oracle 不重不漏 / 非法游标兜底 / ★性能契约 spy / 旧 `page` 忽略）；**全量回归 439 例全绿**（436 + 3）；**反向注入 2 条实测变红并还原**（本页 `LIMIT` 改成与游标无关的大值 ⇒ 性能契约红；SQL 忽略游标恒传 `0` ⇒ 不重不漏 + 展开回复 + 性能契约 **3 条**红）。
  ③ ★ **C-6 基线 + 对照**（Testcontainers MySQL 8.4，单楼 2 万条回复）：优化前 HTTP 第 1 页 **21ms** / 末页 **68ms**、DB 返 **2 / 20000** 行；改造后 **20ms / 8ms**、DB 返 ≤ `pageSize+1` 行（`EXPLAIN ANALYZE`：现状 `LIMIT 20000 → rows=20000`；游标末页 `index condition (comment_id > …) → rows=2`）。
  ④ **契约变更登记**：这是**有意破 `page` 契约**（用户 2026-10-13 接受）⇒ 按 `.docs/INDEX.md` §一 ① 的规矩**同批改前端**，并把新形状登记在注释（`CursorResult` 类注释 / `CommentController#replies` / `api.js`）；旧 `page` **静默退化为第 1 页**（已加用例钉住，不报错）。★ 既有 `/comment/replies` 用例**因契约变更如实改形状**（非"测试迎合"）：**"页间不重不漏"的业务断言未削弱**，且换成独立 oracle。
  ⑤ **连带处置**：审查提出的三处口径问题已修（注释把"非法"与"越界"混为一谈；oracle 夹具缺对照；补 `page` 忽略用例）；`F-20` 的 **② 已交付**（①③④⑤⑥ 仍留池）。

### T15 收藏缓存层收口 + `/favorite/status` 短路（来源 F-17）【已完成】

> ★★ **范围两定（2026-10-13 用户拍板）**：**② 计数侧「值不值得缓存」→ 保留**（判据 = **成本比较**、**不造数**：
> `FavoriteCache` 类注释已登记"`SISMEMBER` vs `idx_user_content` 点查**在本机是同一量级**"⇒ 造数也定不了；
> 依据三条 = 收益测不出 / 写频率低 ⇒ 命中率高 / 拆除是净风险；已回写设计篇 §3.7）；
> **① `/favorite/status` 短路 → 做**（判据 = **同一语义不该有两个来源** —— 详情页按钮走**缓存**、本端点走 **DB**，
> `detail.js` 的 `initFavorite()` 与 `openPicker()` **已经在同一个按钮上混用**）。⇒ 本任务**有代码变更**。

> ⚠️ **先质疑（G14）**：
> - **任务**：★ ① 的判据是"**统一来源**"，**不是"省钱"**（本仓无流量、省的是亚毫秒点查 ⇒ 收益测不出）—— **别写成性能改善**。
> - **设计**：★ 它是"**在保住响应自洽（`isFavorited ≡ !folders.isEmpty()`）前提下能做到的最大统一**" —— `true` 分支仍**必须**由 DB 的 `folders` 推导；⚠️ **"完全统一到缓存"会打破响应自洽**（会出现 `{isFavorited:true, folders:[]}`），**不要那样做**。

* **入口线索**：`favorite/cache/FavoriteCache.java` / `FavoriteRedisOps.java` · `favorite/service/FavoriteService.java#isFavorited / batchIsFavorited / countFavorites` · `/favorite/status`（`FavoriteController`）· 设计篇 **§3.7**（含 T11 落地表与那两行 ⏳）· 回归锚点 `FavoriteCacheTests` / `FavoriteCountMaintenanceTests`
* **目标**：① ✅ 实现短路 —— 落到 `getFavoriteStatus` 的"先问成员集"：`FavoriteCache.isFavorited` 返回 `false` ⇒ 返回 `{false, []}`、**不查库**；**miss 走 DB + 回填**（回填 = 采纳 (b)，见下方范围决策）；**Redis 挂 ⇒ 降级走 DB**；
  ② ✅ **重写** `FavoriteStatusVO` 类注释 —— 现论证"**与详情页按钮同源**" + 明确**容忍度边界**（自洽保证 / 与 DB 一致不再保证 + 三类触发面）；
  ③ ✅ 评测现有 status / count 用例是否需清 Redis —— **结论：无需新增**。三个会打到 status 的测试类（`FavoriteStatusAndCountTests` / `FavoriteCacheTests` / `FavoriteExistenceTests`）本来就 `@BeforeEach` 清 Redis；且本仓 user / content 自增 id 不会复用 ⇒ 即便跨类残留也撞不上 key。⭐ **回答"不用改"也要给出判据**，别只写结论。
  ④ ✅ **已办**：F-17 ② 的结论与依据已回写设计篇 **§3.7**（含"限流前提强度下降"的补充）
* **红线边界**：① ★ **重判前不许先改实现**（F-17 明写）；② 若拆 ⇒ 必须同步处置 `FavoriteCacheTests` 并说明**判别力去向**（不许静默删用例）；③ 不得破坏 `isFavorited ≡ !folders.isEmpty()`（T5 不变量）；④ 不得顺手改 `favorite_count` 的维护口径（T12 口径与不变量不动）
* **强制探索**：① 复算"详情页按钮走**缓存**"这条路与**同一个 key**：`detail.js#initFavorite` 读 `interaction.isFavorited` → `InteractionService#getStatus` → `FavoriteService#isFavorited`（**`FavoriteCache` 支撑**）→ `user:favoriteSet:{userId}`；② 列出**三个不同的触发面**：**多端并发** / **失效失败的静默降级**（T11 的 warn 后靠 TTL 自愈）/ **绕过写路径直接改库**（测试夹具、数据修复）—— 用户接受的是第 1 类，**别把三件混成一件**；③ ⚠️ 不要为"计数侧"再造数测耗时（同量级、测不出，见 `FavoriteCache` 类注释）
* **验收**：① ✅ "未收藏时不查库"可观察 —— `@MockitoSpyBean` 对 `FavoriteItemDao.findFoldersByUserAndContent` 断言 **0 次调用**（另加 `findAllFavoritedContentIds` / `existsByUserAndContent` 两个入口也 `never` ⇒ 三个入口钉死"真的一次没查"）；
  ② ✅ **夹具带对照** —— 同一用户两个内容：已收藏的照旧落库取 `folders`、未收藏的零查库（同一用例内成对出现）；
  ③ ✅ **响应自洽** —— 陈旧场景下仍保证 `isFavorited ≡ !folders.isEmpty()`，且**用例里显式写明"不保证与 DB 一致"**；
  ④ ✅ **反向注入** —— 去掉短路 ⇒ **5 例全红**（不止 ① 一条，实测比任务书预期更宽）；
  ⑤ ✅ 详情页按钮态与弹窗态**不再互相打架** —— 接口级对照：三种状态（未收藏 / 已收藏 / 移出后）二者一致，并补了**陈旧场景**这一支（旧实现下会一个 true 一个 false ⇒ 用例 5 曾因此只有 4 例红，补了第 ④ 步才做到"必红"）
* **回写**：① **改了什么**：`getFavoriteStatus` 改为"先问成员集" —— `if (!favoriteCache.isFavorited(u,c)) return FavoriteStatusVO.notFavorited();`，已收藏则仍去 `itemDao.findFoldersByUserAndContent` 取 `folders`、布尔照旧由 `folders.isEmpty()` 推导 ⇒ **没有新增 `FavoriteCache` 方法**（三态读 / 单飞 / 回填 / 降级全部复用 `isFavorited`）；`FavoriteStatusVO.notFavorited()` 承载短路返回值；VO 类注释重写为"**与详情页按钮同源** + 容忍度边界"。👇 见下方"范围决策"。
  ② **验证**：新增 `FavoriteStatusShortCircuitTests` **5 例**；**4 条反向注入逐条实测变红并还原**（去掉短路 ⇒ **5 例全红** / true 分支也短路 ⇒ 2 例红 / miss 不回填 ⇒ 4 例红 / Redis 不降级 ⇒ 1 例红）；favorite 域 **96 例**、全量 **445 例**全绿（440 + 5）。
  ③ **连带处置**：`FavoriteCacheTests` 两处过时注释已更正；`FavoriteExistenceTests` 补了 T15 后的因果链说明；审查提出的"回填可能覆盖并发失效"这一反向代价已登记进 `getFavoriteStatus` javadoc；oracle 已改为**带 `user_id`**。

> **★★ 范围决策（2026-10-14 用户拍板：miss 分支到底回不回填）**
> 任务书写的是"**miss 不短路（走 DB + 回填）**"，但"回填"有两种互斥实现，裁决时把用户分成两类看：
> <table>
>   <caption>(a) 不回填 vs (b) 回填</caption>
>   <tr><th></th><th>正常用户</th><th>不正常用户（多账号、卡在限流判定线下的低频骚扰）</th></tr>
>   <tr><td><b>(a) 不回填</b></td><td>冷 miss 仍是 1 次点查，最省</td>
>       <td>★ <b>冷状态永不自愈</b> ⇒ 每请求稳定换 1 次查库，<b>上限只取决于账号数</b></td></tr>
>   <tr><td><b>(b) 回填</b>（采纳）</td><td>冷 miss 变成 1 次成员集装载（约每 TTL 一次，且详情页通常已预热）</td>
>       <td>一次装载后<b>自愈</b>；且攻击者账号零收藏 ⇒ 那趟装载查出空集、写完空标记后连后续 60s 都免了</td></tr>
> </table>
> ⇒ **采纳 (b)**。⚠️ **正当性要写准**：它不是"能挡住攻击者"，而是"**移除一个每请求、不自愈的查库口子**"；
> **限流（F-22）仍不可替代**（回填挡不住"换个端点刷"），别把它写成安全收益。
> ★ 副作用是本题的判据本身兑现得更彻底：未收藏分支的答案与详情页按钮**逐字同源**（同一份成员集）——
> 按 (a) 做只在 key 恰好是热的时候同源，那就把"同一语义不该有两个来源"做了一半。
> ⚠️ (b) 相对拍板原文**更宽**：除"key 存在且 `SISMEMBER` 明确 false"外，`FavoriteCache.isFavorited` 的
> **空标记命中**与**冷 miss 新鲜回源**同样会走进短路分支 —— 两者证据都**不弱于** `SISMEMBER=false`
> （前者更强、后者本就是刚从 DB 读出）。用户 2026-10-14 采纳该口径，已在 §3.7 / F-17 同步。

```
### T# <任务名>（来源 N# / R-## / 能力 x期-x）【待执行】

> ⚠️ **先质疑（G14）**：
> - **任务**：前提是否成立？需求是否已被既有实现覆盖？执行时机对不对？
> - **设计**：是否弊大于利？有没有更合适的方案 / 更便宜的拆法？
> 质疑成立 ⇒ **停下回报或登记 `CURRENT_ISSUES.md` I-##**（G3 / G13 / G14 允许并鼓励）。

* **入口线索**：
* **目标**：
* **红线边界**：
* **强制探索**：
* **验收**：
* **回写**：（完成后三句内写清改了什么、验证结果；探索过程不写）
```

---

## 上一张清单（收藏二期，2026-10-11 → 2026-10-12）

> 四要素 / 强制探索 / 反向注入逐条实测 / 独立审查记录的**正文已清出**（历史在 git）；
> 逐条编号索引（结论 + 归宿）见 [收藏功能-编号索引.md](收藏功能-编号索引.md) **A.1（T9–T12）/ A.2（R-10）/ A.3（I-09~I-12）**。

| 任务 | 产出 | 验证（当时） | 收口 |
|------|------|------------|------|
| **T9** 互动域骨架与两种契约 | `interaction` 域（仅 service + model，不拆 HTTP、无 controller/dao/cache/event）；单条 + 批量契约；收藏域 `isFavorited` / `batchIsFavorited`（不 JOIN） | 新增 11 例；**7 条反向注入**逐条变红并还原 | ✅ |
| **T10** 详情互动态内联（**breaking 收口**） | `ContentDetailVO` 顶层 `isLiked`/`isFollowed` → 内嵌 `interaction`（匿名 ⇒ `null`）；`ContentStatusFiller` 降为 content 侧薄壳；前端 `detail.js` 改读 `item.interaction.*` | `ContentReadTests` 17→19 例；**5 条反向注入**；浏览器实测（真库真服务）；详情页请求 3→2 | ✅ |
| **T11** 收藏缓存层 | `FavoriteCache` / `FavoriteRedisOps` / `AfterCommit`（提交后钩子，**不建 event**）；只有 `add` 条件写、计数侧一律 `DEL`；`folders` 不缓存 | 新增 14 例；**10 条反向注入**；既有用例两处机制级调整 | ✅ |
| **T12** `favorite_count` 冗余列启用 + 事务内批量重算 | **V3** 存量回填 + `recountFavoriteCounts`（1 趟 GROUP BY + 1 趟批量 UPDATE 绝对值）；**四条**写路径接入（move 为 I-12 补入）；读切列 | 新增 10 例；**6 条反向注入**；`EXPLAIN` 实测回写 §3.2；V3 在 dev 库冒烟通过 | ✅ |

> 回归基线轨迹：391（一期收尾）→ 402（T9）→ 404（T10）→ 418（T11）→ **428（T12）**。
> ⏳ **二期唯一遗留**：`/favorite/status` 的"未收藏短路"未做 + "T12 后计数侧还值不值得缓存"未重判 ⇒ 已合并结转 `FURTHER_ISSUES.md` **F-17**。

> 收藏一期（T1–T8，2026-10-08 → 2026-10-11）一览同样已清出，见 `收藏功能-编号索引.md` A.1。

---

## 结转与留池（下一期开窗时重建）

> **本期（第 3 张清单，T13–T15）已按 G14 逐条标注「先质疑」**，范围见 `CURRENT_NEEDS.md` §四；
> ★ **反面清单同时在 `CURRENT_NEEDS.md` §四**（那里是唯一事实源，本文件不复述）。
> 本表本期**无新增结转项**：二期收尾（2026-10-12）那批已逐行处理完毕（进候选 / 转 `FURTHER_ISSUES.md` **F-16 ~ F-22** / 废弃），明细随正文清出（历史在 git）。
> ⚠️ **本张清单收尾时按 `CURRENT_TASKS模板.md` 重建本表** —— 不允许静默跳过任何已登记项。
