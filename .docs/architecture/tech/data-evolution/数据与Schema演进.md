# 数据与Schema演进

> 定位（先读这一句）：本仓结构演进走 **Flyway SQL-first**，历史结构以 **V1 baseline 冻结**形态搬入，
> 此后**只增不改**（新增版本号，回写历史脚本会被 checksum 校验挡下 —— **校验和覆盖整个文件字节，注释也算**，见 §2.4）。
> **本篇只讲 schema 演进策略 / V1 冻结口径 / 环境与表结构总览 / 存量数据债 / 结构校验 / 检查清单**。
> **事务切分（`@Transactional` 载体、`readOnly`、去事务）不在本篇**，去向见文末 [§边界与去向](#边界与去向机制外链)。
> 表"怎么被业务使用"（读路径、写扩散、缓存失效）见各域文档，如 [feed 域架构](../../domain/feed/feed.md)。
>
> ⚠️ **路径重定向（2026-10-18）**：本文件已从 `architecture/tech/数据与Schema演进.md` 移入
> `architecture/tech/data-evolution/`。`src/main/resources/db/migration/V2__favorite_tables.sql` 与
> `V4__watch_later.sql` 的注释仍写**旧路径** —— 那是 **Flyway checksum 冻结**（改一字符就 `Migration checksum mismatch`），
> 按本节重定向读作本文件即可（见 §2.4）。

---

## 一、策略：SQL-first、先搬后改

两条口径，都落在配置与迁移脚本本身：

| 口径 | 含义 | 取证处 |
|---|---|---|
| **SQL-first** | 结构演进的唯一事实源是 `db/migration` 下的 `.sql` 脚本，不用 ORM 的自动 DDL | `spring.flyway.locations: classpath:db/migration` |
| **先搬后改** | V1 = TV 现结构的**忠实快照**，不做任何设计改进；改进一律留给后续版本 | `V1__baseline_tv_schema.sql` 头注释 |

V1 的来源是**从活库导出**（`information_schema` + `SHOW CREATE TABLE`），**不是**任何离线的历史 schema 文本
（V1 头注释明确：离线那份已过期，只有 12 张表，缺 `auto_bigv` / `feed_inbox` / `feed_inbox_sync`）。

Flyway 的定位是**结构演进，不是备份**——业务数据仍靠 dump（`application.yaml` 的 Flyway 段注：

> "Flyway 管结构演进，不是备份；业务数据仍靠 dump。"）

`spring.flyway` 的有效配置：

| 键 | 值 | 作用 |
|---|---|---|
| `enabled` | `true` | 启用 |
| `locations` | `classpath:db/migration` | 脚本目录（`V*.sql` 按版本号顺序应用） |
| `baseline-on-migrate` | `true` | 非空库首次迁移时插入 baseline 标记行 |
| `baseline-version` | `0` | **关键**：baseline 标记落在 0（见 §二） |
| `baseline-description` | `TV baseline (schema as-is)` | 标记行描述 |
| `validate-on-migrate` | `true` | 迁移前校验已应用脚本与磁盘脚本一致（checksum 守卫，见 §二） |
| `clean-disabled` | `true` | `clean` 永不开放，防误删 |

V1 脚本内**全部** `CREATE TABLE IF NOT EXISTS`——这不是风格偏好，而是配置使然（见 §二）。

---

## 二、V1 baseline 冻结

### 2.1 为什么 `baseline-version=0` 是这套脚本成立的前提

`baseline-version=0` 让 baseline 标记落在 **0**，于是 **V1 在两处都会被应用**：

```
已有库（结构已存在） → baseline 0 落标 → 应用 V1 → 结构已在 ⇒ 每个 CREATE TABLE IF NOT EXISTS 成为 no-op
空库 / 测试容器       → baseline 0 落标 → 应用 V1 → 真正建出结构
```

⇒ **一套 V1 脚本同时服务活库与测试库两种环境**，无需为已有库手工 baseline 到 1。

> ⚠️ 反例（注释点明）：若 `baseline-version=1`，则已有库会因 ≥1 而**跳过 V1**、空库仍执行 V1
> ——两边立即分叉。这正是要避免的。

（注：`baseline-on-migrate` 只在**非空 schema** 上生效；测试容器的库是**空**的，Flyway 直接应用 V1。
故测试环境天然走"V1 建结构"这条路，与开发库的"baseline 0 + V1 成为 no-op"路径**语义等价**。）

### 2.2 为什么不能改 V1

V1 一旦在任一库上被应用，就写进了该库的 `flyway_schema_history`（`version=1`、`type=SQL`、`success=1`、含校验和）。
此后**回写 V1 会同时踩两处**：

1. **校验和守卫**：`validate-on-migrate: true` 会在下次迁移前，把磁盘脚本的校验和与 `flyway_schema_history`
   中已记录值比对；**不一致 ⇒ 校验失败 ⇒ 启动被拒**（不是静默通过）。
2. **baseline 行不会重算**：已应用的行不会重跑，V1 的改动**对已有库不生效**（它只是 no-op），
   但**对新空库/新测试容器生效**（那里会真正执行改动后的 V1）
   ⇒ **活库与测试库结构分叉**：测试通过的结构，活库里并不存在（或反之）。

**结论**：V1 是**冻结**的。它是一份"已经发生过的历史"，不是可以被编辑的当前状态。

### 2.3 增量演进：逐版本新增（当前已应用到 V5）

要改结构，**新增一个版本号更高的脚本**，绝不回写 V1：

```
db/migration/
├── V1__baseline_tv_schema.sql        ← 冻结，永不再改
├── V2__favorite_tables.sql           ← 已应用（2026-10-08 收藏一期 T1），同样冻结
├── V3__backfill_favorite_count.sql   ← 已应用（2026-10-12 收藏二期 T12）：**只改数据、不改结构**
├── V4__watch_later.sql               ← 已应用（2026-10-14 收藏三期 T17）：稍后再看建表
├── V5__coin.sql                      ← 已应用（2026-10-19 投币 T36）：`content_coin` + 三列
└── V6__<描述>.sql                    ← 下一次演进写这里（编号递增，绝不回写 V1~V5）
```

- V2 在**所有环境**都会被执行（活库：V1 已应用 ⇒ 只有 V2 是新的，会真正跑；空库：V1→V2 顺序跑完）。
  这正是"活库与测试库不分叉"的关键——**增量脚本在两处都生效**。
- `validate-on-migrate` 会校验 V1 未被动过、且 V2 尚未被应用（或已应用且内容一致）。
- ★ **现状（2026-10-08）**：首个增量脚本 **`V2__favorite_tables.sql`** 已落地（收藏一期 T1，
  见 §四"V2 增量"）。它同时是这条策略的**首个实例**：活库走"baseline 0 + V1 no-op + 只跑 V2"，
  空库/测试容器走"V1 → V2 顺序跑完"。
- ★ **现状更新（2026-10-12）**：收藏二期 T12 又落了 **`V3__backfill_favorite_count.sql`**（数据迁移，见 §四）。
- ★ **现状更新（2026-10-19）**：投币专项 T36 落了 **`V5__coin.sql`**（`content_coin` 建表 + `users` 两列 +
  `content.coin_count`，见 §四）⇒ **下一次演进走 V6**；V2 / V3 / V4 / V5 同 V1 一样**逐字节冻结**。

⚠️ **V5 落地时活库路径的特殊之处**：三条 `ALTER TABLE … ADD COLUMN` 是裸语句（MySQL 8.0 不支持
`ADD COLUMN IF NOT EXISTS`），只由 Flyway 应用一次；落笔前已用 `information_schema` 复核活库
`spring_tv` 无同名列/表（取证见 `V5__coin.sql` 头部注释）。

### 2.4 ★ 已应用的迁移**连注释也不能改**（2026-10-11 实测踩到）

`validate-on-migrate: true` 的校验和覆盖**整个文件字节**，**注释与其中的文字同样计入**。
所以"只把一行 `--` 注释里的文档指向改个名"这种看似完全无害的动作，同样会触发：

```
Migration checksum mismatch for migration version 2
-> Applied to database : -582740905
-> Resolved locally    : -720022917
⇒ flywayInitializer 建 bean 失败 ⇒ 启动中止 ⇒ .\mvnw.cmd spring-boot:run 报 BUILD FAILURE（6.8s 即失败）
```

（真实案例：2026-10-11 收藏一期收尾做"登记台清出 + 引用修复"时，把 `V2__favorite_tables.sql:18`
注释里的 `CURRENT_NEEDS.md` 改成了新文档名，dev 库立刻起不来。已**逐字节还原**修复，未用 `flyway repair`。）

**四条可执行纪律**：

1. **`src/main/resources/db/migration/**` 视同只读** —— 不止 V1，**所有已应用过的 V2+ 同样冻结**。
   要改结构 ⇒ 新增更高版本号（§2.3）；要改注释 ⇒ 只能改**尚未在任何库应用过**的新脚本。
2. ★ **做"文档改名 / 引用修复"时，必须把该目录排除在外** —— 它与 `tools/docs/doc_links.py` 的检测面**无关**
   （检测面只认 5 种形态），门禁**不会**替你拦下这类改动，只能靠这条纪律 + 收尾时手工 `git diff` 复核。
3. **迁移脚本里不要写"会漂移的指向"**：宁可不写文档名，或写**不可变锚点**（如「见《数据与Schema演进》§四」）。
   ⚠️ **已写下的历史指向不要回头去改** —— 让**目标文档**承接重定向
   （例：`V2__favorite_tables.sql:18` 至今仍写 `CURRENT_NEEDS.md R-01~R-08`，而 `CURRENT_NEEDS.md` §三
   已注明这批编号转到了编号索引 —— **一跳可解析**即可，不必触碰冻结文件）。
4. `flyway repair` 只用于"本地脚本确需修正、且**所有环境都能同步**"的场合；本仓口径是**一律不回写**，
   所以首选手段永远是**还原**。

---

## 三、环境

### 3.1 数据库（dev）

| 项 | 值 | 键 |
|---|---|---|
| 库名 | `spring_tv` | `spring.datasource.url` 中的路径段 |
| 主机 / 端口 | `localhost` : `3306` | 同上 |
| 完整 URL | `jdbc:mysql://localhost:3306/spring_tv?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8` | `spring.datasource.url`（可用环境变量 `DB_URL` 覆盖） |
| 账号 / 口令 | `root` / `MySQL`（dev 默认，`DB_USERNAME` / `DB_PASSWORD` 可覆盖） | `spring.datasource.username` / `password` |
| 连接池 | HikariCP（`minimum-idle=5` / `maximum-pool-size=20` / `connection-timeout=5000ms`） | `spring.datasource.hikari.*` |

> ⚠️ 本机 `3306` 是**宿主原生 mysqld 8.0.45**，不是 Docker 的 mysql8.4（后者映射在 3307，当前未启动）。
> dev 库已从老项目独立为 `spring_tv`——此后本项目**只写 `spring_tv`**。

### 3.2 媒体根

| 项 | 值 | 键 |
|---|---|---|
| 媒体**磁盘根**（落盘 + 静态服务同一物理根） | `D:/data/projects/MySpringTV/media`（环境变量 `UPLOAD_ROOT` 可覆盖；**空值拒绝启动**） | `video.upload.root` |
| 对外 **URL 前缀** | 空串（改它会让 `/search/IdSearch` 的 `videoUrl` / `imageUrls` 形状变化） | `video.media.base-url` |

> ★ 两个键**严格分家**：一个是**磁盘根**，一个是**URL 前缀**，语义不同、取值互不影响。

### 3.3 DB 存的是相对 URL

DB 的媒体列（如 `content_media.url`）存的是**应用内相对 URL**（形如 `/upload/video/xxx.mp4`），
不是绝对路径。故**换媒体根 = 拷贝目录 + 改一处配置**：

```
拷贝 <源根>/{video,image,cover}/…  →  改 video.upload.root 指向新根
（目录结构不变时，DB 无需改动一行）
```

⚠️ 顺序必须是**先拷贝后翻配置**——反过来会让存量行在切换窗口里全部呈现为"文件缺失"。

### 3.4 测试如何覆盖数据源与媒体根

`AbstractIntegrationTest` 用 `@DynamicPropertySource` **无条件覆盖**以下属性
（唯一入口，`application.yaml` 里配了什么都会被盖掉——**防误连**）：

| 被覆盖的键 | 覆盖为 | 理由 |
|---|---|---|
| `spring.datasource.url` / `username` / `password` | Testcontainers MySQL 容器 | 不加这层，测试会**静默连到宿主 3306 开发库**（不报错，只"意外通过"或写坏真实数据） |
| `spring.data.redis.host` / `port` | 容器映射端口 | 同上 |
| `spring.rabbitmq.host` / `port` / `username` / `password` | 容器 | 同上 |
| `video.upload.root` | 项目内 `target/test-media` | 不加这层，测试上传/删除会往**真实媒体根**写真实文件 |

```java
// AbstractIntegrationTest
registry.add("spring.datasource.url", Containers.MYSQL::getJdbcUrl);
...
registry.add("video.upload.root", AbstractIntegrationTest::testMediaRoot); // → <repo>/target/test-media
```

> 容器里的库是**空**的 ⇒ 天然走"V1 建结构"这条路径（见 §2.1）；`InfrastructureConnectivityTests`
> 还会显式断言 URL **不含** `:3306/`，证明连的是容器而非宿主开发库。

---

## 四、表结构总览

V1 共定义 **15 张业务表**，V2 再增 **2 张**（收藏一期），V4 再增 **1 张**（收藏三期 T17 稍后再看），
V5 再增 **1 张**（投币 T36 `content_coin`）+ **3 列**（`users.coin_deci` / `users.last_sign_date` / `content.coin_count`）；
**当前**活库/测试库的 `information_schema`
（`table_type='BASE TABLE'`）计数 = **20** = 19 张业务表 + 1 张 Flyway 元表 `flyway_schema_history`
（与 `InfrastructureConnectivityTests` 的断言一致）。按归属域列出（归属判据见 [模块边界与依赖](../module-boundary/模块边界与依赖.md)）：

| # | 表 | 归属域 | 用途（一句） |
|---|---|---|---|
| 1 | `users` | user | 账号、口令散列、粉丝/关注计数、`role`（0 普通 / 1 管理员） |
| 2 | `auto_bigv` | user | 自动大V状态表：**存在即自动大V**（滞回判定产物，仅存 `user_id` + `created_at`） |
| 3 | `follow` | follow | 关注关系（`uk_user_follow` 防重复；被关注者上有粉丝侧索引） |
| 4 | `feed_inbox` | feed | 收件箱窗口：**每用户一个有界窗口**，时间线真相源（`uk_user_content`） |
| 5 | `feed_inbox_sync` | feed | 收件箱窗口同步状态：**存在即已同步**（读侧闸门据此决定是否回退纯拉） |
| 6 | `content` | content | 内容主表（视频/图文统一，含计数、软删、媒体完整性聚合、评论区开关、`ft_search` 全文索引；**`type`：1 视频 / 2 图文**） |
| 7 | `content_media` | content | 内容资源（`type`：1 视频 / 2 图片 / 3 封面） |
| 8 | `comment` | comment | 评论主表（含楼中楼 `@` 引用、回复计数） |
| 9 | `comment_like` | comment | 评论点赞（`uk_user_comment`） |
| 10 | `comment_media` | comment | 评论资源（图片） |
| 11 | `content_like` | like | 内容点赞（`uk_user_content`） |
| 12 | `coupon` | coupon | 优惠券（库存 + 抢购时段，含 `CHECK stock >= 0`） |
| 13 | `coupon_order` | coupon | 抢券订单（`uk_coupon_user` / `uk_coupon_code`；**全库唯一外键** → `coupon.id`，`ON DELETE CASCADE`） |
| 14 | `video` | 遗留（无当前域消费） | TV 遗留视频资源表（`videoID` 非自增） |
| 15 | `videoinfo` | 遗留（无当前域消费） | TV 遗留视频元信息表 |

**V2 增量（`V2__favorite_tables.sql`，2026-10-08 收藏一期 T1）**：

| # | 表 / 列 | 归属域 | 用途（一句） | 一期口径 |
|---|---|---|---|---|
| 16 | `favorite_folder` | favorite | 收藏夹：`is_private` / `description` 建表即带；`uk_user_default (user_id, default_uniq)` 保证**每用户至多一个默认夹** | R-05 一次到位；默认夹懒建（R-01） |
| 17 | `favorite_item` | favorite | 收藏记录：`uk_folder_content` 防重复收藏、`idx_user_content` 服务"按人去重"、`idx_folder_time` 服务夹内倒序分页 | 失效内容**不清理**，返回脱敏占位（R-03） |
| — | `content.favorite_count` | （列在 content 上，业务归 favorite） | 收藏数**冗余列**（按人去重） | ★ **一期只建不读不写**（R-06），二期启用 |

**V3 增量（`V3__backfill_favorite_count.sql`，2026-10-12 收藏二期 T12）——★ 数据迁移，不改结构**：

| 项 | 内容 |
|---|---|
| 做了什么 | 存量回填 `content.favorite_count`：一条聚合 `UPDATE`（按 `favorite_item` 的 `COUNT(DISTINCT user_id)` 回写），**单条 SQL、未分批**（dev 库规模：content 51 行 / favorite_item 4 行；容器空库 ⇒ no-op） |
| 为什么单独一个版本 | 它**不新增表/列**（列是 V2 建的），只把 V2 时"全是 0 或空"的列填成真值 ⇒ 属**数据迁移**；与结构变更分开，回滚与审计都清楚 |
| 落地验证（可复算） | dev 库 `spring-boot:run` 日志 `Successfully applied 1 migration`；全表复算 `SELECT COUNT(*) FROM content c LEFT JOIN (…) t … WHERE c.favorite_count <> COALESCE(t.cnt,0)` = **0 行错位**；`flyway_schema_history` = 0(baseline) → 1 → 2 → 3 全 success |
| 冻结状态 | ✅ **已应用 ⇒ 逐字节冻结**（§2.4：连注释都不能改） |

**V4 增量（`V4__watch_later.sql`，2026-10-14 收藏三期 T17）**：

| # | 表 | 归属域 | 用途（一句） | 口径 |
|---|---|---|---|---|
| 18 | `watch_later` | favorite | 稍后再看：`uk_user_content (user_id, content_id)` 幂等（重复 add 撞它 ⇒ `ON DUPLICATE KEY UPDATE create_time` **移到最前**）+ `idx_user_time (user_id, create_time)` 服务倒序分页 | **独立于收藏夹**（不共表）；**只存视频**（写入侧校验，不进结构）；无外键（同 V1/V2 其余表） |

**V5 增量（`V5__coin.sql`，2026-10-19 投币专项 T36）**：

| # | 表 / 列 | 归属域 | 用途（一句） | 口径 |
|---|---|---|---|---|
| 19 | `content_coin` | coin | 投币记录：`uk_user_content` 保证"一人一内容一行（累计 1~2 枚）"、`idx_content_id` 服务 `SUM(amount)` 复算；**没有**状态 / 软删列（不可取消） | 单行聚合而非逐次流水（封顶走条件 `UPDATE`）；被拒的投币随事务回滚、**不落行** |
| — | `users.coin_deci` | （列在 users 上，业务归 coin） | 硬币余额的**十分位整数**（值 = 币 × 10） | **有符号** `int`（扣款靠 `coin_deci >= ?` 条件守卫，不用 unsigned + CAST） |
| — | `users.last_sign_date` | （同上） | 最近签到日（上海自然日） | `date`、可空（NULL = 从未签到，是幂等判据的一半） |
| — | `content.coin_count` | （列在 content 上，业务归 coin） | 累计被投币数 | `int unsigned` 但**只增不减** ⇒ **刻意没有** like / comment 的防负守卫（口径差异，非漏改） |

> 📌 本表出现的 `R-##` / `T#` 编号：收藏相关见 [favorite.md 附录「编号速查」](../../domain/favorite/favorite.md)，投币相关见 [coin.md 附录「编号速查」](../../domain/coin/coin.md)（编号的**活**查证入口；逐条论证在相应 `archive/` 或 git）。

> 落地形态的两处非显然设计（都是"一次到位 / 别在下游重复推导"的东西）：
> ① `favorite_folder.default_uniq` 是 **VIRTUAL 生成列**（默认夹 `1`、自建夹 `NULL`），
>    唯一键挂它 —— MySQL 唯一索引**不约束 NULL**，于是"同一用户可有多条自建夹、至多一条默认夹"
>    能由**一个**唯一键表达。没有它，T2 的懒建在并发下只能靠"先 SELECT 后 INSERT"，
>    而两个事务都能查到"不存在"。
> ② `favorite_item` **刻意没有** `content_id` 前导索引：一期收藏数走
>    `COUNT(DISTINCT user_id) WHERE content_id = ?`，它的**耗时基线是二期优化的对照**（T5 要求记录）。

**结构要点（取自 V1）：**

- **唯一外键**：`coupon_order.coupon_id → coupon.id`（`ON DELETE CASCADE`）；表序因此把 `coupon` 置于
  `coupon_order` 之前。其余表以应用层维护引用、不建外键。
- **全文索引**：`content.ft_search(title, description)` 用 `ngram` 解析器（MySQL 内置，空库同样可用）；
  `users.ft_idx_username(username)`。
- **时间列注释**：`auto_bigv.created_at` 明示"只作诊断，不参与判定"。

> 归属以"哪个域的 DAO/Mapper 触碰它"为准。`video` / `videoinfo` 在全仓 `src/` 下**无任何 Mapper/DAO 引用**
> （除 V1 脚本自身），故标为**遗留、无当前域消费**。

> ⚠️ **计数列的 `±` 更新写法 ⇒ 看列类型（`T34`）**：`int unsigned` 计数列（`content.like_count` /
> `content.comment_count` / `users.follow_count` / `users.follower_count`）的防负守卫**必须**写成
> `CAST(col AS SIGNED) + delta >= 0` —— 朴素 `col + delta >= 0` 在 unsigned 列上**自身也下溢**（1690，实测）；
> `comment.like_count`（**有符号**）用原生 `col + delta >= 0` 即可。语义 = **block**：守卫拦住 ⇒
> 影响 0 行、不抛错、不回滚 ⇒ 取消赞 / 取关照常成功（计数停在 0）。
> ★ **例外且有意**：`content.coin_count` 因"投币不可取消 ⇒ **只增无减**"而**不需要守卫** ——
> 见 [coin.md](../../domain/coin/coin.md) §二。

---

## 五、存量数据债

存量数据的质量问题（如 `content.file_exists` / `last_verify_time` 表意"媒体完整性尚未全量核对"，
以及 `video` / `videoinfo` 两张**无消费者的 TV 遗留表**）**继承自历史数据、非本仓引入**；
处置口径是**逐条表态、不阻塞**——不因数据瑕疵阻断 schema 演进与上线。

> 本篇只给这一条边界口径，**不逐条展开**；需要时在对应域的文档或工单里单独表态。

---

## 六、校验：结构连续性

**目标**：证明"活库结构 == V1 脚本定义的结构（+ `flyway_schema_history`）"。

| 手段 | 说明 |
|---|---|
| **CI 下限断言** | `InfrastructureConnectivityTests` 断言 `information_schema.tables`（`table_type='BASE TABLE'`）计数 = **20**（19 业务表 + `flyway_schema_history`），且 `flyway_schema_history` 的**全部行**版本序为 `1 → 2 → 3 → 4 → 5`、`type=SQL`、`success=1`。这是在**空库路径**上可复算的结构连续性锚点。⚠️ 断言**全部版本行**而非只看首行：只看首行时"新增的 V2/V3/V4/V5 没被应用"是察觉不到的（首行永远是 V1）。★ **每加一个 `V<n>` 都要同时改两处锚点**：本行提到的计数/版本序，以及 `favorite/WatchLaterSchemaTests#全部迁移版本都被应用`（`endsWith` 末版本）—— 后者 T36 首次漏改、由全量回归抓到。 |
| **列集对拍** | 本仓 `tools/` 下**当前无**专门的结构比对脚本。复算方式：取活库列清单，与 V1 脚本中各 `CREATE TABLE` 的列清单做**差集**——两侧应仅相差 `flyway_schema_history`。 |
| **收藏域结构契约** | `favorite/FavoriteSchemaTests`（T1）把 V2 的**列集 / 唯一键 / 索引**钉成会失败的用例；唯一键那两条是**真插两行**看它拦不拦（不是查 `information_schema`）。 |

列集复算（活库侧）：

```sql
SELECT table_name, ordinal_position, column_name, column_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = DATABASE()
ORDER BY table_name, ordinal_position;
```

将结果与 V1 脚本逐表逐列对拍：**活库的列集 ⊇ V1 的列集**，且多出的表**只有**
`flyway_schema_history` 与 V2 引入的 `favorite_folder` / `favorite_item`
（V2 另给 `content` 加了一列 `favorite_count`）。任一侧出现对方没有的列/表，即为结构分叉信号
（多半是回写了 V1，或活库被手工改过）。

> 复算纪律：凡"结构一致 / 未分叉"的结论，都必须能由上表两条之一当场复算得出；做不到就不要写。

---

## 七、新代码检查清单

**我要加一张表：**

1. 新建 `V2__<描述>.sql`（编号递增，**绝不回写 V1**）。Flyway 默认命名 `V<n>__<desc>.sql`。
2. `CREATE TABLE` 写在该文件里；按 §四 归属判据确定它归**哪个域**，只由该域改。
3. 若在**测试容器**（空库）与本机活库上都要求不报错：沿用 V1 的 `IF NOT EXISTS` 幂等约定（或等价写法）。

**我要改一列 / 加索引：**

1. 同样新建 `V<n>__<描述>.sql`，写 `ALTER TABLE …`。**不要**去改 `V1__baseline_tv_schema.sql`——
   改了会被 `validate-on-migrate` 的校验和守卫拒于启动（§2.2）。
2. 列的类型/可空/默认值变更要**兼容既有数据**（存量行已存在，`ALTER` 需带默认值或先回填）。

**通用红线：**

- **不回写任何历史脚本**（V1 冻结）；所有变更**只增新版本号**。
- **不动 `spring.flyway` 三个关键键**：`baseline-version: 0`、`baseline-on-migrate: true`、`clean-disabled: true`。
- 媒体列**存相对 URL**（§3.3），不要写入绝对路径。
- 事务/`@Transactional` 的取舍不在本篇：见 [事务边界](../transaction/事务边界.md)。

---

## 边界与去向（机制外链）

| 不在本篇的内容 | 去向 |
|---|---|
| 事务切分原则、事务边界载体独立 bean、`readOnly`、去事务口径 | [事务边界](../transaction/事务边界.md) |
| 各域业务语义（表怎么被读/写、缓存失效、写扩散） | [feed 域架构](../../domain/feed/feed.md) 及各域文档 |
| 缓存 key 命名、三态、写后失效 | [缓存与数据一致性](../cache/缓存与数据一致性.md) |
| 包结构与依赖方向（"哪个域拥有哪张表"的判据） | [模块边界与依赖](../module-boundary/模块边界与依赖.md) |
| `*Properties` 的绑定校验机制（fail-fast） | 各配置类注释（本篇只引用键与值） |

> 本篇**不引用**任何迁移期文档（决策留痕表 / 遗留台账 / 事务边界决策表 / `archive/`）；
> 所有结论均从 `application.yaml`、`V1__baseline_tv_schema.sql` 与测试基类本身取证。
