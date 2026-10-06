# MQ 与投递可靠性

> 定位（先读这一句）：**本仓 MQ 的拓扑、可靠性配置、投递管道与补偿口径** —— 一件事只有一处。
> **本篇只讲"机制"**：拓扑怎么声明、失败往哪走、投递为何不占 Web 线程、补偿缓冲为何存在。
> feed 的 **fanout 业务**（写扩散步骤 / 游标口径 / 只多不丢）与**重建去抖语义**归
> [feed 域架构](../domain/feed.md)——本篇**只链接不复述**；缓存 / 事务 / 事件范式 / 观测同样不在本篇，
> 去向见文末 [§十 边界与去向](#十边界与去向机制外链)。
>
> 证据来源：`feed/mq/*`、`common/config/Feed*.java`、`application.yaml` 的 `spring.rabbitmq.*`。
> 类名 / 配置键给默认值；结论只从代码取证。

---

## 一、全景：一条消息从发布到消费

```
① 发布（AFTER_COMMIT，见 ../domain/feed.md 的事件订阅）

   FeedPushNotifier.publishContentPublished ─┐
   AuthorBackfillNotifier.publishAuthorBackfill ─┤
   InboxRebuildNotifier.publishInboxRebuild ─► InboxRebuildDebouncer（去抖，见 ../domain/feed.md）
                                                 └─┐
                                                   ▼
                         FeedDelivery.deliver（非阻塞、绝不抛）
                                                   │
                                                   ▼
                 FeedDeliveryDispatcher.submit（单 worker + 有界队列，§五）
                                                   │   worker 线程
                                                   ▼
        ┌────────────── FeedDeliveryBuffer.publish（§六）──────────────┐
        │  处于"不可达窗口"：零 I/O 暂存，不触达 broker ──► pending 队列 │
        │  否则（可用）──►                                              │
        └───────────────────────────────┬───────────────────────────────┘
                                        ▼
                    FeedPublisher.publish（RabbitTemplate.send，绝不抛，§四）
                                        │
        ┌───────────────────────────────┴────────────────────────────────┐
        ▼ (1) feed.push                     (2) feed.rebuild              │
  feed.push.exchange [topic]          feed.rebuild.exchange [topic]      │
        │ binding: feed.push.#              │ binding: feed.rebuild.#     │
        ▼                                   ▼                             │
  feed.push.queue                    feed.rebuild.queue                 │
        │                                   │                             │
        ▼ FeedPushConsumer                  ▼ FeedRebuildConsumer         │
   ├ rk=feed.push.content                  rk=feed.rebuild.inbox         │
   │   ─► FeedInboxWriter.fanout            ─► FeedRebuildService          │
   ├ rk=feed.push.backfill                    .rebuildInbox(userId)       │
   │   ─► FeedInboxWriter.backfillAuthor                                 │
   └ 其它 rk：debug 跳过（不死信）                                        │
        │                                   │                             │
        │ 消费失败：本地重试(2)耗尽 ⇒ reject(requeue=false) ⇒ 队列 DLX ────┤
        └───────────────┬───────────────────┘                             │
                        ▼                                                     │
                  feed.dlx [direct]  (rk = feed.dlq)                          │
                        ▼                                                     │
                  feed.dlq  （消息 TTL = video.feed.dlq-ttl，默认 7d）────────┘
                                                              （超期自动删除 = 证据窗口有界）
```

**一句话链路**：业务方（`FeedDelivery.deliver`）只把消息丢进**后台单 worker 的有界队列**即返回；
worker 经**补偿缓冲**转 `FeedPublisher` 落 broker；broker 按 topic 通配路由到队列，
两类消费者各自处理，**重试耗尽才转死信**。

---

## 二、拓扑与声明

### 2.1 声明方式：`Declarables` + `RabbitAdmin`

`FeedRabbitConfig.feedTopology(FeedProperties)` 返回**一个 `Declarables` bean**（3 交换机 + 3 队列 + 3 绑定）。
Boot 自动装配的 `RabbitAdmin` 实现 `ConnectionListener`，在**每条新连接**上幂等声明全部元素——
同名同参重复声明在 broker 侧是 no-op。**命名与形态的唯一源是 `FeedTopology`**（常量），
业务侧不得另造字符串字面量（口径同 `common.cache.CacheKeys` 的"命名唯一源"）。

### 2.2 拓扑要素（确切名字）

| 交换机（type/durable） | 队列（durable） | 绑定 / 路由键 | DLX（死信路由键） | TTL |
|---|---|---|---|---|
| `feed.push.exchange`（topic） | `feed.push.queue` | 绑定 `feed.push.#`；路由键 `feed.push.content`、`feed.push.backfill` | `feed.dlx`（rk `feed.dlq`） | 队列无 TTL |
| `feed.rebuild.exchange`（topic） | `feed.rebuild.queue` | 绑定 `feed.rebuild.#`；路由键 `feed.rebuild.inbox` | `feed.dlx`（rk `feed.dlq`） | 队列无 TTL |
| `feed.dlx`（direct） | `feed.dlq` | 绑定 rk `feed.dlq` | **无**（自身不设死信参数，防环） | `x-message-ttl` = `video.feed.dlq-ttl`（默认 **7d**） |

交换机均 `durable=true`、`autoDelete=false`；队列均 `durable=true`、非排他、非自动删。
**死信路由键固定为 `feed.dlq`（不沿用原 key）** —— 避免 DLX 路由不确定。

### 2.3 通配绑定与前向兼容

两个业务队列都以**通配模式**绑定（`feed.push.#` / `feed.rebuild.#`）：**将来加消息子类型免重声明绑定**。
代价与配套纪律：

- 消费者**必须自己按 `receivedRoutingKey` 分发**（`FeedPushConsumer.handle` / `FeedRebuildConsumer.handle`）；
- **未登记的路由键只记 debug 后跳过，不投死信**（避免未知类型在 DLQ 堆积）——前向兼容的代价是
  "未知键静默跳过"，这是有意口径。

### 2.4 `video.feed.dlq-ttl` 的两个硬约束

1. **TTL 是队列声明参数**：broker 拒绝同名不同参的重复声明（`406 PRECONDITION_FAILED`）——
   调整该值后**必须先在 broker 删除既有 `feed.dlq` 队列**。
2. **值经 `Math.toIntExact(long)` 转换**：`x-message-ttl` 是 32 位整型，上界
   `Integer.MAX_VALUE` 毫秒（约 **24.86 天**），**超界在 `feedTopology` bean 创建期即抛异常**
   （启动即拒），不会静默溢出为负 TTL——报错点落在启动期而非远处的 broker 406。

---

## 三、可靠性配置

全部取自 `application.yaml` 的 `spring.rabbitmq.*`（无自研连接管理，交框架原生）。

| 配置键 | 值 | 作用 |
|---|---|---|
| （连接自动恢复） | 框架默认开启，本仓**未显式关闭** | 断线自动恢复、消费者自动重建（`FeedPushConsumer` 类注释）；`InboxRebuild`/`Feed` 消费者挂载用 `@RabbitListener`（`SmartLifecycle`），随上下文启动 |
| `connection-timeout` | `2000ms`（dev） | broker 不可达时 `RabbitTemplate.send` 单次等待上界（§四、§五的代价来源） |
| `listener.simple.prefetch` | `1` | 见 §3.2 |
| `listener.simple.concurrency` | `1` | 每队列一条消费 channel（隔离 head-of-line） |
| `listener.simple.acknowledge-mode` | `auto` | 成功 ⇒ ack；重试耗尽 ⇒ reject |
| `listener.simple.retry.enabled` | `true` | 容器内**本地有限重试**（同线程退避） |
| `listener.simple.retry.max-retries` | `2` | **首次之外**的重试次数（非总次数） |
| `listener.simple.retry.multiplier` | `1` | **固定**退避（有意：`2` 会变成指数退避） |
| `listener.simple.retry.initial-interval` | `1000ms` | 固定退避间隔 |
| `listener.simple.retry.max-interval` | `10000ms` | 退避上限 |
| `listener.simple.default-requeue-rejected` | `false` | **重试耗尽后不重回队列** ⇒ 经队列 DLX 转死信 |

### 3.1 本地有限重试 + 耗尽转死信

消费回调**抛出**（解析失败 / DB 类失败）⇒ 容器在**同一消费线程内**按 `retry.*` 退避重试，
`max-retries=2` 次后仍失败 ⇒ reject（`default-requeue-rejected=false`）⇒ 经源队列的 DLX 参数
路由到 `feed.dlx` → `feed.dlq`。**重放从方法入口整体重跑**（消费者不做部分确认），
靠消费侧幂等对冲重复（§七）。**消费者不重复记栈**（`FeedPushConsumer` 类注释）：异常栈由容器出口统一记录。

### 3.2 为什么 `prefetch=1`

预取大于 1 会让**同一队列在途多条**消息，破坏"失败重试时本队列其余消息不被耽搁"的平衡；
`concurrency=1` 配合 `prefetch=1` ⇒ 同一队列**串行**处理，失败消息的本地重试不会与其它消息交错
（Boot 默认 `prefetch=250`）。代价：单队列吞吐受限于串行——这是为"重试语义可证"付的代价。

---

## 四、发布侧：不等 confirm 的取舍

### 4.1 不启用 `publisher-confirm`

配置中**没有** `publisher-confirm-*`（Boot 默认即不启用），`FeedPublisher` 直接用
`RabbitTemplate.send(exchange, routingKey, Message)`。语义：

- `send` **写完 socket 即返回**——"天然不阻塞"**只在 broker 可达时成立**；
- broker 不可达时会先等一次 `connection-timeout`（dev 2s）才抛 `AmqpException`。

两个**已登记的代价**：

1. **`SENT` ≠ broker 已确认**（`DeliveryOutcome.SENT` 注释明示）——消息可能丢在 broker 侧；
2. **发送中途连接断开**（状态不明，可能已投出）也归入 `UNAVAILABLE` ⇒ 走**至少一次**重放，**依赖消费侧幂等**。

### 4.2 `FeedPublisher.publish` 的三态返回值

**契约：绝不抛异常**（投递失败只降级，不影响发起它的发布 / 关注 / 取关业务）。

| `DeliveryOutcome` | 触发 | 处置 |
|---|---|---|
| `SENT` | `send` 返回（写 socket 成功） | 视为已投出（**不等于** broker 确认） |
| `UNAVAILABLE` | `AmqpException`（broker 不可达 / 连接级故障）；或其它 `RuntimeException`（兜底） | **入补偿缓冲**，恢复后重放（§六） |
| `UNSENDABLE` | `JsonCodec` 序列化失败（`CacheUnavailableException`） | **不入缓冲**——重放必然同样失败，就地丢弃 + WARNING（**持栈**，该链唯一捕获点） |

**为什么必须三态而非 boolean**：补偿缓冲成立的前提是"重放会成功"。二态会把 `UNSENDABLE`
（编程错误）也塞进缓冲，永久占位并在每次探测里重复失败一次——既是内存泄漏也是噪声。

日志口径：`UNAVAILABLE` 记 **WARNING 结论行不持栈**（`RabbitTemplate` 内部已记一次异常，
"一条故障只留一条诊断"）；`UNSENDABLE` 持栈。

> 发布侧**未配置** `template.retry`：发布侧重试由"补偿缓冲 + 消费侧幂等"承担，不叠一层模板重试。

---

## 五、后台单 worker：为什么投递不能占用 Web 线程

`FeedDelivery.deliver` 是三类投递的**统一门面**（rule of three），管道：
`deliver → FeedDeliveryDispatcher.submit →（worker 线程）FeedDeliveryBuffer.publish → FeedPublisher.publish`。

**核心动机**：一旦 broker 不可达，`RabbitTemplate.send` 会等一次 `connection-timeout`（dev **2s**）。
若投递发生在 Web 线程（发布 / 关注 / 取关请求），**每次命中就是一次 2s 的 RT 阻塞**。
`FeedDeliveryDispatcher` 把这笔等待整体挪到**专用后台单线程**，Web 线程只做"入队"即返回。

形态（`FeedDeliveryDispatcher`）：

- **单 worker + 有界队列**（`ThreadPoolExecutor(1,1)` + `ArrayBlockingQueue(queueCapacity)`，
  容量 `video.feed.delivery.queue-capacity` 默认 **1000**）；
- **单 worker 是设计而非省略**：只保证"同一提交源内的提交序"（写扩散与重建本就来自不同线程 / 时刻），
  **不宣称跨来源全局序**；真实瓶颈在 broker 连接而非 CPU；
- **有界队列 = 背压**：队列满 ⇒ 拒绝（默认 `AbortPolicy`）⇒ `submit` 捕获 `RejectedExecutionException`
  记 WARNING 并返回 `false`（**降级丢弃，不影响业务**）；**不得用 `CallerRunsPolicy`**（会把等待拉回 Web 线程，解耦白做）；
- **`submit` 绝不抛**：`null` 任务 / 已关停 / 队列满 / 意外异常一律降级出口；
- **MDC 串联**：提交时捕获 `reqId`、worker 内恢复、执行完还原 worker 原上下文（异步段日志不掉 `req=`）；
- **关停有界 drain**（`@PreDestroy`）：停收 → 在 `video.feed.delivery.drain-timeout`（默认 **5s**）内投完存量 →
  超时 `shutdownNow()` **丢弃剩余**并记 WARNING（不挂住应用关停）。worker 为守护线程。

**残余（登记）**：① 队列满 / 关停期未投完 ⇒ **丢弃**（由收件箱重建 / 纯拉自愈兜底）；
② 内存队列 ⇒ **单实例语义**（多实例部署需重评）；③ 本类**不承诺吞吐提升**。

---

## 六、补偿缓冲：不可达窗口

`FeedDeliveryBuffer`：broker 不可用时**确定投不出去**的消息暂存**有界内存队列**，恢复后重放。

### 6.1 为什么能安全重放

三类消息的**消费侧本就幂等**：写扩散 / 补推 = `INSERT IGNORE`；重建 = 整窗替换 + `SET NX EX` 去重锁
（明细见 [feed 域](../domain/feed.md)）。⇒ 重放是**至少一次**语义、零副作用。**这是本方案成立的基石。**

### 6.2 不可达窗口（本类必须先于"每次投递都等连接超时"）

若每次投递都真去撞 `connection-timeout`（dev 2s），一次 MQ 故障会让**后台单 worker 每 2s 才吞下一条**消息
⇒ 有界队列（1000）很快被灌满 ⇒ **补偿反而失效**（新消息进不来、也进不了缓冲）。
故本类自持一个**极小的不可达窗口**状态（单个 `volatile long downSinceMillis`，`0` = 可用）：

- 一次 `UNAVAILABLE` ⇒ 进入"不可达"；
- **窗口内新消息直接暂存、不触达 broker**（零 I/O）；
- 窗口期满 ⇒ 放行**一次**真实投递作为探针。

用**单个 `volatile long`**（而非 `boolean + long`）是因为两个字段无法原子快照，跨线程读者可能读到
"新 down 配旧时刻"⇒ 提前放行、破坏"窗口内不空转"。

### 6.3 重放触发 = 定时探测（唯一通道）

`@PostConstruct start()` 以 `scheduleWithFixedDelay` 每 `video.feed.compensate.probe-interval`
（默认 **30s**）触发 `probe()`：**缓冲为空则零 I/O 直接返回**；非空则 `flush`（`AtomicBoolean` 互斥）逐条重放。
恢复后可补偿的**时延上界 = 探测周期 + 投递 + 消费**。

- **探针不受 `allowAttempt()` 约束**（它就是窗口的触发器，且要能自驱恢复）——因此本仓在
  "启动时 broker 不可达"下**能自驱恢复**（不依赖业务投递触发重放）。
- **代价（登记）**：broker 持续不可达且缓冲非空时，**每个探测周期付一次连接超时**
  （dev 2s，占调度线程）；一个周期内**热路径与探针叠加时最多 2 次**。上界简单可证，故不做额外裁剪。
- `start()` **绝不抛**；若定时任务启动失败 ⇒ **彻底失去自动重放**（积压等同丢失，残余①）——必须显式告警。

`flush` 口径：`SENT` 计数继续；`UNAVAILABLE` ⇒ 记 down、**放回队尾并结束本轮**（避免坏状态下空转）；
`UNSENDABLE` ⇒ 就地放弃（重放无意义）。投出 > 0 时记一条 INFO 结论行（常态零输出）。

### 6.4 容量与失败口径

- 缓冲**有界**（`video.feed.compensate.buffer-capacity` 默认 **10000**）；满则丢弃新消息，
  **首次记一条 WARNING 后节流**（不按条刷），队列腾出后自动恢复告警；
- 空载荷早退（投出去只会让消费侧解析失败 → 白占 DLQ）；
- 关停（`@PreDestroy`）⇒ 丢弃剩余（汇总一条 INFO），不阻塞关停。

### 6.5 残余（登记）

① 内存缓冲 ⇒ **应用重启 / 崩溃即丢**（跨重启不可补）；② 关停期剩余**丢弃**；
③ 缓冲满 ⇒ 丢弃 + 节流 WARNING；④ 重放 = 至少一次 ⇒ **依赖消费侧幂等**（已具备）；
⑤ 内存态 ⇒ **单实例语义**（多实例部署需重评）。

> 与 TV 的差异：TV 另有"连接回调 `onConnected` 立即重放"的加速通道，本仓**不搬**
> ——Spring AMQP 的连接自动恢复不暴露等价的业务可见钩子。本仓探针**会真的尝试投递**
> （而非只读本地状态），故"启动时不可达"能自驱恢复（§6.3）。

---

## 七、消费侧幂等与投递语义

### 7.1 语义：至少一次

客户端在每个故障分支都倾向**重投**：`SENT` 不保证送达（§4.1）、`UNAVAILABLE` 覆盖"状态不明"（§4.2）、
容器本地重试会**从方法入口整体重跑**、连接自动恢复可能重发。⇒ 全链**只承诺至少一次**。

### 7.2 幂等靠什么

| 投递 | 消息 | 幂等手段 |
|---|---|---|
| 写扩散 `feed.push.content` | `FeedPushMessage(contentId, authorId)` | `FeedInboxWriter.fanout` 内 DB 批量 **`INSERT IGNORE`**（唯一键静默忽略重复行） |
| 降级补推 `feed.push.backfill` | `AuthorBackfillMessage(authorId)` | `FeedInboxWriter.backfillAuthor` 同为 **`INSERT IGNORE`** 追增 |
| 收件箱重建 `feed.rebuild.inbox` | `InboxRebuildMessage(userId)` | **整窗替换**（清窗 + 重查 + 归并裁剪 + `INSERT IGNORE` + upsert 同步态）+ `SET NX EX` 去重锁 |

**重放从方法入口整体重跑**：消费者不做"部分确认"，失败即整条消息重来——
上限由幂等手段吸收（重跑等价于再执行一次同一幂等操作）。

> 具体写扩散步骤 / 游标口径 / "只多不丢"不变量 / 重建六步与去抖语义**归 [feed 域](../domain/feed.md)**，本篇不复述。
> 重建过程自身的 Redis / DB 失败在 `FeedRebuildService` 内**降级吞掉并 ACK**（不走死信）；
> **只有"载荷不可用"（空 / 非法 JSON）才在消费者侧抛出** ⇒ 重试耗尽转死信留证。

### 7.3 消息契约（载荷只放可重算的最小标识）

`FeedPushMessage(contentId, authorId)` / `InboxRebuildMessage(userId)` / `AuthorBackfillMessage(authorId)`：
**只放接收侧真正需要的标识，不放任何内容快照**——收件箱是**派生产物**，内容按 DB 真相在消费时刻重算；
一旦塞入不可重算的快照，收件箱就从"派生副本"变成"真相源"，无法再靠重建自愈。
序列化走 `common.cache.JsonCodec`（JSON / UTF-8），发布侧 `contentType=application/json`。

---

## 八、配置：为什么拆成三个 record

| record | 前缀 | 键（默认） | 关注点 |
|---|---|---|---|
| `FeedProperties` | `video.feed` | `dlq-ttl`（7d） 等 feed 业务窗口 / 批尺寸 | feed**业务** |
| `FeedDeliveryProperties` | `video.feed.delivery` | `queue-capacity`（1000）、`drain-timeout`（5s） | 投递**基建**（线程池） |
| `FeedCompensateProperties` | `video.feed.compensate` | `buffer-capacity`（10000）、`probe-interval`（30s） | 投递**基建**（补偿缓冲） |

### 8.1 为什么投递 / 补偿参数各自独立成 record（不塞进 `FeedProperties`）

两条理由（`FeedDeliveryProperties` / `FeedCompensateProperties` 类注释）：

1. **关注点不同**——`FeedProperties` 是业务窗口 / 批尺寸，另两个是投递基建的容量 / 关停上界；
2. **键形状不同（硬约束）**——**record 只能绑到自身前缀下的扁平键**；想保留
   `feed.delivery.*` / `feed.compensate.*` 的**嵌套形状**，就必须给独立前缀。
   若把嵌套键塞进 `video.feed` 的扁平 record ⇒ **嵌套键被静默忽略并绑成 0**
   ——症状是"配置写了却不生效"，离病因远（口径同 `video.cache.redis-breaker`）。
   `application.yaml` 里这两段是**嵌套键**，正是绑到独立前缀的实证。

### 8.2 校验口径

三个 record 都在**绑定期校验**：`queueCapacity` / `bufferCapacity` / 各窗口与批尺寸非正 ⇒ **启动即拒**；
`drainTimeout` / `probeInterval` 为 `Duration`，**必须带单位后缀**（裸数字会被静默解析成秒级）。
`dlq-ttl` 另有 `Math.toIntExact` 上界（§2.4）。

### 8.3 `probe-interval` 入配置的唯一理由

TV 的探测周期是 `MqDeliveryBuffer` 的**包内常量**（`30_000ms`，原注"非必要不入配置"）。
本仓把它入配置的**唯一理由**：让"恢复后重放"这件事可被**运维与故障注入测试驱动**
（默认值不变 = 原口径）。它不是可随意调的调优参数——它**同时是"不可达窗口"长度与恢复补偿的时延上界**。

---

## 九、新代码检查清单

**要发一条 feed 消息时**：

- [ ] 目标交换机 / 路由键**只从 `FeedTopology` 取**，不写字符串字面量（§2.1）。
- [ ] 从 `FeedDelivery.deliver` 出去（**不要在业务代码里直接用 `RabbitTemplate`**）；它非阻塞、绝不抛、走后台单 worker。
- [ ] 载荷**只放可重算的最小标识**（id 即可），不放内容快照（§7.3）；用 record，走 `JsonCodec`。
- [ ] 投递点放在 `AFTER_COMMIT`（提交后副作用，见 [feed 域](../domain/feed.md) 的事件订阅）。
- [ ] **不要**给投递加"等 confirm / 等 broker 应答"——发布侧**有意不启用** `publisher-confirm`（§四）。
- [ ] 确保**消费侧幂等**（`INSERT IGNORE` / 整窗替换）——重复投递是常态（至少一次，§七）。

**要加消费者 / 路由键时**：

- [ ] 新 key 落在 `feed.push.#` / `feed.rebuild.#` 通配内**免改绑定**；否则须在 `FeedRabbitConfig` 加队列 / 绑定（§2.2）。
- [ ] 消费者**自己按 `receivedRoutingKey` 分发**；**未登记键记 debug 跳过、不投死信**（§2.3）。
- [ ] 失败一律**抛出**交容器（`retry.*` + `default-requeue-rejected=false` → DLQ），**不要自吞异常**；
      只有"已知缺口有兜底"的重建过程才在 service 内降级 ACK（§7.2，见 feed 域）。
- [ ] 需要新配置时**新开独立前缀 record**，**不要**把嵌套键塞进 `FeedProperties`（否则静默绑成 0，§8.1）。

**要改 DLQ 相关时**：

- [ ] 改 `video.feed.dlq-ttl` 前，**先在 broker 删除既有 `feed.dlq` 队列**（否则 406）；值不得超过 ≈24.86 天（§2.4）。

---

## 十、边界与去向（机制外链）

| 不在本篇的内容 | 去向 |
|---|---|
| feed 的 fanout 写扩散步骤、游标口径、"只多不丢" | [feed 域架构](../domain/feed.md)（本篇只链接） |
| 重建触发链、去抖语义、重建六步、同步态 | [feed 域架构](../domain/feed.md)（本篇只链接） |
| 事件"谁声明、谁订阅"、`AFTER_COMMIT` 范式、禁止代发 | [领域事件与解耦](./领域事件与解耦.md) |
| 缓存三态 / 空标记 / 写后失效 / 消费侧读缓存回源 | [缓存与数据一致性](./缓存与数据一致性.md) |
| 事务切分原则、事务边界载体（`FeedWindowWriter` 等） | [事务边界](./事务边界.md) |
| 日志四输出端 / MDC / 指标 / 审计 | [可观测](./可观测.md) |
| 模块包结构与依赖方向（`feed/event` 订阅、ArchUnit） | [模块边界与依赖](./模块边界与依赖.md) |

> 本篇**只讲 MQ 机制**：拓扑 / 重连 / 重试+DLQ / 预取 / 发布三态 / 后台单 worker / 补偿缓冲 / 探针 / 幂等。
> 不含任何迁移期文档（决策留痕表 / 遗留台账 / 事务边界决策表 / archive）。
