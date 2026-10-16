# favorite 域架构（收藏 / 收藏夹 / 稍后再看）

> 定位（先读这一句）：favorite 是"**用户把内容收进容器、并能按容器回看**"的域 ——
> 收藏夹 + 收藏记录 + 稍后再看。
> **本篇只讲本域的数据模型 / 端点 / 口径与边界**；缓存三态 / 事务写法 / 跨域依赖 / Flyway 纪律 /
> 事件范式等**跨域机制不在本篇**，去向见文末 [§边界与去向](#边界与去向机制外链)。
> 用户看得到的行为与规则 → [favorite-需求](./favorite-需求.md)（**唯一事实源**）。
>
> ★ **本域一轮已收口**（2026-07 → 2026-10-15，`T1`–`T19`）：**当前口径以本篇 + 代码为准**；
> 逐条**历史论证**（当初为什么这么定）在 [archive/favorite-2026-10-15/](../../archive/favorite-2026-10-15/)
> —— 那是**只读追溯**，不再维护（数字 / 状态**可能已过期**）。

---

## 一、这个域解决什么问题

一句话：**让用户把内容收进容器、按容器回看**。本域有三个容器形态：

| 形态 | 语义 | 与其它形态的关系 |
|---|---|---|
| **收藏夹**（`favorite_folder`） | 有名字、可公开 / 私密、可有多个（每用户**至多一个默认夹**） | 同一内容可**同时**进多个夹 |
| **收藏记录**（`favorite_item`） | "某内容在某个夹里"这条关系，按时序排列 | 收藏数**按人**去重，**不看夹数** |
| **稍后再看**（`watch_later`） | **独立于收藏夹**的第二个池子，**只收视频** | ★ **结构上不共表** ⇒ 收藏侧零污染（§九） |

难点不在"存一行关系"，在三处：

| # | 难点 | 本篇 |
|---|------|------|
| 1 | **时效性**：内容会被删 / 下架，但夹里要**留位置**且要能移出 | §五 |
| 2 | **计数一致性**：收藏数是**冗余列**，写入时须与明细一致 | §四 |
| 3 | **多容器不串味**：稍后再看 / 他人公开夹都不能污染收藏读语义 | §九、§十 |

---

## 二、数据模型

```
favorite_folder                                   favorite_item
  id              bigint PK                         id           bigint PK
  user_id         bigint 归属                       folder_id    bigint → 收藏夹
  name            varchar(100)                      user_id      bigint 冗余（查去重用）
  description     varchar(255) 简介                 content_id   bigint
  is_private      tinyint 私密                      create_time  datetime
  is_default      tinyint 默认夹                    UNIQUE uk_folder_content (folder_id, content_id)
  default_uniq    tinyint VIRTUAL 生成列            KEY idx_user_content (user_id, content_id)
  create_time     datetime                          KEY idx_folder_time (folder_id, create_time)
  update_time     datetime
  UNIQUE uk_user_default (user_id, default_uniq)    ← 每用户至多一个默认夹

watch_later（V4，稍后再看；与收藏**不共表**）
  id bigint PK · user_id bigint · content_id bigint · create_time datetime
  UNIQUE uk_user_content (user_id, content_id)      ← 幂等（重复 add 撞它 ⇒ 只刷时间）
  KEY idx_user_time (user_id, create_time)          ← 倒序分页

content.favorite_count bigint（V2 加列 / V3 回填；冗余列，见 §四）
```

- ⚠️ `is_private` / `description` **建表（V2）时就带上** —— "默认公开"一旦上线即**对外契约**，后补是 breaking。
- ⚠️ `content.favorite_count` 列 **V2 就加**（零成本）但**一期不读不写**，二期（**V3 回填**）才启用。
- ★ `uk_user_default (user_id, default_uniq)`：`default_uniq` 是 **VIRTUAL 生成列**（默认夹 `1` / 自建夹 `NULL`），
  MySQL 唯一索引**不约束 NULL** ⇒ 用一个键表达"多条自建夹 + 至多一条默认夹"。
  它是**默认夹懒建**在并发下唯一的防重复手段（`INSERT … WHERE NOT EXISTS` 兜不住：两事务都能查到"不存在"）。
- ⚠️ `favorite_item` **刻意不加 `content_id` 前导索引** —— 它是 §四 那条一期计数基线的对照面。

---

## 三、端点

| 端点 | 鉴权 | 作用 |
|---|---|---|
| `POST /favorite/folder/add` | 登录 | 新建收藏夹（超**个数**上限 ⇒ 400） |
| `POST /favorite/folder/update` | 登录 | 改名 / 简介 / 私密开关（**部分更新**） |
| `POST /favorite/folder/remove` | 登录 | 删除（**默认夹拒删 409**；夹内条目**同事务一并删**） |
| `GET /favorite/folder/list` | 登录 | 我的全部夹（含私密）+ 每夹条数 |
| `GET /favorite/folder/public?userId=X` | **匿名** | 某人的**公开**夹（名称 + 条数 + `id`） |
| `GET /favorite/folder/items?folderId=X` | **匿名** | 打开**他人公开夹**看内容（§十） |
| `POST /favorite/add` | 登录 | 收进指定夹（`folderId` 缺省 = 默认夹**懒建**） |
| `POST /favorite/remove` | 登录 | 从指定夹移出（**支持批量**） |
| `POST /favorite/move` | 登录 | 移到其它夹 |
| `GET /favorite/status` | 登录 | 我对某内容是否已收藏（含所在夹） |
| `GET /favorite/count` | **匿名** | 某内容的收藏数 |
| `GET /favorite/list` | 登录 | 夹内内容分页（收藏时间倒序） |
| `GET /watchlater/list` | 登录 | 稍后再看清单（`create_time` 倒序） |
| `POST /watchlater/{add,remove,move}` | 登录 | 加入 / 移出 / 挪进普通夹（§九） |

- ⚠️ 鉴权**逐端点**声明；收藏与稍后再看两组**绝不加类级** `@RequiresLogin` ——
  那会连带坏掉 `/folder/public`、`/folder/items` 两条**匿名契约**。
- ⚠️ 分页参数名 = `page` + `pageSize`（与全项目其余分页端点一致；早期草稿的 `size` **不采用**）。
- 🔑 **"我的"与"他人的"拆成两个端点**：把"他人视角"做成**独立且危险的路径**，才能单独写
  "去掉 `is_private = 0` 就变红"的契约测试；混在一个端点里靠"传没传 `userId`"分流，分支写错即**直接泄露私密夹**。

---

## 四、计数口径（`favorite_count` 冗余列）

- **唯一事实源 = `favorite_item` 明细**；`content.favorite_count` 是**冗余列**，唯一价值是**快**。
- 一期：实时 `COUNT(DISTINCT user_id)`；二期：读列，**写路径用「事务内批量重算」维护该列** —— **不是增量**。
- 统一入口 `FavoriteService#recountFavoriteCounts(Set<Long> contentIds)`：
  **1 趟** `SELECT content_id, COUNT(DISTINCT user_id) … GROUP BY content_id` +
  **1 趟** CASE-WHEN 批量 `UPDATE`（写**绝对值**）。
- **接入四条写路径**：`add` / `remove` / **`move`** / `deleteFolder`（`move` 漏判会等价新增收藏，见 `I-12`）。
- ⚠️ **为什么不能用增量**：`favorite_item` 的唯一键是 `uk_folder_content`（**夹内**唯一），
  与判定维度 `(user_id, content_id)` **不重合** ⇒ 同一用户**并发往两个夹**收同一内容时两个事务都判"无行"、都 `+1` ⇒ **计数偏大**。
- ⚠️ **为什么不用 `SELECT … FOR UPDATE`**：判"**不存在**"时 RC 下不加锁（形同虚设）、
  RR 下 gap lock 共享 ⇒ 双方都过判定、INSERT 互等 ⇒ **死锁**；且本仓是"唯一键 + 409"的**乐观**风格，**无取锁顺序规范**。
- ★ **不变量**：`content.favorite_count == COUNT(DISTINCT user_id)` —— 由**独立 oracle** 钉住（一期实时 COUNT 天然就是二期的验收 oracle）。
- ⚠️ **残留并发窗口**（如实登记）：两个**不同用户**并发**首次**收藏同一内容 ⇒ 后提交者把陈旧绝对值写回、列**短暂偏小**（重算幂等 ⇒ **自愈**）⇒ `task/FURTHER_ISSUES.md` **F-19**。
- ★ **一期实测基线**（容器 MySQL 8.4，2 万行 / 目标内容 2000 个不同 user）：
  `EXPLAIN` = `type=range, key=idx_user_content, Extra="Using where; Using index for group-by (scanning)"`，≈ **17 ms**
  —— ⚠️ **别**推断成"无 `content_id` 前导索引 ⇒ 全表扫"（实测**推翻**，实为 **loose index scan**；`I-06`）。

---

## 五、失效条目口径

- **失效判据 = content 装载契约"取不到"**：**不存在 / 已删下架 / 媒体损坏 / 未知 type 统一折叠**
  （口径 `R-11`「**看不了 ≈ 失效**」）。列表经 `ContentService.loadContentVOs` 批量取展示字段，
  **本域 SQL 只查 `favorite_item` 三列**（不 LEFT JOIN / 不直连他域表）。
- 出参 VO **只给占位**：`invalid = true`、`cover = null`、`title` =「内容已失效」、`author = null`；
  **保留 `id` / `contentId`**（供移出）。
- ★ **分页单位 = `favorite_item` 行**（**不是**"内容"）：失效记录**占一条、返回一条占位**；
  **一整页都是失效条目也要照常返回满页**（`G12` **长期红线**）。
- ★ **"可移出 / 不可移入或移动" 的根因是存在性校验**，**不需要专门写分支**：

| 端点 | 校验内容存在性？ | 失效条目的结果 |
|---|---|---|
| `remove`（移出） | **不校验** —— 直接按 `(folder_id, content_id)` 删行 | **移得掉** ✅（`G11` **长期红线**） |
| `add` / `move`（移入 / 移动） | 校验（`isContentExist` 的 SQL 带 `is_deleted = 0`） | **自然被拒** ✅ |

- ⚠️ **别照搬 `/profile` 口径**：`ProfileService` 对已删内容是"**跳过、total 不变**"（分页出空洞）；
  收藏夹按 B 站是"**返回占位条目**"（条数连续）—— 两者不同，**别复用同一段填充逻辑**。
- ⚠️ R-11 之后"列表失效集" ⊋ "`add`/`move` 拒收集"：数据损坏态（`is_deleted = 0` 但装不出来）
  列表占位、而 `add`/`move` 仍收得进 —— 属**已拍板接受**的边缘态（**无 API 可达**）。

---

## 六、互动态（`interaction` 子对象）

- **详情**（`GET /search/IdSearch`，路径与参数**不变**）响应内嵌子对象：
  `interaction: { isLiked, isFavorited, isFollowed }`；**匿名 ⇒ `interaction` 为 `null`**（`R-10`，**二期唯一有意 breaking**）。
- `ContentVO`（列表面）与 `ContentDetailVO`（详情面）**逐字段同形**（`R-13`，T13 交付）
  ⇒ 互动态在全仓**只有一处定义**、前端一套读法。
- 三维是 **`Boolean` 三态**：`true` / `false` / **`null`**。★ `null` = **本面不查该维**
  （与 `F-13` 投币"该内容类型不适用该维"**共用同一表示**）。
- ⚠️ **键集恒定（恒三维）**，不用"缺键"表达"不查"；**0 维** = **对象在、三维全 `null`**
  （**不是** `interaction: null` —— 那是**匿名**的语义，两者必须可区分）。
- 互动域（`interaction/`，**无 controller / dao / cache / event**）**只提供「单条 + 批量」契约**；
  **只给批量契约加"维度集合"入参**，"每面带哪几维"是**编译期常量**（形状是契约、常量随时改）：

| 面 | 维度 | 登录时该条目的 `interaction` |
|---|---|---|
| `/start` | `{关注}` | `{isLiked: null, isFavorited: null, isFollowed: …}` |
| `/search/keywordSearch` | **空集（0 维）** | 三维全 `null` |
| `/feed` / `/profile.contentPage` | `{赞}` | `{isLiked: …, isFavorited: null, isFollowed: null}` |
| `/search/IdSearch`（详情） | 三维 | 三维恒非 `null` ⇒ **详情 JSON 逐字不变** |

- ⚠️ `interaction` **只能挂 `ContentVO`**，**绝不进 `ContentCacheDTO`** —— 那是**缓存形状**，
  互动态是**请求态**，进缓存即**跨用户污染**。
- ⚠️ 互动域**不得依赖 content**（"哪些维度适用"由调用方把 `type` / `authorId` **传入**）；
  它**排除「评论点赞」**（那是 like 域既有职责）。

---

## 七、缓存边界（本域缓存了什么、没缓存什么）

| 读 | 频率 | 怎么取 |
|---|---|---|
| 我是否收藏过（`isFavorited`） | 高频 | Redis `Set` + `SISMEMBER`（`user:favoriteSet:{userId}`） |
| 收藏数（`count`） | 高频 | Redis `String`（`content:favoriteCount:{id}`）；miss 回源**主键点查** |
| **收在哪些夹（`folders`）** | **低频** | **直查 DB**，且 `SISMEMBER` 为 false 时**短路** ⇒ **刻意不缓存** |

- ★ **`folders` 为什么不缓存**：它**低频**且"未收藏时短路"⇒ 正常路径下 DB 查询**几乎不发生**；
  给它加缓存要维护 **5 个**失效点，收益只是"省一次**亚毫秒**索引点查"⇒ **不划算**（缓存第一原则：只为"**高频 × 昂贵**"加）。
- ★ **写后失效**：**只有 `add` 做条件写**（单调 ⇒ `SADD` 恒安全），`remove` / `move` / `deleteFolder` **整体失效**、
  计数侧**一律 `DEL`** —— 根因见 `I-11`（夹内唯一 ⇒ 不是"±1"关系）。
- ★ **提交后钩子**：`TransactionSynchronizationManager`（**有同步则登记、无则立即执行**），**不建 `favorite.event`**
  —— `addItem` / `removeItems` 刻意**无事务**，`@TransactionalEventListener` 会**静默丢弃**。
- ★ **`/favorite/status` 短路**（`F-17`，T15）：`isFavorited` 为 false ⇒ 返回 `{false, []}`、**不查库**；
  **miss 走 DB + 回填**；Redis 挂 ⇒ **降级走 DB**。⚠️ 判据是"**移除一个每请求、不自愈的查库口子**"，**不是安全收益**。
- ⚠️ 三态 / 空标记 / 单飞 / 熔断等**机制**见 [缓存与数据一致性](../tech/缓存与数据一致性.md) · [韧性](../tech/韧性.md)。
- ⚠️ **"限流在场"曾是本设计的前提**（§3.7 原话）⇒ **强度已下降**：最尖锐的"匿名 + 17ms 聚合"已由冗余列消除，
  剩余只属"任何登录接口的常态"。限流在本仓**整体缺失** ⇒ `task/FURTHER_ISSUES.md` **F-22**（与 `F-01`/`F-02` 同属"上线准备度"专项）。

---

## 八、容量上限（**软上限**，配置常量）

| 项 | 值（`video.favorite.*`） |
|---|---|
| 默认夹内容上限 | **5000**（`default-folder-max-items`） |
| 自建夹内容上限 | **500**（`custom-folder-max-items`） |
| 自建夹**个数**上限 | **20**（`custom-folder-max-count`）；默认夹不计入 |

- ⚠️ **数字数的是 `favorite_item` 行**（**含失效条目**）—— 与 `FavoriteFolderVO#itemCount`、`/favorite/list` 的 `total` **同一口径**。
- ⚠️ **配置常量、不进 schema**（改数不动代码）；绑定期**校验"必须为正"**（`int` 原生类型键名写错会静默绑成 0 ⇒ 每次新增都 400，故**故意响亮失败**）。
- **超限 ⇒ 400**。判定点 = `createFolder`（个数）· `addItem`（目标夹）· `moveItem`（**目标夹** —— 漏它就能"绕道 move 把夹撑爆"）。
- ★ **顺序口径 —— 按类型分家**：**参数型 400**（夹名空 / 超长 / 格式非法）**先于**归属 403/404（不碰库）；
  **状态型 400**（**容量**）**后于**归属 —— 提前判则"他人夹**满** ⇒ 400 / **未满** ⇒ 403"，成了**可区分他人夹满/未满**的探测口。
- ★ **默认夹懒建不在判定面上**（个数只数自建夹）：否则"夹数满 ⇒ 默认夹建不出来"，用户**再也收藏不了**且**不自愈**（重演 `I-08` 的前端互锁）。
- ★ **它是软上限**（`COUNT → INSERT` **非原子** ⇒ 真并发下可瞬时越界，越界后自限；**读路径不因越界做任何隐藏**）。
  ⚠️ **为什么不加锁**（完整论证见 `FavoriteLimitProperties` 类注释 + `F-23`）：① **RR 快照陷阱**（锁晚于第一条普通读 ⇒ 锁在但不生效、**无报错**）；
  ② **本域无可锁常驻行**（0 个夹时无行可锁；`uk_user_default` 上锁不存在的行 = gap lock，正是明文排除的死锁来源）；
  ③ 要覆盖**全部**写点、且 `createFolder` 现在**没有事务**。⇒ 硬上限正解 = **计数列 + 原子条件 `UPDATE`**（coupon `stock` 形态），**不是**行锁。

---

## 九、稍后再看（独立表 + 独立端点）

- ★ **不共 `favorite_folder`**（这是本项最大的取舍）：共表（哪怕加 `folder_type` 列）的话，
  `isFavorited` / `favorite_count` / 夹列表 / 公开夹 **四条读语义全要加"排除稍后再看"过滤**，**漏一处即静默错答案**
  ⇒ **独立表后收藏侧一行不改**，污染在结构上不可能发生。
- **域归属 = 留在 `favorite` 域内**（复用本域装载契约与失效口径），**不新开域**。
- ★ 三条实测语义（用户 2026-10-14 拍板；用户视角见 [favorite-需求 §六](./favorite-需求.md#六用户看得到的规则)）：

| 语义 | 落地口径 |
|---|---|
| **只能加视频** | `add` / `move` 要求 `type = 1` 且未软删：不存在 / 已软删 ⇒ **404**；存在但非视频 ⇒ **400**「只能添加视频」 |
| **重复添加 ⇒ 移到最前** | `INSERT … ON DUPLICATE KEY UPDATE create_time = CURRENT_TIMESTAMP`（撞 `uk_user_content`）⇒ 不新增行、不改 `id`、只刷时间。排序 `create_time DESC, id DESC` |
| **除清单外无状态面** | ★ **红线**：**不新增** `/watchlater/status`、**不**把"是否在稍后再看"塞进 `interaction` / `/favorite/status` / 任何 `Content*VO` |

- **`add` 容量（**100**，`watch-later-max-items`）**：判据用 `COUNT(*) WHERE user_id = ? AND content_id <> ?`（**排除自身**）
  ⇒ "已在池中再次加入"（移到最前）**已满时仍成功**，只有"加**新**内容且已满"才 400 —— 同 §八 的**状态型 400、软上限**。
- **`remove` = 批量**：★ 同 `G11` 的理由 —— **不校验内容存在性**（失效条目必须删得掉）、幂等（0 行不算错）；空集 ⇒ 400。
- **`move`**：`folderId` **缺省 = 默认夹（懒建）**；实现 = **同事务**"加入目标夹 + 从稍后再看删行"，
  "加入目标夹"**复用 `FavoriteService.addItem`**（已含存在性 / 归属 / 容量 / 计数重算 / 缓存失效四件事）。
  失败面：**非视频 400 / 内容不存在或失效 404** → **目标夹不存在 404 / 非我 403 / 目标夹已满 400** → **目标夹已有同内容 409**。
- **不做缓存**（低频私有读，无热路径需求）。
- ⚠️ **表结构走 V4**（V1–V3 **已应用 ⇒ 逐字节冻结**，见 [数据与Schema演进 §2.4](../tech/数据与Schema演进.md)）。

---

## 十、他人公开夹"点开看内容"（⚠️ 破公开面键集）

- `PublicFavoriteFolderVO` **加 `id`**（一期刻意只给"名称 + 数量"、不含 `id`，本次是**兑现一期预告**）。
- 新端点 `GET /favorite/folder/items?folderId=X`：**匿名可访问**（**逐端点**声明，**绝不**类级 `@RequiresLogin`）。
- ★ **私密夹与不存在的夹同一拒绝口径 = `404`「收藏夹不存在」（同码同文案、不可区分）**
  —— 选 `404` 而非 `403`：本域 `403` 的语义是"**存在但不是我的**"，与"**不公开**"不是一回事；
  **不能**先返回 200 再空列表（那会让"私密"变成**可探测的**）。
- 出参**复用** `PageResult<FavoriteItemVO>`（同 §五 的失效占位形状），**不新造**。
- ★ 夹内装配**共用一份实现**（`assembleFolderItems`）供"我的 / 他人公开"两条路径调用 ⇒ 失效口径不漂移。
- ⚠️ **已接受的代价**：新端点向**匿名者**暴露 `favorite_item` 行的 `id` 与 `favoriteTime`；
  且 `/folder/items` 对任意 `folderId` 构成"**它是不是公开夹**"的**存在性探针** —— 但这是"点开他人公开夹"这一能力的**固有面**（公开夹本就任何人可看）。

---

## 附录：编号速查（**活**的查证入口）

> ★ 本表**每号一行**，是代码 / 测试 / mapper 注释里数百处 `T#` / `R-##` / `I-##` / `G#` 的**活解析入口**。
> **完整论证**（当初为什么这么定、代价与边界）在
> [收藏功能-编号索引.md（归档全文）](../../archive/favorite-2026-10-15/收藏功能-编号索引.md)（**只读追溯**）。
> ⚠️ 纪律：**收编进本表只许一行结论**；理由随正文进 archive / git（防再膨胀）。

**任务 `T#`**（`T1`–`T15` = 收藏一期 / 二期 / 第 3 张清单；`T16`–`T19` = 第 4 张清单；`T20` = 收藏专项收口；`T21` 起 = 第 5 张清单）

| 编号 | 一句话 | 详见 |
|---|---|---|
| T1 | V2 建表 + `favorite` 域骨架 + `ArchitectureTests.DOMAINS` 登记 | `V2__favorite_tables.sql` |
| T2 | 收藏夹 CRUD 四端点；默认夹拒删 **409**、删夹同事务删条目 | §三 |
| T3 | `/folder/update` 部分更新 + `GET /folder/public`（匿名） | §三 |
| T4 | `/favorite/{add,remove,move}`；默认夹懒建；批量移出 = 单条 `DELETE … IN` | §三 |
| T5 | `/favorite/status`（登录）+ `/favorite/count`（匿名）；计数 `COUNT(DISTINCT user_id)` | §四 |
| T6 | `/favorite/list` 倒序分页 + 失效脱敏占位；T6-5 改走装载契约 | §五 |
| T7 | 前端点亮（抽屉 / 弹窗 / 夹页 / 失效占位卡） | `static/static/js/views/` |
| T8 | 契约测试收口（"另一夹 / 另一内容 / 另一人"对照 + 事务永久判别力） | `FavoriteTransactionTests` |
| T9 | 新建 `interaction` 域（单条 + 批量契约）；收藏补 `isFavorited` / `batchIsFavorited` | §六 |
| T10 | 详情互动态**破契约收口**（顶层 `isLiked`/`isFollowed` → 内嵌 `interaction`） | §六 |
| T11 | 收藏缓存层（`FavoriteCache` + `AfterCommit` + `FavoriteCacheProperties`） | §七 |
| T12 | `favorite_count` 冗余列启用（**V3 回填** + 事务内批量重算 + 四写路径） | §四 |
| T13 | 列表互动态形态统一（`R-13`：删顶层、改内嵌、三态、维度常量） | §六 |
| T14 | `/comment/replies` 游标化（`F-20 ②`；破 `page` 契约） | [archive](../../archive/favorite-2026-10-15/收藏功能-编号索引.md) |
| T15 | 缓存层收口 + `/favorite/status` 短路（`F-17`） | §七 |
| T16 | 收藏夹**容量上限**（5000/500/20 + 钉住"允许重名"） | §八 |
| T17 | **稍后再看**（独立表 **V4** + 四端点 + "移入收藏夹"） | §九 |
| T18 | 他人公开夹**点开看内容**（加 `id` + 匿名端点 + 私密/不存在同 404） | §十 |
| T19 | 稍后再看**前端**（抽屉入口 + 清单页 + 详情页按钮） | `static/static/js/views/watchlater.js` |
| T20 | **收藏专项收口**：四份设计文档**物理归档** + 设计沉淀进本目录 + 引用清扫 | `archive/favorite-2026-10-15/`（归档 README） |
| T21 | 评论主楼读侧**区间读**（`F-20 ①`：`LRANGE 0 -1` 全量读 → 本页区间；基线 p1 热 51ms→10ms） | `CommentCache#getRootPage`（T21 段） |

**决策 `R-##`**

| 编号 | 一句话 | 详见 |
|---|---|---|
| R-01 | 默认夹**懒建**（首次收藏时）；夹列表必须能返回空态 | §二 |
| R-02 | 删自建夹时夹内条目**一并删除** | §四 |
| R-03 | 失效内容**占位保留并标已失效**，不自动清理 | §五 |
| R-04 | 已失效内容的**媒体一期不拦**（静态直出，属内容域议题） | `F-10` |
| R-05 | 公开 / 私密**一期就给**（`is_private` 建表即带，零成本） | §二 |
| R-06 | `favorite_count` 一期**实时 `COUNT`**、列只建不用（现成验收 oracle） | §四 |
| R-07 | 失效条目：**计入**视频数 / **可移出、不可移入或移动** / 收藏数仍算 | §五 |
| R-08 | 「私密」的可观察落点 = **独立端点** `GET /favorite/folder/public?userId=X` | §三 |
| R-09 | 默认夹唯一 = **`default_uniq` VIRTUAL 生成列 + `uk_user_default`** | §二 |
| R-10 | 详情互动态 = **内嵌 `interaction` 子对象**（**不拆接口**；匿名 ⇒ `null`） | §六 |
| R-11 | 失效口径「**看不了 ≈ 失效**」（四类统一折叠，走装载契约） | §五 |
| R-12 | 第 3 张清单方向 = **跨域性能与契约收口**（限流拆出） | `T13`–`T15` |
| R-13 | 列表侧互动态**形态统一**（原子：形态 + 三态 + 选维度） | §六 |
| R-14 | 第 4 张清单方向 = **收藏三期（周边）** | `T16`–`T19` |

**问题 `I-##`**

| 编号 | 一句话 | 详见 |
|---|---|---|
| I-01 | 骨架缺"每用户至多一个默认夹"唯一键 ⇒ `default_uniq` 生成列 + `uk_user_default` | §二 |
| I-02 | 结构锚点只断 `flyway_schema_history` **首行** ⇒ 改为断言**全部版本行** | `InfrastructureConnectivityTests` |
| I-03 | `Envelope.str` 走 `data.<field>` ⇒ 对**字符串型 data** 恒 null；改 `Envelope.data().asString()` | `support/Envelope` |
| I-04 | `deleteFolder` 的 `@Transactional` **零判别力** ⇒ 打桩"**最后一步**"获永久判别力 | `FavoriteTransactionTests` |
| I-05 | 注入脚本 `git checkout --` 会连**未提交改动**一起抹 ⇒ **先 commit 再注入** | `AGENTS.md`「测试纪律」 |
| I-06 | "无 `content_id` 索引 ⇒ 全表扫"被实测**推翻**（实为 loose index scan） | §四 |
| I-07 | 曾持 content 封面规则副本 + 跨域直连他域表 ⇒ T6-5 走装载契约根治 | §五 |
| I-08 | 弹窗"必选夹" × 默认夹**只能懒建** 互锁 ⇒ 补"默认收藏夹"占位行 | `static/static/js/views/detail.js` |
| I-09 | `ProfileService` 内联点赞批量 ⇒ 结转 `F-16`（**T13 核销**） | `T13` |
| I-10 | Bean 环 `content → interaction → favorite → content` **只被 `@Lazy` 断、没消** | `F-18` |
| I-11 | 四写点"条件写缓存"在 `uk_folder_content` 下**不成立** ⇒ `AfterCommit` + **只有 add 条件写** | §七 |
| I-12 | T12"三条写路径"**少列 `move`**（等价新增收藏，不变量被 API 可达路径打破） | §四 |
| I-13 | 任务书 T13 口径**四处自相矛盾** ⇒ 按 B站 实际展示面拍板 | `R-13` |
| I-14 | `FollowCacheTests` 全量偶发红（**别域消费者**进验证窗口）⇒ **结转 `F-24`** | `F-24` |

**运行约定 `G#`**

| 编号 | 一句话 | 现状 |
|---|---|---|
| G7 | 一期**不许写缓存、不许做冗余计数增量维护** | ✅ 二期已解除（T11 / T12） |
| G9 | `favorite` 域**必须登记** `ArchitectureTests.DOMAINS`（否则规则是**永远绿的假绿**） | ✅ 已落地 |
| G10 | 🚫 **收藏不得接 feed**（不发 `ContentPublishedEvent`、不调 `FeedDelivery`） | **长期红线**（无实现可去掉 ⇒ 靠 review） |
| G11 | ★ `/favorite/remove` **不得校验内容存在性**（否则失效条目**永远清不掉**） | **长期红线**（§五） |
| G12 | ★ 夹内列表**分页按 `favorite_item` 行**（不是按"内容"） | **长期红线**（§五） |
| G8 | 一期测试只断**可观察行为**、二期换实现时**测试不许改** | 跨期元约定（例外仅 `R-10` / `I-12`） |
| G13 | 清单只给方向与红线、**不写死实现步骤** | 跨期元约定 |
| G14 | 每个任务**显式标注「可质疑」** | 跨期元约定 |

> ★ `G1`–`G6` / `C-1`–`C-6` / `G8` / `G13` / `G14` 是**跨期元约定**（任务与需求的管理纪律），
> 定义在 `task/CURRENT_TASKS.md`（运行约定）与 `task/CURRENT_NEEDS.md`（通用约定），**不属本域**。

---

## 边界与去向（机制外链）

| 不在本篇的内容 | 去向 |
|---|---|
| 用户看得到的行为与规则 | [favorite-需求](./favorite-需求.md)（**唯一事实源**） |
| **本域历史论证**（逐条编号的取舍、实测明细） | [archive/favorite-2026-10-15/](../../archive/favorite-2026-10-15/)（**只读追溯**） |
| 缓存三态 / 空标记 / key 命名 / 写后失效 | [缓存与数据一致性](../tech/缓存与数据一致性.md) |
| 熔断 / 单飞 / 降级 | [韧性](../tech/韧性.md) |
| 事务边界怎么切、`@Transactional` 写法 | [事务边界](../tech/事务边界.md) |
| 包结构与跨域依赖、ArchUnit 规则 | [模块边界与依赖](../tech/模块边界与依赖.md) |
| Flyway 策略、V1 冻结、V2–V4 怎么写 | [数据与Schema演进](../tech/数据与Schema演进.md) |
| 端点是否需要登录、401/403 语义 | [安全与鉴权](../tech/安全与鉴权.md) |
| 收藏要不要发事件、谁订阅 | [领域事件与解耦](../tech/领域事件与解耦.md) |
| 本期做哪些 / 任务拆解 / 未排期的债 | `task/CURRENT_NEEDS.md` · `task/CURRENT_TASKS.md` · `task/FURTHER_ISSUES.md` |
