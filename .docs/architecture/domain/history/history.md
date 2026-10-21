# 播放记录（history）域

> 定位（先读这一句）：本域记录**谁把哪个视频看到哪了**，并在**首次播放**时给内容累计一次**播放量**。
> 本篇只讲本域的**数据模型 / 端点契约 / 写读路径 / 口径 / 边界**；**用户可见规则**另篇
> （[history-需求](./history-需求.md)）。
> **不展开**横切机制（缓存三态 / 事务 / 鉴权 / 限流 / 事件），去向见文末
> [§边界与去向](#边界与去向机制外链)。
>
> 📌 **落点纪律**：本域**开工就建**本篇与需求篇 —— 它们是**活的事实源**（执行中随代码改、
> 收口时即终态）；**执行态**在 `task/CURRENT_TASKS.md` 的 `T#` 回写里；**过程物**（设计讨论 /
> 方案比较）走 git，不进本篇。
> ★ **代码注释只许引用本篇路径 + `类#方法`**，**不得引用 `task/` 登记台**（登记台每期重建 ⇒
> 引用即悬空），也不得引用任何草稿文件。

---

## 一、范围

| 做 | 不做 |
|---|---|
| 进度上报（`/history/report`） | 历史删除 / 清空 |
| 播放事件（`/history/play`，去重计播放量） | 前端页面（`static/` 本期不动） |
| 历史列表（`/history/list`，失效占位） | 历史列表缓存 |
| 续播位置（`/history/position`） | 服务端进度节流（心跳节流由客户端负责） |
| mp4 时长解析（发布 / 换源） | 「已看完」标记（由前端按 `progress / duration` 推导） |
| 详情播放量（第 5 个公共计数） | 历史容量上限 / 旧行清理 |
| 失效内容占位（抄收藏域形态） | 存量内容时长回填 |

---

## 二、数据模型（Flyway **V6**；遗留表清理 **V7**）

```sql
-- V6：播放记录
ALTER TABLE `content_media`
  ADD COLUMN `duration_seconds` int DEFAULT NULL
  COMMENT '视频时长（秒；仅 type=1，mp4 mvhd 解析；失败/图文为 NULL）';

ALTER TABLE `content`
  ADD COLUMN `play_count` int unsigned NOT NULL DEFAULT 0
  COMMENT '播放量（按用户去重，一人一次；只增）';

CREATE TABLE IF NOT EXISTS `watch_history` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `content_id` bigint NOT NULL,
  `progress_seconds` int NOT NULL DEFAULT 0 COMMENT '上次进度（秒）；0=刚打开',
  `play_counted` tinyint NOT NULL DEFAULT 0 COMMENT '播放量是否已计（一人一次的去重标记）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次观看时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后上报时间（列表排序键）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_content` (`user_id`,`content_id`) COMMENT '一人一内容一行：重复上报只更新，不追加',
  KEY `idx_user_update` (`user_id`,`update_time`) COMMENT '历史列表按最后观看倒序',
  KEY `idx_content_id` (`content_id`) COMMENT '不变量复算 / 按内容清理的候选索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='播放记录（一人一内容一行）';
```

```sql
-- V7：清理 TV 遗留表（src/ 零引用，已核；删除不可逆 ⇒ 单独一版）
DROP TABLE IF EXISTS `videoinfo`;
DROP TABLE IF EXISTS `video`;
```

> ⚠️ **迁移锚点同步**：V6/V7 会让 `InfrastructureConnectivityTests`（表数 **20 → 19**、版本
> `1..5` → `1..7`）与 `WatchLaterSchemaTests#全部迁移版本都被应用`（`endsWith("5")` → `"7"`）变红，
> **必须同 commit 改**。这不是回归，而是把"新版本真的被应用 / 遗留表真的被删"变成一次人工认账。
> `RateLimitWeightTableTests.EXPECTED_NON_GET_AT_LEAST`（36 → 38）在**加端点时**同步。

**口径**：

| # | 口径 | 理由 |
|---|---|---|
| 1 | 一人一内容**一行**（`uk_user_content`） | 重复上报只更新；列表里不出现同一视频两条 |
| 2 | `progress_seconds` 单位**秒** | 客户端只传秒；不引入毫秒/时间戳 |
| 3 | **不存 duration 快照** | 时长真相只有 `content_media.duration_seconds` 一份；历史读当前值 |
| 4 | `play_counted` 与 `progress` **解耦** | `/report` 只改进度，`/play` 只改标记（§四） |
| 5 | `update_time` 必须**显式** `SET ... = CURRENT_TIMESTAMP` | `ON UPDATE` 在"值没变"时不触发 ⇒ 重复上报同一秒不会移到最前 |
| 6 | `idx_content_id` 不是运行时读路径 | 只服务不变量复算（`play_count == COUNT(*)`）与将来的清理工具 |

> ⚠️ `play_count` 是 `unsigned` 且**只增**（没有取消播放）⇒ 不需要 T34 那套
> `CAST(... AS SIGNED)` 下溢守卫。这是口径差异、不是漏改。

---

## 三、端点契约

写 = `POST` + `x-www-form-urlencoded`，读 = `GET` + query（沿袭 `/like/*`、`/watchlater/*`）。
`HistoryController` **类级 `@RequiresLogin`**：四个端点都只对当前登录用户有意义。

| 端点 | 入参 | 出参（`data`） | 说明 |
|---|---|---|---|
| `POST /history/report` | `contentId`、`progress`（秒，整数） | 无（`msg="success"`） | 一条 ODKU；后到即新（LWW）；按当前时长 clamp |
| `POST /history/play` | `contentId` | 无（`msg="success"`） | 首播去重后 `play_count + 1`；行不存在则建行（progress=0） |
| `GET /history/list` | `page`、`pageSize` | `PageResult<HistoryItemVO>` | 按 `update_time DESC, content_id DESC`；缺省 20 / 上限 100 |
| `GET /history/position` | `contentId` | `{ "progress": 123 }` | 无记录 ⇒ `0`；**不校验内容、不返回 404** |

**`HistoryItemVO`**（JSON 形状，扁平，同 `WatchLaterItemVO` 的形态）：

```json
{"contentId":3,"watchedTime":"2026-10-21T00:00:00","invalid":false,
 "title":"标题","coverUrl":"/upload/cover/x.png","authorName":"作者",
 "progress":123,"durationSeconds":600}
```

- 正常行：`invalid=false`，字段从 `ContentService#loadContentVOs` 抄出；`durationSeconds` 同源。
- 失效行：`invalid=true`、`title="内容已失效"`、`coverUrl/authorName/durationSeconds=null`、
  **`progress` 保留**（历史数据仍在）。判据 = 装载契约"取不到"（不存在 / 已删 / 媒体损坏）。
- `watchedTime` = `watch_history.update_time`（最后上报时间），不是首次观看时间。
- ★ 本类**一个形状两个角色**（同 `WatchLaterItemVO`）：Mapper 行类型只填
  `contentId / watchedTime / progress`；其余字段由 Service 组装。`invalid` **必须用包装
  `Boolean`**（`boolean` 会被 Lombok 生成 `isInvalid()`，埋 JSON 键名漂移的坑）。
- `position` 只给进度：时长已随详情 `durationSeconds` 返回，不重复读一次内容。

**限流权重**：`[POST /history/report] = 1`、`[POST /history/play] = 1`
（**有意低于**"其余非 GET 写 3"：上报是设计上最高频的写；无心跳前提。推翻 = 接前端心跳时重估）。

> 📌 **交付进度（2026-10-21，T49 落地后）**：上表**四个端点全部已在代码里**（写路径 T47、读面 T49）。
> 四个端点共用一个类级 `@RequiresLogin`；`SecurityContractTests#history写端点需登录` 已钉住
> `/report`、`/play`、`/list`、`/position` 四条路由（`I-23` 核销）。
> ⚠️ 判别力边界：本仓默认拒绝 ⇒ 该用例只钉"路由落到了 `@Controller` 处理器上"，
> **不证明**类级注解在位；HTTP 侧 401 是假绿（`@CurrentUserId` 解析器兜出）。

---

## 四、写路径

### 4.1 `/history/report`：一条 ODKU，**不加事务**

```sql
INSERT INTO watch_history (user_id, content_id, progress_seconds)
VALUES (?, ?, ?)
ON DUPLICATE KEY UPDATE
  progress_seconds = VALUES(progress_seconds),
  update_time = CURRENT_TIMESTAMP;
```

校验顺序（参数形态 → 存在性 → 类型）：

| # | 判据 | 拒绝 |
|---|---|---|
| 1 | `progress < 0` | 400「播放进度不能为负数」 |
| 2 | 内容不存在 / 已软删 / 媒体损坏 | 404「内容不存在」 |
| 3 | `type != 1`（图文） | 400「只能记录视频的播放进度」 |

- 校验复用 `ContentService#loadContentVOs(List.of(contentId))`：**返回空列表** ⇒ 404；`type != 1` ⇒ 400；
  顺带拿 `durationSeconds` 做 clamp（`progress = min(progress, duration)`；时长 null 则不 clamp）。
  **不新增 content 域方法、不新增 DAO 读边。**
- ★ **不在这里计播放量** ⇒ ODKU 的 affected-rows 歧义（Connector/J 默认
  `useAffectedRows=false` ⇒ "插入"与"值没变的更新"都返回 1）**不构成问题**——这是拆端点的最大收益。
- **不做"进度只能增"的守卫**：后到即新。重看从头会把进度改回 0（与 B站 不同，见
  [history-需求](./history-需求.md) §六）。

### 4.2 `/history/play`：首播判定，`@Transactional`

```sql
-- ① 行不存在：直接建行并标记已计
INSERT IGNORE INTO watch_history (user_id, content_id, progress_seconds, play_counted)
VALUES (?, ?, 0, 1);
-- affected = 1 ⇒ 首播；= 0 ⇒ 行已存在，走 ②

-- ② 行已存在：条件抢占标记
UPDATE watch_history SET play_counted = 1
 WHERE user_id = ? AND content_id = ? AND play_counted = 0;
-- affected = 1 ⇒ 首播；= 0 ⇒ 已计过，直接 200

-- ③ 首播分支（与 ①/② 同一事务）
UPDATE content SET play_count = play_count + 1 WHERE id = ?;
-- ④ 发布 PlayCountChangedEvent(contentId)（AFTER_COMMIT 更新 content:playCount）
```

- ★ **不用 `ON DUPLICATE KEY UPDATE` 判首播**：`useAffectedRows=false` 下"插入"与"值没变的更新"
  都返回 1 ⇒ 会把重复播放误判成首播。`INSERT IGNORE` 的 `1/0` 无歧义（与 coin 的
  `insertIgnoreZero` 同形）。
- 并发：两个首播请求，①或②只有一个 `affected=1` ⇒ 播放量恰 `+1`。
- 锁序：先 `watch_history` 后 `content`；**内容删除不碰 `watch_history`**（无反向锁序）。
  理论上仍有"`INSERT IGNORE` 共享锁 → 条件 `UPDATE` 排他锁"的 1213 窗口
  （同 `coin.md` §九 #5，未复现）。
- `/play` 在行不存在时**建行（progress=0）**：否则"先 play 后 report"会丢播放量；
  历史里出现一条 00:00 的记录是预期行为。
- `/play` 是**唯一**计播放量入口；`/report` 不计数（用户 2026-10-21 拍板）。
  播放事件丢失 ⇒ 本次不计，下次观看会再发；**只看一次且丢包 ⇒ 永久少计**（§十 #2）。

### 4.3 事务边界

| 端点 | 事务 | 理由 |
|---|---|---|
| `/report` | **不加** | 单条 ODKU 自带原子性（[事务边界](../../tech/transaction/事务边界.md) §一：单写不加事务） |
| `/play` | **加** | 标记位 + `content.play_count` 必须同进同退；否则不变量没有自愈路径 |

---

## 五、播放量（content 域自持，**无新增跨域读边**）

| 件 | 形态 |
|---|---|
| 列 | `content.play_count`（`unsigned`，只增；内容删除**不归零**） |
| 缓存 key | `content:playCount:{id}`（content 域自持，同 `content:commentCount`） |
| 详情读 | T44 计数聚合 **4 键 → 5 键**：`ContentCountColumns` 加 `playCount`、`ContentDao#findCountColumns` 加列、`ContentCountAggregate` 的解析 / 合并 / 回填各加一位。⚠️ record 组件**必须用包装 `Long`**（MyBatis `<constructor>` 按 javaType 反射，原生 `long` 会 `NoSuchMethodException` ⇒ 详情 500，T44-2 实测） |
| 回填 | content 域内 `ContentCountCache#backfillPlayCount`（**不经互动域**——键是自己的） |
| 失效 | `content.event.PlayCountChangedListener` 订阅 `history.event.PlayCountChangedEvent`，**条件 `INCRBY 1`**（冷 key 不建，同 coin 的 `content:coinCount`） |
| 不变量 | `content.play_count == COUNT(*) FROM watch_history WHERE content_id = ? AND play_counted = 1` |

- **只在详情平铺**（`ContentDetailVO.playCount`），**不进列表**（T43 口径）、**不进 `ContentCacheDTO`**。
- 图文帖 `play_count` 恒 0（没有 `/play` 路径能通过类型校验）。
- 内容删除：详情 404 在聚合之前短路；`play_count` 与历史行都**不动**（失效占位要它）；
  `content:playCount:{id}` 在 `ContentCache#removeContent` 里**一并失效**（同 `commentCount` 的孤儿键口径
  —— 键删了也不会再回填，因为详情 404 在聚合之前）。
- 不新增 `content → history` 边：详情不反查历史，播放量走"列 + 自己的 key"。

> 📌 **交付状态（2026-10-21，T48 落地后）**：上表**全部已在代码里** ——
> 写侧 = `content.play_count` 列 + `HistoryService#play` 的首播 `+1` + `PlayCountChangedEvent`（T47）；
> 读侧 = 5 键聚合（`ContentCountColumns` 第 5 组件 `Long playCount`）+ 本域 `backfillPlayCount` +
> `content.event.PlayCountChangedListener` 的条件 `INCRBY 1`（`AFTER_COMMIT`）+ 详情 `ContentDetailVO.playCount`
> + `ContentCache#removeContent` 一并失效（T48）。**写路径没有为读面改过一行**。
> 断言口径见 `ContentPlayCountTests`（含"冷 key 不写半套""回滚不污染""删除后 404 不回填"
> 与 `UploadDurationTests` 的详情 19 键精确集）。

---

## 六、时长（upload 域解析，内容载荷投影）

### 6.1 `Mp4DurationReader`（`upload/service`，纯函数）

用 `RandomAccessFile` **只读 box 头**，不整文件读入内存：

1. 顺序读 `size(4) + type(4)`；
   - `size == 1` ⇒ 再读 8B `largesize`；
   - `size == 0` ⇒ 本 box 到 EOF；
   - `mdat` 用 `seek` 跳过（moov 可能在文件尾，非 faststart）。
2. 在 `moov` 内找 `mvhd`（下列偏移均相对 **`mvhd` 载荷起点**，即跳过 8B box 头）：
   - version 0 ⇒ `timescale@12`、`duration@16`（各 4B）；
   - version 1 ⇒ `timescale@20`、`duration@24`（4B / 8B）。
3. `timescale == 0`、`duration` 全 F（未知）、无 `moov`、截断、非 mp4 ⇒ `OptionalInt.empty()`。
4. 返回值 = `duration / timescale`（向下取整秒）。

### 6.2 两个调用点（**漏一处就是显式不一致**）

| 调用点 | 行为 |
|---|---|
| `POST /api/upload/video` | 落盘后解析；发布事务里写 `content_media.duration_seconds` |
| `POST /api/upload/replace`（`type=1`） | 换源时重解析并**同一事务**更新该媒体行的时长；`type=2/3` 传 null，**不能清空视频行的时长** |

- 解析失败 ⇒ `null`，**不阻断发布**（内容仍可播，只是没有百分比）。
- **存量 257 条不回填**：时长 null = 未知；进度按秒展示。
- 归属：解析器在 **upload 域**（它持有"上传文件的形状"与 `FileUploadService#resolveAbsolutePath`）；
  content 只收 `durationSeconds` 参数，**不反向依赖 upload**。

### 6.3 载荷投影（**三个落点，漏一个就恒 null**）

- `ContentCacheDTO` 加 `durationSeconds`（JSON 键同名，**所有内容响应新增一个键**，用户 2026-10-21 同意）。
- `ContentCache#buildContentMedia` 在 `type=1` 分支从**已有的视频行**取时长写入 DTO
  —— **零额外查询**（媒体行本来就要装配）。
- ★ `ContentCache#copyCommon` **必须同步**：`toContentVO` / `toDetailVO` 都只走这一次手写字段拷贝
  （`ContentCache.java` 的 `copyCommon`）。**只改 DTO 不改这里 ⇒ 详情/列表的 `durationSeconds` 恒 null**，
  而 DB 行断言仍然全绿（最隐蔽的假绿）。
- ⚠️ 老缓存条目（部署前写入）缺该键 ⇒ TTL 内为 `null`，到期自愈；**不做批量刷缓存**。
- 媒体行 = **1 个 resultMap + 5 条 select**（`findMediaByContentId` / `findMediaByContentIds` /
  `findMediaByContentTypeSort` / `findAllMedia` / `findMediaById`）+ 实体 + `addMedia` / `updateMediaUrl`；
  **漏一条 select 就是静默 null**（§十一 的测试钉住）。
- ★ 仓内**原本没有**内容 JSON 的精确键集断言 ⇒ 本域开工时要补一条：新响应**含** `durationSeconds`、
  且不抖落既有键（`ContentReadTests` 的 `keysOf` 只用于 `interaction`，不能当整体键集证据）。
  **已补（T46）**：`UploadDurationTests#详情键集含durationSeconds且不抖落既有键`（详情 18 键精确集会，
  逐字列出）、`#列表也带时长`（列表面也带该键 —— 它是慢变字段，不受 T43"列表零计数"约束）。
  反向验证实测：抽掉 `copyCommon` 那一行 ⇒ 回显 3 红而 **DB 断言仍绿**（就地看到这条假绿）。

---

## 七、读路径

### 7.1 历史列表

1. 一页 `watch_history`（`idx_user_update`，`update_time DESC, content_id DESC`）+ 一次 `count`；
2. `ContentService#loadContentVOs` 一趟批量取展示字段；
3. 取不到的条目构造**失效占位**（`total` 不变、页内条目不缩水）。

**不缓存**：私有 + 写多读少 + 每次上报都变 ⇒ 缓存必然"写后就废"。这是**刻意不做**；
推翻 = 出现真实读放大（本仓无流量，见 `AGENTS.md`「项目定位与优化口径」）。

### 7.2 续播位置

一条 `uk_user_content` 点查；无行 ⇒ `progress=0`。不校验内容、不返回 404
（内容是死是活由详情的 404 负责；这里只回答"我的进度是多少"）。

> 📌 **已交付（T49）**：§7.1 / §7.2 两条读路径均已落地（`HistoryService#list` / `#position`）。
> 顺序与 `total` 由 `HistoryListTests` 用**独立 oracle** 复算（排序夹具让 `update_time` 序与
> `content_id` 序**反向**、同秒用例让行 id 序与内容 id 序**反向**）；占位三字段、
> "已删内容仍答进度"由同批用例钉住。列表**不缓存**（§7.1 的口径未变）。

### 7.3 详情

`ContentDetailVO` 加 `playCount`（第 5 个公共计数），由 `ContentCountAggregate` 一趟提供；
`durationSeconds` 随父类 `ContentCacheDTO` 返回。详情路径的 Redis / DB 趟数不变
（仍是 2 趟 Redis + 冷内容 1 条列查）。

**已交付（T48）**：`playCount` 与赞/评/藏/币同批从聚合层返回，**列表不带**（T43 口径）；
详情 JSON 的精确键集由 `UploadDurationTests#详情键集含durationSeconds且不抖落既有键` 钉住（19 键）。

---

## 八、错误码

| 码 | 触发 | 文案 |
|---|---|---|
| 400 | `progress < 0` | 播放进度不能为负数 |
| 400 | 图文帖上报 / 播放 | 只能记录视频的播放进度 |
| 404 | 内容不存在 / 已软删 / 媒体损坏 | 内容不存在 |
| 401 | 未登录 | （鉴权链兜底） |
| 200 | `/play` 重复上报 | 幂等，不报错、不重复计数 |

★ **"媒体损坏"只有一个判据**：`type=1` **视频媒体行缺失**（`ContentCache#buildContentMedia` 返回 false）。
**不是** `durationSeconds == null`（解析失败仍可播），也**不是** `content_media.file_exists = 0`
（扫描标记缺失不等于不能播）——把这两者当 404 会把正常视频判死。

---

## 九、依赖边与登记

| 边 | 形态 | 依据 |
|---|---|---|
| `history → content.service.ContentService#loadContentVOs` | 写路径的 404 / 400 / 时长来源 | 《模块边界与依赖》§三 合法 Service 契约（先例 favorite / feed） |
| `history → content.dao.ContentDao#updatePlayCount` | 首播时同事务 `+1` | 《模块边界与依赖》§三「同事务改计数 ⇒ DAO 薄依赖」（先例 like / comment / coin） |
| `content.event.PlayCountChangedListener` ← `history.event.PlayCountChangedEvent` | 提交后条件 `INCRBY 1` | 规则 3：订阅落在**自己**的 `event` 包 |
| `history.dao.WatchHistoryDao` | 自有表 | — |

- **没有 `content → history` 边** ⇒ 不新增装配期环、不需要 `@Lazy`。
- **开工时须同步登记**：`ArchitectureTests.DOMAINS` 加 `"history"`；
  《模块边界与依赖》§一 域名清单 + §六 补 `content ⇄ history` 一行（只有单向）。
  不登记 = 新域是规则空洞 / 无声绕开判据。

---

## 十、已知代价

| # | 代价 | 边界 / 推翻条件 |
|---|---|---|
| 1 | **重看从头会把进度改回 0**；客户端重试的旧包后到会倒退 | 用户 2026-10-21 拍板"先到即旧、后到即新"（LWW）。B站 不会丢更靠后的进度 ⇒ **刻意偏离**。推翻 = 客户端带单调序号，或服务端加"只增"守卫 |
| 2 | **播放事件丢失 ⇒ 本次不计；只看一次且丢包 ⇒ 永久少计** | 一人一次口径下无法区分"没播过"与"事件丢了"。推翻 = 让 `/report` 在 `play_counted=0` 时补计（代价：每次上报多一条条件 UPDATE） |
| 3 | **`/play` 与 `/report` 解耦**：只发 play 不报进度 ⇒ 行停在 0 秒；只报进度不发 play ⇒ 不计播放量 | 前端必须两个都发；无前端期由测试与文档保证。推翻 = 合并端点为单入口 |
| 4 | 换源后 `duration_seconds` 变化 ⇒ 旧进度可能大于新时长，比例失真 | 写入时 clamp；**读时不 clamp**（不改存储值）。下次上报即修正。推翻 = 出现大量换源 |
| 5 | 老缓存条目在 TTL 内 `durationSeconds=null` | 部署后最长一个 `contentTtl`（15m）自愈；不刷缓存 |
| 6 | 存量内容时长**永不回填**（null） | 进度按秒展示、无百分比；推翻 = 写一次性回填工具（读 media 文件） |
| 7 | 历史**不可删** ⇒ 行无界增长 | 一人一内容一行，增长上限 = 用户 × 看过的内容；无清理任务。推翻 = 需要删除时先重定播放量口径（B站 删历史不减播放量） |
| 8 | 失效占位保留 `progress`（页面仍显示"看到 X 秒"） | 历史数据是真实的；前端可选择不渲染。推翻 = 产品要求占位一律置空 |
| 9 | 首播的理论 1213 死锁窗口（`INSERT IGNORE` → 条件 `UPDATE`） | 与 coin 同族，未复现；真实并发出现 ⇒ 改 ODKU 或重试 |
| 10 | 列表 offset 翻页在上报并发下会重复/漏 | 排序键 `update_time` 会被上报改写；B站 用游标。本仓沿用 `PageResult`（跟随主流）。推翻 = 历史翻页成为主路径 |
| 11 | 播放量**只在详情**，列表卡片没有 | T43「列表零计数」口径；B站 卡片有播放量 ⇒ 这是**本仓口径**，不是遗漏 |
| 12 | ★ `INSERT IGNORE` 会把**非唯一键的错误**（列截断 / 未来的 CHECK 或第二条唯一键）也降级成 warning ⇒ `affected=0` 被一律读作"已计过"，那一次播放量被**静默丢弃** | 前提：`watch_history` **只有 `uk_user_content` 一个唯一键**、且 `user_id` / `content_id` 是 `bigint`、`progress_seconds` 恒 0 ⇒ 当前不可能触发（T47 代码复核结论）。推翻 = 给本表加约束 / 加第二个唯一键 / 改写入列宽 ⇒ 届时应改成"先查后插"或 `ON DUPLICATE KEY` + 显式判别，别继续靠 `IGNORE` 的 0 |

---

## 十一、测试口径（钉住什么）

**契约（HTTP）**（落点：`history/HistoryWriteTests`，2026-10-21 交付 10 例）：
- `/report`：首次建行；低进度覆盖高进度（**LWW 的判别力**，不是"只增"）；超过时长被 clamp；
  图文 400、已删 404、匿名 401；`progress=-1` 400；★ **重复上报同一进度仍刷新 `update_time`**
  （把该行拨回 2020 再上报 —— 不显式 `SET` 时列上的 `ON UPDATE` 不触发，这条是唯一能钉住口径 5 的断言）。
- `/play`：首播 `play_count + 1`；重复 play 不变；**并发两次恰 +1**（`INSERT IGNORE` + 条件 UPDATE
  的判别力）；行不存在时建行（progress=0）；★ **被拒请求一行都不许落**（不是"先写再校验"）。
- `/list`、`/position`（T49 交付，`HistoryListTests` 14 例 + `HistoryPositionTests` 6 例）：
  列表顺序 / `total` 用**独立 oracle** 复算；占位三字段 + `progress` 保留 + 页内条数不缩水；
  续播"无记录 ⇒ 0 / 已删仍答 / 不存在 ⇒ 0"。两个读端点已补进
  `SecurityContractTests#history写端点需登录`（`I-23` 核销）。
- `@RequiresLogin` 的判别力**只在** `SecurityContractTests`（HTTP 侧 401 是假绿）。

**DB 终态 / 独立 oracle**：
`content.play_count == SELECT COUNT(*) FROM watch_history WHERE content_id=? AND play_counted=1`；
`watch_history` 的 `(user_id, content_id)` 唯一（`HistorySchemaTests#同一用户同一内容撞唯一键`，
**真插两行**看它拦不拦）。

**时长**（`Mp4DurationReaderTests` 15 例 + `UploadDurationTests` 8 例）：
`Mp4DurationReaderTests` 用**手搓字节**（moov 在前 / 在尾、v0 / v1 64 位、`size==1`、`timescale=0`、
截断、非 mp4）；`UploadDurationTests` 上传一个手搓 mp4 ⇒ `content_media.duration_seconds` 落库 + 详情回显
（**两段都断**：只断 DB 会漏掉 `copyCommon` 漏搬的假绿）；
换源 type=1 刷新时长（含"换成坏文件 ⇒ 回到 null"）、type=3 不清空视频时长；解析失败仍 200。
★ **内容 JSON 键集**：`UploadDurationTests#详情键集含durationSeconds且不抖落既有键`（**19 键**精确集；
T48 加 `playCount` —— 契约跟随本篇 §五「第 5 个公共计数」，不是迎合）。

**迁移锚点**：表数 20→19、版本 `1..7`、`endsWith("7")`、非 GET 端点 36→38（**迁移 / 加端点时各自同步**）。

**计数聚合**：5 键 MGET / 有选择回填 / 冷 key 不建（`content:playCount`）/ Redis 降级。
⚠️ 跨域缓存断言用 `StringRedisTemplate` + `CacheKeys`，**不要 import `content.cache`**
（`ArchUnit` 规则 2 会让测试红；本仓既有测试同款手法）。

**反向验证（2026-10-21 逐条实测登记）**：
- `addMedia`/select 不带 `duration_seconds` ⇒ `UploadDurationTests` 4 红；
- 抽掉 `copyCommon` 的 `setDurationSeconds` ⇒ **3 红（回显/列表），而 DB 断言仍绿** ⇒ 证明"只断 DB"确实是假绿；
- `replaceMedia` 不调 `updateMediaDuration` ⇒ 2 红（换源刷新 / 换源成坏文件）；
- `/report` 的 ODKU 改 `GREATEST` ⇒ LWW 红；去掉显式 `update_time` ⇒ "重复上报刷新时间"红；
- 去掉 clamp ⇒ 截断红；去掉 `play_counted = 0` 条件 ⇒ 重复 / 并发 / 不变量 4 红；
- 抽走 V7 迁移 ⇒ `HistorySchemaTests` 2 红（⚠️ 必须同时清 `target/classes` 副本，否则读到旧资源、假绿）；
- 去掉 V6 的 `uk_user_content` ⇒ 唯一键 + 索引全集 2 红。

---

## 附录：编号速查（**活**的查证入口）

> 本表**每号一行**；未完成任务不收编（滚动见 `task/CURRENT_TASKS.md`）。逐条论证走 git。

| 编号 | 一句话 | 详见 |
|---|---|---|
| R-18 | 播放记录专项拍板：**做播放量**（一人一次，冗余列 + 独立 key + 详情第 5 计数）/ **进度与播放事件拆两端点** / 历史**不可删** / 失效占位 / 时长存 `content_media` + `ContentCacheDTO` 投影 / V7 删遗留 `video`·`videoinfo` | 本篇 · [需求篇](./history-需求.md) |

---

## 边界与去向（机制外链）

| 不在本篇的内容 | 去向 |
|---|---|
| **用户可见规则**（看到什么 / 重看怎么算 / 播放量口径） | 同目录 [history-需求](./history-需求.md) |
| 缓存三态 / 空标记 / 写后失效 / 单飞 | [缓存与数据一致性](../../tech/cache/缓存与数据一致性.md) |
| 事务切分、载体独立 bean、`AFTER_COMMIT` | [事务边界](../../tech/transaction/事务边界.md) · [领域事件与解耦](../../tech/events/领域事件与解耦.md) |
| 鉴权链（`@RequiresLogin` / 默认拒绝） | [安全与鉴权](../../tech/security/安全与鉴权.md) |
| 端点权重表（新增非 GET 端点必须登记） | [限流](../../tech/rate-limit/限流.md) |
| 跨域边与 ArchUnit 规则 | [模块边界与依赖](../../tech/module-boundary/模块边界与依赖.md) |
| schema 演进纪律（V1 冻结 / 迁移文件逐字节冻结） | [数据与Schema演进](../../tech/data-evolution/数据与Schema演进.md) |
| 未处置的候选（前端接入 / 时长回填 / 容量上限） | `task/FURTHER_ISSUES.md` |
| 本期做哪些、任务怎么拆 | `task/CURRENT_NEEDS.md` · `task/CURRENT_TASKS.md` |
