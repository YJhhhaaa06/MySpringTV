# 当期问题（Current Issues）

> **定位**：执行过程中**当场发现、且本期或下期要处理**的问题 —— 阻塞、偏差、待裁决、踩到的坑。
> **与 `FURTHER_ISSUES.md` 的分工**：**本期/下期解决** → 本文件；**暂时不会排期解决**（结转 / 留池）→ `FURTHER_ISSUES.md`。
> **边界**：需求与痛点 → `CURRENT_NEEDS.md`；任务执行态 → `CURRENT_TASKS.md`。
>
> ★ **膨胀控制（2026-10-11 起执行）**：处理完即标状态；**期收尾时把正文清出**（归宿 = git commit），本表只留一行摘要。
> 清出前**必须先把编号收进** ★ **活速查**（`architecture/domain/favorite.md` **附录「编号速查」**，`I-##` 表）
> 与 `archive/favorite-2026-10-15/收藏功能-编号索引.md` **A.3**（完整论证）。
> **编号不回收**：跨期未决的结转时**保留原编号**并在 `FURTHER_ISSUES.md` 中标注来源。

---

## 登记格式

问题 + 现象 + 证据（可复核）+ 影响 + 处置 + 去向。编号 `I-##` 顺延（**不回收**）；从别处结转进来的保留原编号。

**分类取值**：阻塞 / 偏差（与计划不符）/ 缺陷 / 环境 / 决策 / 债务。

---

## 问题登记（I-##）

> **状态取值**：待处理 / 已纳入NEEDS / 已解决 / 待裁决（须停下问用户）/ 已转 `F-##`。

**⏳ 本张清单（第 5 张 = 限流专项）已开窗（2026-10-16）**（执行中按下面格式登记；编号从 **I-15** 起）。

### I-15 `@ConfigurationProperties` record **能**绑嵌套段 —— 本仓两处注释的说法经实测不成立【**分类：债务** · 状态：**待处理**】

- **问题**：`application.yaml` 的 `feed.delivery` 段注释（该措辞的**唯一出处**）写着
  "record 只认本前缀下的扁平键，**嵌套会被静默忽略并绑成 0**"，并以此作为"嵌套段必须另起 record"的理由；
  `FeedDeliveryProperties` / `FeedCompensateProperties` 的类注释有同族表述（"record 只能绑自身前缀下的扁平键"）。
  ⚠️ **不含** `FeedProperties` javadoc —— 它讲的是"**刻意分家**"（关注点分离），那是个**结论**、不依赖这个理由。
- **现象**：实测**不成立** —— 同一前缀下把三段声明为**嵌套 record 字段**（`server` / `user{authed,anon}` / `cost`），
  三段都正确绑定（层层非 `null`、值正确）。
- **证据（可复核）**：落地侧 = `RateLimitPropertiesBindingTests#三段嵌套都绑上`（T23，**真上下文**、入库）。
  旁证 = 三个一次性探针 `temp-script/RateLimitBindingProbe.java`（YAML → `YamlPropertySourceLoader` → `Binder`）、
  `RateLimitContextProbe.java` / `RateLimitRealProbe.java`（真实 `ApplicationContextRunner` +
  `@ConfigurationProperties`，含"非法值 ⇒ 上下文起不来"对照）—— ⚠️ 它们在 `temp-script/`（**本地排除、不入库**）
  ⇒ **跨机不可复核**，故以入库的集成测试为准。
- **影响**：**不是运行期缺陷**，是**文档误导** —— 后人会据一个不成立的约束去设计（T23 的选型就被它挡过一次）。
- **处置**：注释纠偏（改 `application.yaml` 的 `feed.delivery` 段注释与两个 Properties 类注释的"嵌套会被静默忽略 / 只能绑扁平键"表述）。
  ⚠️ 那是 **feed 域**的注释，**不在 T23 范围内**（不顺手改别人）⇒ 留待处理。
- **去向**：**待处理**（限流专项收口前若仍无人认领，结转 `FURTHER_ISSUES.md`）。速查行见 `architecture/tech/限流.md` §二。

### I-16 设计稿对 `miss-multiplier` 的**首版值**自相矛盾（§9.1 写 3.0 / §九 写 1.0 = 关闭）【**分类：决策** · 状态：**已解决**】

- **问题**：`限流-设计稿.md` §九 的参数结构写"`miss-multiplier` **默认 1.0 = 关闭**（显式给 >1 才生效）"，
  而 §9.1 首版数值表把该键的**首版值**写成 **3.0**；`CURRENT_TASKS.md` 的 `T26` 目标也只说"默认 1.0 = 关闭"。
- **现象**：两处对"首版该配多少"给出相反答案 ⇒ T23 落 `application.yaml` 时必须二选一。
- **证据（可复核）**：`grep -n "miss-multiplier\|missMultiplier" .docs/task/限流-设计稿.md .docs/task/CURRENT_TASKS.md`。
- **影响**：只影响**一个配置数值**，不影响结构；但**信号②的回传机制（`T26`）尚未落地**，
  此刻给 3.0 也是**无效力的空档**（没人统计 miss）。
- **处置**：T23 **取"1.0 = 关闭"**（`RateLimitProperties` 校验 `>= 1.0`，测试断言 `== 1.0`），
  并在 `application.yaml` 该键注释里写明这是取了哪一处口径；**是否按 §9.1 启用（>1）留给 `T26` 拍板**。
- **去向**：**已解决**（2026-10-16，`T26` 落地信号②时裁决启用——见下方核销）。速查行见 `architecture/tech/限流.md` 附录。
- **核销（T26，2026-10-16）**：已裁决**启用（3.0）**——`application.yaml` 该键改为 3.0（注释同步为
  落地形态说明）、`RateLimitFilterContractTests` **pin `=1.0`** 隔离信号②并加守门断言（pin 失效 ⇒ 该
  断言与冷态差额断言双向暴露）；1.0 = 关闭仍是合法配置（由 `RateLimitMissPenaltyDisabledTests` 钉住）。
  另同步 `RateLimitPropertiesBindingTests` 的原 `==1.0` 断言改为 `==3.0`（保留"防键名写错静默绑
  默认值"的判别力——全量回归首跑据此抓到一处漏改，见 T26 回写）。

### I-17 `doc_refs.py` 的通过语 over-claim"四条不变式成立"（②的"`task/` 禁入代码"面未实现）【**分类：债务** · 状态：**待处理**】

- **问题**：`tools/doc_refs.py` 通过时打印"✅ …（INDEX §五.5 **四条不变式成立**）"，
  但它只实现了①（`.md` 引用可解析）与③（别名黑名单）＋②的"裸文件名只解析到 archive 副本"一种形态；
  **②的"`task/CURRENT_*` 登记台禁入代码"根本没有检查** ⇒ 绿色结论**可被证伪**。
- **现象**：T23 自己就在代码里新引了两处（`RateLimitProperties` 的 `CURRENT_ISSUES.md`、
  `application.yaml` 的 `CURRENT_TASKS.md`），门禁照样报"四条不变式成立"（**已在本任务修正**）。
  存量另有一处：`favorite/dao/FavoriteItemDao.java` 引 `CURRENT_NEEDS.md`（属收藏域，未处理）。
  ⚠️ `db/migration/**` 里那处（`V2__favorite_tables.sql`）**逐字节冻结**，按纪律不属于本问题的可修面。
- **现象②（T25 补登记）**：③的**别名黑名单不完整**——`ALIASES = 需求篇|设计篇|分期篇|三期设计|骨架 §`
  **不含"设计稿"**：T25 在代码/测试注释里新写了 8 处"设计稿 §X"（后被审查抓住、当场改指
  `architecture/tech/限流.md`），`doc_refs.py` 全程绿灯——同一类盲区的第二个实例。
- **证据（可复核）**：`grep -rnE "CURRENT_(TASKS|ISSUES|NEEDS)" src` 与 `tools/doc_refs.py` 的 `scan()`（只产三类结果）；
  别名盲区见 `tools/doc_refs.py` 的 `ALIASES` 常量。
- **影响**：门禁的**可信度**——它把"没查"包装成"已成立"，而本仓的立足点正是"把纪律变成会失败的命令"。
- **处置**：二选一 —— ①给 `doc_refs.py` 补一条"引用路径必须落在 `architecture/**` / `archive/**`（显式路径）"的规则
  （会同时让存量那 1 处变红，需一并改指）＋ 把 `设计稿` 补进 `ALIASES`；②把 banner 收窄，明确"②的路径前缀面与③的黑名单面未覆盖"。
  ⚠️ 改门禁须按 `.docs` 约定做反向验证，不属 T25 范围。
- **去向**：**待处理**（属 `tools/` 与收藏域，不在 T23 范围）。速查行见 `architecture/tech/限流.md` 附录。

### I-18 设计稿称 TTL 淘汰"完全一致、无损"—— 对欠账态（`tokens < 0`）不成立，实际会**豁免剩余欠账**【**分类：决策** · 状态：**待处理**】

- **问题**：`限流-设计稿.md` §五「自证性质」称"能被 TTL 淘汰的桶……'淘汰后下次从满桶起算'与
  '公式算出来的值'**完全一致**，**无损**"。该论证**隐含前提 `tokens ≥ 0`**。
- **现象**：T24 复核得精确边界：**淘汰无损 ⟺ `t ≥ cap − TTL×rate`**（`g = TTL×rate − cap ∈ [0, rate)`
  是 `ceil` 的向上取整余量）；**首版三组参数（20/2、60/6、200/50）恰整除 ⇒ g = 0**
  ⇒ **一切负 tokens（欠账态）被 TTL 淘汰时豁免 |t|**（淘汰重建 = 满桶，惰性公式值 = 满桶 − |t|）。
- **证据（可复核）**：`RateLimitRedisOps.BucketSpec#ttlSeconds`（上限取整）与上述推导；
  T24 的 `RateLimitRedisOpsTests`（惰性补充 / 封顶 / 负桶用例）+ `RateLimitRedisOps` 类注释
  「自证边界的精确表述」。
- **影响**：**不是运行缺陷**——活跃刷子的访问持续刷新 TTL（欠账永不脱落），豁免只发生在
  "停止活跃满桶时间之后"（语义 = **沉默即重新做人**）。属**表述过强 + 语义需钉死**：
  不给精确边界，后人会误以为"欠账一直累积直到还清"（据此做设计会踩空）。
- **处置**：T24 已按"**接受该语义**"落地——精确边界写进 `RateLimitRedisOps` 类注释与
  `architecture/tech/限流.md` §一；**设计稿自身的表述修正归 `T29` 收口**（沉淀 / 归档时以精确版替换）。
- **去向**：**待处理**（`T29` 收口时核销）。速查行见 `architecture/tech/限流.md` 附录。

### I-19 设计稿 §七 的跳过清单漏了 `/upload/**`（静态媒体服务）—— T25 按"ResourceHandler 静态服务"同一口径补上【**分类：决策** · 状态：**待处理**】

- **问题**：`限流-设计稿.md` §七 的跳过清单只列了 `/`、`/index.html`、`/favicon.ico`、`/static/**`、
  `/actuator/**`，但本仓的静态媒体服务 `WebMvcConfig#addResourceHandlers` 挂在 **`/upload/**`**
  （视频 / 封面播放 URL）——清单漏列。
- **证据（可复核）**：`WebMvcConfig.addResourceHandlers`（`/upload/**` → 媒体磁盘根）；
  `AccessLogTests` 的静态探针即走 `/upload/**`；业务上传端点是 `POST /api/upload/*`
  （`UploadController`），与 `/upload/` 前缀**零交集**，不受影响。
- **影响**：若按清单原样实现，视频 / 图片播放请求会白白过闸扣费，甚至被 429 拦掉
  （"429 掉 CSS"的同款事故，只是对象是视频文件）。
- **处置**：T25 执行期按 G13 自行决断——`/upload/` 并入跳过前缀（设计稿的跳过**理由**
  "静态资源不是业务成本"原文适用）；已写进 `RateLimitFilter` 类注释与
  `architecture/tech/限流.md` §三。**设计稿 §七 的清单补列归 `T29` 收口**。
- **去向**：**待处理**（`T29` 收口时核销）。速查行见 `architecture/tech/限流.md` 附录。

### I-20 设计稿 §二 把信号②写进**门时**的 cost —— 但 "miss" 是**本请求运行期事实**，门时取不到【**分类：偏差** · 状态：**已解决**】

- **问题**：`限流-设计稿.md` §二 的账本时序把 `cost = cost.base(路由) × cost.miss-multiplier`（信号① × ②）
  直接写进门时那一次 Lua 的入参；而 §四 把信号②定性为"**本请求运行期事实**"。两者不可同时为真：
  缓存读发生在请求处理中（门**之后**），门时"这次请求 miss 没 miss"尚未发生。
- **现象**：按字面实现不可能；且一次请求可在多个域多次回源——详情页（`/search/IdSearch`）冷态最多
  ≈4 处（content 完整信息 + 赞 / 藏 / 关注三态），评论页（`/comment/show`）冷态 ≈4~5 处
  （内容门禁 + 评论两键组 + 评论点赞态）。
- **证据（可复核）**：`RateLimitFilter` 链序（`architecture/tech/限流.md` §三，门在 `DispatcherServlet`
  之前）；`ContentService#getContentDetailVO`（= `ContentCache#getContent` 的 CacheAside miss 回源
  ＋ `InteractionService#getStatus` 三趟缓存读）；`CommentService#getCommentsForContent`
  （= `CommentCache#getRootPage` 两键组 ＋ `LikeService#batchIsCommentLiked`）。
- **影响**：不是运行缺陷，是**设计稿与可实现形态的偏差**——按字面读会把"信号②落不了地"误当实现问题；
  正确形态必须**拆两阶段**。
- **处置**：`T26` 按 G13 决断 —— **门时按 `base` 扣（现行为）＋ 请求处理完后按差额
  `base × (multiplier − 1)` 补扣**（仅对**放行过的**请求；用户桶 / 服务器桶同扣；fail-open 同门）。
  "回传形态"细则（二值标记 / 线程封闭 / 清理点）随 `T26` 回写；设计稿 §二 公式的语义修正
  （= "请求**总成本**，分两阶段兑现"）随 `T26` 修订本稿。
- **去向**：**已解决**（2026-10-16，`T26` 落地——见下核销）。速查行见 `architecture/tech/限流.md` 附录。
- **核销（T26，2026-10-16）**：已按**两阶段**落地——`RateLimitFilter#settleMissPenalty`（门后差额
  `base × (m−1)`，仅"门放行过"的请求、两桶同扣、失败吞掉）＋ `RateLimitRedisOps#deductDelta`（先补后扣）；
  设计稿 §二 已加修订注（公式 = "请求总成本"的简写）。速查行已更新。

> 📌 **征集专项收口的结转**：`I-14`（`FollowCacheTests` 全量偶发红 —— 别域消费者进验证窗口）
> **已结转 `FURTHER_ISSUES.md` **F-24****（它**不属收藏域**，是 feed 域测试隔离债）⇒ 移出本表。

---

## 待裁决（G3：须停下问用户）

> 按 `CURRENT_TASKS.md` 的运行约定 G3：**需要用户拍板的，登记在此并停下问**，
> 不擅自决定、不硬扛、不超范围。

| 编号 | 事项 | 为什么必须问 | 问于（日期） | 结论 |
|------|------|-------------|-------------|------|
| **R-15** | ★ **第 5 张清单方向**（性能第二轮续 / 上线准备度专项 / 存量数据债） | 三条路的代价与前提不同，且**拍板前不许拆任务**（C-1） | 2026-10-16 | ✅ **已拍板 = 限流专项**（候选乙的限流部分）⇒ 设计见 `限流-设计稿.md`，任务见 `CURRENT_TASKS.md` `T23`–`T29` |
| **R-16** | **限流的熔断 / 降级细化**（熔断参数 / 多实例口径 / 是否 fail-closed） | 用户 2026-10-16 明确"**实现完再决定**" ⇒ 须在专项收口前拍板 | 待问 | ⏳ **待拍板**（见 `CURRENT_NEEDS.md` §三） |

> 📌 **R-12 / R-14 已清出**（第 3 张清单 / 收藏专项收口）—— 结论与理由见
> **favorite.md 附录「编号速查」**（`R-##` 表）与 `archive/favorite-2026-10-15/收藏功能-编号索引.md` **A.2**。

---

## 已解决（收藏专项，2026-10-08 → 2026-10-15）

> 正文已随各期收尾清出（历史在 git）；**结论与理由收编进**
> **favorite.md 附录「编号速查」**（`I-##` 表）与 `archive/favorite-2026-10-15/收藏功能-编号索引.md` **A.3**
> （那是这些编号的唯一**活**查证入口 / 历史入口）。

| 编号 | 一句话结论 | 解决于（任务 / 日期） |
|------|-----------|---------------------|
| I-01 ~ I-08 | 一期 8 条（默认夹唯一键 / 结构锚点 / `Envelope` / 事务判别力 / 注入纪律 / `EXPLAIN` 实测 / 装载契约根治 / 弹窗互锁） | 一期 / 2026-10-08 → 10-11 |
| I-09 ~ I-12 | 二期 4 条（`ProfileService` 内联点赞 ⇒ **F-16** / Bean 环 **@Lazy** 断 / 缓存写点口径 / T12 少列 `move`） | 二期 / 2026-10-11 → 10-12 |
| I-13 | T13 任务书口径**四处自相矛盾** ⇒ 按 B站 实际展示面拍板（`/start`=`{关注}`、`/search`=**0 维**） | T13 / 2026-10-13 |
