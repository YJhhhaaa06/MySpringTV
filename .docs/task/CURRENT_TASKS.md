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

* **方向**：★ **播放记录（history）专项**（2026-10-21 起，需求源 = `CURRENT_NEEDS.md` §四 `R-18`）——
  进度上报 / 播放事件（去重计播放量）/ 历史列表（失效占位）/ 续播 / mp4 时长解析。
  **用户 2026-10-21 拍板**：做播放量（B 方案：冗余列 + 独立 key + 详情第 5 计数）；进度与播放事件
  **拆两端点**（`/report` 纯进度、`/play` 唯一计数入口）；历史**不可删**；失效内容**做占位**；
  时长存 `content_media` + `ContentCacheDTO` 投影；V7 删遗留 `video` / `videoinfo`。
* **活事实源 / 设计落点**：[history.md](../architecture/domain/history/history.md)（设计）+
  [history-需求.md](../architecture/domain/history/history-需求.md)（用户可见规则）。
  ★ **代码注释只引这两篇 + `类#方法`**；不得引用任何草稿 / 本登记台。
* ★ **编号**：**从 `T45` 起**（`T43` 占位说明、`T44` 收口结论均见 git）。首批只拆 `T45`–`T47`；
  这批执行完**停下来复盘**，再决定下一批（播放量读面 / 历史读面 / 收口）。
* ★ **执行约定**：一任务一 commit（G1）；每 commit 可编译且自带验收测试 + 反向验证。
  清单只给**目标 / 红线 / 验收入口**，**不写实现步骤**（G13）——执行者自行决定拆法；
  遇到清单前提不成立先登记 `CURRENT_ISSUES.md`，不擅自扩范围。
* **回归基线**：默认组 **659 例** + resilience **41 例**（2026-10-20 T44 收口）。
  ⚠️ 本环境跑全量需 `DB_POOL_MAX_SIZE=4`（否则 Testcontainers MySQL 报 `Too many connections`）。

---

## 运行约定（只保留当前仍然有效的）

| 号 | 约定 |
|----|------|
| G1 | 默认一任务一窗口一 commit；★ **但允许执行者自拆** —— 任务可能是连贯的、要深入调查后才知怎么拆，执行者可自行拆子任务与多个 commit（**每 commit 仍须可编译**，message 带任务号），拆法写进回写 |
| G2 | **证据可复核**：入口与验收能定位（文件:行 或 可复现命令），不接受纯文字断言 |
| G3 | **疑问分级**：可自答的 → 任务回写留一句；须裁决的 → **停下问用户**，不擅自拍板、不硬扛、不超范围 |
| G4 | 一任务 = 四要素 + 三句以内回写；探索过程 / 失败尝试 / 中间稿一律走 git，不进本文件 |
| G5 | **声明式机制必须配契约测试**：断言通过 ≠ 机制生效；写「证明某机制」的用例前先自问「去掉那行实现会红吗」（详见仓库根 `AGENTS.md`「测试纪律」节） |
| G6 | 改 schema 走 Flyway **最高版本 +1**（⚠️ **已应用的迁移文件逐字节冻结**——**连注释都不能改**，见 `architecture/tech/data-evolution/数据与Schema演进.md` §2.4）；★ 本期有 schema 变更：**V6**（播放记录）+ **V7**（删遗留表），见 T45 |
| G8 | ★ **跨期约定（升格保留）**：测试必须只断言可观察行为（HTTP 状态 + body + DB 终态）—— 换实现时测试**不许改**；改了就说明测试在抄实现（纪律①）。⚠️ **回源口径契约变更除外**（如 T12 / T44-1 的"回源真理源切换"）：oracle 必须跟随真理源，此时改测试属**契约跟随**而非迎合，须在用例注释里写明口径变更依据 |
| G13 | ★ **跨期约定（升格保留）**：清单只给方向与红线，不写死实现步骤 —— 有些问题**只有执行期才看得见**。执行中发现清单前提不成立 / 与既有实现冲突 / 有更好的拆法 ⇒ **自行决断或停下登记 `CURRENT_ISSUES.md` I-##** |
| G14 | ★ **每个任务都显式标注「可质疑」（2026-10-11 用户要求）**：**任务可被质疑**（前提不成立 / 需求已被既有实现覆盖 / 执行时机不对）；**设计也可被质疑**。⚠️ 但本项目是学习项目、**无真实流量**（见 `AGENTS.md`「项目定位与优化口径」）⇒ **质疑成立 ≠ 停做**：**先把代价写进回写**；若仍做，注明"**已知代价后选择做**"。**只有质疑指向真实缺陷才必须停下回报** |

> **跨期约定指针（代码直接引用这些号 ⇒ 行不可删）**：`G7`（一期不写缓存 / 不做增量维护，✅ 二期已解除）、
> `G9`（`favorite` 登记进 `ArchitectureTests.DOMAINS`）、`G10` / `G11` / `G12`（**长期红线**）——
> 结论见 **`favorite.md` 附录「编号速查」**（`G#` 表）。
> ⚠️ `C-1`–`C-6`（需求侧通用约定）在 `CURRENT_NEEDS.md` §一。

---

## 任务

> 每任务必含**四要素 + 回写**；状态取值：待执行 / 执行中 / 已完成 / 搁置（搁置**必须**注明去向）。
> ★ **每个任务开头的「先质疑」是约定（G14），不是客套** —— 认真回答它。
> ⚠️ **读法（G14 校准）**：块里列的是**已知代价 / 反对理由**，**不是"请自行否决"** ——
> 出现"停下回报"字样时按 G14 理解：**先记代价、再决定**；**只有指向真实缺陷才必须停**。

**（`T44-1`–`T44-3` 已收口：`34fe36f` / `88cb8b3` / `aadbf7c`；T43 占位编号的裁定见 git）**

### T45 V6/V7 迁移 + 结构契约【已完成】

> ⚠️ **先质疑（G14）**：① 表结构是后面所有任务的硬前提，先做不返工；② **删遗留表不可逆**
> （dev 库旧数据会丢，已与用户确认）⇒ 先 `grep` 留零引用证据，再删；③ `InfrastructureConnectivityTests`
> 与 `WatchLaterSchemaTests` 的表数 / 版本锚点**必须同 commit 改**，否则全量回归红。

* **入口线索**：`src/main/resources/db/migration/V6__watch_history.sql`（新）/
  `V7__drop_legacy_video.sql`（新）/ `HistorySchemaTests`（新）/
  `InfrastructureConnectivityTests` / `WatchLaterSchemaTests` /
  `architecture/tech/data-evolution/数据与Schema演进.md` / `INDEX.md` §一（版本号）
* **目标**：V6 建 `content_media.duration_seconds`、`content.play_count`、`watch_history`（DDL 见
  `history.md` §二，**逐字一致**）；V7 删 `video` / `videoinfo`。空库迁移后 **18 张业务表 + flyway = 19 行**，
  版本序 `1..7`。
* **红线边界**：V1–V5 **逐字节不动**；本任务**不写业务代码**；索引集只许 `PRIMARY` /
  `uk_user_content` / `idx_user_update` / `idx_content_id`；删表前用可复现 `grep` 留证据。
* **验收**：`HistorySchemaTests` 钉列集 / 索引集 / 唯一键**真插两行** / 两遗留表不存在；
  `InfrastructureConnectivityTests`（20→19 行、版本 1..7）与 `WatchLaterSchemaTests#endsWith("7")` 同步；
  `数据与Schema演进.md` 与 `INDEX.md` 的"当前版本 / 下一次"同步；`mvnw test-compile`。
* **回写**（`b0bc6d4`）：V6/V7 落地（删表证据：`videoinfo` 只命中 V1 自身、`(from|into|update|table) video`
  0 命中）；`HistorySchemaTests` 6 例全绿（含"遗留表真的没了"）；两处锚点同 commit 改为 19 行 / 版本 `1..7` /
  `endsWith("7")`。反向验证实测：**去掉 `uk_user_content`** ⇒ 唯一键 + 索引全集 2 红；
  **抽走 V7** ⇒ 遗留表 + 版本序 2 红（⚠️ 抽 V7 必须同时清 `target/classes` 的副本，否则 Flyway 读的是旧资源、假绿）。

### T46 时长：解析 → 落库 → 内容载荷【已完成】

> ⚠️ **先质疑（G14）**：① 时长是"进度百分比"的唯一分母，但**解析失败不能阻断发布**——
> 否则异常 mp4 会把发布流程变成新的失败点；② 换源也会换视频，只在上传时解析会留
> "时长永远停在旧值"的显式不一致 ⇒ 两个调用点都要接；③ 存量 257 条不回填，null = 未知（已接受）。

* **入口线索**：`upload/service/Mp4DurationReader`（新）/ `UploadController#uploadVideo` / `#replace` /
  `ContentService#addVideo` / `#replaceMedia` / `ContentMedia` + `ContentMediaDao` + `ContentMediaMapper.xml` /
  `ContentCacheDTO` / `ContentCache#buildContentMedia` / **`ContentCache#copyCommon`** / `UploadFlowTests`
* **目标**：发布与换源（`type=1`）解析 mp4，`content_media.duration_seconds` 落库；
  所有内容响应多 `durationSeconds`（由 `buildContentMedia` 从**已有视频行**投影，**零额外查询**）；
  解析失败 ⇒ null、仍 200；存量不回填。
* **红线边界**：用 `RandomAccessFile` 只读 box 头，**不整文件读**；`moov` 任意位置（非 faststart）/
  v0·v1 / `size==1` / `timescale==0` 或 duration 全 F ⇒ null；`type=2/3` 换源**不得清空**
  视频行的时长；不改前端；`ContentCacheDTO` **只加 duration 一个字段**（计数禁入）；
  **`copyCommon` 必须同步**（只改 DTO 不改它 ⇒ VO 恒 null，DB 断言仍绿 = 假绿）；
  `addMedia` 签名变更要同步两处测试桩（`ContentMilestoneTests` / `UploadTransactionTests`）。
* **验收**：`Mp4DurationReaderTests` 手搓字节（moov 前 / 尾、v1 64 位、`size==1`、`timescale=0`、
  截断、非 mp4）；上传手搓 mp4 ⇒ DB 列落库 + 详情回显；换源两分支；解析失败仍 200；
  **内容 JSON 键集用例**（含 `durationSeconds`、不抖落既有键——仓内原本没有精确键集断言）。
* **回写**（`c9fb17f` + `0d39568`）：分两个 commit —— ①解析器（`RandomAccessFile` 只读 box 头，
  15 例手搓字节：moov 在尾 / v1 / `size==1` / `timescale=0` / 全 F / 截断 / 非 mp4）；
  ②落库与投影（`ContentMedia` + 5 条 select 全带列、`addMedia` 加参、`replaceMedia` 只在 `type=1`
  调 `updateMediaDuration`、`buildContentMedia` 投影 + `copyCommon` 同步、`UploadDurationTests` 8 例）。
  ★ 反向验证实测三组：**`copyCommon` 漏搬 ⇒ 3 条回显用例红而 DB 断言仍绿**（亲眼看到"只断 DB"的假绿）；
  去 clamp/换源刷新 ⇒ 2 红；`addMedia`/select 不带列 ⇒ 4 红。§六.3 的"三个落点"实测全部有判别力。

### T47 history 写路径：report / play + 播放量【已完成】

> ⚠️ **先质疑（G14）**：① `/report` 用一条 ODKU、**不判首播** ⇒ 绕开 Connector/J
> `useAffectedRows=false` 的 affected-rows 歧义（拆端点的核心收益）；② 若把两端点合并，
> 就必须在热路径上判首播 ⇒ 回到 ODKU 歧义 / 两条语句，**不合并**；③ `/play` 是唯一计数入口，
> 播放事件丢失会少计（已知代价，已接受）；④ 本任务**不碰详情读面**——播放量写完还看不见，
> 是下一批的事（每 commit 仍可编译）。

* **入口线索**：`history/controller/HistoryController`（新，类级 `@RequiresLogin`）/
  `history/service/HistoryService`（新）/ `history/dao/WatchHistoryDao` + `WatchHistoryMapper.xml` /
  `history/event/PlayCountChangedEvent` / `ContentDao#updatePlayCount` /
  `ArchitectureTests.DOMAINS` / `architecture/tech/module-boundary/模块边界与依赖.md` §一·§六 /
  `application.yaml` 权重表 / `SecurityContractTests`
* **目标**：`/history/report`（ODKU、LWW、按 `durationSeconds` clamp、**不判首播**）+
  `/history/play`（`INSERT IGNORE` + 条件 `UPDATE` 判首播、`play_count + 1`、单事务、发布事件）；
  `history` 进 `DOMAINS`；两个 POST 登记权重（各 1）；类级 `@RequiresLogin`。
* **红线边界**：`/play` 是唯一计数入口（`/report` 不写 `play_count`）；**不用 ODKU 判首播**；
  内容删除**不碰** `watch_history`；不建缓存；不改 `ContentDetailVO`（下一批）；不改 `/report`
  为"进度只能增"；**依赖边登记与代码同 commit**（`DOMAINS` + 边界文档 §一 清单补 `history`
  ——现清单漏了 `coin`，实际 12 域，补后 13 —— + §六 新增 `content ⇄ history` 一行）。
* **验收**：LWW（低覆盖高）/ clamp / 图文 400 / 已删 404 / 匿名 401 / `progress<0` 400；
  重复 play 不 +；**并发 play 恰 +1**；DB oracle `play_count == COUNT(play_counted=1)`；
  `RateLimitWeightTableTests` 36→38；`SecurityContractTests` 四端点。
* **回写**（`92e10cf`）：`history` 域四类文件（Controller 类级 `@RequiresLogin` / Service /
  DAO + Mapper / 事件）落地；`/report` 一条 ODKU（显式 `SET update_time`）、`/play` 单事务
  （`INSERT IGNORE` → 条件 `UPDATE` → `play_count+1` → 发事件）。登记三处：`DOMAINS` 加 `history`、
  权重表两端点各 1（36→38）、边界文档 §一 补 `coin`+`history`（11→13 域）与 §六 `content ⇄ history`
  单向行。`HistoryWriteTests` 10 例全绿，反向验证两组各 2 / 4 红（`GREATEST`+无 `update_time` ⇒ LWW
  与刷新时间红；去 clamp + 去 `play_counted=0` ⇒ 截断 / 重复 / 并发 / 不变量 4 红）。
  ⚠️ 验收行的"四端点"与范围矛盾 ⇒ 本批只钉两路由，已登记 **I-23**（读面批次必须补）。

> **下一批（等 T45–T47 执行完复盘后再拆）**：播放量读面（`content:playCount` + 聚合 5 键 +
> 详情 VO + `ContentCache#removeContent` 失效该键 + 缓存文档 §5.1/§5.2 四→五）、
> 历史列表 / 续播 / 失效占位、文档收口 + 全量回归。**不要提前拆**——上一批的实测结论会改拆法。

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

---

## 结转与留池（收尾时逐行核销）

> ★ **不允许静默跳过任何已登记项** —— "本期纳入"与"不做"都必须在表里有一行。
> 反面清单的唯一事实源 = `FURTHER_ISSUES.md` 的 `F-##` 行（每行带**推翻条件**）。

| 来源 | 事项 | 去向（决定人 + 日期） |
|------|------|---------------------|
| T44 设计讨论 | **F-26**：载荷读并入聚合层（单飞跨载荷/计数合一，冷内容 3→2 条 SQL） | **留池**（FURTHER_ISSUES F-26，2026-10-20；推翻条件 = 实测详情载荷 miss 热点；理由 = 复刻 CacheAside 三态的漂移风险 > 常数 SQL） |
| T44 设计讨论 | 聚合路径计数键**不做命中续期**（四键 TTL 跨域） | **登记为已知代价**（缓存文档 §5.2，2026-10-20；热键每 TTL 周期重回源 1 条点查）；推翻 = 出现 TTL 到期回源的实测压力 ⇒ 届时补一趟跨域 EXPIRE pipeline 或统一 TTL |
| T44-1 执行发现 | favorite 键在内容删除时**不失效**（favorite 域不订阅 REMOVE，明细行不删、列不重算，键残留 TTL 自愈） | **如实钉进测试**（`ContentDetailCountAggregateTests#删除后404不回填计数键`，2026-10-20）——既存口径非本期引入；推翻 = 出现"已删内容的收藏数被外显"的读路径 ⇒ 届时给 favorite 域补 REMOVE 订阅 |
