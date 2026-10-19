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

* **方向**：★ **投币（硬币）专项**（2026-10-19 起）—— 设计已成篇（`architecture/domain/coin/coin.md` ·
  `coin-需求.md`），范围 = **设计 §四「涉及面」** 的逐项落地。
* **活事实源 / 设计落点**：本专项 = **`architecture/domain/coin/`**（**活文档**：开工就建、执行中随代码改、
  收口时是终态）；执行态只有本文件；**过程物**（设计讨论 / 方案比较）在收口时归档到
  `archive/coin-<归档日>/`（只读追溯）—— **不是现在**。
* **下一个编号**：任务从 **`T36`** 起（`T34` / `T35` 已终结，**编号不回收**）。
* ★ **执行约定**：本专项按**逐任务开窗**推进（一任务一 commit，G1）；每个 `T#` 必须**自包含** ——
  执行者可能**不带前文上下文**，故入口线索 / 红线边界 / 验收要能**独立读懂**，不许依赖"上一窗的对话"。
* **本期交付一览**：**`T36`–`T42`（7 项）** —— 逐页登记见下（已终结编号的**一行结论**在**活速查**，全文在 git）。
* **收尾 checklist**（本期逐条打勾）：□ 回归基线全绿 · □ 完成态已清出（记入 git）· □ 编号已收编进**速查** ·
  □ 问题已结转（`FURTHER_ISSUES.md`）· □ **引用三道校验**（`doc_links` / `doc_refs` / `doc_stats`）· □ 工作树干净
* **回归基线**：上一轮 = 全量 **620** 例（2026-10-18，T34 回写）；⚠️ 本环境跑全量需 `DB_POOL_MAX_SIZE=4`
  （否则 Testcontainers MySQL 报 `Too many connections`）。

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

> ★ **本专项的任务**：设计见 `architecture/domain/coin/`；每个 `T#` 按下面模板填四要素 —— **开工时先读设计 §四**。

**（`T34` / `T35` 已清出）** 编号归宿（`AGENTS.md`「文档与引用纪律」：清出前先给编号找活速查落点）：
`T34` ⇒ [数据与Schema演进](../architecture/tech/data-evolution/数据与Schema演进.md) §四（unsigned 计数列的 `±` 守卫写法）；
`T35` ⇒ [模块边界与依赖](../architecture/tech/module-boundary/模块边界与依赖.md) §五（ArchUnit 规则 5 的由来）。
全文在 git。

**待执行**（`T36`–`T42`；来源均为 `R-17`，覆盖面 = `architecture/domain/coin/coin.md` §四 涉及面）：

### T36 V5 迁移 + 结构契约 + 新域登记（来源 `R-17` / 设计 §二）【已完成】

> ⚠️ **先质疑（G14）**：
> - **任务**：前提成立 —— `users` 无 `coin_deci` / `last_sign_date`、`content` 无 `coin_count`、仓内**无** `content_coin`（V1 基线取证）；余额 / 单视频封顶 / 幂等记账**没有载体就无从谈起**。
> - **设计**：三列 + 一表是**最小载体**（单行 `amount` 聚合的理由见设计 §二）；⚠️ 已知代价 —— **失败的投币尝试会留 `amount = 0` 行**（设计 §十 #1，对外不可观察）；余额走**十分位整数**（设计 §四）而非小数列。**已知代价后选择做**。

* **入口线索**：`src/main/resources/db/migration/`（**最高版本 = V4 ⇒ 本次新建 `V5__`**）；`favorite/FavoriteSchemaTests`（**结构契约测试样板**）；`ArchitectureTests.DOMAINS`（《模块边界与依赖》§七 清单第 1 条：新域必须登记）；`InfrastructureConnectivityTests`（结构连续性锚点）。
* **目标**：新建 `V5__coin.sql`（`users` +`coin_deci`（**有符号 `int`**）与 `last_sign_date`（`date`）；`content` +`coin_count`（`int unsigned`）；`content_coin` +`uk_user_content`）；新建 `coin/` 包骨架；`ArchitectureTests.DOMAINS` 加 `coin`；新增 `CoinSchemaTests`。
* **红线边界**：① **绝不回写 V1–V4**（已应用的迁移**逐字节冻结**，连注释都不能改 —— `数据与Schema演进.md` §2.4）；② 列类型照设计 §二**逐字**；③ `content_coin` **不加**状态 / 软删列（本期无该语义）。
* **强制探索**：① 取证 `FavoriteSchemaTests` 的"**唯一键真插两行**"手法并**照抄**（不许换成查 `information_schema`）；② 复算 `InfrastructureConnectivityTests` 的**表计数与版本序**断言现值（该文档 §六 的描述疑似滞后于 V3/V4），随 V5 同步 +1；③ 确认加 `coin` 进 `DOMAINS` 后既有 5 条 ArchUnit 规则仍绿。
* **验收**：① `CoinSchemaTests`：三列在位（表 / 列 / 类型 / 默认值）+ `uk_user_content` **真插两行被拒**；② `ArchitectureTests` 5 条全绿；③ V5 在**空库**（测试容器）与**活库**（已到 V4）两条路径都干净应用，版本序 `1→…→5`。
* **回写**：新建 `V5__coin.sql`（`users` 两列 / `content.coin_count` / `content_coin` 单行聚合表），`ArchitectureTests.DOMAINS` 加 `coin`，`InfrastructureConnectivityTests` 表计数 19→20、版本序补 `5`，新增 `CoinSchemaTests`（6 例）。空库路径（容器）与活库路径（`spring-boot:run` 直连 `spring_tv`）均 `now at version v5`，活库 information_schema 复核三列类型与设计 §二 逐字一致；`CoinSchemaTests` + `ArchitectureTests` + `InfrastructureConnectivityTests` 共 15 例全绿。
* **口径纠偏（G13）**：设计原写「失败的投币尝试留 `amount = 0` 行」，但投币是**单事务**、409/400 抛异常即回滚 ⇒ `INSERT IGNORE` 一行都不会留，且与 T38 验收②「余额不足 ⇒ 无行」直接冲突。按**验收②（零副作用）**实现，`V5` 的列注释与设计 §二 / §十 #1 同步改为真实口径（#1 换成"`count=2` 但只剩 1 枚也答 409"这条真实代价）。

### T37 硬币供给端：签到 + 余额读出（来源 `R-17` / 设计 §三 · §四 · §五.2）【待执行】

> ⚠️ **先质疑（G14）**：
> - **任务**：前提成立 —— 没有"硬币从哪来"就没人能投币（供给端必须先于写路径）；签到必须**幂等**（双击不能重复到账）。
> - **设计**：⚠️ 已知代价 —— 口径**刻意偏离 B站**（B站 靠任务领币；本仓**没有定义"在线"**，见 `coin-需求` §二）；幂等用**一条条件 `UPDATE`**（比"先查后写"更省更快），代价 = **不做连签奖励**（已裁剪）。

* **入口线索**：`user/dao/UserDao.java` + `mapper/UserMapper.xml`（**计数列方法进所在表的 DAO** —— `updateFollowCount` 先例）；`user/controller/UserController.java` 的 `/user/me`；`user/model/vo/UserInfoVO.java`（**`record`，加字段会改构造签名**）；`like/LikeController`（写端点 `form` 形态样板）；`common/security/RequestMappingLookup`（`@RequiresLogin` 的权威源）。
* **目标**：`UserDao` +4（`updateCoinDeci(userId, deltaDeci)` / `signIn(userId, today)`（**返回影响行数**）/ `findCoinDeci(userId)` / `findLastSignDate(userId)`）+ Mapper 语句；`coin/service/CoinService#signIn`（**一条原子条件 `UPDATE`、不加 `@Transactional`、不发事件**）；`coin/controller/CoinController`（**类级 `@RequiresLogin`** —— coin 两个端点**都需登录、没有匿名端点**，与 `LikeController` 同族；★ 类注释须写明"**绝不照抄 `FavoriteController` 的逐端点声明**"及其理由）；`UserInfoVO` +`coin`（`BigDecimal`，scale 1）与 +`signedToday`（`last_sign_date == 上海今日`）。
* **红线边界**：① **`coin_deci` 全程"十分位整数"** —— 换算**只许发生在 VO 出口**（设计 §四）；② 签到**不得**发领域事件；③ **不新增**"余额查询"端点（复用 `/user/me`）；④ 文案冻结：「签到成功」/「今日已签到」。
* **强制探索**：① 逐处改动 `UserInfoVO` 的构造调用点（`UserService.getProfile` + 测试）；② 复算"上海自然日"取值口径（`ZoneId.of("Asia/Shanghai")`）与库里 `datetime` 的既有时间语义一致；③ 把新端点纳入 `SecurityContractTests` 的断言面。
* **验收**：`CoinSignInTests`（HTTP + DB 终态 + **独立 oracle**）：① 首次 ⇒ 200 + `credited=true` + 余额 +1；② 同日重复 ⇒ 200 + `credited=false` + 余额**不变**；③ **并发签到** ⇒ `coin_deci` 只 +10、`last_sign_date` 只推进一次（**去掉 `WHERE` 守卫必红**）；④ `/user/me` 的 `coin` 为一位小数、`signedToday` 与库中 `last_sign_date` 复算一致；⑤ 未登录 ⇒ 401（判别力由 `SecurityContractTests` 守）。
* **回写**：（待执行）

### T38 投币写路径：校验顺序 + 两台原子条件写（来源 `R-17` / 设计 §三 · §五.1 · §八）【待执行】

> ⚠️ **先质疑（G14）**：
> - **任务**：前提成立 —— 一次投币同时动 `content_coin` / `content.coin_count` / **两个用户**的 `coin_deci`，**必须单事务**；封顶与余额**都不能**"先读后写"（并发会突破 2 / 扣成负数）。
> - **设计**：⚠️ 已知代价 —— 封顶走 `INSERT IGNORE`（`amount=0`）+ 条件 `UPDATE` ⇒ 留 `amount=0` 行（设计 §十 #1）；"不能自投 / 图文不可投 / 封顶 2"是**本仓自定的对齐口径**（`coin-需求` §三）。

* **入口线索**：`like/LikeService#likeContent`（**校验顺序样板**：存在性 → 冲突）；`favorite/service/FavoriteService`（**取证"容量 400"用哪个异常类**，余额不足沿用**同类**）；`content/dao/ContentDao.java`（`isContentExist` / `findAliveContentType` —— 窄查询先例）；`application.yaml` 的 `video.rate-limit.cost.base`；`RateLimitWeightTableTests`（**完整性扫描会逼新端点定价**）。
* **目标**：`ContentDao` +`updateCoinCount(contentId, delta)` 与**一条窄查询** `findAliveContentAuthorAndType(contentId)`（取 `authorId` + `type`，**登记进《模块边界与依赖》§六**，见 T42）；`coin/dao/ContentCoinDao`（**按表命名**，镜像 `ContentLikeDao`）+ `mapper/ContentCoinMapper.xml`，方法 `insertIgnoreZero` / `addAmount`（条件）/ `findAmount` / `findAmountsByUser`；`CoinService#add` 按设计 §五.1 的**七级顺序**落地；`CoinController` 的 `POST /coin/add`；**权重表登记 2 个新端点**（值各 **3**，照"其余写路径 3"；⚠️ key 形态**必须带方括号**：`"[POST /coin/add]"` —— 漏写方括号会被 Spring 静默吞掉键名，靠**绑定期校验**才会在启动时报出）。
* **红线边界**：① **三处计数 / 余额必须同进同退**（单 `@Transactional`）；② 扣款**必须**条件 `UPDATE`（影响 0 行 ⇒ 400「硬币不足」），**不得**先读后写；③ 拒因优先级照设计 §五.1（**409 先于 400**）；④ **不得**出现任何删除 / 取消路径。
* **强制探索**：① 复算"可投判定"（`coin_deci >= count × 10`）与设计 §四 的"整数部分"口径一致；② 取证 `isContentExist` 是否带 `is_deleted = 0`；③ 读 `RateLimitWeightTableTests` 断言，确认**漏配真的会红**。
* **验收**：`CoinAddTests`（HTTP + DB 终态 + **独立 oracle**）：① 成功 ⇒ 200 + 余额 −`count×10` + `content.coin_count` +`count` + **作者 `coin_deci` +`count`**（三条**分别独立复算**）；② 余额不足 ⇒ 400 **且零副作用**（无行、计数不变、作者余额不变）；③ 已满 ⇒ 409 且 `amount` 停在 2；④ 图文帖 ⇒ 400；⑤ 自投 ⇒ 400；⑥ 内容不存在 ⇒ 404；⑦ `count=3` ⇒ 400；⑧ **并发**：两线程各投 1 ⇒ 恰一条成功、`amount` 恰为 2（**去掉条件 `WHERE` 必红**）；⑨ 未登录 ⇒ 401。
* **回写**：（待执行）

### T39 coin 缓存层（两个 key）+ 内容删除级联（来源 `R-17` / 设计 §六）【待执行】

> ⚠️ **先质疑（G14）**：
> - **任务**：前提成立 —— 详情页每读一次"我投了几枚"若直查 DB = **每请求一次点查**；本仓对"我 X 了吗"的既有口径**就是每用户维度缓存**（`user:likeSet` / `user:favoriteSet`）。
> - **设计**：⚠️ 已知代价 —— `content:coinCount` 走**独立 key**（**对 `F-25` 的站边**：不把"计数连坐失效整份 `content:{id}`"复制到新域）⇒ 列表面多一趟 `MGET`（设计 §十 #2）。★ **纠正一处易混**：「每用户完整哈希」**不是** `F-21` 被否的三件套（见设计 §6.2）。

* **入口线索**：`like/cache/LikeCache` + `LikeRedisOps`（**三态读 / 空标记 / 单飞 / 条件写 Lua 样板**）；`favorite/cache/FavoriteCache`（"计数只走独立 key"样板）；`common/cache/CacheKeys`（键命名唯一源）；`common/config/LikeCacheProperties`（**绑定期校验样板**）；`content/event/ContentCacheChangedListener`（**内容删除分支** —— `deleteContentLike` 的调用点）。
* **目标**：`CacheKeys` +2 键 + `empty:` 派生；`application.yaml` +`video.cache.coin-ttl`；`common/config/CoinCacheProperties`（★ **与 `LikeCacheProperties` 同目录、同前缀段** —— 本仓 `*Properties` 一律在 `common/config/`，**不要**在 `coin/` 下新建；`coin-ttl` 并入既有 `video.cache` 段；非正数 ⇒ **启动拒**）；`coin/cache/CoinRedisOps`（`HGET` / 批量 / 条件 `HSET`（**写新总量，精确回放**）/ 条件 `INCRBY` / 回填 / 空标记 Lua）+ `CoinCache`（三态读、**全量回填**（`amount > 0`）、单飞、降级）；★ **`content:coinCount` 的 miss 回源 = 读 `content.coin_count` 那一列**（经 `ContentDao`，与 `findAliveContentType` 同族的单列窄查询 —— 开工时定是新增还是复用）；`coin/event/CoinChangedEvent` + `CoinChangedListener`（`AFTER_COMMIT` 条件写两个 key）；`CoinService#deleteContentCoinCount` 并接进 `content` 侧删除分支。
* **红线边界**：① **只碰 coin 域自己的两个 key** —— **不得**去碰 `content:{id}`（`LikeChangedListener` 瘦身后的纪律）；② 写一律**提交后**；③ 冷 key **不写半套**；④ Redis 失败 ⇒ **降级、不抛、不 500**；⑤ ★ **内容删除只失效 `coinCount` 缓存、不物理删 `content_coin` 行**（不可取消 + 不变量 `coin_count == SUM(amount)` 要求行留存 —— **与 like 的 `deleteByContentId` 有意不同**，须写进类注释）。
* **强制探索**：① 复算 `LikeCache` 的三态判定顺序（**先空标记、后数据 key**）与"写失败 ⇒ 失效让读自愈"手法；② 定 `CoinChangedEvent` 的字段集（`userId` / `contentId` / **新总量** / `delta` / `authorId`）—— 本域要**精确回放**，不能照抄 `INCR` 形态；③ 定位内容删除分支的现有调用行。
* **验收**：`CoinCacheTests`：① 条件写（已加载 ⇒ 命中；**冷 key ⇒ 一个字节都不写**）；② **事务回滚 ⇒ 两个 key 根本不存在**（`AFTER_COMMIT` 的存在证明 —— 让事务在**已有成功写之后**失败，范式同 `LikeCacheTests.事务回滚后缓存未被污染`）；③ 空集 ⇒ 写空标记、下次读**不回源**；④ miss ⇒ 全量回填且**清空标记**；⑤ `resilience` 组：Redis 不可达 ⇒ 降级直查 DB、不 500。★ 反向验证：去掉条件 `EXISTS` 守卫 ⇒ **必红**。
* **回写**：（待执行）

### T40 读面接入：互动态 `coin` + 公共计数 `coinCount`（来源 `R-17` / 设计 §三 · §七）【待执行】

> ⚠️ **先质疑（G14）**：
> - **任务**：前提成立 —— 没有读面，前端拿不到"我投了几枚 / 这条被投了几枚"。
> - **设计**：⚠️ 已知代价 —— `interaction` 键集 **3 → 4**（列表各面不选该维 ⇒ 列表里 `coin` **恒 `null`**）；**`getStatus` 按 `type` 分流**而**不给** `InteractionDimension` 加 `COIN` 常量（**YAGNI，有意与原三维不同**）；`coinCount` 走 **VO 字段**、**不进 `ContentCacheDTO`**。

* **入口线索**：`interaction/model/vo/InteractionStatusVO` + `InteractionService#getStatus`（**已收 `type`**）；`content/model/vo/ContentVO` / `ContentDetailVO`；`content/service/ContentStatusFiller`（`/start` `/search` `/profile` 三面共用组装点）；`feed/service/FeedService`（自填互动域那一处）；`content/service/ContentService`（详情装配）；`InteractionStatusContractTests` / `ProfileReadTests`（**键集断言会被本任务打破，属预期 breaking**）。
* **目标**：`InteractionStatusVO` +`coin`（`Integer`：`0/1/2` = 数量、`null` = 不适用 / 本面不查）；`getStatus` 内**按 `type == 1` 查**（经 `CoinService#getCoinAmount`）、图文置 `null`；`ContentVO` / `ContentDetailVO` +`coinCount`（`Long`）；三个组装点各加**一趟批量读**（`CoinService#batchGetCoinCount`）。
* **红线边界**：① **`coinCount` 绝不进 `ContentCacheDTO`**（冻结的缓存形状不动 —— 最易被"顺手"破坏的一处）；② **不加** `COIN` 维度常量；③ **不得循环调单条**（批量语义原样透传 —— `ContentStatusFiller` 类注释的既有纪律）；④ 列表 `coin` 恒 `null` 是**契约**、不是漏填。
* **强制探索**：① 逐条复算 `InteractionStatusContractTests` / `ProfileReadTests` 的"键集恰好三维"断言，按新口径改成"**恰好四维**"（**不许为变绿而删断言**）；② 取证详情页 `getStatus` 的 `type` 来源；③ 确认 `coinCount` 在详情 / 列表两条路径都能由 `content.coin_count` **独立复算**。
* **验收**：`CoinReadTests`（HTTP + 独立 oracle）：① 详情 `interaction.coin` 与 `content_coin.amount` **复算一致**；② **图文帖 ⇒ `coin = null`** 且 `coinCount` 有值；③ 匿名 ⇒ `interaction` 整体 `null`（**不是**四维全 null 的空壳）；④ 列表：`coin` **恒 `null`**、`coinCount` 与 DB 复算一致；⑤ 键集 = **恰好四维**。
* **回写**：（待执行）

### T41 前端接入（`api.js` / `detail.js` / `user.js`）（来源 `R-17` / 设计 §四）【待执行】

> ⚠️ **先质疑（G14）**：
> - **任务**：前提成立 —— 后端契约变了（2 个新端点 + 3 个新字段），**不同步 `api.js` 前端就调不通**（`INDEX.md` §一 边界① 是硬要求）。
> - **设计**：⚠️ 已知代价 —— 前端**不做**投币动画 / 音效 / 明细页（`coin-需求` §六 已裁剪）；签到按钮的置灰态**依赖 T37 交付的 `signedToday`**。

* **入口线索**：`static/js/api.js`（**全站唯一 API 出口，必须同步**）；`static/js/views/detail.js`（投币按钮）；`static/js/views/user.js`（签到按钮 + 余额）；`static/js/auth.js`（会话信息）；`static/js/utils.js`（现有工具函数）。
* **目标**：`api.js` +`coinAdd` / `coinSignIn` 封装；`detail.js` 投币按钮（`0` ⇒ 可选 1 或 2；`1` ⇒ 只能再投 1；`2` ⇒ 提示「对本稿件投币枚数已用完」并禁用；**图文帖不渲染**；弹层显示 `/user/me` 的余额；成功后就地更新 `coinCount` / `interaction.coin` / 余额）；`user.js` 签到按钮（`signedToday` ⇒ 置灰）+ 余额（一位小数）。
* **红线边界**：① 前端**不自算规则**（封顶由后端 409 兜底，前端只做展示态）；② **不引入构建步骤 / 新依赖**（本仓前端是**原生 JS**）；③ 不碰后端。
* **强制探索**：① 复算 `utils.js` 有无现成的数字格式化，没有则**只在本页内**写一位小数格式化；② 取证现有互动按钮组的 DOM 位置与事件绑定方式，**照抄同款**（不自创新模式）。
* **验收**：浏览器手工清单 —— ① 已投 0 ⇒ 可选 1/2、成功后按钮变「已投 1」；② 再投 1 ⇒ 「已用完」+ 禁用；③ 图文帖**无**投币按钮；④ 余额不足 ⇒ 弹「硬币不足」；⑤ 签到 ⇒ 余额 +1、按钮置灰；⑥ **刷新后**余额与已投数仍正确（证明读面生效）；⑦ 控制台**零报错**。
* **回写**：（待执行）

### T42 收口：依赖边登记 + 文档收编 + 回归基线（来源 `R-17` / 设计 §九）【待执行】

> ⚠️ **先质疑（G14）**：
> - **任务**：前提成立 —— 期收口 SOP（`INDEX.md` §五.5 五步）要求编号收编 / 三道校验 / 登记台重建；`content ⇄ coin` 那条**窄读例外**按《模块边界与依赖》§六 的纪律"**登记即认账**"（不登记 = 无声绕开判据）。
> - **设计**：⚠️ 已知代价 —— `F-18` **复核结论 = 未触发**（coin 域不依赖 content 的任何 Service / Bean ⇒ 装配期环没多出来），**维持留池**；本任务只留一行复核留痕、**不做**抽窄组件。

* **入口线索**：`tech/module-boundary/模块边界与依赖.md` **§六**（`content ⇄ favorite` / `content ⇄ comment` 行的写法是样板）；`ARCHITECTURE.md` §一 / §三；`coin.md` **附录「编号速查」**；`INDEX.md` §五.5 收口 SOP；`CURRENT_TASKS.md` 周期头的收尾 checklist。
* **目标**：§六 补 `content ⇄ coin` 行（读状态走 Service / 同事务改计数走 **DAO 薄依赖** / 那条**窄读例外**实名登记）；`coin.md` 附录**收编 `T36`–`T42`**（每号一行结论）；`FURTHER_ISSUES.md` 的 `F-13` 行收尾核销。
* **红线边界**：① 收编**只许一行结论**（理由随正文进 git —— 防索引膨胀）；② 代码注释**只许**引用 `architecture/**`（`task/` 登记台不得进代码）；③ **不得**改 V1–V4 迁移文件。
* **强制探索**：① `grep` 取证"coin 域**确实没**依赖 content 的 Service / Bean"，给 `F-18` 的"未触发"结论留**可复算证据**；② 复算 `doc_refs.py` 是否因新域注释里的路径引用报警。
* **验收**：① **全量回归**：`.\mvnw.cmd -B clean verify`（⚠️ **必须带 `clean`** —— 不带会被 `target/classes` 旧 class 掩盖"源码编译不过"，实测过该路径）＋ `python tools/run_tests.py --group resilience`；**须设 `DB_POOL_MAX_SIZE=4`**；② **反向验证清单**逐条实测并在回写登记（改哪一行必红 / 红数标组口径）；③ 三道门禁全绿：`doc_links` 0 / `doc_refs` 0 / `doc_stats` 无新超阈值；④ 周期头**收尾 checklist 六项**逐条打勾。
* **回写**：（待执行）

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
| 待登记 | | |
