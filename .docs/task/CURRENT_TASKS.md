# 当期任务（Current Tasks）

> **定位**：登记**当期正在执行的任务**及其执行态（四要素 + 三句以内回写）。
> **计划态**（设计 / 骨架 / 验收口径）在 `architecture/`（横切机制 `tech/`、业务域 `domain/`）——
> 各专项设计稿随收口归档到 `archive/<域>-<归档日>/`（**只读追溯**）；
> **本期范围与反面清单**见 `CURRENT_NEEDS.md` §四。**本文件只记执行态**，不复制计划。
> **需求来源**：`CURRENT_NEEDS.md`（N# / R-##）；**执行中发现的问题** → `CURRENT_ISSUES.md`。
>
> ★ **膨胀控制**：任务完成即标注，**期收尾时清出本文件**（归宿 = git commit）。
> 清出前**必须先把该编号收进** ★ **活速查**（落点见 `CURRENT_NEEDS.md` §三 末）——
> 代码 / 测试注释里的 `T#` / `R-##` / `I-##` 引用，那里是它们唯一的**活**查证入口；
> **理由 / 论证**随正文进 archive / git（**速查只许一行结论**）。
> **编号不回收**（下期顺延）；**登记台文件复用、不改名**。

---

## 周期头（每窗开读）

* ★ **当前状态：本期 = 幂等专项 P1（`R-24` 已拍板 2026-10-24）** —— `T66` / `T67` / `T68` **待执行**。
  设计 = [写接口幂等](../architecture/tech/idempotency/写接口幂等.md)（用例与对外契约见
  [测试与契约](../architecture/tech/idempotency/写接口幂等-测试与契约.md)）。
  上一窗口 P0（`T63`–`T65`）已交付；更早的接口契约专项（`T54`–`T62`）结论见
  [接口契约.md 附录「编号速查」](../architecture/tech/api/接口契约.md#附录编号速查每号一行结论)。
* ★ **下一编号** = 任务 **`T69`** / 决策 **`R-25`** / 问题 **`I-30`** / 结转 **`F-28`**。
  开工前先读：`CURRENT_NEEDS.md`（本期范围与反面清单）· `FURTHER_ISSUES.md`（结转池）·
  `architecture/tech/idempotency/写接口幂等.md`（设计）· `architecture/tech/api/接口契约.md`（现在的对外契约长什么样）。
* ★ **回归基线**（2026-10-24，`T65` 后复跑）：默认集 **811 例**（`-DexcludedGroups=resilience`）——
  全量首跑 **1 例红**：`CoinAddTests#并发各投一枚不突破封顶`（`[500, 200]`，即 `I-28` 的既存死锁抖动，
  **非本次引入**；隔离复跑 **19/19 绿**）；其余全绿。`resilience` 组 **41/41 绿**。
  ⚠️ 本环境跑全量需 `DB_POOL_MAX_SIZE=4`（否则 Testcontainers MySQL 报 `Too many connections`）。
* ★ **执行约定**：一任务一 commit（G1）；每 commit 可编译且自带验收测试 + 反向验证。
  清单只给**目标 / 红线 / 验收入口**，**不写实现步骤**（G13）——执行者自行决定拆法；
  遇到清单前提不成立先登记 `CURRENT_ISSUES.md`，不擅自扩范围。

---

## 运行约定（只保留当前仍然有效的）

| 号 | 约定 |
|----|------|
| G1 | 默认一任务一窗口一 commit；★ **但允许执行者自拆** —— 任务可能是连贯的、要深入调查后才知怎么拆，执行者可自行拆子任务与多个 commit（**每 commit 仍须可编译**，message 带任务号），拆法写进回写 |
| G2 | **证据可复核**：入口与验收能定位（文件:行 或 可复现命令），不接受纯文字断言 |
| G3 | **疑问分级**：可自答的 → 任务回写留一句；须裁决的 → **停下问用户**，不擅自拍板、不硬扛、不超范围 |
| G4 | 一任务 = 四要素 + 三句以内回写；探索过程 / 失败尝试 / 中间稿一律走 git，不进本文件 |
| G5 | **声明式机制必须配契约测试**：断言通过 ≠ 机制生效；写「证明某机制」的用例前先自问「去掉那行实现会红吗」（详见仓库根 `AGENTS.md`「测试纪律」节） |
| G6 | 改 schema 走 Flyway **最高版本 +1**（⚠️ **已应用的迁移文件逐字节冻结**——**连注释都不能改**，见 `architecture/tech/data-evolution/数据与Schema演进.md` §2.4）；★ 上次 schema 变更：V6/V7/V8（播放记录专项），**下次 V9** |
| G8 | ★ **跨期约定（升格保留）**：测试必须只断言可观察行为（HTTP 状态 + body + DB 终态）—— 换实现时测试**不许改**；改了就说明测试在抄实现（纪律①）。⚠️ **外部契约变更除外**：断言期望值随契约改属**契约跟随**，但**必须在用例注释里写明"契约变更依据 + 变更点"** |
| G13 | ★ **跨期约定（升格保留）**：清单只给方向与红线，不写死实现步骤 —— 有些问题**只有执行期才看得见**。执行中发现清单前提不成立 / 与既有实现冲突 / 有更好的拆法 ⇒ **自行决断或停下登记 `CURRENT_ISSUES.md` I-##** |
| G14 | ★ **每个任务都显式标注「可质疑」**：**任务可被质疑**（前提不成立 / 需求已被既有实现覆盖 / 执行时机不对）；**设计也可被质疑**。⚠️ 但本项目是学习项目、**无真实流量**（见 `AGENTS.md`「项目定位与优化口径」）⇒ **质疑成立 ≠ 停做**：**先把代价写进回写**；若仍做，注明"**已知代价后选择做**"。**只有质疑指向真实缺陷才必须停下回报** |

> **跨期约定指针（代码直接引用这些号 ⇒ 行不可删）**：`G7`（一期不写缓存 / 不做增量维护，✅ 二期已解除）、
> `G9`（`favorite` 登记进 `ArchitectureTests.DOMAINS`）、`G10` / `G11` / `G12`（**长期红线**）——
> 结论见 **`favorite.md` 附录「编号速查」**（`G#` 表）。
> ⚠️ `C-1`–`C-6`（需求侧通用约定）在 `CURRENT_NEEDS.md` §一。

---

## 已收口（留痕，不留细则）

* ★ **接口契约规范化 `T54`–`T62`** ✅ **2026-10-24 收口** —— 唯一活入口 =
  [接口契约 附录「编号速查」](../architecture/tech/api/接口契约.md#附录编号速查每号一行结论)
  ＋ [接口契约-护栏与Spec](../architecture/tech/api/接口契约-护栏与Spec.md)（登记簿 / `openapi.yaml` 的生成与护栏）。
  **归档判定：不建 `archive/`** —— 本专项的设计是**活文档**（`tech/api/接口契约.md` 不随期滚动），
  与 history / coin 同例；过程留在 git（`T54` 起至 `T62` 的逐条 commit）。
  **`T62` 收口动作**：① 体量治理（`接口契约.md` **47.9 → 39.9 KB**：删与附录重复的进度表、`§2.9`/`§2.10`
  移出为护栏篇、欠账快照压成结论；**`§二` 的 2.1–2.8 编号一个没动** ⇒ 279 处裸 `§2.x` 引用零风险；
  残余代价 = `§二` 仍 371 行 > 300 行建议线，已写进文件头注）；② 过期口径清扫（3 个 Controller 的
  "写 endpoint 收 form / 返回中文文案字符串"注释、coin/history 四篇的 form 与 `/content/detail`、
  12 处 `§2.9`/`§2.10` 引用改指护栏篇）；③ 三道文档门禁 **0 违规**（`doc_stats` 正文已无超 40 KB 文件）；
  ④ 全量回归 **806 + resilience 41 = 847/847 绿**；⑤ 登记台重建（`CURRENT_NEEDS` / `CURRENT_ISSUES`
  清出，`I-27`/`I-28` 结转 `FURTHER_ISSUES`）。
  ⚠️ **对外契约的唯一事实源 = [接口契约.md](../architecture/tech/api/接口契约.md)**，不是本文件。
* **播放记录（history）`T45`–`T53`** ✅ 2026-10-22 收口 —— 结论 / 编号唯一活入口 =
  [history.md 附录「编号速查」](../architecture/domain/history/history.md#附录编号速查活的查证入口)；
  过程见 git（第一轮 `6f895f5..3ea45f1`）。
* 更早：投币 `T36`–`T42`（`domain/coin/` 速查）· 计数缓存 `T43`–`T44`（缓存文档 §5.1/§5.2）·
  收藏 `T1`–`T20`（`archive/favorite-2026-10-15/`）· 限流 `T23`–`T29`（`archive/rate-limit-2026-10-17/`）。

---

## 任务

> 每任务必含**四要素 + 回写**；状态取值：待执行 / 执行中 / 已完成 / 搁置（搁置**必须**注明去向）。
> ★ **每个任务开头的「先质疑」是约定（G14），不是客套** —— 认真回答它。
> ⚠️ **读法（G14 校准）**：块里列的是**已知代价 / 反对理由**，**不是"请自行否决"** ——
> 出现"停下回报"字样时按 G14 理解：**先记代价、再决定**；**只有指向真实缺陷才必须停**。

```
### T# <任务名>（来源 R-## / 设计 §x）【待执行】

> ⚠️ **先质疑（G14）**：
> - **任务**：前提是否成立？需求是否已被既有实现覆盖？执行时机对不对？
> - **设计**：是否弊大于利？有没有更合适的方案 / 更便宜的拆法？
> 质疑成立 ⇒ **停下回报或登记 `CURRENT_ISSUES.md` I-##**（G3 / G13 / G14 允许并鼓励）。

* **入口线索**：
* **目标**：
* **红线边界**：
* **强制探索**：
* **验收**：
* **回写**：（完成后三句内写清改了什么、验证结果；探索过程不写）
```

**本期：`T66`–`T68`（幂等专项 P1，来源 `R-24`）【待执行】** —— 设计 = [写接口幂等](../architecture/tech/idempotency/写接口幂等.md)；
用例与对外契约 = [测试与契约](../architecture/tech/idempotency/写接口幂等-测试与契约.md)。
**上一窗口 `T63`–`T65` ✅ 已交付**（卡片保留在下方作留痕）。

### T66 幂等骨架 + 评论端点接入（来源 `R-24` / `N5` / 设计篇 §三–§十一）【待执行】

> ⚠️ **先质疑（G14）**：
> - **任务**：骨架与第一个端点能否分开做？—— **不建议**：只交付骨架时没有任何 `KEYED` 端点生效，
>   它是"有代码无生效路径"的一步，既拿不到端到端验收，也违反"不上机制的字段就是死字段"的既有口径。
>   故合并；内部可按 G1 自拆为多个 commit（骨架 / 接入 / 契约），**每个必须可编译**。
> - **设计**：★ **注册表必须一次填满**（完整性测试要求全部非 GET 端点显式登记）。这意味着
>   `video` / `post` 一旦登记成 `KEYED` 就**立即生效** ⇒ 本任务必须给它们各跑一条冒烟（同 key 重发不重复建内容），
>   完整矩阵留 `T68`。若认为"分批生效"更安全 ⇒ 停下回报：那需要一个"未启用"的第三档，
>   而设计已明确**不设第三档**（多一档就多一条判定规则）。

* **入口线索**：
  - 新增包 `common/idempotency/`：`IdempotencyScope`（注册表，键 = `"<方法> <路由模式>"`）、
    `IdempotencyProperties`（前缀 `video.idempotency`）、`IdempotencyRedisOps extends RedisOps`（3 个 Lua）、
    `IdempotencyStore`（状态机 + 降级）、`IdempotencyFilter`（`@Order(RateLimitFilter.RATE_LIMIT_ORDER + 10)`）、
    `IdempotencyMetrics`。
  - `common/cache/CacheKeys` 加 `idempotency(slug, userId, clientKey)`（形状 `idem:{slug}:{uid}:{key}`）。
  - `common/exception/ErrorCode` 加 3 个码（400 `IDEMPOTENCY_KEY_INVALID` / 409 `IDEMPOTENCY_IN_PROGRESS` /
    422 `IDEMPOTENCY_KEY_CONFLICT`）；`ErrorCodeContractTests` 要求**每个码有产码点**。
  - `CommentController#add`（现为 `ApiResponse<Void>`）+ `CommentService#addComment`（现为 `void`）+
    新增 `comment/model/vo/CommentCreatedVO`（`N5`）。
  - 注册表扫描口径照 `RateLimitWeightTableTests#完整性_全部非GET端点都已登记`
    （`RequestMappingHandlerMapping#getHandlerMethods` + `getPatternValues`），**并补它缺的反向断言**。
  - 契约面：`接口契约.md`（评论响应形状）、`openapi.yaml`（`-Dapi.openapi.write=true`）、
    `endpoint-inventory.tsv`（`-Dapi.contract.write=true`）—— 生成后**必须 review diff**。
* **目标**：`POST /api/v1/comments` 具备完整幂等语义（效果层 + 响应层）；注册表覆盖全部非 GET 端点；
  缺 key 放行、非法 key 400、同 key 不同 body 422、PENDING 短轮询后 409 四条旁路可用。
* **红线边界**：
  - `@Order` 必须是 `RATE_LIMIT_ORDER + 10`（鉴权 / 限流之内侧）；**不得**挪进 `RateLimitFilter`。
  - **快照先于 `copyBodyToResponse()`**；`COMPLETE` / `RELEASE` 必须 **owner CAS**（禁无条件写）。
  - 缺 key **不得写占位**；只固化 **2xx**；错误响应绝不进快照。
  - 注册表 `NATURAL` 必须逐条真判断（判据：重试不改 DB 终态 **且** 响应可判）；备注只写灰色地带。
  - 不改 schema；不动 `src/main/resources/static/**`；不加 `require-key` 开关；不复用 `X-Request-Id`。
  - 评论 `data` 改 `{"commentId": N}` 属**对外契约变更** ⇒ 相关用例注释写明依据（G8 例外条款）。
* **强制探索**：① `ContentCachingRequestWrapper` 在"handler 没读 body"时缓存为空 —— 2xx 路径是否必然已读？
  取不到 fp 时怎么处理（登记为"无 fp"而非"fp 不匹配"）；② `IdempotencyFilter` 的 `finally` 与
  `RateLimitFilter` 的 `settleMissPenalty` 的执行顺序（会不会在重放路径上误补扣）；
  ③ 短轮询 `sleep` 占的是容器线程：核实本仓 Tomcat 线程池配置，判断 2×100 ms 是否可接受（必要时写进已知代价）；
  ④ `endpoint-inventory.tsv` 是否记录**响应形状**（不记则只需改接口契约 + openapi，别多改）。
* **验收**：
  - 注册表四条断言全绿（`seen` sanity / `missing` / `methodUnlimited` / **`stale`**）。
  - 评论 6 条用例（同 key 重发返回同一 `commentId` + `Idempotent-Replayed` / 副作用只 +1（独立 oracle 直查 DB）/
    并发同 key 只执行一次 / 同 key 异 body 422 且零副作用 / 失败后同 key 可重试 / 缺 key 放行不建键 + 非法 key 400）。
  - `video` / `post` **冒烟各一条**：同 key 重发 ⇒ 内容只有一条（全矩阵归 `T68`）。
  - 反向验证至少 3 条真红：去掉 `COMPLETE` 的 owner 条件 ⇒ 并发用例红；`RELEASE` 改无条件写 ⇒ 断连场景红；
    快照挪到 flush 之后 ⇒ 断连场景红。
  - 契约面 review diff：只有评论响应从 `{}` 变 `{"commentId":N}`；`openapi.yaml` / 登记簿无其他漂移。
  - 全量 `run_tests.py` + `--group resilience` 绿。
* **回写**：（完成后三句内写清改了什么、验证结果；探索过程不写）

### T67 投币端点接入幂等（来源 `R-24` / 设计篇 §三）【待执行】

> ⚠️ **先质疑（G14）**：
> - **任务**：投币已有"封顶 2 枚"的兜底，还需要幂等吗？—— **需要**：封顶只挡超上限，
>   `count=1` 的重试正好落在合法区间（会真扣第二次）。封顶 ≠ 幂等。
> - **设计**：★ **投币是累加语义**（两台设备各投 1 枚 = 2 枚，是两次独立意图）。
>   若实现为了"更严格"改成按 `(user, content)` 去重，就是**改业务行为** ⇒ 已被明确否决。
>   本任务只按 `Idempotency-Key` 去重，**不得**引入任何基于内容的去重键。

* **入口线索**：`CoinController#add`、`CoinService#add`（`@Transactional`，返回 `CoinBalanceVO`）·
  `CoinAddTests`（含 `#并发各投一枚不突破封顶`，即 `I-28` 的既存抖动）· 设计篇 §三 coin 行 / §九 指纹。
* **目标**：同 key 重试 ⇒ 只扣 1 枚、第二次返回**同一 `balance`**；**不同 key 各投 1 ⇒ 累计 2 枚**。
* **红线边界**：
  - 不得改"目标累计量（set）"语义；不得按 `contentId` / `(user, content)` 做去重。
  - `PENDING` TTL 用注册表里的 30 s；快照含 `balance`（重放要能判成功）。
  - `I-28` 的并发死锁抖动不属本任务：并发用例偶发红时，按"隔离复跑"判定，**不许**把期望值改成实测值。
* **强制探索**：① 同一用户对同一内容的**两个不同 key** 是否真的走到两条独立业务路径（不要被缓存或
  `uk_user_content` 提前拦住）；② 重放时 `balance` 是"首次那一刻的余额"——这个语义是否要在契约里写明。
* **验收**：
  - 6 条通用用例 + ★ **两个不同 key 各投 1 ⇒ 最终累计 2 枚、作者收益只加两次**（反向：把 key 换成
    `contentId` ⇒ 本用例必红）。
  - 同 key 重试：`content_coin` 行数不变、`users.coin_deci` 只减一次、`content.coin_count` 只 +1（独立 oracle）。
  - 全量 + resilience 绿。
* **回写**：（同上）

### T68 上传两条接入幂等（来源 `R-24` / 设计篇 §三、§九）【待执行】

> ⚠️ **先质疑（G14）**：
> - **任务**：上传类已有 `T63` 的稳定标识删图，幂等键是否重复劳动？—— 不重复：`T63` 解决"重试删错行"，
>   本任务解决"重试多发一条内容"。两者互补。
> - **设计**：multipart **算不出指纹** ⇒ "同 key 换文件"不会被识别，只能靠 key 本身。这是**有意接受的代价**
>   （读文件进内存更贵），登记在设计篇 §十三 第 3 条；若认为必须识别 ⇒ 停下回报（要改上传通路为流式摘要）。

* **入口线索**：`UploadController#uploadVideo` / `#uploadPost`（`ApiResponse<ContentCreatedVO>`）·
  `ContentService#addVideo` / `#addPost`（各自单 `@Transactional`）· `FileUploadService#saveFile`（UUID 文件名）·
  设计篇 §五（`PENDING` 120 s）、§九（不包装 request）。
* **目标**：两条 multipart 端点具备完整幂等语义，且**第二次请求不落任何文件**（filter 早于解析）。
* **红线边界**：
  - **不包装 request**（`ContentCachingRequestWrapper` 会把文件读进内存）；不参与指纹比对。
  - `PENDING` TTL = 注册表里的 120 s（上传耗时远大于评论）。
  - 快照 ≤ 64 KB（`{"contentId":N}` 远小于此，超限即降级不幂等并记 ERROR）。
  - **孤儿文件清理不属本任务**（`T63` 已登记相关残留）。
* **强制探索**：① 第二次请求"文件没落盘"怎么**可观察地**断言（扫 `upload` 目录文件数，而不是断言"没调用
  `saveFile`"——后者是断言内部调用）；② 首次请求失败时已落盘的文件是否会被重试再写一份（会 ⇒ 登记，不在本任务修）。
* **验收**：
  - 6 条通用用例（两条端点各一遍）。
  - ★ 第二次同 key 请求 ⇒ **上传目录文件数不变**、`content` / `content_media` 行数不变（独立 oracle）。
  - 反向：把 filter 移到 `DispatcherServlet` 之后（或删掉占位的前置性）⇒ 文件数用例必红。
  - 全量 + resilience 绿。
* **回写**：（同上）

### T63 媒体删除改为稳定标识定位（来源 `N4` §G·P0-1）【已完成】

> ⚠️ **先质疑（G14）**：
> - **任务**：这是"幂等需求"还是"缺陷"？—— 两者都是。它在 P1 幂等键下会被掩盖（同 key 重放），
>   但幂等键依赖客户端正确复用 key；改定位是**服务端结构性正确**，且顺手消掉一个"重试即丢用户图"的窗口
>   ⇒ **不该等 P1**。
> - **设计**：代价是**对外契约变更**（删图的选择参数）。若判定"改契约成本 > 丢一张图的概率"⇒ 停下回报。
>   但现状是重试会**删除另一个对象**（不是重复自己那份），默认按"必须修"处理。

* **入口线索**：`ContentController#mediaDelete`（`DELETE /api/v1/contents/{id}/media?type=&sort=`，
  提交后 `deleteFileByUrl`）；`ContentService#deleteMedia`（`findOwnedMedia` → `deleteMediaByContentIdAndTypeSort`
  → `compactImageSort`）；`ContentMediaMapper.xml#compactImageSort`（`sort=sort-1 where type=2 and sort>?`）；
  详情装配 `ContentCache#buildContentMedia` + `jointUrl`（`imageUrls` 是 `List<String>`，**不含 mediaId**，
  且可能带 `video.media.base-url` 前缀）；`FileUploadService#saveFile`（文件名 `UUID + 后缀`）与
  `resolveAbsolutePath`（只认 `/upload/{dir}/{file}`，**带前缀直接返回 null ⇒ 静默不删**）；
  测试 `ContentAuthorTests:130,152,157,161`（全部用 `query("type","2","sort",…)`）；
  契约面 `接口契约.md` §2.5 的 DELETE 例外清单 + `openapi.yaml`。
* **目标**：删图按**稳定标识**定位 ⇒ 重试只可能 404，**不再命中另一张图**；`sort` 退回"仅展示排序"。
* **红线边界**：
  - 不动 `content_media` schema（迁移逐字节冻结）。
  - 仍只允许 `type=2`；`compactImageSort` 保留（前端"第 N 张"语义）。
  - 资源型删除"第二次 404"不变（接口契约 §2.4①），**不得**改成 204。
  - 归属/参数顺序不变：内容 404 → 非作者 403 → `type≠2` 400 → 媒体不存在 404。
  - **本任务内 DELETE 只保留一套定位**（`type + 稳定标识`），不得再留 `sort` 入口。
    ⚠️ 换源 `PUT /contents/{id}/media` **仍按 `type+sort`**（文件动作，接口契约 §2.4② 已冻结）——
    这是**既存形态、本任务不修**，但要在回写里登记残留："删完第 N 张后 `sort` 前移，原 `sort=N+1` 的换源会指向下一张"。
  - ★ **删除语句必须按"查出来的 `mediaId`"删，且判 affected rows**（0 行 ⇒ 404）。
    只把**查询**换成稳定标识、删除仍按 `sort` 是**无效修复**：查完到删之间若有别的请求压缩过 `sort`，照样删错行。
  - ★ **`url` 唯一性不是 DB 约束**（`content_media` 只有 PK 与 `idx_content_id`；存量数据 url 也不是 UUID）。
    查询必须 `ORDER BY id LIMIT 1`（或显式处理多行），**不得**假定唯一 —— 否则命中多行会 `TooManyResultsException` ⇒ 500。
  - ★ **`url` 必须先归一化**：入参可能带 `video.media.base-url` 前缀（客户端拿到的就是拼好的值），
    比较前剥离前缀；**物理删除只用 DB 行的 url**，绝不用客户端传值。
* **强制探索**：① 载体二选一并写判据 —— **`?url=`（本任务取此路：详情零变更，url 是生成后不再复用的业务键）**
  vs `?mediaId=`（真代理键，但详情媒体形状必须带 id ⇒ `imageUrls` 破坏性改造或并列数组 + `openapi.yaml` 变更）。
  推翻条件：出现"同内容内 url 重复"或"客户端必须按 id 操作" ⇒ 改 `mediaId` 并同步详情形状；
  ② grep 现存依赖 `type+sort` 的调用方 —— 已核实：`ContentAuthorTests` **会红**（须按 G8 写依据）、
  `endpoint-inventory.tsv` **不含 query 列**、`RateLimitWeightTableTests` 按**路由模式**查表、`PayloadShapeContractTests`
  不覆盖 `imageUrls` ⇒ 后三者**不受影响**（别再改它们）；旧 SPA `static/**/editWork.js` 调的是 T58 前的旧路径，早已 404（`R-23`）；
  ③ `+` 在 query 里会被解成空格：UUID 与存量文件名都不含 `+`，写一句"入参须 URL-encode"即可，不加机制。
* **验收**：
  - ★ **重试用例（本任务的存在理由）**：3 张图，删第 2 张后**重发完全相同的请求** ⇒ 第二次 **404**，
    且第 3 张**仍在**（终态 url 集合 = 原第 1、3 张）。反向验证：定位改回 `sort` ⇒ 本用例必红（只剩第 1 张）。
  - ★ **并发双删同一标识**：恰好一个 204、一个 404，且剩余 `sort` 仍是 1..n 连续（打红"不看 affected rows / 再压一次"）。
  - 鉴权/归属/参数四条路径逐条保持（含 `type≠2` 400）；`base-url` 非空时带前缀的 url 也能删中（归一化）。
  - 契约面：`openapi.yaml` 重生成 + **review diff**，正确开关是 **`-Dapi.openapi.write=true`**
    （登记簿是 `-Dapi.contract.write=true`；⚠️ 写成 `-Dapi.*.write=true` 会静默不生成）；§2.5 例外清单同步。
  - 全量 `run_tests.py` + `--group resilience` 绿。
* **回写**：契约 `DELETE …/media?type=&sort=` → `?type=&url=`（保留 `type` 保错误顺序、不加 `@NotBlank` 不新增文案）；
  Service 改为 **url 归一化 → 按 url 查代理键（`order by id limit 1`，该表无 `(content_id,url)` 唯一键）→ 按 id 删 + 判 affected rows（0 ⇒ 404）→ 用被删行 `sort` 压缩**，
  并删掉 `deleteMediaByContentIdAndTypeSort` 消除双入口。定向 6/6、全量 **808** + resilience **41** 绿，spec 仅该端点参数变化（2 插 3 删）。
  反向验证两处真红：查询忽略 `url` ⇒ 重试回 **204**（删错行）；忽略 affected rows ⇒ 并发双删 **204/204**。
  ⚠️ 残留：`video.media.base-url` 前缀分支**无集成用例覆盖**（`stripMediaBaseUrl` 只有代码）；换源 `PUT …/media` 仍按 `type+sort`，删图后换源会指向下一张（既存形态、本任务不修）。

### T64 重复领券返回原券码（来源 `N4` §G·P0-3）【已完成】

> ⚠️ **先质疑（G14）**：
> - **任务**：409 是"正确拒绝"，改 200 会掩盖真错吗？—— 只有"**本人已领过这张券**"才 200。
> - **设计**：`I-25` 拒绝过"catch 1062 再翻译"。本任务**不是**推翻它 —— 不猜唯一键，改为**回查订单表**
>   （确定事实）。若认为"多一次点查不值"，停下回报。
> - ★ **优先级必须先定**（否则用例自相矛盾）：**回查在前** —— 已领过 ⇒ 200 + 原码，**与库存 / 时间窗无关**；
>   只有**未领过**的人才可能因库存 0 / 未开始 / 已结束拿 409。

* **入口线索**：`CouponService#grabCoupon`（`@Transactional`：`deductStock` → `insertOrder`）；
  `CouponDao`（**无** `(coupon,user)` 窄查询 ⇒ 需新增）+ `CouponMapper.xml`；`CouponController#grab` → `CouponClaimVO`；
  `GlobalExceptionHandler`（`DuplicateKeyException` → 409 `SYSTEM_DUPLICATE_KEY`）；
  测试 `CouponFlowTests`（重复领券 409 断言）+ ★ **`CouponConcurrencyTests:88-112`**（8 线程断言"1 成功 + 其余全是
  `DuplicateKeyException` + stock 减 1"，**改完必红，要一并改写**）；文档 `接口契约.md` §2.2「已知代价·第二例」
  ＋ ★ 同篇 **`:73` / `:95` 的 409 例子清单**（含"重复收藏、重复领券"）＋ `CURRENT_ISSUES.md` 的 `I-25` 行。
* **目标**：同 `(coupon,user)` 重复领券 ⇒ **200 + 原券码**（幂等重放）；码不变、库存不重复扣、订单仍 1 行。
* **红线边界**：
  - **禁止猜键**：不能 catch 后一律当"已领"（`uk_coupon_code` 撞车是真故障）。
  - **可判定为重复的路径（先查命中）不得扣库存**。
  - ★ **并发窗口下允许先扣，但撞键必须整笔回滚**（异常穿出事务边界）；
    **回查必须发生在原事务结束之后**（按结果重入新事务，或拆到无事务的编排层）。
    这不是可选项：① 在事务内 catch 住异常再查询并正常返回，`deductStock` 会**随事务提交**；
    ② MySQL 默认 RR 下，同事务内的一致性读**看不到赢家刚提交的行** ⇒ 回查拿到 null。
  - **不得**用 `SELECT … FOR UPDATE` 串行化（本仓已排除该手法，见 `FavoriteItemMapper.xml` 的死锁注释）。
  - 不得在同类自调用上加 `@Transactional`（CM-2 同款坑）。
  - 券码形状（16 位大写 hex）与 `CouponClaimVO` 形状不变；回查**不过滤 `coupon_order.status`**
    （该列目前只读不写，2=已使用 尚无写入方）—— 按"不过滤"实现并登记为已知边界。
* **强制探索**：① 并发窗口下"赢家必已提交"这一前提是否成立（撞键时 loser 的 INSERT 要等到赢家提交才会报 1062）
  ⇒ 决定"回查一定能命中"可用；② `I-25` 的销账怎么写（"改成回查"而非"开始判 1062"）；
  ③ 是否有更省的形态（先查命中 ⇒ 直接返回；未命中 ⇒ 走原有扣减 + 插入，撞键时**整体重试一次**）。
* **验收**：
  - 顺序两连：第 1 次 200 得码 A；第 2 次 **200 且 `couponCode == A`**；`coupon_order` 1 行、`coupon.stock` 只减 1。
  - ★ **优先级用例**：领一次后把券改成**已结束**（或把 stock 改 0）→ 再领 ⇒ **仍 200 且同码**（打红"先判时间窗/库存"）。
  - ★ **并发**（改写 `CouponConcurrencyTests`）：同 user 8 线程 ⇒ **8 条全 200、券码只有 1 个不同值**、
    `stock` 减 1、订单 1 行。反向：去掉回查 ⇒ 出现 409 ⇒ 红。
    ⚠️ 若实测**不可达**（例如 RR 下回查仍拿不到）⇒ 按 G3 **停下回报**，不许把期望值改成实测值（那正是迎合）。
  - 真失败不变（**未领过者**）：库存 0 / 未开始 / 已结束 ⇒ 409；不存在的 couponId ⇒ 409。
  - 契约跟随三处：`接口契约.md` §2.2 的 `I-25` 改**已销账**、`:73`/`:95` 的 409 例子删掉"重复领券"、
    `CURRENT_ISSUES.md` 的 `I-25` 行同步；`CouponFlowTests` 的 409 用例注明依据。
* **回写**：`grabCoupon` 去掉 `@Transactional` 改**编排层**（先回查 → 内层 `CouponClaimer.claim` 单事务 →
  撞键 / 扣减 0 行后**在事务外**回查，命中即重放、查不到原样重抛）；新增 `CouponDao.findCouponCode`
  （**不过滤 `status`**，该列目前只读不写 = 已知边界）。契约：重复领券 **409 → 200 + 原码**，
  `I-25` 销账三处（§2.2 / 附录 / `CURRENT_ISSUES`）。
  定向 17/17、全量 **810** + resilience **41** 绿；反向验证：去掉 catch 里的回查 ⇒ **并发用例 2/3 红**
  （`DuplicateKeyException` / `ConflictException` 冒到断言）。
  ⚠️ **判别力分工（重要）**：顺序"重复领券"用例由**先回查**守住 ⇒ 去掉 catch 回查它**仍绿**；
  catch 回查只由**并发两例**钉住。卡片原写"反向：去掉回查 ⇒ 出现 409 ⇒ 红"对顺序用例不成立，以本处为准。
  ⚠️ 残留：`接口契约.md` 40.4 KB（HEAD 已 40.09 KB，非本任务引入）；`CURRENT_TASKS` 与仓内既有并发用例
  均只跑 8 线程，未做更高压。

### T65 重复收藏改为幂等成功（来源 `N4` §G·P0-4）【已完成】

> ⚠️ **先质疑（G14）**：
> - **任务**：`T59` 已把 like / follow / watch-later / 收藏移动做成幂等，`POST /favorites` 是漏网的一条
>   ⇒ 需求成立，且与 `T59` 口径一致。
> - **设计**：★ **「夹已满 + 重复收藏」从 400 变 200 不是"复用 `addItemIfAbsent` 的连带变更"，而是目标的强制结果**
>   —— 只要目标写"重复 = 200"，实现就必须在抛出 400 之前知道它是重复；任何保留 400 的写法都会让
>   "首次 200、重试 400"这对响应不幂等（`N4` 的两层判据）。**所以没有"保留 400 同时幂等"这条路**；
>   要么接受 200，要么放弃本任务。

* **入口线索**：`FavoriteService#addItem` / `#addItemIfAbsent`（`insertItemIgnore` + `existsByFolderAndContent`）；
  ★ **`#moveItem` 里还有第三份内联实现**（`existsByFolderAndContent` → `requireFolderCapacity` → `insertItemIgnore`）；
  `FavoriteItemDao`；`FavoriteController#addItem`；断言点 `FavoriteItemWriteTests:94-113`（重复 409）、
  `FavoriteCapacityTests:286-303`（满夹 + 重复 400）；`ErrorCode` 里"收藏按唯一键兜底"的注释。
* **目标**：重复收藏 = **200**（终态已达成），不重复插入、不重复计数重算；与 `T59` 的 like/follow 同口径。
* **红线边界**：
  - 不变量 `favorite_count == COUNT(DISTINCT user_id)` 仍成立（重复不得再 +1）。
  - 失败面不变：内容失效 404 / 他人夹 403 / 夹不存在 404 / **加新内容**时夹满 400。
  - ★ **重复判定必须在容量判定之前**（否则满夹重试仍 400 —— 见"先质疑"）。省查询的写法：
    "满夹分支才查重、未满直接插"，不要为每次收藏都多一次 `exists`。
  - 并发判据靠 `INSERT IGNORE` + affected rows，不得只靠"先查后插"。
  - 既有 409 用例属**契约跟随**（G8 例外），用例注释必须写依据（`N4` §G / `T65`）。
  - `PUT /watch-later/{id}/folder` 依赖的"满夹重复移动必须成功"不得回退。
* **强制探索**：① 三份"幂等加入"判据（`addItem` / `addItemIfAbsent` / `moveItem` 内联）如何收敛 ——
  要么抽一个私有方法，要么写明第三份为什么不收；② 重复路径上的副作用实际只有三件事
  （`favoriteAdded` 的 SADD、`invalidateFavoriteCount` 的 DEL、`recountFavoriteCounts` 的绝对值重算），
  **三者本身都幂等、且本域写路径没有任何 `publishEvent`** ⇒ 不要用"缓存事件只发一次"做验收（那是断言内部调用，
  违反纪律①，也证明不了用户可见的重复副作用）；③ `favorite.md:227` 写着"目标夹已有同内容 409"，
  这是 `T59` 之后**既存漂移**，本任务顺带修正。
* **验收**：
  - 连发两次同 `(user, folder, content)` ⇒ **两次 200**；`favorite_item` **1 行**、
    `content.favorite_count` **直查 = 1**（不读接口回显，独立 oracle）。
  - ★ **并发两线程**同 `(user, folder, content)` ⇒ 两个 200、`favorite_item` 恰 1 行、`favorite_count` = 1。
    （没有这条，"靠 `INSERT IGNORE` 不得只靠先查后插"这条红线**无法被判红**。）
  - 满夹 + 重复 ⇒ **200**（新口径显式用例）；满夹 + 新内容 ⇒ 400（不变）。
  - 对照夹具：另一用户 / 另一内容 / 另一夹不受影响（去掉 `WHERE` 项必红）。
  - 全量 + resilience 绿；`ErrorCode` 与 `favorite.md` 的过期口径同批改。
* **回写**：三份"幂等加入"（`addItem` / `addItemIfAbsent` / `moveItem` 内联）收敛到私有
  `addItemIdempotent` + `insertIdempotent`（**容量 → 满夹分支才判重 → `INSERT IGNORE` → 重算 → 缓存**），
  裸 `insertItem` 与 `requireFolderCapacity` 一并删除。契约：`POST /favorites` 重复 ⇒ **200**，
  **满夹 + 重复 ⇒ 200**（目标强制），满夹 + 新内容仍 400；`接口契约` 409 例子与 `favorite.md` 的既存漂移同批修正。
  定向 23/23、收藏域 **142/142**、全量 **811** + resilience **41** 绿。反向验证两处真红：去掉 `IGNORE` ⇒
  写用例 2 红（200→409）+ 并发 1 红；满夹分支删判重 ⇒ 容量用例 1 红（200→400）。
  ⚠️ 判别力缺口（如实登记）：重复路径若"顺手"再跑一次重算 + 刷缓存，**没有任何可观察差异**
  （三者本身幂等、本域无事件）⇒ 这条只能靠 review 守，不能写成测试断言。

---

## 结转与留池（开窗时逐行核销）

> ★ **不允许静默跳过任何已登记项** —— "本期纳入"与"不做"都必须在表里有一行。
> 反面清单的唯一事实源 = `FURTHER_ISSUES.md` 的 `F-##` 行（每行带**推翻条件**）。

| 来源 | 事项 | 去向（决定人 + 日期） |
|------|------|---------------------|
| 接口契约专项（2026-10-24 收口） | **`T61` 前端适配层** —— 原目标"旧 SPA 不挂" | **搁置**（`R-23`，2026-10-23）：旧 SPA 随前端重制整体删除 ⇒ 不为将死代码续命；是否改成"重制前的消费样例"由重制期重定 |
| 接口契约专项（2026-10-24 收口） | **`R-24` 通用客户端幂等键（`Idempotency-Key`）** —— 原记"本期 P1" | ✅ **已开窗**（2026-10-24，`R-24` 已拍板）⇒ `T66`–`T68`。设计 = [写接口幂等](../architecture/tech/idempotency/写接口幂等.md)。推翻条件 = 前端重制不做自动重试 / 只对 5xx 重试 / 重试窗口 > 3 min |
| 幂等专项开窗（2026-10-24） | **收藏夹重名**（`POST /favorite-folders` 重试会建两个同名夹） | **留池**（`N4` §G·P2）：重名是 `T16` 有意允许的业务（加唯一键会破业务），且夹可改名可删、影响面小 |
| 接口契约专项（2026-10-24 收口） | **`I-27` 上传超限 / 畸形 multipart 仍是 500** | **待裁决**（是否新增 413 类别）⇒ 已结转 `FURTHER_ISSUES.md`（保留原编号） |
| 接口契约专项（2026-10-24 收口） | **`I-28` 并发投币撞封顶偶发 500**（MySQL 死锁被兜成 500，既存竞态） | **待排期**（下期小任务：Service 死锁重试 *或* 缩小事务持锁面）⇒ 已结转 `FURTHER_ISSUES.md` |
| 本期收口复核（2026-10-21） | 下表三行**逐行复核：全部维持原决定**，无一条满足其推翻条件 | **维持**（本行不删；下期收尾再复核） |
| T44 设计讨论 | **F-26**：载荷读并入聚合层（单飞跨载荷/计数合一，冷内容 3→2 条 SQL） | **留池**（FURTHER_ISSUES F-26，2026-10-20；推翻条件 = 实测详情载荷 miss 热点） |
| T44 设计讨论 | 聚合路径计数键**不做命中续期**（五键 TTL 跨域） | **登记为已知代价**（缓存文档 §5.2，2026-10-20） |
| T44-1 执行发现 | favorite 键在内容删除时**不失效**（键残留 TTL 自愈） | **如实钉进测试**（`ContentDetailCountAggregateTests`，2026-10-20） |
| 接口契约专项（2026-10-22） | **`F-15`（前端互动态维度常量）** —— 原决定"归前端重制自然解决" | **仍归重制**（本专项只动契约形状，不改维度语义；重制时按 `favorite.md` §六 先把常量加回） |
| 接口契约专项（2026-10-22） | **旧前端 18 个 view 的重写**（登录/详情/后台等页面体验） | **不做**（`T61` 已搁置；页面重写属重制期） |
