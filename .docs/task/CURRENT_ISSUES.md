# 当期问题（Current Issues）

> **定位**：执行过程中**当场发现、且本期或下期要处理**的问题 —— 阻塞、偏差、待裁决、踩到的坑。
> **与 `FURTHER_ISSUES.md` 的分工**：**本期/下期解决** → 本文件；**暂时不会排期解决**（结转 / 留池）→ `FURTHER_ISSUES.md`。
> **边界**：需求与痛点 → `CURRENT_NEEDS.md`；任务执行态 → `CURRENT_TASKS.md`。
>
> ★ **膨胀控制（2026-10-11 起执行）**：处理完即标状态；**期收尾时把正文清出**（归宿 = git commit），本表只留一行摘要。
> 清出前**必须先把编号收进** ★ **活速查**（落点见 `CURRENT_NEEDS.md` §三 末）。
> **编号不回收**：跨期未决的结转时**保留原编号**并在 `FURTHER_ISSUES.md` 中标注来源。

---

## 登记格式

问题 + 现象 + 证据（可复核）+ 影响 + 处置 + 去向。编号 `I-##` 顺延（**不回收**）；从别处结转进来的保留原编号。

**分类取值**：阻塞 / 偏差（与计划不符）/ 缺陷 / 环境 / 决策 / 债务。

---

## 问题登记（I-##）

> **状态取值**：待处理 / 已纳入NEEDS / 已解决 / 待裁决（须停下问用户）/ 已转 `F-##`。

### I-23 T47 的验收口径与实际范围不自洽（「四端点」vs 只做写路径两端点）【已解决】

| 项 | 内容 |
|---|---|
| 分类 | 偏差（清单内部矛盾，非实现缺陷） |
| 现象 | `CURRENT_TASKS.md` T47 的**目标**只含 `/history/report` + `/history/play`，但**验收**写「`SecurityContractTests` 四端点」；而 `/history/list`、`/history/position` 被明确划给**下一批**读面（同段"本任务不碰详情读面"）。两者不可能同时满足。 |
| 证据 | `CURRENT_TASKS.md` T47 验收行 vs 目标行；`architecture/domain/history/history.md` §三（**当时**四端点；`/flush` 加入后现为五个）+ §七（读路径属下一批） |
| 影响 | 若照验收硬做四端点，就得先落地读面（超出 T47 范围）；若只做两端点，则"四端点"这条验收本批无法兑现。 |
| 处置（G13 自行决断） | T47 只落写路径两端点；`SecurityContractTests#history写端点需登录` 钉 `/report` 与 `/play` 两条路由，并在用例注释里**显式写明**"`/list`、`/position` 属下一批，必须在那批补进本用例" ⇒ 缺口留在代码里可见，不靠记忆。实测：`SecurityContractTests` 30 例全绿（含新用例）。 |
| 去向 | **已核销（T49）**：`/history/list`、`/history/position` 两条 `GET` 已补进同名用例（四路由齐）；类级 `@RequiresLogin` 自动覆盖，无需逐端点加注解。 |

### I-24 三处 status 与 §2.1 措辞不一致（T55 只换码、未动类别）【待处理】

| 项 | 内容 |
|---|---|
| 分类 | 偏差（规范与实现的既存不一致，**非本次引入**） |
| 现象 | §2.1 把 409 描述为"参数合法但当前状态不允许（重复点赞、**夹已满**、手机号占用）"、404 为"资源不存在"，但实现里：① 收藏夹已满 / 稍后再看已满 / 收藏夹数量已达上限 → **400**；② 硬币不足 → **400**；③ 未映射路径 → **500**（已归 `T56`）。 |
| 证据 | `FavoriteService#requireFolderCapacity`（`FAVORITE_FOLDER_FULL`）、`WatchLaterService`（`WATCHLATER_FULL`）、`FavoriteService` 夹数上限（`FAVORITE_FOLDER_LIMIT`）、`CoinService` 硬币不足（`COIN_INSUFFICIENT`）；`RateLimitFilterContractTests:292`（部分已被 T54 修成 404） |
| 影响 | 前端若严格按 §2.1 写分支，"夹已满"会被当 400（字段错误）处理，而不是 409（状态冲突）。当前前端未这样写 ⇒ 无运行期事故；但规范与实现并存两份口径，下一个人一定会问"到底信哪个"。 |
| 处置（G13 自行决断） | `T55` **只换码、不动状态类别**：这三处是**域文档冻结的契约**（`coin.md` §五.1 的拒因七级顺序、收藏容量口径），改状态属对外契约变更、超出"码表"边界；且码已能唯一表达原因（前端不必靠 status 区分）。 |
| 去向 | **`T58`/`T59` 收口时统一裁决**：要么改实现（400→409）、要么改 §2.1 措辞（把"配额类"从 409 行移出）。二者必选其一，**不许两边都留着**。 |

### I-25 重复抢券的对外码是基础设施码 `SYSTEM_DUPLICATE_KEY`（T55 暴露）【待处理】

| 项 | 内容 |
|---|---|
| 分类 | 债务 / 决策（**T55 换码表时才看得见**：旧实现所有 409 共用 `code=409`，这条路径的"泛码"被数字掩盖了） |
| 现象 | `POST /coupon/grab` 重复领取时撞 `uk_coupon_user` ⇒ `DuplicateKeyException` ⇒ 出口只能回 `SYSTEM_DUPLICATE_KEY`（msg「数据已存在，请重试」）。前端拿不到「您已抢过该优惠券」这句精确提示，也无法按码区分"抢过了"与"真出事了"。 |
| 证据 | `CouponService#grabCoupon`（C-1 决定"不判 1062"）+ `CouponFlowTests#重复抢券库存只扣一次`（断言 `SYSTEM_DUPLICATE_KEY`）；`uk_coupon_user` / `uk_coupon_code` 见 `V1__baseline_tv_schema.sql:163-164` |
| 影响 | 只影响**提示文案的精确度**（状态仍 409、库存仍只扣一次、回滚语义不变）。但它是"码表能表达原因"这个承诺的第一个反例。 |
| 处置 | `T55` **不自行推翻 C-1**：出口拿到 `DuplicateKeyException` 时分不清撞的是 `uk_coupon_user` 还是 `uk_coupon_code`，硬猜会把两种原因报反；在 Service 里 catch 再翻译 = 恢复 C-1 删掉的 1062 判断，属决策变更。⇒ 如实登记，测试按实际行为钉住。 |
| 去向 | **待裁决**。三个选项：① 维持现状（泛码 + 通用文案）；② 在 `CouponService` catch 唯一键异常并翻译成 `COUPON_ALREADY_GRABBED`（推翻 C-1，需重新登记 R-##）；③ 改成"先查后插"（引入竞态，C-1 已否决）。建议在 `T58`/`T59` 收口时与 `I-24` 一起裁决。 |

### I-26 手写校验（含发布/编辑表单）仍只有 msg、不产 `errors[]`（T57 暴露）【待处理】

| 项 | 内容 |
|---|---|
| 分类 | 决策 / 债务（范围边界，**不是漏改**） |
| 现象 | `T57` 只为**校验族**（`@Valid` / 缺参 / 类型 / 不可解析 / 方法校验）产出 `errors[]`。而真正"表单多字段"的发布与编辑仍走手写校验：`UploadController#requireTitle`（标题空/超 50 字）、`normalizeDescription`（简介超长）、`ContentService#updateContentInfo`（标题/简介）、`SearchController#keywordSearch`（关键词空）——它们抛 `ParamException`，对外只有一条 `msg`。 |
| 证据 | `UploadController:236/239/250`、`ContentController#update` → `ContentService#updateContentInfo`、`SearchController:104`；逐字文案断言见 `UploadFlowTests:208`、`ContentReadTests:521/566/274` |
| 影响 | 前端重制时，**发布 / 编辑作品**这类多字段表单拿不到字段级定位（仍要靠匹配中文）。与 §2.5.1"校验失败必有 `errors[]`"看起来矛盾（实际是范围边界，已在 §2.5.1 明确写出）。 |
| 处置（`T57` 自行决断，G13 不擅自扩范围） | 本任务**有意不做**，三条理由：① 这些校验是 **fail-fast 单条**（一次只报一条），"多字段同时错"的需求本就不成立；② 发布是 **multipart**，`@Valid` 绑不到表单字段，要迁得先设计"表单绑定 + 字段名"机制；③ 迁移会改动 5 处以上**逐字冻结文案**与业务校验顺序，属载荷面（`T59`）与域文档的范围。 |
| 去向 | **待裁决**，建议在 `T59` 收口时定：① 维持现状（`msg` 够用，发布表单逐条提示）；② 引入"可携带 `errors[]` 的参数异常"（如 `ParamErrorsException`）并把 upload / content 的手写校验迁过去 —— 需同时改域文档与上述逐字断言。 |

### I-27 上传超限 / 畸形 multipart 仍是 500（T57 复核时发现）【待处理】

| 项 | 内容 |
|---|---|
| 分类 | 缺陷 / 决策（要修就得动 §2.1 的状态表） |
| 现象 | 用户上传超过限额的文件（`spring.servlet.multipart.max-file-size=50MB` / `max-request-size=100MB`）⇒ `MaxUploadSizeExceededException`；multipart 报文畸形 ⇒ `MultipartException`。两者都**没有**专属 handler ⇒ 落 `GlobalExceptionHandler#handleUnexpected` ⇒ **500 `SYSTEM_INTERNAL`「服务器内部错误」**。 |
| 证据 | `application.yaml`（multipart 限额段）+ `UploadController#uploadVideo`（`@RequestPart`）→ `GlobalExceptionHandler#handleUnexpected`；`接口契约.md` §2.1 状态表**没有 413 这一行**，§2.5.1 也没登记这一情形 |
| 影响 | 与 `T57-3` 修掉的那条（`@RequestParam` 约束 → 500）是**同一类**：**用户输入问题报成服务器事故**。超限还很常见（手机拍的 4K 视频轻松过 50MB），且 500 会让前端提示"服务器错误"、监控也会误判成故障。 |
| 处置 | `T57` **不自行放宽 §2.1**（本专项红线：规则不得执行期"顺手放宽"，要改先登记）。故只登记、不实现：正确形状应是 **413**（`Payload Too Large`）+ `errors[field=<part 名>]`，但那是给 §2.1 加一个新状态类别。 |
| 去向 | **待裁决**（建议在 `T58` 或 `T62` 收口时定）：① 加 413 类别 + `UPLOAD_TOO_LARGE` 码（语义最准，需同步 §2.1 / §2.2 / 前端字典）；② 归入既有 400 + `PARAM_INVALID`（不加类别，代价是"文件太大"与"参数不合法"同状态）；③ 维持 500（不推荐：等于承认"用户输入可触发服务器错误"）。 |

**本期其余暂无在办问题** —— 编号从 **`I-28`** 起顺延；已终结问题的**一行摘要**见文末「已解决」。

---

## 待裁决（G3：须停下问用户）

> 按 `CURRENT_TASKS.md` 的运行约定 G3：**需要用户拍板的，登记在此并停下问**，
> 不擅自决定、不硬扛、不超范围。

**本期暂无待裁决事项** —— 下一编号从 **`R-17`** 起（方向由本期开窗时登记）。

---

## 已解决

> 已终结问题的**一行摘要**（正文随各期收尾清出，历史在 git）。**唯一活查证入口** = 对应功能的
> **活速查**（落点见 `CURRENT_NEEDS.md` §三 末）；**逐条历史论证** = 对应 `archive/<域>-<归档日>/`。
> ⚠️ 摘要**不删行**（编号不回收，代码 / 文档里有引用）。

| 编号 | 一句话结论 | 解决于（任务 / 日期） |
|------|-----------|---------------------|
| I-23 | T47 验收「SecurityContractTests 四端点」与范围（只做 `/report`+`/play`）矛盾 ⇒ 本批只钉两路由，注释写明读面两端点下一批必须补进；T49 已补 `/list`、`/position`（四路由齐，核销） | T47 / 2026-10-21（T49 核销同日期） |
| I-15 ~ I-22 | **限流专项 8 条**：配置绑定注释纠偏（record 可绑嵌套段）/ `miss-multiplier` 取 3.0 / 门禁规则补强（`task/` 登记台与"设计稿"别名禁入代码）/ TTL 欠账边界（`t ≥ cap − TTL×rate`）/ 跳过清单补 `/upload/**` / 信号② 两阶段（门后差额补扣）/ 指标逐桶返回 / 未鉴权绕过登记 `D-8`（**非修复**） | T25–T29 / 2026-10-16 → 10-17 |
| I-01 ~ I-08 | 一期 8 条（默认夹唯一键 / 结构锚点 / `Envelope` / 事务判别力 / 注入纪律 / `EXPLAIN` 实测 / 装载契约根治 / 弹窗互锁） | 一期 / 2026-10-08 → 10-11 |
| I-09 ~ I-12 | 二期 4 条（`ProfileService` 内联点赞 ⇒ **F-16** / Bean 环 **@Lazy** 断 / 缓存写点口径 / T12 少列 `move`） | 二期 / 2026-10-11 → 10-12 |
| I-13 | T13 任务书口径**四处自相矛盾** ⇒ 按 B站 实际展示面拍板（`/start`=`{关注}`、`/search`=**0 维**） | T13 / 2026-10-13 |

> 📌 **收藏期的完整论证**在 `archive/favorite-2026-10-15/收藏功能-编号索引.md` **A.3**；
> **限流期的完整论证**在 `archive/rate-limit-2026-10-17/限流-设计稿.md`（文末有 `T29` 修订注）。
