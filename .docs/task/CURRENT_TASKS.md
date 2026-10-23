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

* **方向**：★ **接口契约规范化专项（API 契约）** —— 为**前端重制**准备（2026-10-22 起，`R-21`）。
  四层：① 状态码语义（HTTP 类别 + 字符串码）② 错误出口合一 ③ 路由语义（方法 / 路径 / 命名）
  ④ 载荷形状（入参 / 出参 / 分页 / 时间）。
* **活事实源 / 设计落点**（不随期滚动，收口即终态）：
  [接口契约.md](../architecture/tech/api/接口契约.md)（★ 现状快照 §三 / 三条总决策 §一 / 规范 §二 / 编号速查附录）·
  [安全与鉴权.md](../architecture/tech/security/安全与鉴权.md)（鉴权链）·
  [限流.md](../architecture/tech/rate-limit/限流.md)（`cost.base` 权重表口径）。
  ★ **代码注释只引这些篇 + `类#方法`**；不得引用任何草稿 / 本登记台。
* ★ **编号**：`T54`–`T62`（9 项，可自拆子任务）。**`T54` ✅ / `T55` ✅ / `T56` ✅ / `T57` ✅（均 2026-10-22）/
  `T58` 🚧（2026-10-23，19/67 端点，余 48 条按域推进）**（结论见
  [接口契约 §2.9 / §2.1–2.2 / §2.5.1](../architecture/tech/api/接口契约.md) 与附录速查）；**下一编号从 `T63` 起**，
  `R-21`/`R-22` 之后的决策从 `R-23` 起。
* ★ **执行约定**：一任务一 commit（G1）；每 commit 可编译且自带验收测试 + 反向验证。
  清单只给**目标 / 红线 / 验收入口**，**不写实现步骤**（G13）——执行者自行决定拆法；
  遇到清单前提不成立先登记 `CURRENT_ISSUES.md`，不擅自扩范围。
* ★ **顺序纪律（本专项特有）**：`T54` → `T55`/`T56`/`T57`（错误面）→ `T58`/`T59`（路由面 / 载荷面）→
  `T60` → `T61` → `T62`。**码表不定就改码 = 改两遍；路径不定就改形状 = 前端映射表重写两次。**
  路由一改，限流权重表（键是 `"[POST /x]"`）、安全契约测试、前端调用点**必须同一 commit 跟上**。
* **回归基线**（2026-10-22，**`T57` 后复跑**）：默认集 **788/788 绿**（`--excludedGroups=resilience`），
  `resilience` 组另跑 **41/41 绿** ⇒ 合计 **829/829**（`T56` 后是 818，`T57` 新增 11 例：
  `ValidationErrorContractTests`；`UploadFlowTests#缺必填part为400` 在原用例上补断言，不增条数）。
  ⚠️ 本环境跑全量需 `DB_POOL_MAX_SIZE=4`（否则 Testcontainers MySQL 报 `Too many connections`）。
* ★ **本专项不动 schema**（纯 HTTP 层）⇒ 不产生 Flyway 迁移；若执行中发现必须动表，先停下登记 `I-##`。

---

## 运行约定（只保留当前仍然有效的）

| 号 | 约定 |
|----|------|
| G1 | 默认一任务一窗口一 commit；★ **但允许执行者自拆** —— 任务可能是连贯的、要深入调查后才知怎么拆，执行者可自行拆子任务与多个 commit（**每 commit 仍须可编译**，message 带任务号），拆法写进回写 |
| G2 | **证据可复核**：入口与验收能定位（文件:行 或 可复现命令），不接受纯文字断言 |
| G3 | **疑问分级**：可自答的 → 任务回写留一句；须裁决的 → **停下问用户**，不擅自拍板、不硬扛、不超范围 |
| G4 | 一任务 = 四要素 + 三句以内回写；探索过程 / 失败尝试 / 中间稿一律走 git，不进本文件 |
| G5 | **声明式机制必须配契约测试**：断言通过 ≠ 机制生效；写「证明某机制」的用例前先自问「去掉那行实现会红吗」（详见仓库根 `AGENTS.md`「测试纪律」节） |
| G6 | 改 schema 走 Flyway **最高版本 +1**（⚠️ **已应用的迁移文件逐字节冻结**——**连注释都不能改**，见 `architecture/tech/data-evolution/数据与Schema演进.md` §2.4）；★ 上次 schema 变更：V6/V7/V8（播放记录专项） |
| G8 | ★ **跨期约定（升格保留）**：测试必须只断言可观察行为（HTTP 状态 + body + DB 终态）—— 换实现时测试**不许改**；改了就说明测试在抄实现（纪律①）。⚠️ **外部契约变更除外**：本专项**就是**在改对外契约 ⇒ 断言期望值随契约改属**契约跟随**，但**必须在用例注释里写明"契约变更依据 + 变更点"**，并与产品口径一一对应 |
| G13 | ★ **跨期约定（升格保留）**：清单只给方向与红线，不写死实现步骤 —— 有些问题**只有执行期才看得见**。执行中发现清单前提不成立 / 与既有实现冲突 / 有更好的拆法 ⇒ **自行决断或停下登记 `CURRENT_ISSUES.md` I-##** |
| G14 | ★ **每个任务都显式标注「可质疑」（2026-10-11 用户要求）**：**任务可被质疑**（前提不成立 / 需求已被既有实现覆盖 / 执行时机不对）；**设计也可被质疑**。⚠️ 但本项目是学习项目、**无真实流量**（见 `AGENTS.md`「项目定位与优化口径」）⇒ **质疑成立 ≠ 停做**：**先把代价写进回写**；若仍做，注明"**已知代价后选择做**"。**只有质疑指向真实缺陷才必须停下回报** |

> **跨期约定指针（代码直接引用这些号 ⇒ 行不可删）**：`G7`（一期不写缓存 / 不做增量维护，✅ 二期已解除）、
> `G9`（`favorite` 登记进 `ArchitectureTests.DOMAINS`）、`G10` / `G11` / `G12`（**长期红线**）——
> 结论见 **`favorite.md` 附录「编号速查」**（`G#` 表）。
> ⚠️ `C-1`–`C-6`（需求侧通用约定）在 `CURRENT_NEEDS.md` §一。

---

## 已收口（留痕，不留细则）

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

### 接口契约专项：错误面（`T54`–`T57`）

#### T54 端点清单测试 + 契约规范定稿（来源 `R-21` / 设计 §二）【已完成】

> ⚠️ **先质疑（G14）**：
> - **任务**：清单测试是不是"为了改契约而改契约"的仪式？—— 不是：没有清单，`T58`/`T59` 改完无法回答
>   "还剩几个端点没改"，而本仓的纪律恰恰是**状态结论必须可复算**。
> - **设计**：能不能直接上 OpenAPI 当清单（跳过 T54）？—— 不能：先有 springdoc 会把**现状**（含 `?`/`Map`/驼峰路径）
>   一并固化成"规范"，等于把欠账写成标准。

* **入口线索**：18 个 `*/controller/*Controller.java`；`common/security/RequestMappingLookup`（现成的 handler 解析）；
  `common/web/RateLimitWeightTableTests`（已有"扫真实 handler"的写法可抄）；`architecture/tech/api/接口契约.md` §二 / §三。
* **目标**：① 一条**扫真实 handler** 生成全站端点清单的测试（method + path + 返回类型 + 鉴权注解），当前 **67 条**；
  ② 把《接口契约》§二 从初稿定稿到"能当验收依据"（码表命名、路径规则、方法判据、载荷规则）；③ 定"新增端点必须同批进清单"的护栏。
* **红线边界**：**不改任何端点行为**（本任务只加测试 + 写文档）；清单必须来自 handler 扫描，**不得手写枚举**；
  代码注释只许引 `architecture/**`（`task/` 禁入）；不引入 springdoc（那是 `T60`）。
* **强制探索**：`ApiResponse<?>` / `Map<String,Object>` / `Void` 在清单里怎么表达才不至于"改完才发现漏"；
  重复路径（同 path 不同 method）与 `@Public` 类级/方法级混用怎么表达。
* **验收**：`ApiContractInventoryTests` 3/3 绿；条数 **67**，与
  `grep -rc '^\s*@\(Get\|Post\|Put\|Delete\|Patch\)Mapping' src/main/java --include=*Controller.java` 求和一致
  ⚠️ **不能用 `grep -o`**：会把 javadoc 里的 `{@code @PostMapping}` 数进去（立项时的 81 就是这么来的）；
  新增端点不同批登记 ⇒ 必红（已实测）；`doc_links.py` / `doc_refs.py` 0 违规。
* **回写**：交付 `ApiContractInventoryTests`（3 用例：登记簿一致 / 扫描面自检 / 出参统一信封）+
  `src/test/resources/api/endpoint-inventory.tsv`（**67 条** = GET 28 / POST 39，带鉴权档位与返回类型），
  §二 定稿为 2.1–2.9（新定：PATCH 不用 / ID 保持 number / 时间不引入偏移 / `unlike` 全站退役 / 登记簿机制）。
  反向验证：注入 `GET /probe-t54` 且返回 `String` ⇒ 两条用例同时红（漏登记 + 非统一信封），还原后 3/3 绿；
  另改 `.gitignore` 放行该目录（`/src/test/resources/*` + `!/src/test/resources/api/`，否则"契约快照不入库"，
  新克隆上护栏直接失效）。

#### T55 状态码字典：HTTP 类别 + 字符串码（来源 `R-21` §1.1 / 设计 §2.1–2.2；★ 登录口径按 `R-22` 改）【已完成】

> ⚠️ **先质疑（G14）**：
> - **任务**：三维码表（status + 字符串码 + msg）会不会过度设计？—— 已知代价：码表要长期维护、码只会变多。
>   **但**现状 `code == HTTP status` ⇒ body 零信息增量，前端确实无字典可翻，属真实缺陷。
> - **设计**：能不能只靠 HTTP status + `msg`？—— 那样前端仍要匹配中文文案，正是要解决的问题。
> - ★ **原目标里「登录三种失败态可机器区分」被推翻（`R-22` ③）**：那正是手机号探测口
>   （「账号不存在」与「密码错」分开回答 ⇒ 脚本可批量筛已注册手机号）。改为**单码同文案同耗时**。

* **入口线索**：`common/exception/ErrorCode`（20 项、14 引用 / 6 死码）· `BusinessException` 及子类 ·
  `GlobalExceptionHandler` · `common/log/AccessLogFilter`（消费 `int` 型 code，两码 status+code）。
* **目标**：`ErrorCode` 改为"字符串码 + HTTP status + 默认 msg"三元；同义码合并、死码清零或复活（给出理由）；
  **登录失败单码**（`AUTH_BAD_CREDENTIALS`，且耗时也统一）；全站只有一个码源。
* **红线边界**：**访问日志口径必须同批改**（`code` 从 int 换 String，`code=0` 仍是"无信封"哨兵）；
  `msg` 只做中文兜底，**不许**变成第二套码；`JwtAuthFilter` / `RateLimitFilter` 必须取同一份码源；
  成功响应的 `code` 规则（`"OK"` / 201 / 204）在此任务内定稿并写进文档。
* **强制探索**：6 个死码各自的"该不该复活"；`ErrorCode` 被测试直接引用的地方。
* **验收**：**死码表清零**（每个码都有产码点，`ErrorCodeContractTests` 钉住）；登录失败用码而非 `msg` 区分
  （与「账号不存在」同码）；`AccessLogTests` 全绿且判据仍能区分"有信封 / 无信封"。
* **回写**：`ErrorCode` → **42 项**三元（码 = 枚举名，状态由枚举携带，四个取值点共用）；全站抛出点显式赋码
  （**删掉所有"只给 message、码由类决定"的构造器**，这是当年泛码/死码的根因），`ApiResponse.code` 与
  73 处 `Envelope.code` 断言一并换字符串，访问日志 `code=OK`（`0` 保留为"无信封"哨兵）；死码处置 =
  `CONTENT_NOT_FOUND`/`COMMENT_NOT_FOUND` 复活、`DUPLICATE_LIKE→LIKE_ALREADY_LIKED`、`ACCESS_DENIED→AUTH_FORBIDDEN`、
  `INVALID_PHONE`/`CACHE_ERROR`/泛码 `NOT_FOUND`/`CONFLICT`/`PARAM_ERROR` 删除。
  **登录按 `R-22` 收口**：`AUTH_BAD_CREDENTIALS` 单码单文案 + 假哈希抹平 BCrypt 耗时差（只统一 code/msg 不够）；
  注册口 `USER_PHONE_TAKEN` 如实回答已登记为已知代价（§2.2）。
  反向验证两条都真红过：① 注入一个无产码点的码 ⇒ `ErrorCodeContractTests#无死码` 红；
  ② 去掉假哈希 ⇒ `UserFlowTests#登录失败两分支耗时同量级` 红（比值掉到 0.05 量级）。
  护栏还挣到一分 —— §2.2 白名单**漏了既有关注域 `FOLLOW`**（已补，`R-22` ②）。
  ⚠️ 未做：非 GET 端点的 `cost.base` 未动（`T58`）；旧 SPA 只做了**过渡桥**（`api.js`：`err.code` 仍给数字 status
  让 18 个 view 的既有分支继续命中，`err.bizCode` 给字符串码），完整适配归 `T61`；
  两处待裁决已登记 `I-24`（三处 status 与 §2.1 措辞不一致）/ `I-25`（重复抢券对外是 `SYSTEM_DUPLICATE_KEY`）。

#### T56 单一错误出口 + 框架级错误归位（来源 `R-21` §1.1 / 设计 §2.1）【已完成】

> ⚠️ **先质疑（G14）**：
> - **任务**：三处合一是不是"洁癖重构"？—— 不是：现在加一个错误字段要改三处，而 `JwtAuthFilter` / `RateLimitFilter`
>   的产物**没有任何测试钉住形状**（只有各自的用例断言 status），漂移不可见。
> - **设计**：能不能让过滤器抛异常、统一由 advice 接？—— 不能：两个过滤器在 `DispatcherServlet` 之前，
>   advice 覆盖不到（这是既有结论，见 `限流.md`），所以正确做法是**共用 writer**，不是改链序。

* **入口线索**：`common/web/GlobalExceptionHandler`、`common/security/JwtAuthFilter#writeError`（:168）、
  `common/web/RateLimitFilter`（:351）；`common/log/RequestIdFilter`（`X-Request-Id`）。
* **目标**：一个 `ApiErrorWriter`（序列化 + 状态 + 头），三处全部委托；补 **405 / 415 / 未映射 404** 的统一信封；
  `X-Request-Id` 在所有错误响应上都可读。
* **红线边界**：401/403 的短路**必须仍在过滤器内**（不能进 DispatcherServlet）；`Retry-After` 不得丢（含 429 与 503 两种）；
  异常 message **不许**未经脱敏进 `errors` / body（脱敏只在日志出口，body 只给稳定 msg）；
  `RateLimitFilterContractTests` 里"未映射 = 500"的断言按新契约改，**注释必须写明这是契约变更**（G8）。
* **强制探索**：`NoResourceFoundException` / `HttpRequestMethodNotSupportedException` / `HttpMediaTypeNotSupportedException`
  各自由谁抛、会不会先被 catch-all 吃掉；静态资源与 `/upload/**` 是否要豁免（不能把媒体 404 变成信封）。
* **验收**：同一错误场景从三个出口各触发一次，**产物逐字节同形**（status + body + 头）；
  未映射路径 = **404 信封**；405 / 415 有码可查；反向验证：抽掉 writer 委托 ⇒ 三条用例必红。
  ⚠️ 执行期裁定（G13）：字面上的"同一错误场景从三个出口各触发一次"不可满足（一条错误只由一个出口产出）
  ⇒ 按"**同一条合法骨架**"验收：三条出口各取一个码（400 / 401 / 429），字段集 / 字段顺序 / 无 `data` /
  `X-Request-Id` 完全一致，内容类型同为 `application/json`（⚠️ **charset 声明可以不同**：servlet 通道
  显式 `setCharacterEncoding` ⇒ `application/json;charset=UTF-8`，MVC 通道走 Jackson 转换器按
  RFC 8259 省略 charset —— 这是两条通道的**既有差异**，不属本任务要拉平的面）；"逐字节相等"只在
  同码同文案时有意义。
* **回写**：交付 `common/web/ApiErrorWriter`（状态 + 信封 + 头 + 内容类型 + 业务码属性的**唯一**写出点：
  MVC 走 `toResponse`、过滤器走 `write`，两条通道不各自拼装字段），三处出口全部委托、**链序未动**
  （401/403 仍由 `JwtAuthFilter` 在 servlet 前短路）；`NoResourceFoundException` / 405 / 415 这三个
  原来被 catch-all 吞成 500 的框架异常归位，`ErrorCode` 增 3 码（`SYSTEM_ENDPOINT_NOT_FOUND` /
  `PARAM_METHOD_NOT_ALLOWED` / `PARAM_MEDIA_TYPE_UNSUPPORTED`，**42 → 45**）；
  未映射路径 = **404 信封**，静态资源与 `/upload/**` 缺文件 = **404 无体**（不产信封 ⇒ 访问行 `code=0`），
  405 复制 `Allow`、415 复制 `Accept` 且**不回显**客户端发来的类型。
  契约由 `ApiErrorExitContractTests`（5 例：三出口骨架 = `{code,msg}` / 无 data / JSON 内容类型 /
  `X-Request-Id`；429 用"**负余额** + 1 枚/秒补充"可复算触发——存量 0 只有 1 秒预算，慢机器上会偶发放行）
  + `FrameworkErrorContractTests`（5 例：404 信封 / 静态与媒体无体 + 访问行 `code=0` / `Allow` /
  `Accept` 且不回显 / **受保护端点的 415 仍先 401**）钉住；
  两条既有用例按契约变更改断言（G8：`RateLimitFilterContractTests` / `RateLimitWeightTableTests` 的
  "未映射 = 500"→404，注释写明依据与变更点）。全量回归 **817/817 绿**。
  反向验证三次：① `JwtAuthFilter` **自拼装出一个形状不同的**信封（多一个 `trace` 字段）⇒ 骨架用例 +
  charset 用例红（顺手证明"手写 `setContentType` 会丢 UTF-8 声明"）；② 摘掉
  `@ExceptionHandler(NoResourceFoundException)` ⇒ 未映射与静态两例回到 500；③ 静态豁免失效 +
  不复制 `Allow` ⇒ 两例红（媒体 404 变成 `SYSTEM_ENDPOINT_NOT_FOUND` 信封）。
  ⚠️ **如实更正一句过奖的表述**：「抽掉 writer 委托 ⇒ 必红」**不成立**——把委托换回"同样正确"的
  手写体（同一 `ObjectMapper` + 同一 `ApiResponse.error`）产物逐字节相同，HTTP 断言原理上看不见。
  测试钉住的是**形状**，"唯一写出口"是结构属性（由本任务的分工与 review 保证，不靠 HTTP 用例）。
  真正会红的是"形状偏离"，这正是三次注入所证明的。
  ⚠️ **已知代价（已写进 `接口契约.md` §2.1 末注）**：① **405 匿名可见，415 不可见**——
  差别在**时机**：`RequestMappingLookup` 对"路径命中、方法不匹配"直接抛异常 ⇒ 按未映射放行 ⇒
  匿名也能看到 405；415 发生在处理器解析**之后**（读 body 时才找转换器）⇒ 受保护端点仍先 401
  （review 指出初稿把两者并列写错，已改并补用例钉住：`受保护端点的415仍先401`）；
  ② 容器级 `/error` dispatch 不在三出口内（初稿写"不可达"也过头了：`GET /error` 是一个
  `@Controller` 端点、`Accept` 不接受 JSON 时写出会失败 ⇒ 都退回 Boot 默认体）；
  ③ 静态/媒体的"无信封"判定只能是**路径清单**——静态缺文件与未映射走的是同一个 `/**` 资源处理器，
  异常层无从区分；新增资产目录要同步清单，**漂移不会自己变红**。
  ⚠️ **未做（归后任务）**：`static/js/api.js` 顶部注释"唯一无信封的错误：未识别路径…"**已过时**
  （现在未映射有信封、无信封的只剩静态/媒体 404）—— 属 `T61` 的前端适配面，本任务不动前端。

#### T57 校验错误结构化（来源 `R-21` §1.1 / 设计 §2.5）【已完成】

> ⚠️ **先质疑（G14）**：
> - **任务**：前端重制期真的需要 `errors[]`？—— 需要：表单（发布 / 注册 / 改密）多字段同时错时，
>     现状只回一条拼接串，前端无法把错误挂到字段上。
> - **设计**：`errors[]` 会不会与 `msg` 重复？—— 会，故 `msg` 只作"人读摘要"，`errors[]` 是机器读的真源。

* **入口线索**：`GlobalExceptionHandler#handleValidation`（:86-95）、`#handleBadRequest`（:105-118）；
  各 `*Request` 的 `@Valid` 注解。
* **目标**：`errors:[{field, code, message}]` 落地；**缺参 / 类型不匹配 / 请求体不可解析** 归到同一形状；
  顶层仍保留一条 `msg`（给旧前端与新前端的兜底路径）。
* **红线边界**：`code` 用 `T55` 的码表，**不许**用字符串常量；异常 message 里的参数回显（`for value [xxx]`）
  **只能进日志**，`errors[].message` 用稳定文案；`field` 必须是**请求里的字段名**（不是 Java 属性名或参数名混用）。
* **强制探索**：`@RequestParam` 校验（`@NotBlank` 在参数上）现在是否真的生效（既有注释说需要类级 `@Validated`）；
  `MissingServletRequestPartException`（文件缺 part）该不该走 400 + `errors[]`。
* **验收**：两字段同时错 ⇒ 两条 `errors` 且 `field` 准确；非法枚举值 ⇒ 能指出是哪一字段；缺 multipart part ⇒ 400 同形。
* **回写**：`errors:[{field,code,message}]` 落地（`common/web/ApiFieldError` + `ApiResponse#errors` +
  `ApiErrorWriter#summary`，元素构造即校验三条不变量），覆盖**六类**：`@Valid` / 缺 `@RequestParam` /
  缺 part / 类型或取值不可转换 / 请求体整体不可解析 / 控制器参数约束（`HandlerMethodValidationException`）。
  `field` = 请求里的名字（方法校验走注解别名优先），定位不到时**省略**（不编名）；`errors[].code` 恒 `PARAM_INVALID`
  （粒度判据：定位靠 `field`，`code` 只做形状守恒）；`message` = 注解文案或固定文案表，**异常 message 一律不进 body**
  （`for value [xxx]` / `RegisterRequest[password=…]` 都是回显）；顶层 `msg` 由 errors 同源生成（摘要）。
  契约钉在 `ValidationErrorContractTests`（11 例）+ `ApiErrorExitContractTests`（两种合法骨架 + 元素形状）。
  **执行期发现的真实缺陷**：`@RequestParam @NotBlank` 确实生效（Spring 6.1+ 内置方法校验，不需要类级 `@Validated`），
  但 `HandlerMethodValidationException` 无处理器 ⇒ 落 catch-all **500「服务器内部错误」** —— 已在本任务内收口成 400 + `errors[]`。
  反向验证 7 次全部真红：① errors 只取第一条 ⇒ 两字段/摘要两例红；② 用 `ex.getMessage()` 当 message ⇒ 回显例红；
  ③ 一切当"整体不可解析" ⇒ 缺参/类型/取值三例红；④ 摘 `HandlerMethodValidationException` 处理器 ⇒ 方法校验例红；
  ⑤ 给整体不可解析伪造 `field:"body"` ⇒ 该例红；⑥ 业务拒绝也塞 `errors` ⇒ 对照例红；⑦ 按 `field` 去重 ⇒ 不合并例红。
  ⚠️ **未做（有意）**：手写校验（`upload` 发布表单 / `content` 编辑 / `search` 关键词）仍只回 `msg`，
  不产 `errors[]` —— 已登记 `I-26`（fail-fast 单条 + multipart 表单绑定，时机归 `T59`）；
  上传超限 / 畸形 multipart 仍 500 —— 登记 `I-27`（补它要新增 413 类别，属规则变更，不在本任务内放宽）。

### 接口契约专项：路由面（`T58`）

#### T58 路由面重构：方法定性 + 路径资源化 + 命名（来源 `R-21` §1.2–1.3 / 设计 §2.3–2.4、§2.8）【执行中】

> ⚠️ **先质疑（G14）**：
> - **任务**：67 个端点全改路径，是不是"为规范而规范"？—— 已知代价：**一次性打断所有前端调用点**、
>   所有路由级测试都要改。收益 = 方法与命名表达语义（取消点赞不再叫 POST `/remove`），
>   且前端重制**现在**是最便宜的时机（重制完再改就贵十倍）。
> - **设计**：资源化 REST 对"动作型"端点（上报 / flush / 签到 / 播放）是否强扭？—— 是，故设**例外清单**：
>   用资源子路径 + POST 表达，逐条写理由，不许逐端点破例。

* **入口线索**：18 个 `*Controller`（39 POST / 28 GET / 0 PUT / 0 DELETE，见 `endpoint-inventory.tsv`）；`application.yaml` 的 `cost.base`
  （键形如 `"[POST /like/content/add]"`，**非 GET 必须登记**）；`RateLimitWeightTableTests`；`SecurityContractTests`；
  `static/js/api.js` 与 `views/*.js` 的调用点；`common/security/JwtAuthFilter`（无路径耦合，可不动）。
* **目标**：① 39 个 POST 逐个定性（读→GET / 创建→POST / 幂等替换→PUT / 取消删除→DELETE / 动作→POST 子路径）；
  ② 路径资源化 + 全小写 + 统一 `/api/v1`；③ `unlikeContent`/`unlikeComment` 改名（与 `remove`/`delete`/`cancel` 定一词）；
  ④ 产出**接口术语表**（一物一名，含"不要叫"）。
* **红线边界**：**必须同一 commit 内**改完 handler + 权重表 + 安全契约 + 前端调用点（改一半 = 全站 404/500）；
  `cost.base` 的方括号键**逐条改**（漏改不会报错，只有测试能抓）；**GET 不登记权重**（既有口径，别"顺手补"）；
  路径改名会让 `task/` 与域文档里的旧路径失效 ⇒ 该任务收尾时 grep 全仓旧路径（`.docs` + `static`）并更新；
  **不许**同时改载荷形状（那是 `T59`，混在一起无法定位回归）。
* **强制探索**：39 个 POST 的定性表（逐个写"为什么是这个方法"）；哪些动作无法资源化；
  资源复数命名与既有术语（content 是"视频+图文"统称，`/contents` 是否会引起"内容"歧义）；
  路径大小写变更对 `static` 前端与测试的双重影响。
* **验收**：**0 个 POST 承担"取消 / 删除 / 幂等替换"语义**；`RateLimitWeightTableTests` 与真实 handler 一一对应；
  安全契约用例全绿；术语表列全 remove/delete/cancel 的唯一说法；旧路径在本仓 grep 不到残留（`db/migration/**` 除外）。
* **回写**：**方法与路径已全量定稿**（`接口契约.md` §2.3 资源清单 + 例外清单 4 条 + §2.4 定性表 +
  §2.8 补充词条，含 `signIn`/`login` 同名冲突的拆法：`check-in` vs `session`）。
  已交付 **2 个 commit / 19 条端点**：`T58-1` 用户与会话、`T58-2` 内容+上传+评论+点赞。
  ★ **执行期发现三个真实缺陷**（都在同批修掉并配了用例）：
  ① 权重表按**原始 URI** 精确查表 ⇒ 引入 `{id}` 后所有带 id 端点**静默**落 `default-base`
  （完整性用例只比对模式，抓不到）⇒ 改按 `BEST_MATCHING_PATTERN_ATTRIBUTE` 查表 +
  新增「路径变量端点按路由模式扣费」契约用例；
  ② 登记簿与安全契约用 pattern 直接探测 ⇒ `{id}` 匹配不到 handler 恒判 **PUBLIC** ⇒ 探测前把 `{id}` 取样成 `1`；
  ③ id 进路径后「缺 id」从 400 变 **404**（不匹配路由），非法 id 仍 400（`field=id`）——
  3 处用例按契约跟随改口径并留注释。
  ⚠️ **未做（余 48 条端点，按域推进）**：favorite/watch-later、follow、coin、coupon、history、
  reads（详情/搜索/首页/主页/feed）、admin。进度表见 `接口契约.md` §2.3.1（路径已定死，不需要再定一次）。
  ⚠️ **幂等行为（重复 DELETE/PUT 不报 409）与 204 有意留给 `T59`** —— 见 §2.4 注（要清 5 个域的服务判定 +
  相关死码，混进来就不是"只改路由"了）。

### 接口契约专项：载荷面（`T59`）

#### T59 载荷面重构：入参 / 出参 / 分页 / 时间 / ID（来源 `R-21` §1.1 / 设计 §2.5–2.7）【待执行】

> ⚠️ **先质疑（G14）**：
> - **任务**：把 `String("操作成功")` / `Map` / 通配全消灭，收益是不是"审美"？—— 不全是：`/comment/show` 现在
>   **随参数返回两种形状**，`Map` 的键靠约定，这是前端最容易踩的坑（也是重制期最贵的返工来源）。
> - **设计**：`PageResult` 字段被 12+ 端点共用，改名字 = 全站改 —— 已知代价。若只为"好看"不许改；
>   本次改的正当性只来自**形状不一致**（两套信封 + 同端点多态），字段名变更须逐条给理由。

* **入口线索**：★ **`T58` 移交的两件事**：①「重复 DELETE/PUT 不报 409」的幂等化（5 个域的服务判定 +
  清死码 `LIKE_NOT_LIKED`/`LIKE_ALREADY_LIKED`）；②「删除/取消 = 204 + 无 body」（§2.4/§2.5）。
  `ApiResponse<String|Void|Long|Boolean|Map<String,Object>|?>`（见各 Controller 返回类型）；
  `CommentController#show`（:135-145，无分页参数回 `List`、有参数回 `PageResult`）；
  `common/model/dto/PageResult` / `CursorResult`；`common/web/PageParams`；6 个用 `LocalDateTime` 的 VO。
* **目标**：① 入参统一 JSON（multipart 除外），布尔/枚举用真类型；② 出参类型可枚举（无 `?` / `Map` / 裸 `String`）；
  ③ 分页收敛（同一端点只一种形状）；④ 时间格式与 **ID 表示**定稿（BIGINT 走 JSON number 的精度风险要表态）。
* **红线边界**：`/comment/show` **必须**拆端点或定成一种形状，"两种都支持"不算解决；
  204 无 body 要让前端能处理（`T61` 依赖此条）；改 `PageResult` 字段名要**逐端点核对前端与测试**；
  时间格式变更会动 6 个 VO 的断言；**不许**在本任务顺手改路径（`T58` 已定）。
* **强制探索**：ID 精度（当前自增 id 会不会真超 2^53、改成字符串的代价）；`Integer` vs `long` 计数不一致的根因；
  `upload` 返回的 `Map` 键（`contentId` / `url`）是否该各自成 record；时间是否要带时区偏移。
* **验收**：全站出参**穷举后无** `?` / `Map` / 裸 `String`；同一端点任意参数组合只一种形状（按组合跑一遍）；
  分页字段名全站一致；时间字段格式一致（可用一条跨 VO 断言）。
* **回写**：

### 接口契约专项：交付与收口（`T60`–`T62`）

#### T60 OpenAPI 导出（来源 前端重制需要 / 设计 §〇）【待执行】

> ⚠️ **先质疑（G14）**：
> - **任务**：一个学习项目要 OpenAPI 是不是"为了简历加个依赖"？—— 有真实用途：重制的前端要按契约写调用，
>   spec 比 markdown 可靠；且能反过来校验 `T54` 清单。
> - **设计**：会不会产生"第二事实源"？—— 会，故定位死：**代码是源、spec 是产物**，不一致时以代码为准并修注解。

* **入口线索**：`pom.xml`；`common/config/SecurityPassthroughConfig`（默认拒绝，文档端点必须显式放行）；
  `SecurityContractTests`（放行口必须进契约用例）。
* **目标**：springdoc 接入，`/v3/api-docs` 可匿名读；导出静态 spec 供重制使用；端点集与 `T54` 清单对齐。
* **红线边界**：文档端点**必须**走 `@Public`（不能靠"路径白名单"绕过默认拒绝）；spec 里的错误码要与 `T55` 码表一致；
  **不许**为了"文档好看"改运行时行为。
* **强制探索**：springdoc 与 Boot 4.1 的兼容性（版本矩阵）；`@CurrentUserId` 这类自定义解析器在 spec 里怎么表达（不该出现在参数表）。
* **验收**：`/v3/api-docs` 的 path+method 集合 == `T54` 清单集合（差异即红）；匿名可读且不影响鉴权默认拒绝（未放行的新路径仍 401）。
* **回写**：

#### T61 前端适配层（来源 `R-21` §1.2 / `INDEX.md` §一 ①）【待执行】

> ⚠️ **先质疑（G14）**：
> - **任务**：前端都要重制了，还改旧前端是不是浪费？—— 不是：**后端契约一改，旧 SPA 立刻全挂**；
>   而"改一个 `api.js`"是保住可回归能力的唯一便宜办法（`INDEX.md` §一 ① 已把"必须同步 api.js"写成边界）。
> - **设计**：能不能后端兼容旧前端？—— 与 `R-21` §1.2 硬切冲突，且会把兼容债拖到重制后。

* **入口线索**：`static/static/js/api.js`（唯一入口，`request()` 判 `body.code`）、`views/*.js` 里的 `e.code === 401/403/404/409` 分支。
* **目标**：`api.js` 按新契约改造（判 HTTP status、码表映射、204 无体、401 清态、`Retry-After` 退避）；
  18 个 view **尽量不动**（必要时用一层映射让 `err.code` 仍是字符串码）。
* **红线边界**：**不许**为迁就旧前端改后端；`api.js` 顶部那大段端点清单注释必须与 `T58` 后的真实路径一致
  （它现在写着"未映射路径回 404"，而实际是 500 —— 已漂移，见 `接口契约.md` §三 #6）；
  统一请求失败提示只保留一处（`showToast`），不许各 view 各写一套。
* **强制探索**：18 个 view 里哪些 `e.code` 分支会因字符串码失效；上传（multipart）与 204 的兼容；`chunkedList` 是否依赖 `page/pageSize`。
* **验收**：旧页面走查（登录 / 首页 / 搜索 / 详情 / 点赞收藏投币 / 播放记录 / 后台）无回归；
  `api.js` 注释与真实路由一致（可用 `T54` 清单核对）。
* **回写**：

#### T62 收口：文档 + 编号 + 归档判定（来源 专项收口 SOP）【待执行】

> ⚠️ **先质疑（G14）**：
> - **任务**：收口是不是"走流程"？—— 不是：本仓最贵的债是**悬空引用**（`INDEX.md` §五.3），
>   而本专项改了 67 条路径，旧路径会散落在 `.docs` 与代码注释里。
> - **设计**：要不要建 `archive/api-<日期>/`？—— 判据：本专项的设计是**活文档**（`tech/api/接口契约.md` 不随期滚动）
>   ⇒ 与 history / coin 同例**不建归档**，过程留 git。

* **入口线索**：`architecture/tech/api/接口契约.md` 附录；`tools/docs/doc_links.py` / `doc_refs.py` / `doc_stats.py`；
  `INDEX.md` §五.5（收口 SOP 五步）。
* **目标**：`T54`–`T61` 编号收编进附录；`R-21` 结论一行；全仓旧路径 / 旧码 grep 清扫；三道文档门禁 0 违规；
  归档判定写进登记台。
* **红线边界**：`db/migration/**` 一律不改（逐字节冻结）；代码注释只引 `architecture/**`；
  收编时**只增不改号**（`接口契约.md` 章节号若被代码引用过，先 grep）。
* **强制探索**：`.docs` 里残留的旧端点路径（尤其 favorite / history / coin 各篇）；`application.yaml` 权重表的注释是否还写着旧键；
  `FURTHER_ISSUES` 里与本专项相关的行是否需要核销。
* **验收**：`doc_links` / `doc_refs` 0 违规；`doc_stats` 无新超阈值；`mvnw clean test-compile` 过；
  旧路径 grep 结果为空（除 `db/migration/**` 与 `archive/**`）。
* **回写**：

---

## 结转与留池（收尾时逐行核销）

> ★ **不允许静默跳过任何已登记项** —— "本期纳入"与"不做"都必须在表里有一行。
> 反面清单的唯一事实源 = `FURTHER_ISSUES.md` 的 `F-##` 行（每行带**推翻条件**）。

| 来源 | 事项 | 去向（决定人 + 日期） |
|------|------|---------------------|
| 本期收口复核（2026-10-21） | 下表三行**逐行复核：全部维持原决定**，无一条满足其推翻条件 | **维持**（本行不删；下期收尾再复核） |
| T44 设计讨论 | **F-26**：载荷读并入聚合层（单飞跨载荷/计数合一，冷内容 3→2 条 SQL） | **留池**（FURTHER_ISSUES F-26，2026-10-20；推翻条件 = 实测详情载荷 miss 热点） |
| T44 设计讨论 | 聚合路径计数键**不做命中续期**（五键 TTL 跨域） | **登记为已知代价**（缓存文档 §5.2，2026-10-20） |
| T44-1 执行发现 | favorite 键在内容删除时**不失效**（键残留 TTL 自愈） | **如实钉进测试**（`ContentDetailCountAggregateTests`，2026-10-20） |
| 接口契约专项（2026-10-22） | **`F-15`（前端互动态维度常量）** —— 原决定"归前端重制自然解决" | **仍归重制**（本专项只动契约形状，不改维度语义；重制时按 `favorite.md` §六 先把常量加回） |
| 接口契约专项（2026-10-22） | **旧前端 18 个 view 的重写**（登录/详情/后台等页面体验） | **不做**（`T61` 只保适配层不挂；页面重写属重制期，不在本专项） |
