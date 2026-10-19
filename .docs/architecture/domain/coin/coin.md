# 投币（coin）域

> 定位（先读这一句）：投币是本仓**硬币**的持有与流转 —— 观众花硬币给**视频**表态，作者按 `1/10` 收币。
> 本篇只讲本域的**数据模型 / 端点契约 / 口径 / 边界**；**用户可见规则**另篇（[coin-需求](./coin-需求.md)）。
> **不展开**横切机制（缓存三态 / 事务 / 鉴权 / 限流 / 事件），去向见文末 [§边界与去向](#边界与去向机制外链)。
>
> ⚠️ 本仓**没有真实流量**（`AGENTS.md`「项目定位与优化口径」）⇒ 所有数值（TTL / 每日币数 / 上限）
> 都是**拍的**，靠"来源 + 推翻条件"登记，**不靠数值可信**。
>
> 📌 **落点纪律（2026-10-19 确认）**：本域**开工就建**本篇与 [coin-需求](./coin-需求.md) —— 它们是
> **活的事实源**（**执行中随代码改**、收口时即终态）；**执行态**在 `task/CURRENT_TASKS.md` 的 `T#` 回写里；
> **过程物**（设计讨论 / 方案比较 / 否决记录）在**收口时**才归档到 `archive/coin-<归档日>/`（只读追溯）。
> ★ **代码注释只许引用本篇路径**，**不得引用 `task/` 登记台**（登记台每期重建 ⇒ 引用即悬空）。

---

## 一、域定位：为什么是新域

`like` 是**二元态**（赞 / 取消），`favorite` 是**归属**（收进哪个夹）。投币是**有成本的计数 + 余额流转**，
三件事把它与既有域分开：

1. **写路径要动"钱"**：一次投币同时改 `content_coin`（关系）、`content.coin_count`（计数）、
   `users.coin_deci`（**两个用户**的余额 —— 投的人减、作者加），且必须同进同退；
2. **不可取消** ⇒ `content.coin_count` 是全仓**唯一没有"−"路径的计数列** ⇒
   **不需要防负守卫**（对照 `content.like_count` 的守卫，见 [coin-需求 §五](./coin-需求.md) 与本篇 §十）；
3. **"我投了几枚"是一个数量**（`0/1/2`），不是布尔 ⇒ 缓存载体与 VO 形态都与 like 不同（§七）。

**刻意不做的两块**：不建"通知作者"的机制（本仓无通知体系）、不做硬币明细流水页与 UP主获币面板（见 §十）。

---

## 二、数据模型（Flyway **V5**）

```sql
ALTER TABLE `users`
  ADD COLUMN `coin_deci` int NOT NULL DEFAULT 0 COMMENT '硬币余额（单位=十分之一币，值=币×10）',
  ADD COLUMN `last_sign_date` date DEFAULT NULL COMMENT '最近签到日（上海自然日）';

ALTER TABLE `content`
  ADD COLUMN `coin_count` int unsigned NOT NULL DEFAULT 0 COMMENT '累计被投币数（不可取消⇒只增）';

CREATE TABLE IF NOT EXISTS `content_coin` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '投币用户',
  `content_id` bigint NOT NULL COMMENT '内容 id',
  `amount` tinyint NOT NULL COMMENT '累计投币枚数（1~2）',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_content` (`user_id`,`content_id`),
  KEY `idx_content_id` (`content_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='投币记录（单行聚合、不可取消）';
```

**不做逐次追加的流水行**：单视频封顶 2，追加行要靠 `SUM` + 锁才能守住上限（并发下有竞态），
而"明细页"本期已定不做 ⇒ 无收益。单行 `amount` 让封顶可以走**原子条件写**（§五）。

⚠️ `coin_count` 是 `unsigned` 但**只增不减** ⇒ 不需要 T34 那套 `CAST(... AS SIGNED)` 守卫。
**这是口径差异、不是漏改**（对照 `content.like_count` 有 − 路径）。

---

## 三、端点契约

写 = `POST` + `x-www-form-urlencoded`、读 = `GET` + query —— 沿袭 `/like/*`、`/favorite/*` 的既有风格。

| 端点 | 鉴权 | 入参 | 出参（`data`） | msg |
|---|---|---|---|---|
| `POST /coin/add` | `@RequiresLogin` | `contentId`、`count`（`1` 或 `2`） | `{ "balance": 1.5 }` | 「投币成功」 |
| `POST /coin/signIn` | `@RequiresLogin` | — | `{ "balance": 1.5, "credited": true }` | 「签到成功」/「今日已签到」 |

- ★ **不新增"投币状态"读端点**：`0/1/2` 随**详情**内嵌（§七）。这是与 like / favorite **有意的不同**
  —— 它们各有 `status` 端点，投币刻意没有（一个数量字段就够三种分支，且省一趟往返）。
- ★ **余额复用 `/user/me`**（`UserInfoVO` 加 `coin` 字段，一位小数）：不新增"余额查询"端点。
  写操作**响应体回带新余额** ⇒ 前端投币/签到后无需再拉一次 `/user/me`。
  ★ **`UserInfoVO` 同时加 `signedToday`**（= `last_sign_date == 上海今日`）—— 签到按钮要靠它才**进页就置灰**；
  否则只能"点了才知道今天已签"（幂等回「今日已签到」），体验差一档，而它只是**同一张表的两次单列点查**
  （主键命中、成本可忽略；刻意不把两列塞进 `findByIdForProfile` 的列集，见 `UserDao#findCoinDeci` 注释）。
- `balance` 用 `BigDecimal`（手工 scale = 1）序列化 —— `deci / 10` 恒为一位小数、精确无损；
  用 `double` 会在 `0.1` 这类值上引入二进制尾差。

---

## 四、硬币经济（**单位约定**）

| 量 | 口径 |
|---|---|
| 存款单位 | `users.coin_deci` 存的是**币的十分位**：值 `= 币 × 10`（`DECI_PER_COIN = 10`） |
| 签到 | `+10`（= `+1` 枚）；每日一次（**上海自然日**，与库里 `datetime` 既有口径一致） |
| 投币扣款 | `− count × 10` |
| 可投判定 | `coin_deci ≥ count × 10`（等价于 `floor(coin_deci / 10) ≥ count`：余额 `1.5` 只能投 1 枚） |
| 作者收益 | `+ amount`（**十分位**）—— `amount` 枚 × `1/10` 恰好 = `amount` 个十分位 ⇒ **收益换算不需要乘除** |
| 读出 | `deci / 10` 保留一位小数（**精确，不舍入**） |

★ **单位约定必须显式**：单看 `coin_deci = 15` 是 `1.5` 枚、不是 15 枚。三处一起钉：
**列名带 `deci`** + **常量 `DECI_PER_COIN`** + **换算只发生在 VO 出口**
（DAO / Service 全程用十分位整数，**不许在各处 SQL 里散落 `*10`**）。

---

## 五、写路径：校验顺序与并发原子性

### 5.1 投币（单事务）

校验顺序按本仓既有分界线「**参数形态 → 存在性 → 类型/归属 → 资源状态**」：

| # | 判据 | 拒绝 | 类型 |
|---|---|---|---|
| 1 | `count ∉ {1,2}` | 400「投币数量只能是 1 或 2」 | 参数 |
| 2 | 内容不存在 / 已软删 | 404「内容不存在」 | 存在性 |
| 3 | `type != 1`（图文帖） | 400「该内容不支持投币」 | 类型 |
| 4 | `authorId == userId` | 400「不能给自己投币」 | 归属 |
| 5 | 已投满 2 枚 | 409「**对本稿件投币枚数已用完**」（B站 文案） | 资源状态 |
| 6 | 余额不足 | 400「硬币不足」 | 资源状态 |
| 7 | 成功 | 200「投币成功」+ 回带余额 | — |

★ **5 先于 6**：两者同时不满足时答 **409**（同 favorite「参数型 400 先于归属」的分家纪律 —— 资源状态内部另定次序）。

**并发正确性**（两处都是**原子条件写**，没有"先读后写"的窗口）：

```sql
-- 封顶：先保证行存在，再条件累加（影响 0 行 ⇒ 409）
INSERT IGNORE INTO content_coin (user_id, content_id, amount) VALUES (?, ?, 0);
UPDATE content_coin SET amount = amount + ?
 WHERE user_id = ? AND content_id = ? AND amount + ? <= 2;

-- 余额：条件扣款（影响 0 行 ⇒ 400）
UPDATE users SET coin_deci = coin_deci - ?
 WHERE id = ? AND coin_deci >= ?;
```

⇒ 并发两次各投 1 枚**不会突破封顶 2**，也**不会把余额扣成负数**。
★ 余额列**不是** `unsigned`（`int`）—— 扣款由条件守卫，语义是"够不够"，不需要 CAST 技巧。

**事务**：投币是 `@Transactional`（关系行 + 两条计数列 + **两个用户**的余额必须同进同退）；
末尾发 `CoinChangedEvent`，由 `CoinChangedListener` 在 `@TransactionalEventListener(AFTER_COMMIT)` 写缓存。

### 5.2 签到（幂等，一条原子语句，**不加事务**）

```sql
UPDATE users SET coin_deci = coin_deci + 10, last_sign_date = :today
 WHERE id = :userId AND (last_sign_date IS NULL OR last_sign_date < :today);
```

影响 `1` 行 = **本次到账**（回 `credited = true`）；`0` 行 = 今日已签 ⇒ **200**（幂等，不加币、不报错）。
★ 并发双击只到账一次。**单语句自带原子性 ⇒ 不加 `@Transactional`**；签到也**不发事件**
（`/user/me` 直接读 DB，没有需要失效的缓存）。

---

## 六、缓存形态

| key | 结构 | 内容 | TTL | 失效触发方 |
|---|---|---|---|---|
| `user:coinAmount:{userId}` | **HASH**（field = contentId，value = amount） | 我投过币的内容及**枚数** | `video.cache.coin-ttl`（15m） | `CoinChangedListener`（提交后**条件写**） |
| `empty:user:coinAmount:{userId}` | String | 防穿透"该用户一枚币都没投过" | 60s | 写路径清空标记 |
| `content:coinCount:{id}` | String int | 累计被投币数（**公共计数**） | `coin-ttl`（15m） | `CoinChangedListener` 条件 `INCRBY`；内容删除 ⇒ `deleteContentCoinCount` |

- **为什么 HASH 而不是 Set**：值不是布尔而是 `0/1/2`，Set 承载不了"几枚"（like 是 `Set<contentId>`）。
- **读**：`HGET` / `HMGET`；miss ⇒ `SELECT content_id, amount FROM content_coin WHERE user_id = ? AND amount > 0`
  **全量回填**（硬币稀缺 ⇒ 装载量比 `user:likeSet` **更小**）；单飞；空集写空标记；Redis 失败降级直查 DB。
- **写**：条件写 = 只在 key 已加载时才写（冷 key 不建半套）。两处形态不同且都**精确**：
  ① `user:coinAmount` 写的是**新总量**（由事件带出）⇒ **精确回放**、幂等，不像 like 要用 `INCR` + `SADD` 两个 key；
  ② `content:coinCount` 是**单调只增** ⇒ 用条件 `INCRBY amount`。
- ★ **`ContentCacheDTO` 一字符不改**（那个"冻结键集"不动）—— 见下条。

### 6.1 公共计数为什么不内联进内容缓存（对 **F-25** 的站边）

仓内"计数缓存"并存两种形态：`like` / `comment` 的计数**内联**进 `content:{id}` JSON
（代价 = **每次点赞/评论连坐失效整份内容缓存**），`favorite`（T11）**只走独立 key**。
本域选**独立 key**（先例 = favorite），理由是**不把"计数变化连坐失效整份 `content:{id}`"复制到新功能上**；
代价 = 列表面多一趟 `MGET`（见 §十）。取证与两种形态的对照见
[缓存与数据一致性 §五](../../tech/cache/缓存与数据一致性.md)；该取舍已登记 `task/FURTHER_ISSUES.md` 的 **F-25**。

### 6.2 为什么不是 **F-21** 被否决的那种形态

**F-21 否决的是**"布隆过滤器 + 滑动窗口 + 新增冗余表 + `(user,content)` 维度 key"的**组合三件套**；
而"**每用户维度完整集合 / 完整哈希**"是本仓**在生产中跑着**的形态
（`user:likeSet` / `user:favoriteSet` / `user:following` / `feed:inbox`），投币照搬即可。**两者不是一回事。**
（同款误判我 2026-10-18 犯过一次、当日更正 —— 这条区分留在此处，免得下次再混。）

---

## 七、互动态与公共计数（**与 like / favorite 刻意不同形**）

- **详情**：`interaction.coin` = 我投了几枚（`0/1/2`）；**图文帖该维 = `null`**
  —— 即 `interaction` 子对象"不适用该维度"的表示（与"本面不查"**共用 `null`**，不另造表示）。
- 判定落在 `InteractionService#getStatus` 内**按 `type` 分流**（它已收 `type` 入参）；
  ★ **不给 `InteractionDimension` 加 `COIN` 常量** —— 没有任何列表面需要"我投了几枚"（YAGNI）。
- **`coinCount`（公共计数）挂在 `ContentVO` / `ContentDetailVO` 的字段上**，
  **不进 `ContentCacheDTO`**（缓存形状不动），由**组装点**批量读：
  `ContentStatusFiller`（`/start` `/search` `/profile` 三面共用）+ `FeedService` + `ContentService`（详情单条）。
- ⚠️ 由此 `InteractionStatusVO` 的键集 **3 → 4**（多一个 `coin`）；列表各面不选该维 ⇒ 列表里恒 `null`，
  前端静默忽略（沿用三态口径）。
- ★ 与 like / favorite 的**第二处不同**：它们的"我的状态"走独立端点，投币走**详情内嵌**（§三）。

---

## 八、错误码

| 码 | 触发 | 文案 |
|---|---|---|
| 400 | `count` 非 1/2 | 投币数量只能是 1 或 2 |
| 400 | 图文帖 | 该内容不支持投币 |
| 400 | 自己给自己投 | 不能给自己投币 |
| 400 | 余额不足 | 硬币不足 |
| 404 | 内容不存在 / 已失效 | 内容不存在 |
| 409 | 单视频已满 2 枚 | 对本稿件投币枚数已用完 |
| 401 | 未登录 | （鉴权链兜底） |

---

## 九、依赖边与登记

| 边 | 形态 | 依据 |
|---|---|---|
| coin → `content.dao.ContentDao` | `findAliveContentAuthorAndType`（一趟取 `authorId` + `type`）/ `updateCoinCount` / `findCoinCount` / `findCoinCountsByIds`（计数缓存回源） | 《模块边界与依赖》§三「**同事务改计数/级联 ⇒ 允许 DAO 薄依赖**」；其中**纯读的那几条窄查询**属该文 §六 要登记的读例外 |
| coin → `user.dao.UserDao` | `updateCoinDeci` / `deductCoinDeci` / `signIn` / `findCoinDeci` / `findLastSignDate` | **计数列方法进所在表的 DAO**（follow 域 `updateFollowCount` 先例）；`users` 的两列由本域维护 |
| coin → `coin.dao.ContentCoinDao` | `content_coin` 表（**按表命名**，镜像 `ContentLikeDao`） | 自有数据 |
| content → `coin.service.CoinService` | 详情/列表填 `coinCount`（`getCoinCount` / `batchGetCoinCount`） | §三「**读状态 → 走对方 Service 公开方法**」 |
| interaction → `coin.service.CoinService` | 详情互动态的第四维 `coin`（`getCoinAmount`，按 `type` 分流） | §七；**只调 Service**，不碰 `coin.cache`（ArchUnit 规则 2） |
| feed → `coin.service.CoinService` | 列表 `coinCount` 的一趟批量（`batchGetCoinCount`） | §七；feed **不**依赖 `ContentStatusFiller`（那是 content 的装配组件，会把 `feed → content` 的边再宽一层） |

★ **内容删除的级联方向**：`coin.event.ContentRemovedCoinListener` **订阅** content 的
`ContentCacheChangedEvent(REMOVE)` 并失效自己的 `content:coinCount` —— 内容域**不直调**
`CoinService`（S6-B2c 的既有形态，同 `ContentRemovedLikeListener`）。因此上表里没有
`content → coin` 的"删除"那条边。

★ **开工时须同步登记**：《模块边界与依赖》**§六**要补 `content ⇄ coin` 那一行（含上面两条
读例外：`findAliveContentAuthorAndType` / 计数回源窄查询） —— 该文自身纪律是
"纯读例外**登记在此即认账**"，不登记就是"无声绕开判据"。✅ **T42 已登记**（2026-10-19）。

### 9.1 F-18 复核结论（**升级条件未触发**）

投币让 `interaction` 域聚合到**第 4 个域**，字面上够到了 F-18 写的升级条件。**复核后判未触发**：
`coin` 域**不依赖 content 的任何 Service / Bean**（只有 `ContentDao` 薄依赖，与 favorite 同形）
⇒ `contentService → interactionService → favoriteService → contentService` 那个**装配期环没有多出来**，
`@Lazy` 仍是**单点断环**。⇒ **F-18 维持留池**，本次只在回写留一句"已复核、条件未触发"（推翻条件保留）。

---

## 十、已知代价

| # | 代价 | 边界 / 推翻条件 |
|---|---|---|
| 1 | **`count = 2` 而剩余额度只有 1 时，也答 409「已用完」**（文案不够精确——还剩 1 枚） | 判据来自封顶的条件写 `amount + ? <= 2`（影响 0 行 ⇒ 409），它分不出"已满"与"不够这一次"；前端按 `0/1/2` 只给出合法选项（`1` ⇒ 只能再投 1），故**正常客户端不可达**。推翻 = 若要把这条也精确到文案，得先读 `amount` 再判（破坏"不先读后写"）或新增一条拒因码 |
| 2 | **列表面显示 `coinCount` 要多一趟 `MGET`**（§6.1 的代价） | 是**常量往返**、不随条数增长；推翻 = 若列表不需要显示投币数，可退成"只详情显示" |
| 3 | `coin_count` 无 − 路径 ⇒ 与 `like_count` 口径不同（后者有 T34 守卫） | 有意差异（不可取消）；推翻 = 若将来允许取消投币 ⇒ 须补守卫 |
| 4 | 数值全为拍值（`coin-ttl` / 每日 1 枚 / 上限 2 / `1/10`） | **无真实流量** ⇒ 不可验；推翻 = 有真实行为分布或压测基线 ⇒ 重定价 |
| 5 | **签到 / 投币无通知作者机制**；**无硬币明细页**、**无 UP主获币面板** | 本期已裁剪（见 [coin-需求 §六](./coin-需求.md)）；推翻 = 需要这些叙事时另开 |
| 6 | 余额列 `int` 而非 `unsigned` | 语义需要"扣到负数前拦住"，靠条件守卫；给 `unsigned` 会让 `CAST` 技巧渗进来 |
| 7 | **并发首次投币的理论死锁窗口**：`insertIgnoreZero` 在已存在行上取共享锁，紧随的条件 `UPDATE` 要升级排他锁 ⇒ 两个事务同时做这两步时存在 InnoDB `1213` 死锁、其中一个答 500 的可能（`INSERT ... ON DUPLICATE KEY UPDATE` 是同族的经典死锁形态）。**未实测复现**（T38/T42 的并发用例多次运行全绿，窗口在两条语句之间的微秒级） | 本仓无真实流量；改为 ODKU 或加重试都要额外语义/代码。推翻 = 真实并发下出现 1213 ⇒ 届时改 `INSERT … ON DUPLICATE KEY UPDATE amount = amount`（把共享锁提前成排他锁）或对 1213 做一次重试 |

---

## 十一、测试口径（钉住什么）

**契约（HTTP 层）**：余额不足 ⇒ 400 **且无任何副作用**（无投币行、计数不变、零扣款）；
封顶 ⇒ 409 且 `amount` 停在 2；图文 400；自投 400；404；`count = 3` ⇒ 400；
成功 ⇒ 200 + 回带余额；签到第二次 ⇒ 200 但**不加币**。
★ `@RequiresLogin` 的判别力**只在** `SecurityContractTests`（HTTP 侧对未登录一律 401 是**假绿**）。

**DB 终态 / 独立 oracle**（不读缓存、不读接口回显）：
`content.coin_count == SELECT COALESCE(SUM(amount), 0) FROM content_coin WHERE content_id = ?`（**amount > 0 部分**）；
`users.coin_deci == 签到次数 × 10 + 该作者收到的全部收益`。

**并发**：两个线程各投 1 枚 ⇒ 恰好一条成功、另一条 409，`amount` 恰为 2（原子条件写的判别力）。
**并发签到**：两个线程同点 ⇒ `last_sign_date` 只推进一次、`coin_deci` 只 +10。

**缓存**：写后条件写生效（去掉条件写 ⇒ **必红**）；**事务回滚 ⇒ 两个 key 保持预热原值**
（`AFTER_COMMIT` 的存在证明，范式同 `LikeCacheTests.事务回滚后缓存未被污染`）。
⚠️ **不要写成"回滚 ⇒ key 不存在"**：冷 key 本来就不存在 ⇒ 那种断言在"事件没发"与"事件发了但回滚"
两个世界都绿，**零判别力**（T42 复核时纠正的措辞；预热后断言"值没被动过"才是证明）。
**列表的 `coinCount` 是一趟批量**（`batchGetCoinCount` 恰一次、单条读零次 —— T42 补的锚点）。

**反向验证清单**（改哪一行必红）在开工时逐条实测登记。
★ **T42 复核补测的 7 条（已实测，默认组口径）**：① 去掉 `deductCoinDeci` 的 `coin_deci >= ?` ⇒
并发扣款 + 半枚 floor **2 红**；② `updateCoinCount` 的 `+` 改 `=` ⇒ 累计用例 **1 红**（3 → 2）；
③ `updateCoinDeci` 的 `+` 改 `=` ⇒ 累计用例 **1 红**（8 → 2）；④ 列表改循环 `getCoinCount` ⇒
批量锚点 **1 红**（`batchGetCoinCount` 未被调用）；⑤ 事件 `newAmount` 传 `delta` ⇒ 缓存累计用例
**1 红**（`"2"` → `"1"`）；⑥ 删回填 Lua 的 `DEL KEYS[2]` ⇒ 空标记用例 **1 红**；⑦ 调换类型 / 归属判定
⇒ 次序用例 **1 红**。还原后全量 689 例 + resilience 41 例全绿。

---

## 附录：编号速查（**活**的查证入口）

> ★ 本表**每号一行**，是本域代码 / 测试 / 文档注释里 `F-##` / `R-##` / `T#` 的**活解析入口**。
> **逐条论证**随正文进 git；**收编只许一行结论**（纪律见 `AGENTS.md`「文档与引用纪律」）。
> ⚠️ 未完成的任务**不收编**（滚动见 `task/CURRENT_TASKS.md`）。

**需求 / 决策 `F-##` · `R-##`**

| 编号 | 一句话 | 详见 |
|---|---|---|
| F-13 | 投币议题（视频可投 / 帖子不可投；"涉币的余额 / 交易 / 幂等"）—— **已采纳**（2026-10-19）⇒ 本域 | 本篇 · [coin-需求](./coin-需求.md) |
| R-17 | 投币（硬币）域拍板：**签到得币**（每日 1 枚、手动）+ **视频投币**（单视频封顶 2、UP主得 1/10、不可取消、不能自投）；**硬币来源对齐偏离 B站**（本仓无"在线"定义） | 本篇 §四 / §五 |

**任务 `T#`（本期 T36–T42，已收口）**

| 编号 | 一句话结论 | 详见 |
|---|---|---|
| T36 | V5 迁移（`content_coin` + `users` 两列 + `content.coin_count`）+ `CoinSchemaTests` 结构契约 + `coin` 进 `ArchitectureTests.DOMAINS` | §二 |
| T37 | 签到供给端：**一条原子条件 `UPDATE`**（幂等 / 并发安全），不加事务、不发事件；`/user/me` 回带 `coin`（一位小数）与 `signedToday` | §三 / §四 / §五.2 |
| T38 | 投币写路径：七级校验顺序（**5 先于 6**）+ **两台原子条件写**（封顶 / 扣款），单事务、拒单零副作用 | §五.1 |
| T39 | 缓存两 key：`user:coinAmount`（HASH，写**新总量**）+ `content:coinCount`（条件 `INCRBY`）；条件写 / 空标记 / 单飞 / 降级；内容删除**只失效不删行** | §六 |
| T40 | 读面：`interaction.coin`（视频 `0/1/2`、图文 `null`）+ `coinCount`（三面共用批量 + feed 自持一趟 + 详情单条）；`ContentCacheDTO` 未改 | §七 |
| T41 | 前端接入：投币按钮 / 弹层 + 签到按钮 + 卡片投币数（原生 JS、无构建步骤；后端零改动） | [coin-需求](./coin-需求.md) §四 |
| T42 | 期收口：依赖边登记（《模块边界与依赖》§六）+ 编号收编 + 全量回归 + **T36–T41 测试复核**（补判别力缺口：累计语义 / 余额竞态 / 半枚 floor / 校验次序 / 缓存写失败 / 批量不循环） | §九 / §十一 |

---

## 边界与去向（机制外链）

| 不在本篇的内容 | 去向 |
|---|---|
| **用户可见规则**（能投什么 / 投几枚 / 看到哪些数字） | 同目录 [coin-需求](./coin-需求.md) |
| 缓存三态 / 空标记 / 写后失效 / 单飞 | [缓存与数据一致性](../../tech/cache/缓存与数据一致性.md) |
| 事务切分、载体独立 bean、`AFTER_COMMIT` | [事务边界](../../tech/transaction/事务边界.md) · [领域事件与解耦](../../tech/events/领域事件与解耦.md) |
| 鉴权链（`@RequiresLogin` / 默认拒绝） | [安全与鉴权](../../tech/security/安全与鉴权.md) |
| 端点权重表（新增非 GET 端点必须登记） | [限流](../../tech/rate-limit/限流.md) |
| 跨域边与 ArchUnit 规则 | [模块边界与依赖](../../tech/module-boundary/模块边界与依赖.md) |
| schema 演进纪律（V1 冻结 / 迁移文件逐字节冻结） | [数据与Schema演进](../../tech/data-evolution/数据与Schema演进.md) |
| 未处置的候选（硬币明细页 / UP主获币面板 / 通知） | `task/FURTHER_ISSUES.md` |
| 本期做哪些、任务怎么拆 | `task/CURRENT_NEEDS.md` · `task/CURRENT_TASKS.md` |
