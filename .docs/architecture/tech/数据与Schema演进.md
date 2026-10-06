# 数据与Schema演进

> 定位（先读这一句）：本仓结构演进走 **Flyway SQL-first**，历史结构以 **V1 baseline 冻结**形态搬入，
> 此后**只增不改**（新增版本号，回写历史脚本会被 checksum 校验挡下）。
> **本篇只讲 schema 演进策略 / V1 冻结口径 / 环境与表结构总览 / 存量数据债 / 结构校验 / 检查清单**。
> **事务切分（`@Transactional` 载体、`readOnly`、去事务）不在本篇**，去向见文末 [§边界与去向](#边界与去向机制外链)。
> 表"怎么被业务使用"（读路径、写扩散、缓存失效）见各域文档，如 [feed 域架构](../domain/feed.md)。

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

### 2.3 下次演进：走 V2

要改结构，**新增一个版本号更高的脚本**，绝不回写 V1：

```
db/migration/
├── V1__baseline_tv_schema.sql   ← 冻结，永不再改
└── V2__<描述>.sql               ← 新增：只写增量（ALTER TABLE / CREATE TABLE / CREATE INDEX …）
```

- V2 在**所有环境**都会被执行（活库：V1 已应用 ⇒ 只有 V2 是新的，会真正跑；空库：V1→V2 顺序跑完）。
  这正是"活库与测试库不分叉"的关键——**增量脚本在两处都生效**。
- `validate-on-migrate` 会校验 V1 未被动过、且 V2 尚未被应用（或已应用且内容一致）。

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

V1 共定义 **15 张业务表**（活库另有 1 张 Flyway 元表 `flyway_schema_history` ⇒ `information_schema`
计数为 **16**，与测试断言一致）。按归属域列出（归属判据见 [模块边界与依赖](./模块边界与依赖.md)）：

| # | 表 | 归属域 | 用途（一句） |
|---|---|---|---|
| 1 | `users` | user | 账号、口令散列、粉丝/关注计数、`role`（0 普通 / 1 管理员） |
| 2 | `auto_bigv` | user | 自动大V状态表：**存在即自动大V**（滞回判定产物，仅存 `user_id` + `created_at`） |
| 3 | `follow` | follow | 关注关系（`uk_user_follow` 防重复；被关注者上有粉丝侧索引） |
| 4 | `feed_inbox` | feed | 收件箱窗口：**每用户一个有界窗口**，时间线真相源（`uk_user_content`） |
| 5 | `feed_inbox_sync` | feed | 收件箱窗口同步状态：**存在即已同步**（读侧闸门据此决定是否回退纯拉） |
| 6 | `content` | content | 内容主表（视频/图文统一，含计数、软删、媒体完整性聚合、评论区开关、`ft_search` 全文索引） |
| 7 | `content_media` | content | 内容资源（`type`：1 视频 / 2 图片 / 3 封面） |
| 8 | `comment` | comment | 评论主表（含楼中楼 `@` 引用、回复计数） |
| 9 | `comment_like` | comment | 评论点赞（`uk_user_comment`） |
| 10 | `comment_media` | comment | 评论资源（图片） |
| 11 | `content_like` | like | 内容点赞（`uk_user_content`） |
| 12 | `coupon` | coupon | 优惠券（库存 + 抢购时段，含 `CHECK stock >= 0`） |
| 13 | `coupon_order` | coupon | 抢券订单（`uk_coupon_user` / `uk_coupon_code`；**全库唯一外键** → `coupon.id`，`ON DELETE CASCADE`） |
| 14 | `video` | 遗留（无当前域消费） | TV 遗留视频资源表（`videoID` 非自增） |
| 15 | `videoinfo` | 遗留（无当前域消费） | TV 遗留视频元信息表 |

**结构要点（取自 V1）：**

- **唯一外键**：`coupon_order.coupon_id → coupon.id`（`ON DELETE CASCADE`）；表序因此把 `coupon` 置于
  `coupon_order` 之前。其余表以应用层维护引用、不建外键。
- **全文索引**：`content.ft_search(title, description)` 用 `ngram` 解析器（MySQL 内置，空库同样可用）；
  `users.ft_idx_username(username)`。
- **时间列注释**：`auto_bigv.created_at` 明示"只作诊断，不参与判定"。

> 归属以"哪个域的 DAO/Mapper 触碰它"为准。`video` / `videoinfo` 在全仓 `src/` 下**无任何 Mapper/DAO 引用**
> （除 V1 脚本自身），故标为**遗留、无当前域消费**。

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
| **CI 下限断言** | `InfrastructureConnectivityTests` 断言 `information_schema.tables`（`table_type='BASE TABLE'`）计数 = **16**（15 业务表 + `flyway_schema_history`），且 `flyway_schema_history` 最新行 `version=1` / `type=SQL` / `success=1`。这是在**空库路径**上可复算的结构连续性锚点。 |
| **列集对拍** | 本仓 `tools/` 下**当前无**专门的结构比对脚本。复算方式：取活库列清单，与 V1 脚本中各 `CREATE TABLE` 的列清单做**差集**——两侧应仅相差 `flyway_schema_history`。 |

列集复算（活库侧）：

```sql
SELECT table_name, ordinal_position, column_name, column_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = DATABASE()
ORDER BY table_name, ordinal_position;
```

将结果与 V1 脚本逐表逐列对拍：**活库的列集 ⊇ V1 的列集**，且多出的表**只有** `flyway_schema_history`
（若 V2 已落地，则多出 V2 引入的增量）。任一侧出现对方没有的列/表，即为结构分叉信号（多半是回写了 V1，
或活库被手工改过）。

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
- 事务/`@Transactional` 的取舍不在本篇：见 [事务边界](./事务边界.md)。

---

## 边界与去向（机制外链）

| 不在本篇的内容 | 去向 |
|---|---|
| 事务切分原则、事务边界载体独立 bean、`readOnly`、去事务口径 | [事务边界](./事务边界.md) |
| 各域业务语义（表怎么被读/写、缓存失效、写扩散） | [feed 域架构](../domain/feed.md) 及各域文档 |
| 缓存 key 命名、三态、写后失效 | [缓存与数据一致性](./缓存与数据一致性.md) |
| 包结构与依赖方向（"哪个域拥有哪张表"的判据） | [模块边界与依赖](./模块边界与依赖.md) |
| `*Properties` 的绑定校验机制（fail-fast） | 各配置类注释（本篇只引用键与值） |

> 本篇**不引用**任何迁移期文档（决策留痕表 / 遗留台账 / 事务边界决策表 / `archive/`）；
> 所有结论均从 `application.yaml`、`V1__baseline_tv_schema.sql` 与测试基类本身取证。
