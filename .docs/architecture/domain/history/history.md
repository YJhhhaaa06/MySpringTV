# 播放记录（history）域

> 定位（先读这一句）：本域记录**谁把哪个视频看到哪了**，并在**首次播放**时给内容累计一次**播放量**。
> 本篇是服务端的**唯一事实源**（数据模型 / 端点契约 / 写读路径 / 口径 / 边界）。
> **用户可见规则** → [history-需求](./history-需求.md)；**上报缓冲机制** → [history-上报缓冲](./history-上报缓冲.md)；
> **客户端怎么配合** → [history-客户端契约](./history-客户端契约.md)；
> **已拍板接受的代价**（历史不可删 / 无容量上限）→ [history-已知代价](./history-已知代价.md)。
>
> 读法：只想接入或判断行为 ⇒ **§〇 + §三**；改代码 ⇒ 再读 **§四 / §七**；想知道"为什么这么做" ⇒ **§十**。
> ★ 页面与词（播放页 / 历史记录页 / 观看会话 / 标签页）**先看** [history-术语表](./history-术语表.md) ——
> 本域最容易出错的就是这几个词，说错了后面全歪。
>
> ★ **代码注释只许引用本篇路径 + `类#方法`**，不引 `task/` 登记台与任何草稿（登记台每期重建 ⇒ 引用即悬空）。

---

## 〇、六十秒上手

一条数据流：

```
播放页                           服务端                                     存储
/play     ──────────────────>  判首播（一人一次）──> content.play_count +1 ──> 事件 ──> content:playCount
/report   ──────────────────>  校验 + clamp ─────────> Redis 缓冲（hash + dirty 集合）
/flush    ──────────────────>  批量 ODKU ────────────> watch_history（写成功才条件删缓冲）
【服务端轮询】────────────────>  同一段落库（只兜"停止上报"的会话）
/list     <──────────────────  一页 watch_history + 一趟 loadContentVOs（取不到的做失效占位）
/position <──────────────────  一条 uk_user_content 点查（只读**已落库**的值）
```

| 谁 | 做什么 | 端点 |
|---|---|---|
| 播放页 · 开始播放 | 记一次播放量（一人一次，幂等） | `POST /api/v1/contents/{id}/play` |
| 播放页 · 初始化 | 取续播位置（无记录 ⇒ 0） | `GET /api/v1/contents/{id}/progress` |
| 播放页 · 播放中 | 报进度，**只进缓冲不落库** | `PUT /api/v1/contents/{id}/progress` |
| 播放页 · 暂停/退出/切页 | 把该用户缓冲**同步落库** | `POST /api/v1/history/flush` |
| 历史记录页 · 翻页 | 分页读列表（**唯一的读端点**） | `GET /api/v1/history` |

**三条最容易记错的口径**：

1. **一人一内容一行**（`uk_user_content`）：重复上报只改这一行，列表里不会出现两条。
2. **播放量按人去重、只增**；详情平铺，列表卡片也带播放量（GAP-01 起网格封面角标 / 动态嵌入块）。
3. **进度后到即新（LWW）**，没有"只能增"守卫 ⇒ 重看从头会把进度改回 0（刻意偏离 B站，§十 #1）。

**代码落点**（想读实现直接跳这里）：

| 面 | 类 |
|---|---|
| 入口 | `history/controller/HistoryController`（类级 `@RequiresLogin`，五个端点） |
| 写 | `HistoryService#report / #play / #flush` · `HistoryReportBuffer`（Redis 语义层）· `HistoryFlushService`（落库编排）· `HistoryFlushScheduler`（兜底轮询） |
| 读 | `HistoryService#list / #position` · `WatchHistoryDao` |
| 播放量 | `content.play_count` 列 · `ContentCountAggregate`（详情第五键）· `content.event.PlayCountChangedListener` |
| 时长 | `upload/service/Mp4DurationReader` → `content_media.duration_seconds` → `ContentCacheDTO` |

---

## 一、范围

| 做 | 不做 |
|---|---|
| 进度上报（`PUT /api/v1/contents/{id}/progress`，**先进 Redis 缓冲**，见 [上报缓冲](./history-上报缓冲.md)） | 历史删除 / 清空 |
| 播放事件（`POST /api/v1/contents/{id}/play`，去重计播放量；**不进缓冲**） | 前端页面（旧 `static/` 已**删除**，2026-10-26；新前端**已接入**：播放页 2026-10-25、历史列表页 2026-10-26，见 [客户端静默机制](../../frontend/客户端静默机制.md)） |
| 历史列表（`GET /api/v1/history`，失效占位；**不合并缓冲**） | 历史列表缓存 |
| 续播位置（`GET /api/v1/contents/{id}/progress`，**只读已落库的值**） | 服务端**降频**（节流由客户端负责；缓冲只挡写库，不挡请求） |
| 退出 flush（`POST /api/v1/history/flush`）+ 空闲轮询兜底（[上报缓冲](./history-上报缓冲.md)） | 客户端节流（服务端不做；**已由新前端实现**，见 [客户端契约](./history-客户端契约.md) §三） |
| mp4 时长解析（发布 / 换源） | 「已看完」标记（由前端按 `progress / duration` 推导） |
| 详情播放量（第 5 个公共计数） | 历史容量上限 / 旧行清理 |
| 失效内容占位（抄收藏域形态） | ~~存量内容时长回填~~（2026-10-27 已一次性完成，见 §六.2） |

---

## 二、数据模型（Flyway **V6** 建表；**V7** 清遗留表；**V8** 换列表索引）

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

```sql
-- V8：列表索引（**替换**而非新增，索引总数不变）
ALTER TABLE `watch_history`
  DROP KEY `idx_user_update`,
  ADD KEY `idx_user_update_content` (`user_id`, `update_time` DESC, `content_id` DESC, `progress_seconds`)
  COMMENT '历史列表倒序（update_time DESC, content_id DESC）+ 三列覆盖；每条上报改 update_time ⇒ 一并维护';
```

**口径**：

| # | 口径 | 理由 |
|---|---|---|
| 1 | 一人一内容**一行**（`uk_user_content`） | 重复上报只更新；列表里不出现同一视频两条 |
| 2 | `progress_seconds` 单位**秒** | 客户端只传秒；不引入毫秒/时间戳 |
| 3 | **不存 duration 快照** | 时长真相只有 `content_media.duration_seconds` 一份；历史读当前值 |
| 4 | `play_counted` 与 `progress` **解耦** | `/report` 只改进度，`/play` 只改标记（§四） |
| 5 | `update_time` 必须**显式** `SET ... = CURRENT_TIMESTAMP` | `ON UPDATE` 在"值没变"时不触发 ⇒ 重复上报同一秒不会移到最前 |
| 6 | `idx_content_id` 不是运行时读路径 | 只服务不变量复算（`play_count == COUNT(*)`）与将来的清理工具 |

> ⚠️ `play_count` 是 `unsigned` 且**只增**（没有"取消播放"）⇒ 不需要别的计数那套
> `CAST(... AS SIGNED)` 下溢守卫。这是口径差异、不是漏改。

**为什么 V8 要换索引**：列表的 `ORDER BY update_time DESC, content_id DESC` 里 `content_id`
**不在** V6 的 `idx_user_update (user_id, update_time)` 上 ⇒ 排序键不是索引序 ⇒ 每页都对该用户**全部行**
filesort。新索引**前三列就是排序键**，第四列 `progress_seconds` 让本页 SELECT 三列**免回表**；
新索引前缀已完全覆盖旧索引（`countByUserId` 一并覆盖）。代价 = 每条上报都改 `update_time` ⇒
该索引项随每次上报重写（写放大，**已实测无回归**）。实测基线见 §7.1。

> ⚠️ **迁移锚点要同 commit 改**（表数 / 版本 / 端点权重计数 —— **清单在 §十一**）。这不是回归，
> 而是把"新版本真的被应用 / 遗留表真的被删"变成一次人工认账。

---

## 三、端点契约

写 = `PUT` / `POST` + **JSON body**（`T59` 起，见 [`接口契约.md`](../../tech/api/接口契约.md) §2.5）、读 = `GET` + query。
进度覆盖幂等 ⇒ PUT，播放 / flush 是动作 ⇒ POST（见该篇 §2.4）；旧的 form 表单形态已随 `T59` 作废。
`HistoryController` **类级 `@RequiresLogin`**：五个端点都只对当前登录用户有意义。

| 端点 | 入参 | 出参（`data`） | 说明 |
|---|---|---|---|
| `PUT /api/v1/contents/{id}/progress` | 路径 `{id}` + JSON body `{"progress"}`（秒，整数） | 无（`msg="success"`） | **只写 Redis 缓冲、不落库**；后到即新（LWW）；按当前时长 clamp；Redis 不可用 ⇒ **503 + `Retry-After`** |
| `POST /api/v1/history/flush` | 无（`userId` 取自登录态） | 无（`msg="success"`） | 把**该用户全部**脏缓冲**同步**落库（退出信号）；落库失败仍 **200**（数据留缓冲等兜底） |
| `POST /api/v1/contents/{id}/play` | `contentId`（进路径） | 无（`msg="success"`） | 首播去重后 `play_count + 1`；行不存在则建行（progress=0） |
| `GET /api/v1/history` | `page`、`pageSize` | `PageResult<HistoryItemVO>` | 按 `update_time DESC, content_id DESC`；缺省 20 / 上限 100 |
| `GET /api/v1/contents/{id}/progress` | `contentId`（进路径） | `{ "progress": 123 }` | 无记录 ⇒ `0`；**不校验内容、不返回 404** |

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

**限流权重**：`[PUT /api/v1/contents/{id}/progress] = 1`、`[POST /api/v1/contents/{id}/play] = 1`、`[POST /api/v1/history/flush] = 3`。
前两者**有意低于**"其余非 GET 写 3"：上报是设计上最高频的写，★ 接心跳**不需要**重估 ——
缓冲挡的是 DB 写、请求数不变，权重按请求计；`/flush` 取 3 是因为它一次批量写库。

---

## 四、写路径

### 4.1 `PUT /api/v1/contents/{id}/progress`：校验 → **写缓冲**；落库在 flush（一条 ODKU，**不加事务**）

下面这条 SQL 是 **flush / 轮询兜底**执行的语句，语义（LWW、`update_time` 显式 SET、clamp）在
"上报即落库"时代**逐字未变**：

```sql
INSERT INTO watch_history (user_id, content_id, progress_seconds)
VALUES (?, ?, ?)
ON DUPLICATE KEY UPDATE
  progress_seconds = VALUES(progress_seconds),
  update_time = CURRENT_TIMESTAMP;
```

> ⚠️ `progress` 的 clamp 在**请求线程**做（需要 `durationSeconds`）⇒ **Redis 里的值已是 clamp 后的**。

校验顺序（参数形态 → 存在性 → 类型）：

| # | 判据 | 拒绝 |
|---|---|---|
| 1 | `progress < 0` | 400「播放进度不能为负数」 |
| 2 | 内容不存在 / 已软删 / 媒体损坏 | 404「内容不存在」 |
| 3 | `type != 1`（图文） | 400「只能记录视频的播放进度」 |

- 校验复用 `ContentService#loadContentVOs(List.of(contentId))`：**返回空列表** ⇒ 404；`type != 1` ⇒ 400；
  顺带拿 `durationSeconds` 做 clamp（`progress = min(progress, duration)`；时长 null 则不 clamp）。
  **不新增 content 域方法、不新增 DAO 读边。**
- ★ **不在这里计播放量** ⇒ ODKU 的 affected-rows 歧义（Connector/J 默认 `useAffectedRows=false`
  ⇒ "插入"与"值没变的更新"都返回 1）**不构成问题** —— 这是拆端点的最大收益。
- **不做"进度只能增"的守卫**：后到即新。重看从头会把进度改回 0（与 B站 不同，见
  [history-需求](./history-需求.md) §六）。

### 4.2 `POST /api/v1/contents/{id}/play`：首播判定，`@Transactional`

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
  理论上仍有"`INSERT IGNORE` 共享锁 → 条件 `UPDATE` 排他锁"的 1213 窗口（未复现，§十 #9）。
- `/play` 在行不存在时**建行（progress=0）**：否则"先 play 后 report"会丢播放量；
  历史里出现一条 00:00 的记录是预期行为。
- `/play` 是**唯一**计播放量入口；`/report` 不计数。播放事件丢失 ⇒ 本次不计，下次观看会再发；
  **只看一次且丢包 ⇒ 永久少计**（§十 #2）。

### 4.3 事务边界

| 端点 / 路径 | 事务 | 理由 |
|---|---|---|
| `/report` | **不加** | 只写 Redis：单条 Lua（`HSET` + `ZADD`）本身原子（[上报缓冲](./history-上报缓冲.md)） |
| `/flush` / 轮询兜底 | **一批一事务**；失败后**逐条重试** | 批量 ODKU 必须同进同退；一次数据异常若整批回滚会"毒死全批"⇒ 失败降级逐条，把坏行摘出去（[上报缓冲](./history-上报缓冲.md) §四） |
| `/play` | **加** | 标记位 + `content.play_count` 必须同进同退；否则不变量没有自愈路径 |

---

## 五、播放量（content 域自持，**无新增跨域读边**）

| 件 | 形态 |
|---|---|
| 列 | `content.play_count`（`unsigned`，只增；内容删除**不归零**） |
| 缓存 key | `content:playCount:{id}`（content 域自持，同 `content:commentCount`） |
| 详情读 | 计数聚合 **4 键 → 5 键**：`ContentCountColumns` 加 `playCount`、`ContentDao#findCountColumns` 加列、`ContentCountAggregate` 的解析 / 合并 / 回填各加一位。⚠️ record 组件**必须用包装 `Long`**（MyBatis `<constructor>` 按 javaType 反射，原生 `long` 会 `NoSuchMethodException` ⇒ 详情 500） |
| 回填 | content 域内 `ContentCountCache#backfillPlayCount`（**不经互动域**——键是自己的） |
| 失效 | `content.event.PlayCountChangedListener` 订阅 `history.event.PlayCountChangedEvent`，**条件 `INCRBY 1`**（冷 key 不建，同 coin 的 `content:coinCount`） |
| 不变量 | `content.play_count == COUNT(*) FROM watch_history WHERE content_id = ? AND play_counted = 1` |

- **详情平铺**（`ContentDetailVO.playCount`）；GAP-01 起列表也带 `playCount`（按面，见
  [缓存与数据一致性 §5.3](../../tech/cache/缓存与数据一致性.md)），但**不进 `ContentCacheDTO`**。
- 图文帖 `play_count` 恒 0（没有 `/play` 路径能通过类型校验）。
- 内容删除：详情 404 在聚合之前短路；`play_count` 与历史行都**不动**（失效占位要它）；
  `content:playCount:{id}` 在 `ContentCache#removeContent` 里**一并失效**（同 `commentCount` 的孤儿键口径
  —— 键删了也不会再回填，因为详情 404 在聚合之前）。
- 不新增 `content → history` 边：详情不反查历史，播放量走"列 + 自己的 key"。
- ★ **写路径没有为读面改过一行**：`/play` 只做列 `+1` + 发事件，读面（key / 聚合 / 监听器）自己接上。

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
| `POST /api/v1/contents/videos` | 落盘后解析；发布事务里写 `content_media.duration_seconds` |
| `PUT /api/v1/contents/{id}/media`（换源，`type=1`） | 换源时重解析并**同一事务**更新该媒体行的时长；`type=2/3` 传 null，**不能清空视频行的时长** |

- 解析失败 ⇒ `null`，**不阻断发布**（内容仍可播，只是没有百分比）。
- **存量已回填**（2026-10-27，一次性工具 `tools/devclean/backfill_duration.py`）：只填
  `duration_seconds IS NULL` 的行；盘上文件缺失 / 解析失败**保持 NULL**（不写 0）。本机 dev 库
  42/45 回填，3 个文件缺失（`file_exists=0`）。回填不刷缓存 ⇒ `content:{id}` 在 TTL 内仍可能读到 null。
- 归属：解析器在 **upload 域**（它持有"上传文件的形状"与 `FileUploadService#resolveAbsolutePath`）；
  content 只收 `durationSeconds` 参数，**不反向依赖 upload**。

### 6.3 载荷投影（**三个落点，漏一个就恒 null**）

- `ContentCacheDTO` 加 `durationSeconds`（JSON 键同名，**所有内容响应新增一个键**）。
- `ContentCache#buildContentMedia` 在 `type=1` 分支从**已有的视频行**取时长写入 DTO
  —— **零额外查询**（媒体行本来就要装配）。
- ★ `ContentCache#copyCommon` **必须同步**：`toContentVO` / `toDetailVO` 都只走这一次手写字段拷贝。
  **只改 DTO 不改这里 ⇒ 详情/列表的 `durationSeconds` 恒 null**，而 DB 行断言仍然全绿（最隐蔽的假绿）。
- ⚠️ 老缓存条目（部署前写入）缺该键 ⇒ TTL 内为 `null`，到期自愈；**不做批量刷缓存**。
- 媒体行 = **1 个 resultMap + 5 条 select**（`findMediaByContentId` / `findMediaByContentIds` /
  `findMediaByContentTypeSort` / `findAllMedia` / `findMediaById`）+ 实体 + `addMedia` / `updateMediaUrl`；
  **漏一条 select 就是静默 null**。

---

## 七、读路径

### 7.1 历史列表

1. 一页 `watch_history`（`idx_user_update_content`，`update_time DESC, content_id DESC`）+ 一次 `count`；
2. `ContentService#loadContentVOs` 一趟批量取展示字段；
3. 取不到的条目构造**失效占位**（`total` 不变、页内条目不缩水）。

**排序成本基线（V8 前后对照，实测）**：旧索引不含 tie-break 列 `content_id` ⇒ 每页对该用户**全部行**
filesort；新索引前三列即排序键（前向索引扫描）：

| 场景（scratch 库、单用户 N 行） | V6 旧索引 | V8 新索引 |
|---|---|---|
| 页 1（`LIMIT 20 OFFSET 0`）@ 5 千行 | `Using filesort`，3.15 ms | `Using index`，**0.10 ms** |
| 页 1 @ 5 万行 | 37.8 ms | **0.2 ms** |
| 深翻 `OFFSET 49900` @ 5 万行 | 62.3 ms | 43 ms（offset 走动，与索引无关） |
| 建索引（51k 行表 `ALTER`，INPLACE） | — | 276 ms（一次性） |
| 写：同一批 1000 次 ODKU（逐条自动提交） | 5.52 / 5.81 s | 5.93 / 5.55 s（**噪声内，无回归**） |

- 复算：建同构表 → 单用户插 N 行（`update_time` 递减）→
  `EXPLAIN ANALYZE SELECT content_id, update_time, progress_seconds FROM watch_history WHERE user_id=1 ORDER BY update_time DESC, content_id DESC LIMIT 20 OFFSET 0;`
- ★ 本仓**无流量**（`AGENTS.md`）：上表是"造数据放大机制"的**优化前后基线**（跨期约定：
  无基线不得宣称"优化生效"），不是线上问题。

**不缓存**：私有 + 写多读少 + 每次上报都变 ⇒ 缓存必然"写后就废"。这是**刻意不做**；
推翻 = 出现真实读放大。

### 7.2 续播位置

一条 `uk_user_content` 点查；无行 ⇒ `progress=0`。不校验内容、不返回 404
（内容是死是活由详情的 404 负责；这里只回答"我的进度是多少"）。

★ **只认已落库的值，不读上报缓冲**。两条依据：① `/flush` 成功后缓冲的确认脚本在
`HLEN == 0` 分支会把该用户的 hash 整个 `DEL` ⇒ **正常路径上读缓冲必然 miss**，等于白付一趟 `HGET`；
② 崩溃续播改由客户端本地 `history:pending:{contentId}`（**每秒更新的当前进度**，不是上次上报值）承担
—— 初始化取 `max(服务端值, 本地值)`（见 [history-客户端契约](./history-客户端契约.md) §6.2）
⇒ 服务端不必再当"缓冲上游"。代价（多设备 + 双方都未正常退出时可能倒退）见
[上报缓冲](./history-上报缓冲.md) §七 #7。

### 7.3 详情

`ContentDetailVO` 加 `playCount`（第 5 个公共计数），由 `ContentCountAggregate` 一趟提供；
`durationSeconds` 随父类 `ContentCacheDTO` 返回。详情路径的 Redis / DB 趟数不变
（仍是 2 趟 Redis + 冷内容 1 条列查）。**列表也带 `playCount`**（GAP-01，§五.3）。

---

## 八、错误码

| 码 | 触发 | 文案 |
|---|---|---|
| 400 | `progress < 0` | 播放进度不能为负数 |
| 400 | 图文帖上报 / 播放 | 只能记录视频的播放进度 |
| 404 | 内容不存在 / 已软删 / 媒体损坏 | 内容不存在 |
| 401 | 未登录 | （鉴权链兜底） |
| 429 | 上报过密 | （限流，见 [限流](../../tech/rate-limit/限流.md)） |
| 503 | Redis 不可用（`/report`） | 服务繁忙 + `Retry-After` |
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
- **新域开工必须同步登记**：`ArchitectureTests.DOMAINS` 加域名；《模块边界与依赖》§一 域名清单 +
  §六 补该域与其他域的边。不登记 = 新域是规则空洞 / 无声绕开判据。

---

## 十、已知代价

| # | 代价 | 边界 / 推翻条件 |
|---|---|---|
| 1 | **重看从头会把进度改回 0**；客户端重试的旧包后到会倒退 | 拍板"先到即旧、后到即新"（LWW）。B站 不会丢更靠后的进度 ⇒ **刻意偏离**。推翻 = 客户端带单调序号，或服务端加"只增"守卫 |
| 2 | **播放事件丢失 ⇒ 本次不计；只看一次且丢包 ⇒ 永久少计** | 一人一次口径下无法区分"没播过"与"事件丢了"。推翻 = 让 `/report` 在 `play_counted=0` 时补计（代价：每次上报多一条条件 UPDATE） |
| 3 | **`/play` 与 `/report` 解耦**：只发 play 不报进度 ⇒ 行停在 0 秒；只报进度不发 play ⇒ 不计播放量 | 前端必须两个都发；无前端期由测试与文档保证。推翻 = 合并端点为单入口 |
| 4 | 换源后 `duration_seconds` 变化 ⇒ 旧进度可能大于新时长，比例失真 | 写入时 clamp；**读时不 clamp**（不改存储值）。下次上报即修正。推翻 = 出现大量换源 |
| 5 | 老缓存条目在 TTL 内 `durationSeconds=null` | 部署后最长一个 `contentTtl`（15m）自愈；不刷缓存 |
| 6 | 存量内容时长**曾**永不回填（null） | **2026-10-27 已推翻**：一次性工具回填 42/45，3 个文件缺失保持 NULL；进度按秒展示只剩这 3 条与解析失败。工具 = `tools/devclean/backfill_duration.py`（只填 NULL、默认 dry-run、文件缺失不写 0） |
| 7 | 历史**不可删** ⇒ 行无界增长 | 一人一内容一行，增长上限 = 用户 × 看过的内容；无清理任务。推翻 = 需要删除时先重定播放量口径（B站 删历史不减播放量） |
| 8 | 失效占位保留 `progress`（页面仍显示"看到 X 秒"） | 历史数据是真实的；前端可选择不渲染。推翻 = 产品要求占位一律置空 |
| 9 | 首播的理论 1213 死锁窗口（`INSERT IGNORE` → 条件 `UPDATE`） | 与 coin 同族，未复现；真实并发出现 ⇒ 改 ODKU 或重试 |
| 10 | 列表 offset 翻页在上报并发下会重复 / 漏 | 排序键 `update_time` 会被上报改写；B站 用游标。本仓沿用 `PageResult`。推翻 = 历史翻页成为主路径 |
| 11 | 播放量在列表卡片上的口径 | **GAP-01（2026-10-26）起列表带播放量**（网格封面角标 / 动态嵌入块）；其余四维仍只在详情。见 [缓存与数据一致性 §5.3](../../tech/cache/缓存与数据一致性.md) |
| 12 | ★ `INSERT IGNORE` 会把**非唯一键的错误**（列截断 / 未来的 CHECK 或第二条唯一键）也降级成 warning ⇒ `affected=0` 被一律读作"已计过"，那次播放量被**静默丢弃** | 前提：本表**只有 `uk_user_content` 一个唯一键**、`user_id` / `content_id` 是 `bigint`、`progress_seconds` 恒 0 ⇒ 当前不可能触发。推翻 = 给本表加约束 / 加第二个唯一键 / 改写入列宽 ⇒ 届时应改成"先查后插"或 ODKU + 显式判别，别再靠 `IGNORE` 的 0 |

---

## 十一、测试口径（钉住什么）

| 面 | 断言要点 | 落点 |
|---|---|---|
| 上报契约 | 建行 / 低进度覆盖高进度（LWW，不是"只增"）/ 超时长被 clamp / 重复上报**仍刷新 `update_time`** / 图文 400 / 已删 404 / 匿名 401 | `HistoryWriteTests` |
| 播放契约 | 首播 `+1` / 重复不变 / **并发两次恰 +1** / 行不存在时建行 / **被拒请求一行都不落** | `HistoryWriteTests` |
| 缓冲与落库 | 上报不进库 / `/flush` 进库 / 轮询兜底 / 503 一次 DAO 都不调 / 落库失败留缓冲 / 毒批逐条降级 / 并发上报新值优先 | `HistoryFlushTests` · `HistoryReportBusyTests` · `HistoryReportBufferTests` · `HistoryBufferPropertiesTests` |
| 读面 | 列表顺序与 `total`（**独立 oracle**）/ 占位三字段 + `progress` 保留 + 页内条数不缩水 / 续播"无记录 0 / 已删仍答" / 缓冲不参与续播 | `HistoryListTests` · `HistoryPositionTests` |
| 时长 | 手搓 mp4（moov 前/后、v0/v1、`size==1`、`timescale=0`、截断、非 mp4）/ 落库 + 详情回显 / 换源刷新 / 换成坏文件回 null | `Mp4DurationReaderTests` · `UploadDurationTests` |
| 存量回填 | 只填 `NULL` / 媒体根下 44 个真实 mp4 的 Python 与 Java 解析逐一相等 / 3 个文件缺失保持 NULL | `tools/devclean/backfill_duration.py`（一次性） |
| 结构 | 唯一键**真插两行**看拦不拦 / 索引全集 / 版本行 | `HistorySchemaTests` · `WatchLaterSchemaTests` |
| 鉴权 | **五条路由**（`/report` `/flush` `/play` `/list` `/position`）落到 `@Controller` 处理器上 | `SecurityContractTests#history写端点需登录` |

**独立 oracle**：`content.play_count == SELECT COUNT(*) FROM watch_history WHERE content_id=? AND play_counted=1`；
`(user_id, content_id)` 唯一。

**迁移锚点（加迁移 / 加非 GET 端点时各改各的，同 commit）**：
`InfrastructureConnectivityTests`（表数 **19**、版本 `1..8`）、
`WatchLaterSchemaTests#全部迁移版本都被应用`（`endsWith("8")`）、
`RateLimitWeightTableTests.EXPECTED_NON_GET_AT_LEAST`（**39**）。

**几条"看着全绿其实没测到"的边界**（写新用例前先读）：

- **失效判据**只有"装载契约取不到"：`durationSeconds == null`、`file_exists = 0` 都**不是**失效。
  `HistoryListTests#时长未知或未落盘不算失效` 用两条存活视频钉住 —— 夹具默认**不写时长**，
  没有这条时把判据写成 `content == null || durationSeconds == null` 仍然全绿。
- **占位字段的注入形式**：`toItemVO` 里的三次置空对本域 Mapper **是 no-op**（那三列根本没被映射
  ⇒ 恒 null）⇒ 单删 `setAuthorName(null)` **不会红**；要证明"占位不泄露展示字段"，注入必须
  **同时**给 SQL 加越界 join 取作者。
- **`@RequiresLogin` 的判别力只在 `SecurityContractTests`**：HTTP 侧未登录 401 由 `@CurrentUserId`
  参数解析器兜出 ⇒ 去掉注解 HTTP 用例仍绿。
- **续播口径的判别力只由一条承担**：`HistoryPositionTests#缓冲里的新进度不参与续播`（库里 500、缓冲 900
  ⇒ 必须答 500）；该类其余用例在两种实现下都绿。
- **反向验证**（改实现前先 commit 基线）：抽掉 `copyCommon` 的 `setDurationSeconds` ⇒ 回显 3 红而
  **DB 断言仍绿**（就地看到"只断 DB"的假绿）；抽走迁移文件必须**同时清 `target/classes/db/migration/`
  同名副本**，否则读到旧资源、假绿；`/report` 的 ODKU 改 `GREATEST` ⇒ LWW 红。

---

## 十二、上报缓冲 → 已独立成篇

`/report` 只写 Redis、`/flush` 同步落库、轮询只兜"没正常上报退出"的会话。**机制全貌**
（为什么放 Redis / 两个 key / 三条写路径 / 落库与条件删 / 剪枝 / 失败姿态 / 读路径新鲜度 / 已知代价）
见 [history-上报缓冲](./history-上报缓冲.md)。

> ★ **为什么独立**：它自成一套机制（缓冲 / 落库 / 剪枝 / 故障姿态），与域的静态结构（表、端点、时长）
> 耦合很松，却又是**被代码引用最密**的一节 —— 独立后引用能一指到底。
> ⚠️ 本节只保留**域文档必须记住**的两条：① `/report` **不再同步落库**（§4.1 那条 ODKU 现在只在 flush 时执行）；
> ② `/flush` 是本域**第五个端点**、限流权重 3（§三）。

---

## 附录：编号速查（**活**的查证入口）

> 追溯用，**读文档不必读本表**。每号一行结论；未完成的任务不收编（滚动见 `task/CURRENT_TASKS.md`）；
> 逐条论证与失败尝试走 git。`T45`–`T53` 的结论 = 下面各行 + 本篇正文；过程 commit 见
> `CURRENT_TASKS.md` 周期头的区间。
> ⚠️ **表里的端点路径是"当时"的形态**：`T58` 起全站资源化为 `/api/v1/**`（`T59` 又统一了入参 / 出参形状）。
> 本表**不逐行改写** —— 改了就把"留痕"改成假记录；**当前路径以 [接口契约](../../tech/api/接口契约.md) §2.3
> 与 `src/test/resources/api/openapi.yaml` 为准**。

| 编号 | 一句话 | 详见 |
|---|---|---|
| R-18 | 播放记录专项拍板：**做播放量**（一人一次，冗余列 + 独立 key + 详情第 5 计数）/ **进度与播放事件拆两端点** / 历史**不可删** / 失效占位 / 时长存 `content_media` + `ContentCacheDTO` 投影 / V7 删遗留 `video`·`videoinfo` | 本篇 · [需求篇](./history-需求.md) |
| R-19 | `/report` 改**写 Redis 缓冲**、`/flush` 同步落库、空闲轮询只兜"停止上报"的会话；503 + `Retry-After`（**不是降频手段**，挡的是 DB 写） | [上报缓冲](./history-上报缓冲.md) |
| R-20 | `/position` **只点查 DB**（推翻 `R-19` 契约④"读缓冲优先"）：正常路径读缓冲必然 miss；崩溃续播改由客户端本地 pending 承担 | 本篇 §7.2 · [客户端契约](./history-客户端契约.md) §6.2 |
| T45 | V6/V7 迁移 + 结构契约：`duration_seconds` / `play_count` / `watch_history`（唯一键 + 索引）；删遗留 `video`·`videoinfo` | §二 · `HistorySchemaTests` |
| T46 | mp4 时长：只读 box 头解析 `mvhd`；发布与换源两处调用；经 `ContentCacheDTO` + `copyCommon` 投影到所有内容响应；失败不阻断；存量当时不回填，2026-10-27 由一次性工具补做 | §六 · `UploadDurationTests` |
| T47 | 写路径：`/report`（ODKU、无事务、LWW、clamp、**不写 play_count**）+ `/play`（`INSERT IGNORE` → 条件 UPDATE 判首播 → `+1` → 发事件，单事务）；`history` 进 `DOMAINS` | §四 · `HistoryWriteTests` |
| T48 | 播放量读面：`content:playCount`（content 自持）+ 详情计数聚合**四→五键** + `AFTER_COMMIT` 条件 `INCRBY`（冷 key 不建）+ `removeContent` 一并失效；详情精确键集 **19** | §五 · §7.3 · `ContentPlayCountTests` |
| T49 | 读面：`/list`（失效占位、`total` 含失效行、整页失效整页返回；**不缓存**）+ `/position`；四个路由进鉴权契约 | §三 · §7.1/§7.2 · `HistoryListTests` / `HistoryPositionTests` |
| T50 | V8 列表**覆盖复合索引**（替换而非新增）：前三列即 `ORDER BY`、第四列免回表；5k 行 3.15→0.10 ms、50k 行 37.8→0.2 ms；写路径无回归 | §二 · §7.1 · `HistorySchemaTests` |
| T51-1 | 缓冲内核（纯 Redis）：`CacheKeys` 两键 + `video.history.buffer.*` 配置 + 三条 Lua + `maxmemory-policy` 启动自检 | [上报缓冲](./history-上报缓冲.md) §二–§五 · `HistoryReportBufferTests` |
| T51-2 | 写路径接管：`/report` 改走缓冲、新增 `/flush`、轮询兜底、503 + `Retry-After`（熔断先于校验）；权重 38→39 | [上报缓冲](./history-上报缓冲.md) §三–§五 · `HistoryFlushTests` |
| T51-3 | 读路径（当时为读缓冲优先，已被 `T52` 推翻）+ 客户端契约**独立成篇**（本期只登记、不实现） | [客户端契约](./history-客户端契约.md) |
| T52 | `/position` 改回只点查 DB（`R-20`）；写路径一字未动；客户端 `resume()` 取 `max(服务端, 本地)` | 本篇 §7.2 · [上报缓冲](./history-上报缓冲.md) §六/§七 |
| T53 | **文档收口**：四篇改为读者顺序（§〇 上手 + 术语先行）、删除正文里的任务号 / 交付状态记录（追溯收进本表）、新增 [history-术语表](./history-术语表.md) 作为域内术语试点 | 本篇 §〇 · [术语表](./history-术语表.md) |
| I-23 | 验收口径矛盾：`T47` 只该钉写路径两段（`/report`、`/play`），却要求"四端点" ⇒ 本批只钉两路由 + 注释写明读面两段下一批必补；`T49` 补 `/list`、`/position`（四路由齐，核销）。★ 2026-10-30 从冻结登记台收编（原只在 `CURRENT_ISSUES.md` 一行，而 `SecurityContractTests` 引用了它） | `SecurityContractTests` · §三 |

---

## 边界与去向（机制外链）

| 不在本篇的内容 | 去向 |
|---|---|
| **用户可见规则**（看到什么 / 重看怎么算 / 播放量口径） | 同目录 [history-需求](./history-需求.md) |
| **术语与页面对照**（播放页 / 历史记录页 / 会话 / 标签页） | 同目录 [history-术语表](./history-术语表.md) |
| **客户端行为契约**（上报节奏 / 退出顺序 / 503 退避 / 崩溃补偿 / 自测清单） | 同目录 [history-客户端契约](./history-客户端契约.md) |
| **上报缓冲机制**（键 / 原子性 / 落库与条件删 / 剪枝 / 失败姿态 / 已知代价） | 同目录 [history-上报缓冲](./history-上报缓冲.md) |
| 缓存三态 / 空标记 / 写后失效 / 单飞 | [缓存与数据一致性](../../tech/cache/缓存与数据一致性.md) |
| 事务切分、载体独立 bean、`AFTER_COMMIT` | [事务边界](../../tech/transaction/事务边界.md) · [领域事件与解耦](../../tech/events/领域事件与解耦.md) |
| 鉴权链（`@RequiresLogin` / 默认拒绝） | [安全与鉴权](../../tech/security/安全与鉴权.md) |
| 端点权重表（新增非 GET 端点必须登记） | [限流](../../tech/rate-limit/限流.md) |
| 跨域边与 ArchUnit 规则 | [模块边界与依赖](../../tech/module-boundary/模块边界与依赖.md) |
| schema 演进纪律（V1 冻结 / 迁移文件逐字节冻结） | [数据与Schema演进](../../tech/data-evolution/数据与Schema演进.md) |
| 已拍板接受的代价（历史不可删 / 无容量上限） | [history-已知代价](./history-已知代价.md) |
| 本期做哪些、任务怎么拆 | `task/CURRENT_NEEDS.md` · `task/CURRENT_TASKS.md` |
