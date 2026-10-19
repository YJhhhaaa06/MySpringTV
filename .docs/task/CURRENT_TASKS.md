# 当期任务（Current Tasks）

> **定位**：登记**当期正在执行的任务**及其执行态（四要素 + 三句以内回写）。
> **计划态**（设计 / 骨架 / 验收口径）在 `architecture/`（横切机制 `tech/`、业务域 `domain/`）——
> 各专项设计稿随收口归档到 `archive/<域>-<归档日>/`（**只读追溯**）；
> **本期范围与反面清单**见 `CURRENT_NEEDS.md` §四。**本文件只记执行态**，不复制计划。
> **需求来源**：`CURRENT_NEEDS.md`（N# / R-##）；**执行中发现的问题** → `CURRENT_ISSUES.md`。
>
> ★ **膨胀控制**：任务完成即标注，**期收尾时清出本文件**（归宿 = git commit）。
> 清出前**必须先把该编号收进** ★ **活速查**（落点见 `CURRENT_NEEDS.md` §三 末）——
> 代码 / 测试注释里的 `T#` / `R-##` / `I-##` 引用，那里是它们唯一的**活**查证入口；
> **理由 / 论证**随正文进 archive / git（**速查只许一行结论**）。
> **编号不回收**（下期顺延）；**登记台文件复用、不改名**。

---

## 周期头（每窗开读）

* **方向**：★ **计数缓存专项**（2026-10-19 起，`T43`–`T47`）—— 把高频变化的四个计数从内容载荷里搬出去，
  让 `content:{id}` 只被"慢变字段"失效。**用户 2026-10-19 拍板**：
  **计数一律独立 key + 只在详情平铺 + 列表不带计数**（推翻 F-25 的"两种形态并存"）。
* **活事实源 / 设计落点**：口径 = [缓存与数据一致性 §五.1](../architecture/tech/cache/缓存与数据一致性.md)
  （**活文档**，随代码演进）；投币侧受影响的两条依赖边见 [coin.md](../architecture/domain/coin/coin.md) §七 / §九。
  执行态只有本文件。
* **下一个编号**：本期任务为 `T43`–`T47`；再往后从 **`T48`** 起（编号不回收）。
* ★ **执行约定**：一任务一 commit（G1）；每个 `T#` **自包含** —— 入口线索 / 红线 / 验收能独立读懂。
* **本期目标态**：`content:{id}` 的 JSON 里**没有** `likeCount` / `commentCount`；点赞、发评论**不失效**内容载荷；
  详情平铺 `likeCount / commentCount / favoriteCount / coinCount`（各自独立 key）；列表**不返回**任何计数。
* **回归基线**：入场 = 全量 **689** 例（2026-10-19，投币期收尾）+ resilience **41** 例；
  ⚠️ 本环境跑全量需 `DB_POOL_MAX_SIZE=4`（否则 Testcontainers MySQL 报 `Too many connections`）。

---

## 运行约定（只保留当前仍然有效的）

| 号 | 约定 |
|----|------|
| G1 | 默认一任务一窗口一 commit；★ **但允许执行者自拆** —— 任务可能是连贯的、要深入调查后才知怎么拆，执行者可自行拆子任务与多个 commit（**每 commit 仍须可编译**，message 带任务号），拆法写进回写 |
| G2 | **证据可复核**：入口与验收能定位（文件:行 或 可复现命令），不接受纯文字断言 |
| G3 | **疑问分级**：可自答的 → 任务回写留一句；须裁决的 → **停下问用户**，不擅自拍板、不硬扛、不超范围 |
| G4 | 一任务 = 四要素 + 三句以内回写；探索过程 / 失败尝试 / 中间稿一律走 git，不进本文件 |
| G5 | **声明式机制必须配契约测试**：断言通过 ≠ 机制生效；写「证明某机制」的用例前先自问「去掉那行实现会红吗」（详见仓库根 `AGENTS.md`「测试纪律」节） |
| G6 | 改 schema 走 Flyway **最高版本 +1**（**V1 冻结、V2 / V3 / V4 已应用** ⇒ **下一次新建 V5**）；⚠️ **已应用的迁移文件逐字节冻结**（**连注释都不能改**，见 `architecture/tech/data-evolution/数据与Schema演进.md` §2.4）；测试用 DB 时钟造时间窗、关键计数走独立 oracle 查列 |
| G8 | ★ **跨期约定（升格保留）**：测试必须只断言可观察行为（HTTP 状态 + body + DB 终态）—— 换实现时测试**不许改**；改了就说明测试在抄实现（纪律①） |
| G13 | ★ **跨期约定（升格保留）**：清单只给方向与红线，不写死实现步骤 —— 有些问题**只有执行期才看得见**。执行中发现清单前提不成立 / 与既有实现冲突 / 有更好的拆法 ⇒ **自行决断或停下登记 `CURRENT_ISSUES.md` I-##** |
| G14 | ★ **每个任务都显式标注「可质疑」（2026-10-11 用户要求）**：**任务可被质疑**（前提不成立 / 需求已被既有实现覆盖 / 执行时机不对）；**设计也可被质疑**。⚠️ 但本项目是学习项目、**无真实流量**（见 `AGENTS.md`「项目定位与优化口径」）⇒ **质疑成立 ≠ 停做**：**先把代价写进回写**；若仍做，注明"**已知代价后选择做**"。**只有质疑指向真实缺陷才必须停下回报** |

> **跨期约定指针（代码直接引用这些号 ⇒ 行不可删）**：`G7`（一期不写缓存 / 不做增量维护，✅ 二期已解除）、
> `G9`（`favorite` 登记进 `ArchitectureTests.DOMAINS`）、`G10` / `G11` / `G12`（**长期红线**）——
> 结论见 **`favorite.md` 附录「编号速查」**（`G#` 表）。
> ⚠️ `C-1`–`C-6`（需求侧通用约定）在 `CURRENT_NEEDS.md` §一。

---

## 任务

> 每任务必含**四要素 + 回写**；状态取值：待执行 / 执行中 / 已完成 / 搁置（搁置**必须**注明去向）。
> ★ **每个任务开头的「先质疑」是约定（G14），不是客套** —— 认真回答它。
> ⚠️ **读法（G14 校准）**：块里列的是**已知代价 / 反对理由**，**不是"请自行否决"** ——
> 出现"停下回报"字样时按 G14 理解：**先记代价、再决定**；**只有指向真实缺陷才必须停**。

> ★ **下一期任务**：按下面模板填四要素 —— 开工时先读该专项的**活设计**（`architecture/` 下对应域 / 横切面）。

**（`T34` / `T35` 已清出）** 编号归宿（`AGENTS.md`「文档与引用纪律」：清出前先给编号找活速查落点）：
`T34` ⇒ [数据与Schema演进](../architecture/tech/data-evolution/数据与Schema演进.md) §四（unsigned 计数列的 `±` 守卫写法）；
`T35` ⇒ [模块边界与依赖](../architecture/tech/module-boundary/模块边界与依赖.md) §五（ArchUnit 规则 5 的由来）。
全文在 git。

**（`T36`–`T42` 已全部收口并清出）** —— 本期 7 项均已完成，**一行结论**收编进
[coin.md](../architecture/domain/coin/coin.md) 附录「编号速查」（`T36`–`T42` 表）；全文在 git。
★ T42 收口时对 T36–T41 补做了**测试复核**（此前未走 review 流程）：修掉 7 处「全绿但抓不到错实现」的
判别力缺口（累计语义 / 余额竞态 / 半枚 floor / 校验次序 / 缓存写失败与累计总量 / 批量不循环 /
内容删除只失效），并纠正「回滚后 key 不存在」这类**与断言事实相反**的措辞（详见 git 的 `test(coin)` 提交）。

### T43 缓存载荷去计数 + 详情平铺四计数（来源 F-25 / 2026-10-19 拍板）【已完成】

> ⚠️ **先质疑（G14）**：列表去掉计数是**契约破坏**（前端卡片不再显示 ❤💬🪙）。
> 判断：计数不参与任何列表决策、只为装饰付 3 趟批量 ⇒ 用户明确接受破坏，已知代价后选择做。

* **入口线索**：`ContentCacheDTO` / `ContentMapper.xml` 的三个 `SELECT` + `resultMap` / `ContentVO` / `ContentDetailVO`
* **目标**：`content:{id}` 不含 `likeCount` / `commentCount`；详情平铺四个计数；列表零计数
* **红线边界**：`content:{id}` 的**其余键集**与 JSON 属性名不动；不改 schema；不动 `content.like_count` 等列
* **回写**：三个 SELECT 与 resultMap 去掉两列；`ContentVO` 删 `coinCount`；`ContentDetailVO` 加四个 `long` 字段

### T44 评论计数独立 key（`content:commentCount`）【已完成】

> ⚠️ **先质疑（G14）**：为什么不放 comment 域？——`ArchitectureTests` 规则 4 禁止 `content → comment`
> 的 service/model/cache，且反向会成装配环。已按"内容表自己的列"归 content。

* **入口线索**：`ContentCountCache`（新）/ `CacheKeys#contentCommentCount` / `ContentDao#findCommentCount`
* **目标**：评论数独立 key，miss 回源 `content.comment_count` 单列；评论增删后 DEL（不猜 delta）
* **红线边界**：`0` 合法 ⇒ 无空标记；内容删除时一并失效；DB 失败上抛、Redis 失败降级
* **回写**：新增缓存类 + DAO 窄查询；`CommentCacheChangedContentListener` 改只 DEL 该键；`removeContent` 一并清

### T45 点赞连坐失效拆除 + 公共计数聚合【已完成】

> ⚠️ **先质疑（G14）**：为什么计数走 interaction 而不是 content 直连？
> ——`content → favorite.service` 与 `FavoriteService → ContentService` 会闭成直接装配环；
> interaction 对 favorite 已是 `@Lazy`，复用它**不新增断环点**。已知代价：interaction 多一个"公共计数"契约。

* **入口线索**：删 `content.event.LikeChangedContentListener` / `InteractionService#getPublicCounts`
* **目标**：点赞不再失效内容载荷；详情四个计数一趟取齐
* **红线边界**：不得给 content 新增 favorite/comment 依赖；不得再引入 `@Lazy`
* **回写**：删除监听器；`ContentPublicCounts` record + 聚合方法；`ContentService` 去掉 `CoinService`、改注入 `ContentCountCache`

### T46 列表装配点收敛 + 前端对齐【已完成】

* **入口线索**：`ContentStatusFiller#fillCoinCountBatch`（删）/ `StartController` / `ProfileService` / `FeedService` / `utils.js` / `detail.js`
* **目标**：列表不再为计数付任何批量；前端列表卡片去掉计数行、详情直接用内嵌 `favoriteCount`
* **红线边界**：`/favorite/count`、`/like/content/count` 端点保留（外部契约）
* **回写**：删除 `fillCoinCountBatch` 与三处调用；feed 去掉 coin 依赖；详情首屏不再单发 `/favorite/count`

### T47 测试补强 + 反向验证 + 文档收口【已完成】

* **入口线索**：`ContentCountTests`（新）/ `CoinReadTests`（列表用例改写）/ `缓存与数据一致性 §五.1`
* **目标**：载荷无计数、点赞/评论不失效内容 key、四路来源不串线、列表无计数键；三道文档门禁 0 违规
* **红线边界**：不许用 `path(x).asLong()==0` 断言"键不存在"（缺失节点也返回 0 ⇒ 假绿）
* **回写**：见 git 的 `test(count)` / `docs(count)` 提交；`F-25` 核销、`coin.md` 反向修正、`CURRENT_TASKS` 重建


```
### T# <任务名>（来源 R-## / 设计 §x）【待执行】

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

## 结转与留池（收尾时逐行核销）

> ★ **不允许静默跳过任何已登记项** —— "本期纳入"与"不做"都必须在表里有一行。
> 反面清单的唯一事实源 = `FURTHER_ISSUES.md` 的 `F-##` 行（每行带**推翻条件**）。

| 来源 | 事项 | 去向（决定人 + 日期） |
|------|------|---------------------|
| `T42` 复核 | T36–T41 的测试判别力缺口 7 处（累计语义 / 余额竞态 / 半枚 floor / 校验次序 / 缓存写失败与累计总量 / 批量不循环 / 内容删除只失效） | ✅ **已现场修**（`test(coin)` 提交，2026-10-19；反向验证 7 处全红） |
| `T42` 复核 | 并发首次投币的 `INSERT IGNORE` + 条件 `UPDATE` 理论死锁窗口（`1213`，**未复现**） | **登记为已知代价**（coin.md §十 #7）；推翻 = 真实并发出现 1213 ⇒ 改 ODKU / 重试 |
| `T42` 复核 | `@RequiresLogin` 的「显式声明」在**默认拒绝**下不可观测（`SecurityContractTests` 只能钉路由） | **不处置**（漏标注解 = 401 可见故障）；判据边界已写进该用例注释 |
| `T42` 复核 | 测试并行执行会互踩（每测 `DELETE FROM users` / Redis `KEYS *`） | **不处置**：surefire 未开并行；若开启须按类隔离命名空间（登记备查） |
