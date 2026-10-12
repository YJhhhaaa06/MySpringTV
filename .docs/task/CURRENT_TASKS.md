# 当期任务（Current Tasks）

> **定位**：登记**当期正在执行的任务**及其执行态（四要素 + 三句以内回写）。
> 计划态（分期、骨架设计、验收口径）在 [收藏功能-分期与设计.md](收藏功能-分期与设计.md)；
> **本期范围与反面清单**在 [CURRENT_NEEDS.md](CURRENT_NEEDS.md) §四。**本文件只记执行态**，不复制计划。
> **需求来源**：`CURRENT_NEEDS.md`（N# / R-##）；**执行中发现的问题** → `CURRENT_ISSUES.md`。
>
> ★ **膨胀控制（2026-10-11 起执行）**：任务完成即标注，**期收尾时清出本文件**（归宿 = git commit）。
> 清出前**必须先把该编号收进** [收藏功能-分期与设计.md](收藏功能-分期与设计.md) **附录「一期编号索引」** ——
> 代码 / mapper / 测试注释里有**数百处** `T#` / `R-##` / `G#` 引用，那份索引是它们**唯一**的查证入口。
> **编号不回收**（下期顺延）；**登记台文件复用、不改名**（改名会让外部引用悬空）。

---

## 周期头（每窗开读）

* **方向**：收藏 —— 一期（有无）已收尾（2026-10-08 → 2026-10-11）｜**二期（性能 + R-10 契约演进）= 本张清单**｜三期（周边）留池待评审
* **本周期**：`2026-10-11` 起 · **第 2 张清单**（编号 **T9 起**，**不回收 T1–T8**）
* **本张清单范围**：`CURRENT_NEEDS.md` §四 的 **二期-1 ~ 二期-4**；**反面清单 8 条**同在那里（本文件不复制）
* **收尾 checklist**：☑ 回归基线全绿 · ☑ 完成态已清出（记入 git）· ☑ 问题已结转（`FURTHER_ISSUES.md`）· ☑ 工作树干净
* ⚠️ **本期含一条 breaking**（T10 = R-10 契约演进）—— 用户 2026-10-11 明确接受，判据③ 的例外见设计篇 §一；**T10 已于 2026-10-12 交付**

---

## 运行约定（只保留当前仍然有效的）

| 号 | 约定 |
|----|------|
| G1 | 默认一任务一窗口一 commit；★ **但允许执行者自拆** —— 任务可能是连贯的、要深入调查后才知怎么拆，执行者可自行拆子任务与多个 commit（**每 commit 仍须可编译**，message 带任务号），拆法写进回写 |
| G2 | **证据可复核**：入口与验收能定位（文件:行 或 可复现命令），不接受纯文字断言 |
| G3 | **疑问分级**：可自答的 → 任务回写留一句；须裁决的 → **停下问用户**，不擅自拍板、不硬扛、不超范围 |
| G4 | 一任务 = 四要素 + 三句以内回写；探索过程 / 失败尝试 / 中间稿一律走 git，不进本文件 |
| G5 | **声明式机制必须配契约测试**：断言通过 ≠ 机制生效；写「证明某机制」的用例前先自问「去掉那行实现会红吗」（详见仓库根 `AGENTS.md`「测试纪律」节） |
| G6 | 改 schema 走 Flyway **最高版本 +1**（V1 冻结、**V2 已应用** ⇒ 下一次是 **V3**）；⚠️ **已应用的迁移文件逐字节冻结**（**连注释都不能改**，见 `architecture/tech/数据与Schema演进.md` §2.4）；测试用 DB 时钟造时间窗、关键计数走独立 oracle 查列 |
| G8 | ★ **跨期约定（升格保留）**：一期的测试必须只断言可观察行为（HTTP 状态 + body + DB 终态）—— 二期换实现时测试**不许改**；改了就说明测试在抄实现（纪律①）。⚠️ **唯一例外 = T10**（R-10 有意契约演进），见设计篇 §一 |
| G13 | ★ **跨期约定（升格保留）**：清单只给方向与红线，不写死实现步骤 —— 有些问题**只有执行期才看得见**。把执行者当**勤快的外包**。执行中发现清单前提不成立 / 与既有实现冲突 / 有更好的拆法 ⇒ **自行决断或停下登记 `CURRENT_ISSUES.md` I-##**，不硬扛、也不擅自扩大范围 |
| G14 | ★ **每个任务都显式标注「可质疑」（2026-10-11 用户要求；**同日校准口径**）**：**任务可被质疑**（前提不成立 / 需求已被既有实现覆盖 / 执行时机不对）；**设计也可被质疑**（弊大于利 / 有更合适的方案 / 更便宜的拆法）。⚠️ **但本项目是学习项目、无真实流量**（见 `AGENTS.md`「项目定位与优化口径」）⇒ **质疑成立 ≠ 停做**：**先把代价写进回写**；若仍做，注明"**已知代价后选择做**"（理由可以是"展示决策"）。**只有质疑指向真实缺陷**（与既有实现冲突 / 会引入数据错误 / 真会成环）**才必须停下回报** |

> **一期专属约定已随一期清出**（收编进 [设计篇](收藏功能-分期与设计.md) **附录「编号索引」**）：
> `G7` 一期不写缓存 / 不做冗余计数增量维护（⇒ 二期）；`G9` `favorite` 必须登记进 `ArchitectureTests.DOMAINS`（已落地）；
> `G10` 🚫 收藏不得接 feed（**长期红线**，代码直接引用该号）；`G11` `/favorite/remove` 不得校验内容存在性（**长期红线**）；
> `G12` 夹内列表分页按 `favorite_item` 行（**长期红线**）。

---

## 任务

> 每任务必含**四要素 + 回写**；状态取值：待执行 / 执行中 / 已完成 / 搁置（搁置**必须**注明去向）。
> ★ **每个任务开头的「先质疑」是约定（G14），不是客套** —— 认真回答它。
>
> ⚠️ **读法（G14 于 2026-10-11 校准）**：块里列的是**已知代价 / 反对理由**，**不是"请自行否决"** ——
> 出现"停下回报"字样时按 G14 理解：**先记代价、再决定**；**只有指向真实缺陷才必须停**。
> 本项目**无真实流量**（见仓库根 `AGENTS.md`「项目定位与优化口径」）⇒ 这些代价**本身就是学习与展示的对象**：
> 能说清"我付了什么代价、换到什么、什么条件下不值得"，就是收益。

```
### T# <任务名>（来源 N# / R-## / 能力 二期-x）【待执行】

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

### T9 互动域骨架与两种契约（来源 R-10 / 能力 二期-1）【已完成】

> ⚠️ **先质疑（G14）**：
> - **任务**：前提是"`content` 域不该直接依赖 like / follow / favorite 三域"。⚠️ **这个前提在只有 3 个互动维度时并不显然必要** —— `like` 域已经这样很久了。若判断"互动维度不会增长（投币不做）"，**本任务与 T10 都应停下回报**，改走"详情节内联平铺"的更小方案。
> - **设计**：新建一个域是否**弊大于利**？反面事实：**总跨域边数实际增加**（3 → 1+3）。若认为"多一个域只为省 2 条依赖边"不值，**同样停下回报**。

* **入口线索**：设计篇 **§3.6**（形态）+ **§3.4**（新域登记要求）；`CURRENT_NEEDS.md` §三「R-10 结论」（为什么是这条方案、排除了什么）；现状参照 `content/service/ContentStatusFiller`（它**为批量而生** —— 既是正面参照，也是"逐条就完蛋"的反面教材）；三个现成契约 `LikeService.batchIsContentLiked` / `FollowService.batchIsFollowing` / `FavoriteService`（**需新增轻量判存在方法**）
* **目标**：
  - 新建域 `interaction`：★ **不建 `Controller`**（本方案**刻意不拆 HTTP 接口**）；只有 `service` + `model/vo`；**不建 `dao`**（它是聚合器，不是数据所有者）
  - `InteractionStatusVO`：`isLiked` / `isFavorited` / `isFollowed`
  - **两种契约**：单条 `getStatus(userId, contentId, type)` + **批量** `batchGetStatus(userId, contentIds, …)`
  - `ArchitectureTests.DOMAINS` 登记 `interaction`
  - 收藏域补一个**不 JOIN 的轻量判存在**契约（`isFavorited(userId, contentId)`，走 `idx_user_content` 等值点查）—— 见设计篇 §3.6
* **红线边界**：
  - ★ **互动域不得依赖 content**（否则 `content → interaction → content` 成环）；"哪些维度适用"由调用方把 `type` **传入**
  - ★ **不得新增 HTTP 端点**（本方案的核心约束就是"不拆接口"）
  - ★ **批量契约必须原样透传批量语义、不得循环调单条**（`ContentStatusFiller` 类注释的既有纪律：逐条调用会让 100 条推荐页变 200 次往返）
  - ★ **域边界排除「评论点赞」**（`like` 域已含内容赞 + 评论赞）
  - ★ **匿名语义**：`userId == null` ⇒ 不查任何互动域，返回 `null`（**不是**三个 `false`）—— 见 §3.6
* **强制探索**：
  - `ContentStatusFiller` 的四个方法在**后续 T10** 各自被谁取代 / 保留 —— 本任务只需**摸清**，改动归 T10
  - 三个域现有公开方法的签名能否直接复用；`FavoriteService` 需要新增的轻量方法**长什么样**（是否与 T11 的缓存落点重合）
  - **`interaction` 的包结构**：确认它真的不需要 `dao` / `cache` / `event`
  - ★ **`type` 从哪来**：详情 / 列表本来就持有 `type` ⇒ 确认调用方可直传，**不需要互动域反查**（反查即成环）
* **验收**：
  - ★ `ArchitectureTests` 通过 **且** `DOMAINS` **含 `interaction`** —— ⚠️ 前半句是**永远绿的假绿**，**有效验收只有后半句**（本仓明确记载）
  - 单条 + 批量两种契约各有单测（批量：给 N 个 id 返回 N 条映射，**不重不漏**）
  - ★ **可复算判据**：`grep` 确认 `interaction` 域**不 import** `..content..`（无环）
  - 既有回归基线全绿（★ 本任务**不改对外契约**，例数应**不变**；变了说明动到了不该动的地方）
* **回写**：T9 完成（自拆 4 commit：T9-1 实现 / T9-2 测试 / T9-3 独立审查修正 / T9-4 文档同步）——
  新建 `interaction` 域（仅 service + model，不拆 HTTP、无 controller/dao/cache/event；单条 `getStatus(userId,contentId,authorId,type)`
  + 批量 `batchGetStatus(userId, List<InteractionTarget>)`，匿名一律 null 且不查）＋ 收藏域 `isFavorited`/`batchIsFavorited`
  （不 JOIN、走 `idx_user_content`）；`ArchitectureTests.DOMAINS` 已登记。
  验证：新增 **11 例**全绿、全量基线 **402 例**全绿（默认组；＋ resilience **34 例**全绿，合计 **436**。既有 391 例零改动——"例数应不变"按"既有用例不被改动"执行，增量全为新用例）；
  **7 条反向注入**逐条实测变红并还原（单/批量匿名短路、批量循环 ×2、关注维度传错 id、判存在改 JOIN、SQL 去 `user_id`）；
  `grep` 复核 interaction 零 import content。⚠️ 探索期两处决断（G13；代价已记、独立审查评断均成立、零必修）：
  ① 单条签名比设计篇示例多 `authorId`（`isFollowed` 判作者、不得反查内容 —— 设计篇 §3.6 已同步）；
  ② 批量契约填满三维 ⇒ 收藏域**加** `batchIsFavorited`（否则 `isFavorited` 恒 false、且违"不得循环调单条"）。

---

### T10 详情互动态内联（**breaking 收口**）（来源 R-10 / 能力 二期-1）【已完成】

> ⚠️ **先质疑（G14）**：
> - **任务**：前提是"把顶层 `isLiked`/`isFollowed` 搬进 `interaction` 值得付一次 breaking"。**这是本期唯一的对外契约破坏**。执行中若发现改动面远超预期（例如前端牵连面比评估的大），**停下回报**。
> - **设计**：`interaction` 子对象 vs "顶层加 `isFavorited` 平铺"（更小、不 breaking）。用户已拍板前者；但执行中若发现**更便宜的等效实现**，允许回报。

* **入口线索**：设计篇 **§3.6**；`content/model/vo/ContentDetailVO`（⚠️ **其类注释写死了键集**）；`content/service/ContentStatusFiller`；`content/service/ContentService.getContentDetailVO:186`；`content/controller/SearchController:72`；前端 `static/static/js/views/detail.js:251,258,692,732`；测试 `content/ContentReadTests:71,72,119,144`
* **目标**：
  - `ContentDetailVO`：加 `interaction` 子对象、**删顶层** `isLiked` / `isFollowed`，并**改类注释的键集声明**（它是契约）
  - `ContentService.getContentDetailVO` 改调互动域；**匿名 ⇒ `interaction == null`**
  - ★ **列表路径的填充来源也改走互动域**：使 `content` 域**不再 import** `like.service` / `follow.service` —— 否则本方案的收益只剩一半
  - 前端 `detail.js` 改读 `item.interaction.*`；`static/js/api.js` 的注释同步
  - `ContentReadTests` 相应用例改断言（★ **G8 的唯一例外**，设计篇 §一 已声明）
* **红线边界**：
  - ★ **`/search/IdSearch` 路径与请求参数不变**（不拆接口）
  - ★ **匿名 ⇒ `interaction: null`**（不是 `{}`、不是三个 `false`）—— 这是本方案相对平铺的**表达力增益**，别做丢
  - ★ **`likeCount` / `commentCount` / `createTime` 等不在 `interaction` 内** —— 它们是公共计数 / 内容属性，不是"我的状态"，**别顺手搬**
  - ★ **列表 `ContentVO` 的顶层键集不变**（只换**数据来源**，不改契约）—— 两者刻意不同，别一起改
  - ★ 不得改 `/start` / `/search` / `/profile` / `/feed` 的**响应形状**
* **强制探索**：
  - `ContentStatusFiller` 的**去留**：被整体取代（删），还是保留为 `content` 侧的薄壳？两者皆可，但**必须明确选一个并说明**
  - `content/service/ProfileService` 自己内联了一套点赞批量（见其类注释）—— **是否顺带对齐**？（若顺带 = 扩大范围，需回报）
    ⇒ **2026-10-12 拍板：不对齐**（用户"选择不改"）—— 改它要给 `/profile` 每页 +2 趟批量读，且互动域只有"三维一起答"的批量契约。裁剪登记与后续去向见 `CURRENT_ISSUES.md` **I-09**（"维度可选"重载 / 更优解，待处理）
  - `feed/service/FeedService.renderPage` 只填 `isLiked`（不填 `isFollowed`）—— **不在本任务范围**，但回写里要说明为何不动
  - ★ **前端乐观更新**：`detail.js` 的本地 ±1（`toggleContentLike`）改动后是否仍成立；"写后重新拉取"拿到的是哪个字段
* **验收**：
  - 契约测试：详情响应**有** `interaction`、**无**顶层 `isLiked`/`isFollowed`；**匿名 ⇒ `null`**；登录且已赞/已关注 ⇒ 子对象内为 `true`
  - ★ **可复算判据（2026-10-12 收窄，理由见 `CURRENT_ISSUES.md` I-09）**：`grep` 确认**互动态填充路径**（`ContentStatusFiller` / `ContentService` / `StartController`）**零 import** `like.service` / `follow.service`（本方案的收益落点）。⚠️ **例外 = `ProfileService`**（内联点赞批量保留、不对齐，用户 2026-10-12 拍板）⇒ 原判据"整域零 import"**不成立**，以本条为准
  - 前端：浏览器实测详情页三按钮（赞 / 关注 / 收藏）初始态与点击后状态正确
  - 回归基线全绿（⚠️ **例数可能与 391 不同** —— 改断言会动例数，回写里写清）
* **回写**：T10 完成（自拆 5 commit：T10-0 登记 I-09+判据收窄 / T10-1 破契约收口（后端+前端+既有断言）/ T10-2 列表契约测试 / T10-3 审查修正 / T10-4 回写+文档同步）——
  详情 `ContentDetailVO` 顶层 `isLiked`/`isFollowed` → 内嵌 `interaction` 子对象（匿名 ⇒ `null`，不是 `{}`）；`getContentDetailVO` 直调互动域单条契约；
  `ContentStatusFiller` **保留为 content 侧薄壳**（只留 `ContentVO → InteractionTarget` 映射 + 批量透传，不再 import like/follow；映射留在 content 的理由：互动域不得认识 `ContentVO`、且 `/start` 与 `/search` 共享一份）；
  前端 `detail.js` 四个读点 + 两处乐观更新改读 `item.interaction.*`（null 安全），初始收藏态改读内联值 ⇒ 详情页内容相关请求 **3 → 2**，弹窗仍走 `/favorite/status` 取 folders。
  验证：`ContentReadTests` 17 → 19 例（新增"列表顶层键集/值不变"与"批量透传 spy"），全量基线 **404 例**全绿（T9 基线 402 + 本次 2）；
  `--group resilience` **34 例**全绿（合计 **438**）；**5 条反向注入**逐条实测变红并还原（① 去掉 interaction 装配 ⇒ 形状/个性化红；② 去掉匿名短路 ⇒ 匿名用例红；
  ③ 薄壳改逐条调单条 ⇒ 批量透传红；④ 漏填 isFollowed ⇒ 列表用例红；⑤ 子对象塞 likeCount ⇒ 键集用例红）；
  `grep` 复核：互动态填充路径（`ContentStatusFiller`/`ContentService`/`StartController`）**零 import** like/follow.service（content 域仅剩 `ProfileService` = I-09 例外）；`interaction` 域零 import content；
  浏览器实测（真库真服务：内容 258 + 新账号，独立 oracle = DB 行 + 登录态接口 `interaction` 三维全 true）：三按钮初始态 / 点击态 / **整页刷新后持久**全对；匿名三按钮未点亮、页面正常。
  执行期决断（G13/G14，代价已记）：
  ① ★ **装配期 Bean 环** `contentService → interactionService → favoriteService → contentService`（favorite 在 T6-5 依赖 `loadContentVOs`）——首跑实测 25 例 error；
     R-10 的"无环"只成立于**直连边**；在互动域聚合边**单点 `@Lazy`** 断环（三条边全是运行期读依赖、装配期不需要对方实体；删注解 = 启动期响亮失败），登记 **I-10**（备选：把装载契约抽成窄组件）；
  ② 审查（subagent 只读）**零必修**，采纳 2 条建议（薄壳契约前提注释 / 文件尾换行），1 条不采纳（与既有测试写法保持一致）。
  未动项：`ProfileService`（用户 2026-10-12 拍板"选择不改"，I-09）；`FeedService.renderPage` 只填 `isLiked`（**刻意不动**——feed 的 `isFollowed` 一直"不填"，补填属响应值改动，见 `architecture/domain/feed.md` §3.3）。
  文档同步：`模块边界与依赖.md` §三/§六（依赖表 + 新增 `content ⇄ interaction` 传递闭包环行）、`domain/feed.md` §3.3、`CURRENT_NEEDS.md` §三「已核实的事实」、设计篇 §3.6。

---

### T11 收藏缓存层（来源 能力 二期-2）【已完成】

> ⚠️ **先质疑（G14）**：
> - **任务**：前提是"`isFavorited` 高频读 + `count` 回源昂贵，值得上缓存"。⚠️ **若实测发现一期那 17ms 在真实数据量下不成问题**（例如收藏表只有几百行），**停下回报** —— 无基线证据的优化不该做（二期判据①）。
> - **设计**：是否**弊大于利**？缓存引入"三态 / 空标记 / 降级 / 条件写"四套机制与相应测试成本。本仓明确记过"**去掉缓存功能照样能用**" ⇒ 若判断"直接查 DB 就够"，**停下回报**。

* **入口线索**：设计篇 **§3.7**（边界）+ `architecture/tech/缓存与数据一致性.md` §四（读写口径）；**形态照抄 `like/cache/LikeCache` + `like/cache/LikeRedisOps`**（同构，**别自创**）；`favorite/service/FavoriteService`（四个写点）；`FavoriteItemDao.countDistinctUserByContentId`（一期基线 ≈17ms）
* **目标**：
  - `FavoriteCache` + `FavoriteRedisOps`：`user:favoriteSet:{userId}`（**Set**）+ `content:favoriteCount:{id}`（**String**）+ `empty:` 空标记（**仅成员侧**）+ 单飞（`SingleFlight`）+ Redis 失败降级 DB
  - **写路径**：`add` / `remove` / `move` / `deleteFolder` **提交后**条件写缓存
  - **读路径**：`isFavorited` → `SISMEMBER`；`count` → `GET`
  - ⚠️ **`folders` 不缓存**（§3.7：低频 + 未收藏时短路）
* **红线边界**：
  - ★ **计数 key 不得写空标记**（`0` 是**合法值**，缓存文档 §六.1）—— 否则"0 个收藏"与"未知"混淆
  - ★ **失效集 = 数据 key + 空标记**（成员侧）；**凡 DEL 数据 key 必带空标记**
  - ★ **`RedisOps` 是抽象协议基类，绝不能标 `@Component`**（标了会 `NoUniqueBeanDefinitionException`）
  - ★ **写路径：DB 提交后才碰缓存**（`AFTER_COMMIT`）；**不得在事务内写缓存**
  - ★ **Redis 失败 ⇒ 降级走 DB、接口不 500**；**DB 失败 ⇒ 原样上抛**（不吞成空 / 0）
  - ★ **不用 `@Cacheable`**（本仓刻意显式封装）；键一律经 `CacheKeys`，域内不得散写字面量
* **强制探索**：
  - ★ **"提交后写缓存"怎么挂？** `like` 用 `like.event.LikeChangedListener`（**自域事件**），但本仓明写"**收藏域不该有 `event` 包**"（"收藏不是别人要响应的事实"）。⇒ 两条路：① 建 `favorite.event` 的自域事件（**须先论证它与"不该有 event 包"如何共存**）；② 用 `TransactionSynchronization.afterCommit` 直接注册。**这是必须明确表态的设计点**
  - ⚠️ **别在 `AFTER_COMMIT` 监听器里再 `publishEvent` 转发**（事务已结束 ⇒ 事件**静默丢弃**，本仓已踩）
  - 「**条件写只在 key 已加载时执行**」（缓存文档 §四.2）在收藏域怎么表达：冷 key 不写半套数据，改整体失效
  - 空标记与数据 key 的**判定顺序**（**先空标记、后数据 key**）
  - 四个写点各自的事务边界（`deleteFolder` 有 `@Transactional`；`addItem` 懒建路径的特殊性见其注释）
  - ★ 与 T12 的接口：`count` 缓存 miss 时**回源读 `content.favorite_count` 列**（T12 之后）；若 T12 未完成，回源先走实时 `COUNT(DISTINCT)`
* **验收**：
  - 三态各有用例：**命中 / miss 回源回填 / Redis 失败降级**
  - ★ **空标记**：无收藏用户读一次 → 写空标记且**不再回源**；随后 `add` → 读**不再是 false**（**反向验证**：去掉写路径清空标记 ⇒ 该用例必须变红）
  - ★ **降级**：`@MockitoSpyBean` 让 `FavoriteRedisOps` 抛 `CacheUnavailableException` ⇒ 读仍返回 DB 真值、**接口不 500**
  - ★ **用 Redis 的测试 `@BeforeEach` 必须清 Redis**（既有纪律）
  - ★ **不漂移**：写后读到的 `isFavorited` / `count` 与 DB 终态一致（**独立 oracle**：直接查 `favorite_item`）
  - `folders` 仍走 DB（确认**没**被顺手缓存）
* **回写**：T11 完成（自拆 5 commit：T11-1 登记 I-11 / T11-2 基建 / T11-3 接线 / T11-4 测试 / T11-5 审查修正+回写）——
  新建 `favorite/cache`：`FavoriteCache`（三态读 + 单飞 + 降级 + 写路径分档）+ `FavoriteRedisOps`（**只一个**条件写 Lua + 回填/空标记 Lua；抽象的 `RedisOps` 基类未标 `@Component`）+
  `AfterCommit`（提交后钩子）；`CacheKeys` 补 `user:favoriteSet:{id}` / `content:favoriteCount:{id}`；`FavoriteCacheProperties`（`video.cache.favorite-ttl`，默认 **15m**，绑定期校验正数）；
  `FavoriteItemDao` 补两条**为缓存而加**的读（`findAllFavoritedContentIds` 回填全量 / `findContentIdsByFolderId` 删夹前取失效集，T12 复用）；
  `FavoriteService` 四条读委托缓存（签名与端点契约**一字不变**）、四个写点各自在 DB 写成功后经 `AfterCommit` 触缓存。
  ★ **三处偏离任务书措辞**（前提被推翻，登记 **I-11**、用户 2026-10-12 拍板）：
  ① **计数侧一律 `DEL`**（不做 `INCR`/`DECR`）—— `uk_folder_content` 是**夹内**唯一，同一人可把同一内容收进多夹 ⇒ `add` 不必然 +1、`remove` 不必然 −1；
  ② **成员侧只有 `add` 条件写**，`remove`/`move`/`deleteFolder` **整体失效** —— 移出某夹 ≠ 取消收藏；且 `move` **不是**"净效应为零 ⇒ 不写"（源夹不含该内容时等价于新增收藏）；
  ③ **提交后钩子用 `TransactionSynchronization`**（无同步活动 ⇒ 立即执行），不建 `favorite.event` —— `addItem`/`removeItems` 刻意无事务，走事件会被 `fallbackExecution=false` **静默丢弃**，且本域不该有 `event` 包。
  ⏳ **未做**（有意）：`/favorite/status` 的"`SISMEMBER` 为 false 时短路"（会让 `isFavorited` 出现第二处事实源，与 T5 钉住的不变量冲突）⇒ 设计篇 §3.7 仍标 ⏳；`folders` 不缓存（照 §3.7）。
  验证：新增 **14 例**（`FavoriteCacheTests` 13 + `FavoriteCachePropertiesBindingTests` 1）、全量基线 **418 例**全绿（T10 基线 404）；
  **10 条反向注入逐条实测变红并还原**（① `ADD_LUA` 去清空标记 ② 整体失效改空实现 ③ ★**照任务书原口径**写条件 `INCR` + 写路径不失效 ④ `move` 不写 ⑤ 删夹批量失效删掉 ⑥ `AfterCommit` 换成事务体内直接调用 ⑦ 无同步分支改"跳过" ⑧ 不写空标记 ⑨ 降级改 rethrow ⑩ 把 DB 失败吞成 false）；
  其中 ③ 有额外发现：**单独**注入 `INCR` 时全绿（被写路径的 `DEL` 抹掉），只有"条件写 + 不失效"**整体**注入才现形 ⇒ "条件写"与"失效"在本域必须**成对**；
  ⑥ 还暴露了测试自身的缺陷：原回滚用例打桩"**首步** DB 写失败"对"事务内 vs 提交后"**零鉴别力**（两种世界都"什么都没写"）⇒ 已按 AGENTS「最后一步打桩」改成**外层事务在成功写之后置回滚**（事务内即可观察到缓存已失效 ⇒ 变红）；
  既有用例两处调整（**G8 例外**，待用户追认）：`FavoriteExistenceTests` 的批量 DAO 调用断言改指向**回填**那条（T11 后冷 miss 走全量 loader）＋ 清 Redis；`FavoriteStatusAndCountTests` 在**直改 DB** 之后清 Redis（夹具绕开写路径 ⇒ 缓存不失效，"直改 DB ⇒ 下一读可见"不再是缓存下的可观察契约）。两处都不改业务断言口径。
  独立审查（subagent 只读）：**功能层零必修**；采纳 4 条（1 条必修 = 悬空的 `{@link FavoriteCache#invalidateUser}`；3 条建议 = 补"批量**降级**也不逐条点查"的断言、`SMISMEMBER` 措辞对齐、补本回写），0 条不采纳。
  **代价（G14，如实登记）**：本仓**无真实流量** ⇒ 这层缓存的可测收益拿不到基线证据（`SISMEMBER` vs `idx_user_content` 点查同量级）⇒ 属**已知代价后选择做**（价值 = 决策展示 + T12 的对照面）；且**计数侧缓存的边际价值随 T12 下降**（T12 后回源降为主键点查）⇒ 届时"是否仍值得缓存计数"应重新判断。
  文档同步：《缓存与数据一致性》§一（三态机清单）/ §五（新增 favorite 两行）/ §七（新增取舍 7：只有 `add` 条件写）；《韧性》§四.1（单飞接线）/ §六（降级链）；《模块边界与依赖》§二 包结构表（`cache` 新增 favorite）；设计篇 §3.7（T11 落地对照表，含两条 ⏳ 与"计数侧边际价值"提醒）。

---

### T12 `favorite_count` 冗余列启用 + 事务内批量重算（来源 能力 二期-3）【待执行】

> ⚠️ **先质疑（G14）**：
> - **任务**：前提是"实时 `COUNT(DISTINCT user_id)` 慢到要治"。⚠️ **一期的 17ms 是在测试容器上造 2 万行 / 2000 人得到的**；若真实数据量下它本身很快，**停下回报**（别为假想瓶颈付冗余列的维护成本）。
> - **设计**：冗余列 = **第二处事实源**。若判断"17ms 可直接接受、不值得引入冗余"，**停下回报** —— 本仓已记过这条判据（"去掉它功能还在" ⇒ 属可推迟的优化）。

* **入口线索**：设计篇 **§3.2**（口径，含"为什么不能增量 / 不能 `FOR UPDATE`"）+ §3.1（列已在 V2 建、一期不用）；`FavoriteItemDao.countDistinctUserByContentId`（**一期基线：`EXPLAIN` = loose index scan，≈17ms / 2 万行**）；`architecture/tech/数据与Schema演进.md`（Flyway 策略）
* **目标**：
  - **存量回填** `content.favorite_count`（V2 落地时该列全是 0）—— 走 **Flyway V3**（数据迁移）
  - `recountFavoriteCounts(Set<Long> contentIds)`：**1 次** `SELECT content_id, COUNT(DISTINCT user_id) … WHERE content_id IN (…) GROUP BY content_id` + **1 次批量** `UPDATE`
  - 三条写路径接入：`add`（单内容）/ `remove`（单内容）/ **`deleteFolder`（批量）**
  - `getFavoriteCount` 改读列（不再实时 `COUNT(DISTINCT)`）
  - ⏳ **实测**：上面那条 `GROUP BY … IN (…)` 的 `EXPLAIN` + 耗时，**数字写回设计篇 §3.2**（与一期基线对照）
* **红线边界**：
  - ★ **不做增量维护**（4 个写点 + **并发漂移**：`uk_folder_content` 维度与判定维度不重合）
  - ★ **不用 `SELECT … FOR UPDATE`**（判"不存在"时 RC 无效 / RR gap lock 共享 ⇒ 死锁）
  - ★ **不变量必须成立**：`content.favorite_count == COUNT(DISTINCT user_id)`（二期判据②）
  - ★ **一期测试不许改**（G8；唯一例外是 T10，**不在本任务**）
  - ★ ⚠️ **V3 写入后逐字节冻结**（本仓血泪教训：连注释都不能改，改了 `spring-boot:run` 直接 `Migration checksum mismatch`）⇒ **把 SQL 想清楚再写**
* **强制探索**：
  - `favorite_count` 的**列类型**（是否 `unsigned`？**下溢风险与 `F-04` 同类** —— 回填前后都要能自证不为负）
  - **回填怎么做**：一条 `UPDATE content SET favorite_count = (SELECT COUNT(DISTINCT user_id) FROM favorite_item WHERE content_id = content.id)`？⚠️ 先 `SELECT COUNT(*)` **估规模**，判断**是否需要分批**
  - `deleteFolder` 在**大夹（1000+ 条）**下的行为：确认是 **1 次 `GROUP BY` + 1 次批量 UPDATE**，**不是 N 次**
  - `recountFavoriteCounts` 的**事务边界**：必须与关系行的增删**同事务**（否则计数与明细在提交窗口内不一致）
  - `content.favorite_count` 现在有谁读？一期**无人读**；二期**T11 的 count 缓存回源**会读它 ⇒ 确认改语义不会波及别处
* **验收**：
  - ★ **独立 oracle**：抽样若干 content，`content.favorite_count == SELECT COUNT(DISTINCT user_id) FROM favorite_item WHERE content_id = ?`（**不读接口回显**、**不读缓存**）
  - 三条写路径各有用例（含 `deleteFolder` 的**批量**：删一个大夹后，受影响的每个 content 都重算正确）
  - ★ **对照夹具**：同一内容被**多个用户**收藏/取消后，count 仍等于 oracle（夹具要有"另一人"干扰行）
  - ★ **回填正确性**：V3 应用后全表一致（可复算 SQL）
  - ⏳ **实测数字**（`EXPLAIN` + 耗时）记录进设计篇 §3.2
* **回写**：

---

## 上一张清单（收藏一期，2026-10-08 → 2026-10-11）

> 明细（四要素 / 强制探索 / 反向验证逐条实测 / 审查记录）**已清出，历史在 git**。
> 逐条编号索引（含决策与问题）见 [设计篇](收藏功能-分期与设计.md) **附录「一期编号索引」**。

| 任务 | 产出 | 验证（当时） | 收口 |
|------|------|------------|------|
| **T1** V2 建表 + 域骨架 | `V2__favorite_tables.sql`（两表 + `content.favorite_count`）+ `favorite` 域骨架 + `DOMAINS` 登记 | `FavoriteSchemaTests` 3 例；反向验证 2 条 | ✅ |
| **T2** 收藏夹 CRUD | 四端点（写 POST+form / 读 GET+query）；默认夹拒删 409、删夹同事务删条目、空态 `[]` | `FavoriteFolderCrudTests` 12 例；反向验证 4 条 | ✅ |
| **T3** 私密开关 + 他人公开夹 | `POST /folder/update` 升部分更新 + `GET /folder/public?userId=X`（匿名） | `FavoritePrivacyTests` 10 例；反向验证 4 条 | ✅ |
| **T4** 收藏 / 移出 / 移动 | `POST /favorite/{add,remove,move}`；默认夹懒建落点 | `FavoriteItemWriteTests` 11 例；反向验证 6 条 | ✅ |
| **T5** 状态 + 收藏数 | `GET /favorite/{status,count}`；耗时基线（loose index scan ≈17ms） | `FavoriteStatusAndCountTests` 8 例；反向验证 3 条 | ✅ |
| **T6** 夹内列表分页 | `GET /favorite/list` + 失效脱敏占位；**T6-5** 改走 content 装载契约（R-11） | `FavoriteItemListTests` 14 例；反向验证 9 条 | ✅ |
| **T7** 前端点亮 | 抽屉导航 / 收藏弹窗 / 收藏夹页 / 失效占位卡 + `api.js` 端点注释 | 浏览器三回合实测（真库真服务） | ✅ |
| **T8** 契约测试收口 | 补"另一夹/另一内容/另一人"对照夹具；`FavoriteTransactionTests` 给两笔机制欠账永久判别力 | 收藏域 63 例；全量 391 例全绿 | ✅ |

> 回归基线轨迹：328（T1）→ 341（T2）→ 352（T3）→ 364（T4）→ 373（T5）→ 387（T6）→ 388（T6-5）→ **391（T8）**。

---

## 结转与留池（本张清单）

> 未纳入本张清单的已登记项，**逐行显式处理**：进下一张清单 / 登 `CURRENT_NEEDS.md` 留池 / 转 `FURTHER_ISSUES.md` / 废弃。
> **不允许静默跳过**。

| 来源 | 事项 | 去向（决定人 + 日期） |
|------|------|---------------------|
| 二期-1 | 互动域 + 详情内联 | ✅ 本张清单 **T9 / T10** |
| 二期-2 | 收藏缓存层 | ✅ 本张清单 **T11** |
| 二期-3 | 冗余列 + 批量重算 | ✅ 本张清单 **T12** |
| 二期-4 | 契约测试与实测 | ✅ 分摊在 **T9~T12 的「验收」列**（不单列任务） |
| 反面清单 8 条 | 增量维护 / `FOR UPDATE` / 异步重算 / MySQL 关系表 / `folders` 缓存 / 投币 / 前端列表展示 / 其它域优化 | ✅ **已在 `CURRENT_NEEDS.md` §四 + `FURTHER_ISSUES.md`（F-13 / F-14 / F-15）逐条登记**，**不进本张清单** |
| T12 | 若实测证明"写路径聚合成为瓶颈" | ⇒ 届时启动 `FURTHER_ISSUES.md` **F-14**（异步重算） |
