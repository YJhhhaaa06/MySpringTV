# 架构文档（索引）

> 本目录服务**独立开发**（迁移已收口）。**不依赖**迁移期文档
> （`.docs/` 根下的决策留痕表 / 遗留台账 / 事务边界决策表，以及 `archive/`）——
> 那些是迁移过程物，后续由新的留痕策略接管。
>
> 读法：先看 §二 路由表找到该读哪篇，再进对应文档；不要通读全部。

---

## 一、这个系统是什么（30 秒）

类 bilibili 的视频站后端。技术栈：Spring Boot 4.1 / Java 25 + MyBatis + Jedis + Spring AMQP +
Flyway + Testcontainers。

### 1.1 模块地图

```
io.github.yjhhhaaa06.videoweb
├── common/                横切基建（不得依赖任何业务模块）
│   ├── cache/             缓存引擎：CacheAside / CacheKeys / RedisOps / SingleFlight /
│   │                      RedisCircuitBreaker / CacheMetrics
│   ├── config/            各类 *Properties（配置绑定期校验）
│   ├── log/               RequestIdFilter / AccessLogFilter / LogMasker / AuditLog
│   ├── security/          JwtAuthFilter / JwtService / RequestMappingLookup / AdminChecker /
│   │                      @Public / @RequiresLogin / @RequiresAdmin / @CurrentUserId（**默认拒绝**）
│   ├── web/               ApiResponse / GlobalExceptionHandler / PageParams / BusinessCodeAdvice
│   ├── exception/         业务异常族 + ErrorCode
│   └── model/             PageResult 等通用模型
└── 业务域
    ├── user/      账号、登录、自动大V状态
    ├── content/   内容（视频/帖子）、详情、搜索、个人页
    ├── comment/   评论（写路径）
    ├── like/      点赞
    ├── follow/    关注
    ├── feed/      关注动态流（唯一异步编排型域）
    ├── coupon/    优惠券
    ├── upload/    文件上传
    ├── admin/     内容管理端
    ├── favorite/  收藏（收藏夹 / 收藏记录 / 稍后再看；2026-10-07 收藏一期起，2026-10-15 一轮收口）
    ├── coin/      投币（硬币：签到得币 · 视频投币 · UP主 1/10 · 不可取消；2026-10-19 设计落地）
    ├── history/   播放记录（进度 / 播放量 / mp4 时长 / 历史列表 / 续播；2026-10-21 设计并落地，T45–T49）
    └── interaction/ 互动域（聚合"我 赞/藏/关注了吗"三态；**无 controller/dao/cache/event**，R-10 §3.6，2026-10-11 收藏二期 T9 起）
```

> 业务域的内部结构**不在本目录统一描述**——只有"有独有架构、讲不清会出事"的域才单独成篇
> （当前 = [feed](domain/feed/feed.md) · 收藏域 [favorite](domain/favorite/favorite.md)）。
> 薄域（coupon / upload / admin 等）不建文档。

### 1.2 横切机制的一句话版

| 机制 | 一句话 | 详见 |
|---|---|---|
| 模块边界 | `common` 不依赖业务域；跨域只走对方的 Service 契约；边界由 ArchUnit 变成会失败的测试 | [模块边界与依赖](tech/module-boundary/模块边界与依赖.md) |
| 领域事件 | 每个域只声明"自己发生了什么"，**需要响应的人自己订阅**；禁止替别的域发事件 | [领域事件与解耦](tech/events/领域事件与解耦.md) |
| 缓存 | **显式封装层**（不用 `@Cacheable`）：单写 DB 真相 + 写后失效 + 读 miss 回源 | [缓存与数据一致性](tech/cache/缓存与数据一致性.md) |
| 韧性 | 熔断在 `RedisOps.guarded` 收口；单飞只接 miss 回填；降级路径各域既有 | [韧性](tech/resilience/韧性.md) |
| 限流 | **令牌桶**：用户桶（per-key，无条件扣）+ 服务器桶（全局，条件扣），两桶不要求同时扣；429 + `Retry-After` | [限流](tech/rate-limit/限流.md) |
| MQ | 拓扑/重连/重试+DLQ/预取交框架原生；发布不等 confirm；投递三态 + 补偿缓冲 | [MQ与投递可靠性](tech/mq/MQ与投递可靠性.md) |
| 可观测 | 日志四输出端 + MDC 请求标识 + 出口一处脱敏 + Micrometer 打点 + 审计 | [可观测](tech/observability/可观测.md) |
| 安全 | 自研 `JwtAuthFilter` + `@Public` / `@RequiresLogin` / `@RequiresAdmin`（**默认拒绝**；Spring Security **已拍板不迁**，F-01/F-02） | [安全与鉴权](tech/security/安全与鉴权.md)（代价见 [已知代价](tech/security/安全与鉴权-已知代价.md)） |
| 数据 | Flyway SQL-first；**V1 baseline 冻结**；增量演进已到 **V7**（`favorite_*`（V2/V3）+ `watch_later`（V4）+ `content_coin`/`coin_count`（V5）+ `watch_history`/`duration_seconds`/`play_count`（V6）+ 删遗留 `video`/`videoinfo`（V7））；下一次 **V8** | [数据与Schema演进](tech/data-evolution/数据与Schema演进.md) |
| 事务 | 事务边界载体独立成 bean（避免自调用）；纯读去事务 | [事务边界](tech/transaction/事务边界.md) |

---

## 二、按"我要做什么"选文档

| 我要… | 读这篇 | 何时更新 |
|---|---|---|
| **写新业务模块 / 加一条跨域依赖** | [模块边界与依赖](tech/module-boundary/模块边界与依赖.md) | 包结构或 ArchUnit 规则变更 |
| 让某域**响应别的域发生的事** | [领域事件与解耦](tech/events/领域事件与解耦.md) | 新增事件 / 监听器 |
| **加缓存 / 改缓存口径**（三态、空标记、key 命名、失效） | [缓存与数据一致性](tech/cache/缓存与数据一致性.md) | 缓存口径变更 |
| 处理 **Redis 故障 / 惊群 / 降级** | [韧性](tech/resilience/韧性.md) | 熔断 / 单飞口径变更 |
| **加限流 / 调阈值 / 改 429 契约** | [限流](tech/rate-limit/限流.md) | 限流口径、桶参数、权重表变更 |
| **发消息 / 加消费者** | [MQ与投递可靠性](tech/mq/MQ与投递可靠性.md) | 拓扑 / 投递语义变更 |
| 加**日志 / 指标 / 审计** | [可观测](tech/observability/可观测.md) | 观测形态变更 |
| 动**鉴权**（端点是否需登录、管理员判定） | [安全与鉴权](tech/security/安全与鉴权.md) | 鉴权链变更 |
| **改表 / 加 Flyway 迁移** | [数据与Schema演进](tech/data-evolution/数据与Schema演进.md) | schema 演进 |
| **写 `@Transactional`** | [事务边界](tech/transaction/事务边界.md) | 事务口径变更 |
| 了解**关注动态流**整体数据流 | [domain/feed](domain/feed/feed.md) | feed 编排变更 |
| 了解**收藏 / 收藏夹 / 稍后再看**的数据模型与口径 | [domain/favorite](domain/favorite/favorite.md)（用户可见规则见 [favorite-需求](domain/favorite/favorite-需求.md)；已拍板接受的代价见 [favorite-已知代价](domain/favorite/favorite-已知代价.md)） | 收藏域口径变更 |
| 了解**投币 / 硬币**的数据模型与口径 | [domain/coin](domain/coin/coin.md)（用户可见规则见 [coin-需求](domain/coin/coin-需求.md)） | 投币域口径变更 |
| 了解**播放记录 / 播放量 / 视频时长**的数据模型与口径 | [domain/history](domain/history/history.md)（用户可见规则见 [history-需求](domain/history/history-需求.md)） | 播放记录域口径变更 |

---

## 三、文档清单与归属边界

**归属判据**（双判据，满足其一即进 `tech/<面>/`）：

> ① 代码被多个域 import；**或**
> ② 代码不复用，但**改它会影响多个域的对外行为**，或**是别人写新代码前必须遵守的约定**。
>
> 二者都不满足（只影响单域、且不构成他人的写作规范）→ `domain/`。
>
> ★ **每个横切面一个目录**（2026-10-18 起，与 `domain/<域>/` 同款）：`tech/<面>/<面>.md`
> ＋（可选）`tech/<面>/<面>-已知代价.md`。目录名用英文短名（`security` / `rate-limit` /
> `observability` / `cache` / `resilience` / `mq` / `events` / `transaction` /
> `module-boundary` / `data-evolution`），**文件名保持中文**（代码注释按基名解析，目录无关）。

| 文档 | 讲什么 | **不讲什么**（去向） |
|---|---|---|
| [模块边界与依赖](tech/module-boundary/模块边界与依赖.md) | 包结构与依赖方向、`common` 约束、窄端口、ArchUnit 规则 | 事件订阅的形态 → 领域事件 |
| [领域事件与解耦](tech/events/领域事件与解耦.md) | "谁声明、谁订阅"、禁止代发、事件清单、`AFTER_COMMIT` | MQ 投递 → MQ；具体业务流 → 各域 |
| [缓存与数据一致性](tech/cache/缓存与数据一致性.md) | 三态、空标记、`partial` 窗口、key 命名、写后失效、各域缓存清单 | 熔断/单飞 → 韧性；各域业务 → 各域 |
| [韧性](tech/resilience/韧性.md) | 熔断收口、Resilience4j 选型、单飞边界、冷却退避 | 三态/空标记 → 缓存 |
| [MQ与投递可靠性](tech/mq/MQ与投递可靠性.md) | 拓扑、重试+DLQ、投递三态、补偿缓冲、探针 | feed 的 fanout 业务 → domain/feed |
| [可观测](tech/observability/可观测.md) | 日志四端、MDC、脱敏、指标、审计 | 业务日志内容 → 各域 |
| [安全与鉴权](tech/security/安全与鉴权.md) | 鉴权链形态（默认拒绝）、三个授权注解、401/403 语义、**为何不迁 Spring Security**（方案原貌/否决理由/推翻条件） | 脱敏 → 可观测；已接受的代价 → security 台账 |
| [安全与鉴权-已知代价](tech/security/安全与鉴权-已知代价.md) | 鉴权**已拍板接受**的代价台账（不迁 Security / 暗依赖防火墙 / 单 Token / 注解权威源 / 默认拒绝） | 设计论证 → 安全与鉴权；未处置的问题 → `task/FURTHER_ISSUES.md` |
| [限流](tech/rate-limit/限流.md) | 令牌桶口径、两桶非对称扣减、桶参数与权重表、429 契约、接线位置 | 熔断/单飞 → 韧性；过滤器链序 → 安全与鉴权；脱敏 → 可观测；排期 → `task/` |
| [数据与Schema演进](tech/data-evolution/数据与Schema演进.md) | Flyway 策略、V1 冻结、环境、存量数据债 | 事务 → 事务边界 |
| [事务边界](tech/transaction/事务边界.md) | 切分原则、载体独立 bean、`readOnly`、去事务口径 | 提交后副作用的语义 → 领域事件；逐处明细（已归档） |
| [domain/feed](domain/feed/feed.md) | 动态流数据流、两路读、大V路由、重建、消环 | 缓存/MQ/事务机制 → 上述各篇 |
| [domain/favorite](domain/favorite/favorite.md) | 收藏域数据模型、端点、计数口径、失效口径、互动态形态、缓存边界、容量上限、稍后再看、公开夹 | 横切机制 → 上述各篇；用户可见规则 → favorite-需求 |
| [domain/favorite-需求](domain/favorite/favorite-需求.md) | 收藏的**用户可见行为与规则**（唯一事实源） | 骨架设计 → favorite；横切机制 → 上述各篇 |
| [domain/favorite-已知代价](domain/favorite/favorite-已知代价.md) | 收藏域**已拍板接受**的代价台账（牺牲了什么 / 影响多大 / 何时疼 / 为何接受 + 重开信号） | 设计论证与机制 → favorite；未处置的问题 → `task/FURTHER_ISSUES.md` |
| [domain/coin](domain/coin/coin.md) | 投币域：数据模型、端点、**硬币经济的单位约定**、写路径并发口径、缓存形态（两个 key）、互动态表达、依赖边与 F-18 复核、已知代价 | 横切机制 → 上述各篇；用户可见规则 → coin-需求 |
| [domain/coin-需求](domain/coin/coin-需求.md) | 投币 / 硬币的**用户可见行为与规则**（唯一事实源；含三处**刻意偏离 B站**的说明） | 骨架设计 → coin；横切机制 → 上述各篇 |

---

## 四、写作规范（所有 `tech/` 与 `domain/` 文档通用）

1. **首块定位**：3–5 行引用块 —— 定位（先读这一句）+ "本篇只讲什么、不讲的去向（文末 §边界）"。
2. **结论先行**，章节用中文序号（一、二、三…）。
3. **不展开横切机制**：属兄弟文档的内容**只写一句话边界 + 链接**，不复制。
   （复制必然漂移——这是本仓的硬纪律"同一事实只写一处"。）
4. **链接**：兄弟文档用**相对路径** markdown 链接（同目录写 `./xxx.md`；跨面写 `../<面>/xxx.md`）。
   代码引用用 inline code 的**类名**（如 `FeedWindowWriter`），不链源文件。
5. **不引用迁移期文档**：不链 `.docs/` 根的决策留痕表 / 遗留台账 / 事务边界决策表。
   所有**结论**从**代码 / 配置 / 迁移脚本本身**取证。
   ★ **例外（2026-10-15 放宽）**：允许对 `archive/` 挂一条**"历史出处"脚注**
   （如"逐条论证见 `archive/<域>-<日期>/`"）—— 它**只做追溯、不承担结论**；结论仍必须自证于代码。
6. **表格优先**：事实对照用表；流程 / 结构用围栏代码块画。
7. **体量红线**：单文件 ≤ 40 KB、单章节 ≤ 300 行（`python tools/docs/doc_stats.py` 阈值）。
8. **末尾固定节《边界与去向（机制外链）》**：列"不在本篇的内容 → 去向"。
9. 类名 / 配置键用 inline code；参数给**默认值**。
10. **已知代价台账**：每个成篇的域 / 横切面可配一份代价台账 —— 域 = `domain/<域>/<域>-已知代价.md`，
    **横切面 = `tech/<面>/<面>-已知代价.md`**（先例 = `tech/security/安全与鉴权-已知代价.md`；命名必须带前缀，
    `doc_refs.py` 按基名解析），**通用模板 = [已知代价-模板](已知代价-模板.md)（复制即用）**，填好的范例见
    [favorite 的台账](domain/favorite/favorite-已知代价.md)。条目正文**读者视角**——先说人话、再给机制细节，
    新接手的人**不翻源码**就能读懂 What / Impact；只登记**已拍板接受**的，还在评审的进 `task/FURTHER_ISSUES.md`。

---

## 五、维护

```bash
python tools/docs/doc_stats.py      # 体量（防膨胀）
python tools/docs/doc_links.py      # 交叉引用是否指得到（改链接后必跑）
```

> ⚠️ 新增或移动架构文档后**必跑 `doc_links.py`**——**悬空指针比不引用更坏**，它让人以为查过了。
