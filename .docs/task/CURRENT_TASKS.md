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

* **方向**：★ **播放记录（history）第二轮 —— 上报缓冲**（2026-10-22，需求源 = `CURRENT_NEEDS.md` §三 `R-19`）
  —— **已交付**（`T51-1`–`T51-3`）。`/report` 只写 Redis、退出信号 `/flush` 同步落库、
  轮询只兜"没正常上报退出"的用户；`/position` 读缓冲优先；Redis 不可用 ⇒ `/report` 503 + `Retry-After`。
  设计 = [history-上报缓冲](../architecture/domain/history/history-上报缓冲.md)（缓冲内核 / 五条契约 / 失败姿态）；
  客户端那份见 [history-客户端契约.md](../architecture/domain/history/history-客户端契约.md)（独立成篇）。
  ★ **第一轮已收口**（2026-10-21，`R-18`）：进度上报 / 播放事件 / 列表 / 续播 / mp4 时长，`T45`–`T49` 交付。
  **用户 2026-10-21 拍板**：做播放量（B 方案：冗余列 + 独立 key + 详情第 5 计数）；进度与播放事件
  **拆两端点**（`/report` 纯进度、`/play` 唯一计数入口）；历史**不可删**；失效内容**做占位**；
  时长存 `content_media` + `ContentCacheDTO` 投影；V7 删遗留 `video` / `videoinfo`。
* **活事实源 / 设计落点**：[history.md](../architecture/domain/history/history.md)（设计 + 编号速查）+
  [history-需求.md](../architecture/domain/history/history-需求.md)（用户可见规则）。
  ★ **代码注释只引这两篇 + `类#方法`**；不得引用任何草稿 / 本登记台。
* ★ **编号**：第一轮 `T45`–`T49` **全部交付并已清出本文件**（结论见 history.md 附录速查；过程 `git log 6f895f5..3ea45f1`）；
  `T50`（V8 列表索引）**已交付**（2026-10-21）。★ **第二轮 `T51-1`–`T51-3` 已全部交付**（2026-10-22，
  服务端上报缓冲；结论见 history.md 附录速查，设计 = history-上报缓冲.md）；★ **`T52` 已交付**（2026-10-22，
  `R-20` 推翻"读缓冲优先"）；**下一编号从 `T53` 起**
  （`T43` 占位说明、`T44` 收口结论均见 git）。
* ★ **执行约定**：一任务一 commit（G1）；每 commit 可编译且自带验收测试 + 反向验证。
  清单只给**目标 / 红线 / 验收入口**，**不写实现步骤**（G13）——执行者自行决定拆法；
  遇到清单前提不成立先登记 `CURRENT_ISSUES.md`，不擅自扩范围。
* ★ **归档裁定（2026-10-21）**：本期**不建** `archive/` —— `history.md` / `history-需求.md` 是
  **活文档**（域文档不随期滚动，收口即终态），逐条论证与失败尝试已在 git；
  与投币专项同例（只有"一期一稿型设计稿"才归档）。
* **回归基线**（2026-10-22 **T52 后复跑**，`mvnw -o test`）：
  默认组 **799/799 绿**（T51-3 后 800；T52 在 `HistoryPositionTests` 删 2 例 / 加 1 例 ⇒ 净 −1）。
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
| G6 | 改 schema 走 Flyway **最高版本 +1**（⚠️ **已应用的迁移文件逐字节冻结**——**连注释都不能改**，见 `architecture/tech/data-evolution/数据与Schema演进.md` §2.4）；★ 本期有 schema 变更：**V6**（播放记录）+ **V7**（删遗留表）+ **V8**（列表索引，T50） |
| G8 | ★ **跨期约定（升格保留）**：测试必须只断言可观察行为（HTTP 状态 + body + DB 终态）—— 换实现时测试**不许改**；改了就说明测试在抄实现（纪律①）。⚠️ **回源口径契约变更除外**（如 T12 / T44-1 的"回源真理源切换"）：oracle 必须跟随真理源，此时改测试属**契约跟随**而非迎合，须在用例注释里写明口径变更依据 |
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

**（`T44-1`–`T44-3` 已收口：`34fe36f` / `88cb8b3` / `aadbf7c`；T43 占位编号的裁定见 git）**

### 第一轮已收口（`T45`–`T50`）；第二轮已交付（`T51-1`–`T51-3`，2026-10-22）

> **`T45`–`T49` 全部交付**，执行态已按「膨胀控制」清出本文件；**结论的唯一活查证入口**
> = [history.md 附录「编号速查」](../architecture/domain/history/history.md#附录编号速查活的查证入口)（每号一行）。
> 过程 / 论证 / 失败尝试 = git（`6f895f5..3ea45f1`，10 个 commit）。`T50`（V8 列表索引）已交付。
> ★ **第二轮 `T51` 开局（2026-10-22，`R-19`）** —— 服务端上报缓冲；设计见 history-上报缓冲.md。

| 号 | 一句话 | 交付形态 |
|---|---|---|
| T45 | V6/V7 迁移 + 结构契约（`watch_history` / `duration_seconds` / `play_count`；删遗留 `video`·`videoinfo`） | `HistorySchemaTests`；19 行 / 版本 `1..7` |
| T46 | mp4 时长解析 → 落库 → 内容载荷投影（三个落点：DTO / `buildContentMedia` / `copyCommon`） | `Mp4DurationReaderTests` 15 + `UploadDurationTests` 8 |
| T47 | history 写路径 `/report`（ODKU 无事务）+ `/play`（单事务首播判定） | `HistoryWriteTests` 10；权重 36→38 |
| T48 | 播放量读面：5 键聚合 + 条件 `INCRBY` + 详情 `playCount` + 删除失效 | `ContentPlayCountTests` 6；详情键集 19 |
| T49 | 历史读面：`/list`（失效占位）+ `/position`（续播）；`I-23` 核销 | `HistoryListTests` 15 + `HistoryPositionTests` 6 |
| T50 | **V8 列表索引**：`idx_user_update` → `idx_user_update_content`（前三列即 `ORDER BY`、第四列覆盖），消灭每页全量 filesort；替换而非新增 | `HistorySchemaTests` 索引全集；版本 `1..8`；基线见 history.md §7.1 |

> ⚠️ **本轮提醒**：`history` 域**没有**历史缓存与删除能力（刻意不做，推翻条件在需求篇 §六）；
> 前端接入 / 时长回填 / 容量上限仍未做 —— 都等着"推翻条件"成立，不要当遗漏补。
> ★ 服务端心跳缓冲**已由 `R-19` 拍板改为做**（`F-27` 原"想过但否决"的理由前提已被推翻，行保留作留痕）。

### T51-1 缓冲内核（来源 R-19 / 设计 上报缓冲 §2–§5）【已完成】

> ⚠️ **先质疑（G14）**：
> - **任务**：缓冲的键与原子性是否可以先于端点独立落地？——可以：它只碰 Redis，**不接 Service / Controller**，
>   所以本任务不改变任何既有契约（`/report` 仍同步落库），可独立编译、独立验收。
> - **设计**：`HSET`+`ZADD` 是否真需要 Lua？——需要。两次往返之间断连 ⇒ 数据在 hash、标记不在 dirty ⇒
>   永不被轮询捡到，只能等 TTL 静默消失（见上报缓冲 §3）。

* **入口线索**：`common/cache/{RedisOps,RateLimitRedisOps}.java`（`*RedisOps` 子类形态 + Lua 常量写法）、
  `common/cache/CacheKeys.java`（键工厂）、`common/config/CoinCacheProperties.java`（配置类形态）、
  `feed/mq/FeedDeliveryBuffer.java`（有界缓冲 / 关停 / 告警节流的先例）。
* **目标**：`history` 域拥有一套**纯 Redis** 的上报缓冲内核：写入（原子）、按用户取全量、按"值未变"条件删、
  单 field 读取；带 `video.history.buffer.*` 配置与 `maxmemory-policy` 启动自检。
* **红线边界**：
  - **不改** `HistoryService` / `HistoryController` / 任何既有测试 —— 本任务结束时 `/report` 行为**逐字不变**；
  - 键字符串进 `CacheKeys`（唯一源），`history:report:{id}` / `history:dirty` **一经写入即冻结**；
  - 所有 Redis 访问经 `RedisOps.guarded`（继承 `*RedisOps`），**不得裸用** `StringRedisTemplate`；
  - `maxmemory-policy` 自检**不得阻断启动**（命令被托管 Redis 禁用是合法情形）。
* **强制探索**：`ZADD` score 与 hash 内 ts 的**时钟同源**（都取 App 侧传入的 `serverReceiveTs`），
  写一个用例证明"用两个时钟会判错空闲"。
* **验收**：`HistoryReportBufferTests`（Redis-only，无 DB 断言）——原子写 / 幂等重写 / 条件删只删未变的 /
  HGET / 脏集合按 score 取 / TTL 续期 / 僵尸成员清理 / 自检不阻断启动。
  ⚠️ **判别力边界（如实登记）**：能拦"漏掉 `ZADD`"（实测 4 红），**拦不住**"改成两次往返且两条都成功"
  —— 末态相同，只有第二条命令失败才暴露，需给 `RedisConnectionFactory` 注入故障（T51-2 再评估）。
* **回写**：交付 `CacheKeys.{HISTORY_DIRTY,historyReport}` / `HistoryBufferProperties`（构造期不变式
  `ttl > idle + poll + retention`）/ `HistoryBufferRedisOps`（3 段 Lua + `maxmemoryPolicy`）/ `HistoryReportBuffer`
  （Clock 可注入 + 启动自检）。**未碰任何 Service / Controller / 既有测试**，`/report` 行为逐字未变。
  验证：`HistoryReportBufferTests` 13 + `HistoryBufferPropertiesTests` 3 + `ArchitectureTests` 5 全绿；
  反向验证两条（删 `ZADD` ⇒ 4 红；恢复"空快照早退" + 不变量漏 `pollInterval` ⇒ 各红 1）。
  ★ 审查修正三处：**僵尸脏成员必须清**（空快照也走确认，否则 hash 过期后成员永占每轮名额 ⇒ 兜底失效）、
  **亚秒 TTL 不能变 `EXPIRE 0`**（Redis 会立刻删键）、**不变量补 `pollInterval`**。

### T51-2 写路径接管（来源 R-19 / 设计 上报缓冲 §3–§5）【已完成】

> ⚠️ **先质疑（G14）**：
> - **任务**：`/report` 从"同步 ODKU"改成"写缓冲"是**契约变更**（接口仍是 200，但落库时机变了）。
>   `HistoryWriteTests` 现有 10 例里"上报后查 DB"的断言会红 —— 这是**契约跟随**（G8 例外条），
>   不是迎合；改测试必须在用例注释里写明依据 `R-19`。
> - **设计**：`/flush` 是否可复用 `/report`？——不可：`/report` 带 `contentId`+`progress`，`/flush` 只要 userId，
>   语义与权重都不同（§三）。

* **入口线索**：`history/service/HistoryService.java`（`report` / `play`）、`history/controller/HistoryController.java`、
  `history/dao/{WatchHistoryDao,WatchHistoryMapper.xml}`（批量 ODKU 要**新写**一条，语义与 `upsertProgress` 逐字一致，
  只是显式 `SET update_time = ?`）、`common/web/RateLimitFilter.java` + `common/config`（权重表）、
  `support/AbstractHttpIntegrationTest`（HTTP 侧用例）、`Resilience4j` 熔断（`RedisCircuitBreaker#tryAcquire`）。
* **目标**：`/report` 校验（`loadContentVOs` + clamp）后写缓冲；`/flush` 把该用户全部脏数据**同步**落库；
  轮询线程按 `ZRANGEBYSCORE -inf now-60s` 兜底；Redis 不可用 ⇒ `/report` 503 + `Retry-After`，
  **且一次 DAO 都不调用**。
* **红线边界**：
  - 熔断判定**先于** `loadContentVOs`（否则 503 前先付一次降级查库，正好违反"不碰 DB"）；
  - 落库 SQL **只改** `progress_seconds` + `update_time`，**绝不碰** `play_counted`；
  - 落库失败**不删缓冲**；批量失败后**逐条重试**（禁止用 `INSERT IGNORE` 吞错误，§十 #12）；
  - `/flush` 落库失败仍 **200**；`/play` **不进缓冲**（一行不改）；
  - 锚点同 commit：`RateLimitWeightTableTests.EXPECTED_NON_GET_AT_LEAST` 38→39、`SecurityContractTests` 补 `/flush`；
  - ★ **解析不了的值也必须进 `acknowledge`**（写不进库就直接丢弃 + ERROR，但**必须**让条件删把它 HDEL）：
    只写能解析的、把坏 field 留在 hash ⇒ `HLEN > 0` ⇒ 脏成员永远摘不掉，与"僵尸成员"同族
    （T51-1 已在 hash 侧清零补了这个洞，这里是 field 侧）。
* **强制探索**：轮询必须**可被单测直接驱动**（包级方法），不许靠真实计时；`ZRANGEBYSCORE` 的 W 与
  buffer TTL 的关系（TTL 必须 > W + 最大滞留）。
* **验收**：`HistoryFlushTests` + 改后的 `HistoryWriteTests` —— 上报不进库 / flush 进库 / 轮询兜底 /
  503 不碰 DB（spy `never()`）/ 落库失败留缓冲 / 毒批逐条降级 / 并发上报不丢（新值优先）。
* **回写**：自拆两个 commit —— **2a** 落库编排（`HistoryProgressRow` / `HistoryProgressWriter` 独立事务载体 /
  `HistoryFlushService` / `HistoryFlushScheduler` / `upsertProgressBatch` / 成员级剪枝）；**2b** 契约切换
  （`/report` 改走缓冲、`/flush` 端点、`ErrorCode.SERVICE_UNAVAILABLE` + `ServiceBusyException` +
  `Retry-After` 响应头、权重 38→39、`SecurityContractTests` 补路由、`HistoryWriteTests` 按 R-19 改断言）。
  ★ 实现期修掉两处设计稿没写的洞：`readRaw` 曾跳过非数字 field（⇒ 该 field 永远删不掉）、
  空快照必须走确认（否则 hash 过期后脏成员永占每轮名额）。
  验证：`HistoryReportBusyTests` 1（熔断 ⇒ 503 + `Retry-After` + `verify(never()).loadContentVOs`）、
  `HistoryFlushTests` 9、`HistoryWriteTests` 10 改后全绿；**全量 797→800 例全绿**。
  反向验证三条：去掉剪枝 ⇒ 1 红；`isPoison` 恒 false ⇒ 1 红；熔断与校验换序 ⇒ 忙碌用例红。

### T51-3 读路径 + 客户端契约（来源 R-19 / 设计 上报缓冲 §6、客户端契约）【已完成】

> ⚠️ **先质疑（G14）**：
> - **任务**：`/position` 读缓冲优先会让它**依赖 Redis**（Redis 挂 ⇒ 降级 DB 旧值）。这是可接受的：
>   现状 Redis 挂时详情/列表也全部降级，且"续播拿旧值"劣化有限。
> - **设计**：`/list` 不合并缓冲是否会让用户"刚看的没出现"？——是（≤60s）。合并的代价是分页语义崩
>   （`total`/排序键），不划算（上报缓冲 §6）。

* **入口线索**：`history/service/HistoryService.java#position`、`HistoryPositionTests`（既有 6 例）、
  `HistoryListTests`（`/list` 口径）。
* **目标**：`/position` 先 `HGET` 缓冲、命中即返回、miss 查 DB；把 `/list` 不合并缓冲写成**可判别的断言**；
  客户端契约**独立成篇**（退避 / 恢复 / 退出顺序 / `fetch keepalive` / 本地 pending 标记）。
* **红线边界**：不改 `/list` 的排序、`total`、占位口径；`/position` 仍**不校验内容**、无记录仍返回 `0`。
* **强制探索**：证明"缓冲有 900、DB 有 500 ⇒ 返回 900"与"Redis 不可用 ⇒ 降级返回 DB 的 500"两条都要在。
* **验收**：`HistoryPositionTests` 新增 2 例（缓冲优先 / 降级）；`HistoryListTests` 新增"缓冲不影响列表"1 例。
* **回写**：`/position` 改为**先 `HGET` 缓冲、未命中才点查 DB**（`CacheUnavailableException` ⇒ 降级查库，
  返回落库旧值，不 500）；`/list` **不合并缓冲**的口径写成可判别用例（未 flush ⇒ `total=0` 且列表无该条，
  flush 后才出现）。客户端契约已独立成篇（`history-客户端契约.md`，本期只登记、不实现）。
  验证：`HistoryPositionTests` 6→8、`HistoryListTests` 15→16；**全量 800/800 绿**；
  反向验证一条（去掉读缓冲优先 ⇒ `缓冲优先于已落库的值` 红，降级用例仍绿 ⇒ 两条判别力互不覆盖）。

### T52 `/position` 改回直查库（来源 R-20 / 设计 上报缓冲 §六）【已完成】

> ⚠️ **先质疑（G14）**：
> - **任务**：推翻 `R-19` 契约④——《R-19》交付才一天就改，是否翻烧饼？不是：T51-3 的
>   "读缓冲优先"建立在"缓冲是 DB 上游、崩溃用户能拿到新值"上，但 `ACK_LUA` 的
>   `HLEN == 0` 分支在**每次成功 flush 后都会删 hash** ⇒ 正常路径必然 miss，收益只落在异常路径。
>   这是对**前提**的修正，不是口味变化。
> - **设计**：去掉读缓冲会不会让"崩溃续播"退化？会——如果客户端仍只用 `/position` 定位播放头。
>   故本任务**必须同时重写客户端契约 §6.2**：初始化取 `max(服务端值, 本地 pending)`。
>   ⚠️ 这条不是可选配套：补报**改不了已经定位的播放头**，只改库不改定位 = 崩溃重开从头播。

* **入口线索**：`history/service/HistoryService.java#position`、`HistoryPositionTests`、
  `history-客户端契约.md` §6.2 / §七、`history-上报缓冲.md` §六 / §七、`history.md` §7.2。
* **目标**：`/position` 只点查 `uk_user_content`；客户端初始化用本地 pending 补足"缓冲里那份新值"。
* **红线边界**：**不动** `R-19` 的写路径（`/report` 缓冲、`/flush`、轮询、503 + `Retry-After`）；
  不改 `/list` 口径；`/position` 仍**不校验内容、无记录返回 0**。
* **强制探索**：必须有一条断言**能区分两种实现**——库里 500、缓冲 900（未 flush）⇒ 新实现答 500。
  其余用例在两种实现下都绿（如实登记，别声称覆盖）。
* **验收**：`HistoryPositionTests` 7 例（删"缓冲优先"/"降级查库"2 例，加"缓冲不参与续播"1 例）。
* **回写**：`HistoryService#position` 去掉 `reportBuffer.cachedProgress` 与 `CacheUnavailableException`
  降级分支（变为一条 `findProgress`）；`HistoryPositionTests` 删 2 例加 1 例（8→7，判别力集中在新增那条）。
  文档同步：`history.md §7.2` / 需求篇"进度何时可见" / 上报缓冲 §六·§七（#5 改写、新增 #7 多设备倒退与 #8 每心跳校验读）
  / 客户端契约 §6.2（`resume()` 取 `max(服务端, 本地)`）·§七。**未碰任何写路径**。
  验证：`History*Tests` **66/66 绿**、`SecurityContractTests` 30/30 绿。
  **反向验证**：临时改回"读缓冲优先"⇒ 7 例中 **1 红 6 绿**，红的是
  `缓冲里的新进度不参与续播`（`expected 500 but was 900`）——其余 6 条在两种实现下都绿，
  故本口径的判别力**只由这一条承担**（如实登记，不声称整体覆盖）。

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
| **本期收口复核（2026-10-21）** | 下表三行**逐行复核：全部维持原决定**，无一条满足其推翻条件（`history` 专项未触及 TTL 续期 / F-26 / favorite 删除口径） | **维持**（本行不删；下期收尾再复核） |
| T44 设计讨论 | **F-26**：载荷读并入聚合层（单飞跨载荷/计数合一，冷内容 3→2 条 SQL） | **留池**（FURTHER_ISSUES F-26，2026-10-20；推翻条件 = 实测详情载荷 miss 热点；理由 = 复刻 CacheAside 三态的漂移风险 > 常数 SQL） |
| T44 设计讨论 | 聚合路径计数键**不做命中续期**（五键 TTL 跨域，T48 起五个） | **登记为已知代价**（缓存文档 §5.2，2026-10-20；热键每 TTL 周期重回源 1 条点查）；推翻 = 出现 TTL 到期回源的实测压力 ⇒ 届时补一趟跨域 EXPIRE pipeline 或统一 TTL |
| T44-1 执行发现 | favorite 键在内容删除时**不失效**（favorite 域不订阅 REMOVE，明细行不删、列不重算，键残留 TTL 自愈） | **如实钉进测试**（`ContentDetailCountAggregateTests#删除后404不回填计数键`，2026-10-20）——既存口径非本期引入；推翻 = 出现"已删内容的收藏数被外显"的读路径 ⇒ 届时给 favorite 域补 REMOVE 订阅 |
