# feed 域架构（关注动态流）

> 定位（先读这一句）：feed 是本仓**唯一的异步编排型域**。
> **本篇只讲业务数据流与路由策略**；缓存三态 / MQ 投递可靠性 / 事务机制 / 事件范式等**跨域机制不在本篇**，
> 去向见文末 [§十一 边界与去向](#十一边界与去向机制外链)。
>
> 结构：本文属架构文档体系的 `domain/`（业务域）；`tech/`（横切机制）各项**尚未建立**。

---

## 一、这个域解决什么问题

一句话：**把"我关注的人发了什么"按内容新旧倒序、在一个**有界窗口**内呈现给用户**，并在"关注关系变化"
或"内容发布"时让这个视图跟上。

难点不在"查一张表"，在三处：

| # | 难点 | 本篇对应章节 |
|---|------|------|
| 1 | **推 vs 拉**：大V发一条内容不可能给百万粉丝逐个写；普通作者又希望读时快 ⇒ 必须**分流** | §五、§六 |
| 2 | **有界**：动态流无限增长，只能维护**有界窗口**（截断），不能是全量 | §三、§七 |
| 3 | **最终一致**：视图由**异步事件**驱动 ⇒ 必须定义"陈旧/异步可见"的语义边界与**回退**路径 | §三、§四、§七 |

---

## 二、全景：一条动态怎么到用户手上

### 2.1 写入侧（推平面：内容发布 → 写扩散）

```
content 域事务提交
      │  ContentPublishedEvent              （发布者：content 域）
      ▼
ContentPublishedFeedListener               （feed.event，AFTER_COMMIT）
      │  feed.push.content                  （MQ，异步）
      ▼
FeedPushConsumer ──► FeedInboxWriter.fanout(contentId, authorId)
                       │ ① 无条件失效「作者发件箱」缓存
                       │ ② 作者是大V？ ── 是 → 跳过写扩散（改由读时拉，见 §六）
                       │ ③ 否 → keyset 游标遍历粉丝 → INSERT IGNORE 写 feed_inbox
                       │ ④ 写后失效各粉丝「收件箱」缓存（两件套）
                       ▼
                 feed_inbox（DB 真相：每用户一个有界窗口）
```

### 2.2 关注变更侧（重建 + 降级补推）

```
follow 域事务提交
      │  FollowChangedEvent                  （发布者：follow 域）
      ▼
FollowChangedFeedListener                   （feed.event，AFTER_COMMIT）
      ├─ 恒成立：feed.rebuild ──► InboxRebuildDebouncer（per-user 尾沿 1s 合并）
      │                              └─► FeedRebuildService.rebuildInbox(userId)
      └─ 仅当 transition == DOWNGRADED：feed.push.backfill
                                     └─► FeedInboxWriter.backfillAuthor(authorId)
```

### 2.3 读取侧（读平面：两路读，纯拉兜底）

```
GET /feed ─► FeedController ─► FeedService.getFeed
                                   │
                         FeedReadService.readWindow(userId)
                         ┌─────────┴───────────┐
                闸门：feed_inbox_sync 有行？    │
                    │有 ⇒ synced=true          │无 ⇒ synced=false
                    ▼                          ▼
        收件箱腿 ∪ 大V发件箱腿              纯拉降级 purePull
        FeedInboxReader.readInbox           FollowService.getFollowingIds
        FeedOutboxReader.readOutbox         + FeedPullReader（单只读事务）
              └──────► FeedWindows.mergeDedupSortTrim(…, M) ◄──────┘
                              │  （去重 → contentId 降序 → 截断到 M）
                              ▼
                  FeedService.renderPage（事务外批量装载，见 §三）
```

---

## 三、读路径：两路读 + 有界窗口

**入口唯一**：`FeedController` 只注入 `FeedService`，`/feed` 的 URL 与响应信封形状固定
（`{list,total,page,pageSize,totalPages}`；`pageSize` 上限/信封默认均 **100**）。

**分流逻辑**在 `FeedService.getFeed`：先问 `FeedReadService.readWindow(userId)`，

- `synced=false` → `purePull`（**切换前的实现整体保留**，见 §四）；
- `synced=true` → 在归并窗口上**切页**。

### 3.1 归并窗口（口径单一来源）

两腿产出在 `FeedReadService` 归并，规则由 `FeedWindows.mergeDedupSortTrim(raw, windowMax)` **独家实现**
（重建侧与读侧共用，**只有上限参数按层不同**）：

**去重 → contentId 降序 → 截断到 M**。

排序口径 = `contentId`（`content.id` 自增单调 ⇒ id 越大越新；`feed_inbox` 不存时间字段）。读侧上限
`M = video.feed.read-window-max`（默认 **300**）。

### 3.2 两条腿

| 腿 | 实现 | 数据面 | 失败面 |
|---|---|---|---|
| 收件箱腿（推的产物） | `FeedInboxReader.readInbox` | 读缓存 `feed:inbox:{id}`，miss 回源 `feed_inbox` 并回填 | DB 失败**上抛**（⇒ 500）；Redis 失败由 `FeedCache` 内部降级吸收 |
| 大V发件箱腿（读时拉） | `FeedOutboxReader.readOutbox` | 按作者现拉 `content` 最近 N 条（`idx_user_id` 反向索引扫描，免 filesort）；不落表 | **fail-open**：DB 失败吞掉 ⇒ 少显示一批大V内容、**不 500**（下次请求自愈） |

两腿都**不各自去重/排序/截断**，统一交给 §3.1 的归并。

### 3.3 两条读路径共享的装载（`renderPage`）

窗口切页与纯拉两条路径的**内容装载完全共用** `FeedService.renderPage`：**事务外**批量读内容
（`ContentService.loadContentVOs`）+ 批量读点赞态（`LikeService.batchIsContentLiked`），按原序跳过 null。

两个**有意为之**的契约点：

- **只填 `isLiked`，不填 `isFollowed`**。feed 条目刻意不做关注态批量填充——"顺手"改用
  `ContentStatusFiller.fillLikeAndFollowBatch` 会改变响应形状，属契约改动，故不做。
- **跳过 null 会让返回页短于 `pageSize`**（内容已软删 / 缓存空标记）。这不是"到底"，前端已按
  `shortPageMeansEnd:false` 处置。

### 3.4 分页语义：深翻禁止（窗口路径）

窗口已按 contentId 降序、去重、截断到 M。**每次请求重算整个窗口**再切第 `page` 页 ⇒ **深翻天然只读窗口内**；
`offset >= total` 返回**空页**（不报错），且 `total` 保持窗口实际条数（前端据此自然停止翻页）。

---

## 四、纯拉降级（`purePull`）

**触发条件 = 未同步（充要条件）。** 见 §七：`feed_inbox_sync` 无该用户行 ⇒ 该用户**从没成功重建过**，
其窗口要么空、要么只有零散 fanout 增量，**不可作为读源**。

纯拉路径 = 旧实现整体保留：
`FollowService.getFollowingIds`（其自身缓存三态 + 回源 + 降级）→ `FeedPullReader.pullForPage`（**单只读事务**）
→ `renderPage`。对外正确性 = **全量可见**（不受有界窗口约束）⇒ 未同步用户不会因切读而"看不到旧内容"。

两个设计点：

- **`FeedPullReader` 必须独立成 bean**：纯拉的"命中总数 + 当页 id"两条查询必须在**同一只读事务**里
  （`total` 与页内容同源快照，否则会出现"页里有第 11 条但 total=10"这类自相矛盾的信封）。若写成
  `FeedService` 的私有方法 + `@Transactional`，**同类自调用会让事务静默失效**。
- **MQ 可用性不作为回退触发**。读平面不依赖 MQ：MQ 故障期窗口陈旧落在"异步可见 / 最终一致 / 有界"的
  已登记语义内；若以 MQ 故障触发全域纯拉，会把 **push 平面故障放大成读平面 DB 负载尖峰**。

---

## 五、写扩散（fanout）

`FeedInboxWriter.fanout(contentId, authorId)`——把一条新内容写进作者每个粉丝的收件箱 **DB 真相表**
`feed_inbox`，并对其 **Redis 读缓存**做**写后失效**。**本类没有 `@Transactional`**：它是**编排 + 游标迭代**，
事务边界在 `FeedInboxBatchWriter`（每批各自事务）。

**一致性口径** = 单写 DB 真相 + 写后失效 + 读 miss 回源回填。fanout **不写 Redis**，只 DEL 两件套
（数据 key + `empty:`）。

### 5.1 每批三步（顺序不可交换）

① 游标读一批粉丝（DB，keyset 迭代）→ ② DB 批量 `INSERT IGNORE`（幂等）→ ③ 一次 DEL 多键。

### 5.2 ★ 顺序红线：发件箱失效必须**无条件**且**先于**大V早退

```
fanout:
  ① invalidateOutbox(authorId)        ← 无条件，且必须在下面这个 return 之前
  ② if isBigV(authorId) return;       ← 大V早退
  ...
```

若把发件箱失效挪进"收件箱失效段"，**大V发布将永不动它** ⇒ 读侧一直读到旧发件箱。

### 5.3 游标并发口径

游标严格递增（`user_id > cursor`）⇒ 同一遍历**不重**、不受行位移影响。两个已知窗口：

- 遍历期间**新增关注**，若其 `user_id ≤ 当前游标` ⇒ 本轮可能漏 → 由该用户的**关注动作触发的重建**兜底；
- 遍历期间**取关者**仍可能被写入 → 由**下次重建**清理。

两者都不破坏"**只多不丢**"不变量。

### 5.4 失败面

粉丝游标读失败 / DB 落库失败 / 意外异常 **抛出** ⇒ 交消费容器本地有限重试（重放从方法入口整体重跑，
`INSERT IGNORE` 幂等）⇒ 重试耗尽转死信留证。

缓存失效 DEL 失败**不停写**（DB 真相优先、读自愈兜底）：首次记 WARNING 后，**后续批次不再尝试失效**
（一次 Redis 故障只留一条记录）。

### 5.5 降级补推（`backfillAuthor`）

作者由大V**降为普通**时，把其**最近 K 条**存量内容补写进**现任粉丝**收件箱
（K = `video.feed.inbox-window-per-author`，与重建/发件箱窗口同量级 ⇒ 降级前后可见性范围一致）。

- **零删除**：只追增，不触碰"fanout 只追增 ⇒ 只多不丢"不变量；`INSERT IGNORE` 幂等。
- **消费时复查大V**：若消费时刻作者**已重新升级** ⇒ 跳过（可见性由发件箱腿保证，不产生无谓残影行）。

---

## 六、大V路由：pull vs push 的判据

`FeedBigVRouter.isBigVBatch(authorIds)` 是**判定"该作者是否大V"的唯一判定点（读侧）**。

### 6.1 为什么必须单点

同一判定被**三处**消费：fanout 写扩散（大V内容**不进**粉丝收件箱）、窗口重建（**排除**大V作者）、
两路读的"大V发件箱"腿（读谁的发件箱）。三处若各自实现阈值/名单，口径必然漂移。

### 6.2 判定口径（三路并集）

| 路 | 判据 | 说明 |
|---|---|---|
| ① 名单命中 | `BigVProperties.userIds`（`video.bigv.user-ids`） | 显式指定；**不经阈值、也不进 SQL 的 IN 列表** |
| ② 自动大V状态表 | `auto_bigv` 命中 | 滞回判定的产物（曾达线升为大V、掉粉后仍在带内未降级）；**不进粉丝数 IN 列表** |
| ③ 粉丝数 ≥ 阈值 | `users.follower_count >= video.bigv.threshold`（默认 10000） | DB 真值，按 `video.feed.bigv-query-batch`（200）分块 |

**第③路必须保留**：冷启动 / 历史数据未入状态表时，已达标作者不得因"没有状态行"被突然降级。
②③的关系 = ③ 是兜底真值，② 只负责"曾达线、现处带内"的作者**不翻转**（滞回收益 = 带内作者仍按大V早退
⇒ fanout 写扩散归零）。

> **写侧**的滞回判规则只在 `AutoBigVStateService` 表达（两处职责不重叠）：`threshold` = 升级线，
> `downgradeRatio`（默认 0.8）= 降级线系数；两线之间为"带内"，状态不翻转。

### 6.3 两个易误读点

- **批量要分块**：`isBigVBatch` 的 IN 列表 ∝ 关注数（**无上限**）⇒ 按 `bigv-query-batch` 切多条 SQL。
  切分发生在**同一只读事务内**（一次连接借还 + **同一 read view**）⇒ **跨块无时序偏差**。
  ⚠️ 这是本方法**保持事务的唯一理由**（不是"取连接的手段"）。
- **`isBigV(authorId)` 是同类自调用** ⇒ `isBigVBatch` 上的 `@Transactional` **不生效**。
  **无害**：单作者 ⇒ 只有一块 SQL ⇒ 无跨块 read view 问题。

### 6.4 失败面（fail-open）

任一 SQL 失败 ⇒ 结论行 WARNING（不带栈）+ **整批只返回名单命中项**（其余按普通作者处理）。
**不做"成功块部分合并"**。方向：写路径"宁可多写不少写"（残影由读路径按 contentId 去重兜底）；
读路径"少显示一批、**不 500**"（下次请求自愈）。

---

## 七、重建与防抖

### 7.1 触发链

`FollowChangedFeedListener`（AFTER_COMMIT）→ `InboxRebuildNotifier`（含去抖）→ MQ → `FeedRebuildConsumer`
→ `FeedRebuildService.rebuildInbox(userId)`。

**为什么要去抖**（`InboxRebuildDebouncer`，窗口 `video.feed.rebuild-debounce` 默认 1s）：投递是
**单 worker 串行**、消费是**单 channel / prefetch=1 串行** ⇒ N 条重建消息基本不重叠、逐条到达 ⇒
`FeedRebuildService` 那把 `SET NX EX` 锁**几乎永远拿得到** ⇒ **N 次连续关注 ≈ N 次真实整窗重算**。
去抖器把"事件即投递"改为"事件入桶、桶静默满一个窗口后投一条"，**合并窗口内 N 次事件为 1 条**（尾沿）。

### 7.2 重建六步（`FeedRebuildService.rebuildInbox`）

```
① 取锁（SET NX EX，UUID token）
② 读关注集（DB 真值 findAllFollowedUserIds，**不读 FollowCache**）
③ 排除大V作者（判定单点 isBigVBatch，与 fanout/读同源）
④ 单事务「替换窗口 + 写同步状态」 ← FeedWindowWriter（独立 bean，事务边界）
⑤ 提交后失效该用户收件箱缓存
⑥ 释放锁
```

**②为什么不读缓存**：关注集走 DB 真值，与"核对 oracle 同源"；且窗口成本 ∝ 关注数 × K，
缓存窗口读会引入与当前快照不同的时序。

**③为什么排除大V**：fanout 对大V作者跳过写扩散；重建若收录 ⇒ 同一内容在"收件箱窗口 ∪ 大V发件箱"
两路都出（仅靠读侧按 contentId 去重兜底）⇒ **排除即"不与发件箱重复"**。

### 7.3 ★ 顺序红线（`FeedWindowWriter`，挪动即丢消息）

事务内顺序 = **① 先清（`deleteByUser`）→ ② 窗口重查 → ③ 归并裁剪 → ④ `INSERT IGNORE` → ⑤ upsert 同步状态**，
且**此后不得再有任何删除动作**。

理由（锚在 `feed_inbox` 这个 DB 真相层）：

- 某条内容的 fanout 行若在本事务 DELETE **之前**提交 ⇒ 其 `content` 行必已提交于 DELETE 之前，
  而窗口快照读（一致性读）发生在 DELETE **之后** ⇒ **必被本次快照收录**；
- 若在 DELETE **之后**到达 ⇒ 落在"已清空区"且此后无删除 ⇒ **保留**。

⇒ 最终窗口 ⊇ 本次快照，多出的成员只来自 DELETE 之后到达的 fanout——**只多不丢**。
故 **DEL 必须早于窗口重查，且其后不得再删**（先查后清会在"查完 → 清"的窗口里丢掉 fanout 增量）。

**同步状态必须与窗口替换同事务**：否则"同步态已写但窗口回滚"会让读侧闸门误信一个陈旧窗口。
**空窗口同样写**（"空但已同步" = 该用户确实没有可看的内容）。

### 7.4 并发与失败

- **锁只是去重优化，不承担正确性**：TTL（60s）到点后若真有并发重建交叉执行，两次都是同一份 `content`
  真相的窗口快照（`INSERT IGNORE` 幂等）⇒ **并集、只多不丢**。
- **任何失败都不抛出**：Redis / DB 失败一律**降级吞掉并 ACK**（**不抛给消费容器转死信**）——
  缺口已有读态闸门"未同步 ⇒ 回退纯拉"与下次关注/取关重建兜底。只有**载荷非法**才在消费者
  （`FeedRebuildConsumer`）侧抛出、重试耗尽后转死信。

### 7.5 去抖器的两处已知局限（登记）

① 可见性上界 = 窗口 + 投递 + 消费；
② 内存去抖 = **单实例语义**（多实例部署需重评）；
③ 持续高频事件流（同一用户间隔 < 窗口且不停）下尾沿被**无限推迟** —— 属不正常行为、责任归限流专项，
故**刻意不加 maxDelay 上界**。

---

## 八、依赖方向与消环（为什么编排落在 feed 域）

**这是本篇最需要解释的"为什么"**——否则后人会以为 `FeedService` 放错了地方。

`/feed` 的读编排**有意**落在 `feed` 域（历史实现把它放在 content 的 service 包，并形成
`content ⇄ feed` 双向依赖）。本项目把编排整体落在 feed 域，于是：

```
                        ┌─── 事件 ───┐
   content ──发 ContentPublishedEvent──►  feed（订阅）
      ▲                                      │
      └──────── Service 契约 / DAO 薄依赖 ────┘
```

- **跨域只走对方的 Service 契约**：`ContentService.loadContentVOs`、`FollowService.getFollowingIds`、
  `LikeService.batchIsContentLiked`。
- **外加 DAO 薄依赖**：`content.dao.ContentDao`、`follow.dao.FollowDao`、`user.dao.UserDao`
  （读侧窗口 / 重建 / 大V判定所需）。
- **禁止跨域触碰 `cache` 包**（不得读 `follow.cache`）——由 ArchUnit 规则 2 变成会失败的测试。
- **跨域事件只能由自己的 `event` 包消费**——ArchUnit 规则 3；故订阅方落在 `feed.event`。
- **事件方向是单向的**：content 发"内容已发布"、feed 订阅 ⇒ **content 不再依赖 feed**（消掉环）。

> 结论：`feed → {content, follow, like}` 单向，加上"content/follow 只发事件、不反向依赖"，
> 全链路无包环。

---

## 九、失败面与降级一览

| 环节 | 失败 | 行为 | 影响 |
|---|---|---|---|
| 读：闸门 / 收件箱腿 DB | `DataAccessException` | **上抛 ⇒ 500** | 不留"半条时间线"（与纯拉一致） |
| 读：收件箱腿 Redis | 缓存不可用 | `FeedCache` 内部降级走 DB | 对调用方不可见 |
| 读：大V发件箱腿 | DB 失败 | **fail-open** 吞掉 | 少一批大V内容、不 500、下次自愈 |
| 读：窗口路径深翻 | `offset >= total` | 返回空页、`total` 不变 | 前端自然停止翻页 |
| 写：fanout DB | 落库/游标失败 | **抛出** → 容器本地重试 → 耗尽转死信 | 幂等重放 |
| 写：fanout 缓存失效 | DEL 失败 | 不停写；首次 WARNING 后停用后续批次失效 | DB 真相优先、读自愈 |
| 重建：任意过程失败 | Redis / DB | **降级吞掉 + ACK** | 由"未同步 ⇒ 回退纯拉"与下次重建兜底 |
| 重建：载荷非法 | 空/非法 JSON | 消费者抛出 → 重试 → 死信 | 保留证据 |
| 去抖器 | 已关停 / 窗口 0 | 立即执行或降级丢弃（绝不抛） | 不影响关注/取关接口 |

---

## 十、参数一览（`video.feed` / `video.bigv`）

| 键 | 默认 | 含义 |
|---|---|---|
| `video.feed.inbox-window-per-author` | 20 | 重建/补推时**每关注作者取最近 K 条** |
| `video.feed.inbox-window-max` | 200 | 重建窗口裁剪上界 **C**（表侧每用户行数上限） |
| `video.feed.read-window-max` | 300 | 读侧归并窗口裁剪上界 **M**（收件箱腿 ∪ 大V发件箱腿） |
| `video.feed.outbox-window-size` | 20 | 大V发件箱腿每作者取最近 **N** 条 |
| `video.feed.inbox-ttl` / `video.feed.outbox-ttl` | 60m | 收件箱 / 发件箱读缓存 TTL（命中滑动续期） |
| `video.feed.fanout-batch` | 200 | 写扩散粉丝游标每批批量 |
| `video.feed.rebuild-author-batch` | 50 | 重建时"逐作者最近 K"的 SQL 批尺寸 |
| `video.feed.bigv-query-batch` | 200 | 大V批量判定的 IN 分块尺寸（**执行参数**，非判定输入） |
| `video.feed.rebuild-debounce` | 1s | 重建请求去抖窗口；**0 = 显式关闭去抖** |
| `video.feed.dlq-ttl` | 7d | 死信队列消息保留时长 |
| `video.bigv.threshold` | 10000 | 升级线：粉丝数 ≥ 阈值 ⇒ 状态表插入（"存在即自动大V"） |
| `video.bigv.downgrade-ratio` | 0.8 | 滞回降级系数：粉丝数 < ratio × threshold ⇒ 状态表删行 |
| `video.bigv.user-ids` | 空 | 大V名单（显式；读侧命中即大V，不经阈值） |

> `FeedProperties` 在**绑定期**校验（NaN / 非正数 / Duration 缺单位 = 启动即拒）；
> `BigVProperties` 的 `downgrade-ratio` 合法区间 `(0, 1]`。

---

## 十一、边界与去向（机制外链）

本篇刻意**不展开**以下内容，它们属横切机制，待 `tech/` 各篇建立后改为链接：

| 不在本篇的内容 | 去向（规划中） | 本篇只保留的边界 |
|---|---|---|
| 缓存三态 / 空标记 / 两件套 / 单飞 / 熔断 | 《缓存与数据一致性》 | "写后失效 + miss 回源"的口径一句话 |
| MQ 拓扑 / 投递三态 / 补偿缓冲 / 探针 / 幂等 / DLQ | 《MQ 与投递可靠性》 | 只写"谁投什么、失败往哪走" |
| 事务切分原则 / 传播约定 | 《事务边界》 | 只标出事务边界载体类名（`FeedWindowWriter` / `FeedBatchWriter` / `FeedPullReader`） |
| 事件解耦范式（"各方订阅、禁止代发"） | 《领域事件与解耦》 | 只写 feed 订阅了哪两个事件、为何单向 |
| 日志 / 指标 / 审计 | 《可观测》 | — |

### 与历史实现的有意差异（只列需要读者知道的）

| 差异点 | 说明 |
|---|---|
| `/feed` 编排位置 | 从 content 的 service 包**迁到 feed 域**，以消 `content ⇄ feed` 环（见 §八） |
| feed 缓存键 | **两件套**（数据 key + `empty:`），**无 `partial:`**——收件箱键的写者只有读路径，读路径也不做前缀装载 |
| 大V名单配置 | 外置 Properties 文件 + 热更**不搬**；名单并入 `video.bigv.user-ids`（值单一源） |
| 投递可靠性 | 拓扑 / 连接自动恢复 / 有限重试 + DLQ / 预取交框架原生；异步投递线程池与内存补偿缓冲**已补齐** |
