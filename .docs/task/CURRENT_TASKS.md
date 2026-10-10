# 当期任务（Current Tasks）

> **定位**：登记**当期正在执行的任务**及其执行态（四要素 + 三句以内回写）。
> 计划态（分期、骨架设计、验收口径）在 [收藏功能-分期与设计.md](收藏功能-分期与设计.md)；
> **本期范围与反面清单**在 [CURRENT_NEEDS.md](CURRENT_NEEDS.md) §四。**本文件只记执行态**，不复制计划。
> **需求来源**：`CURRENT_NEEDS.md`（N# / R-##）；**执行中发现的问题** → `CURRENT_ISSUES.md`。
> **膨胀控制**：任务完成即标注并在收尾时**清出本文件**（归宿 = git commit）；跨期未完成保留编号结转。

---

## 周期头（每窗开读）

* **方向**：收藏功能 **一期（有无）** —— 「先有无、后性能」，本期不看 QPS
* **目标**：把 `收藏功能-需求.md` 里标「纳入」的每一项做成**能用且行为正确**
  （对应的 9 条能力见 [CURRENT_NEEDS.md](CURRENT_NEEDS.md) §四，骨架设计见分期篇 §二/§三）
* **本周期**：`2026-10-08` 起 · 第 1 张清单
* **收尾 checklist**：□ 回归基线全绿（`python tools/run_tests.py`） · □ 完成态已清出（记入 git） · □ 问题已结转（`FURTHER_ISSUES.md`） · □ 工作树干净

---

## 运行约定（只保留当前仍然有效的）

| 号 | 约定 |
|----|------|
| G1 | 默认一任务一窗口一 commit；★ **但允许执行者自拆** —— 任务可能是连贯的、要深入调查后才知怎么拆，执行者可自行拆子任务与多个 commit（**每 commit 仍须可编译**，message 带任务号），拆法写进回写 |
| G2 | **证据可复核**：入口与验收能定位（文件:行 或 可复现命令），不接受纯文字断言 |
| G3 | **疑问分级**：可自答的 → 任务回写留一句；须裁决的 → **停下问用户**，不擅自拍板、不硬扛、不超范围 |
| G4 | 一任务 = 四要素 + 三句以内回写；探索过程 / 失败尝试 / 中间稿一律走 git，不进本文件 |
| G5 | **声明式机制必须配契约测试**：断言通过 ≠ 机制生效；写「证明某机制」的用例前先自问「去掉那行实现会红吗」（详见仓库根 `AGENTS.md`「测试纪律」节） |
| G6 | 改 schema 走 Flyway **V2**（V1 冻结）；测试用 DB 时钟造时间窗、关键计数走独立 oracle 查列 |
| G7 | ★ **一期不许写缓存、不许写冗余计数增量维护**（属二期）；若某任务发现自己需要它们，先停下改分期而不是硬做 |
| G8 | ★ **一期的测试必须只断言可观察行为**（HTTP 状态 + body + DB 终态）—— 二期换实现时测试**不许改**；改了就说明测试在抄实现（纪律①） |
| G9 | `favorite` 域**必须登记进 `ArchitectureTests.DOMAINS`**：不登记则 ArchUnit 规则 1/2/3 根本不覆盖它，"规则通过"是**永远绿的假绿** |
| G10 | 🚫 **红线：收藏不得接 feed** —— `FavoriteService` **不得**调 `FeedDelivery.deliver`、**不得**发 `ContentPublishedEvent`。⚠️ 它是"不要做某事"，**没有实现可去掉 ⇒ 写成测试必假绿**，靠 review 守；真要写成测试，先注入缺陷证明它会红 |
| G11 | ★ **`/favorite/remove` 不得校验内容存在性** —— 直接按 `(folder_id, content_id)` 删行。⚠️ 与 `/like/*` 写路径"先校验存在性"的惯例**刻意不同**：加了校验，失效条目就**永远清不掉**（用户在夹里看得见占位却删不掉）。反向：`add`/`move` 照常校验，「失效不可移入」由此**自然产生**，无需专门分支 |
| G12 | ★ **夹内列表分页按 `favorite_item` 行分页**（不是按"内容"）：失效记录占一条、返回一条占位；判失效 = **content 装载契约"取不到"**（不存在 / 已删 / 媒体损坏 / 未知 type，口径"看不了 ≈ 失效"见 R-11）——⚠️ 原"**LEFT JOIN content** + `is_deleted` 判定"半句已随 T6-5 残留提交作废（它测不出"视频行缺失 / 未知 type"）。一张信封被失效条目占满也照常返回 |
| G13 | ★ **清单只给方向与红线，不写死实现步骤** —— 有些问题**只有执行期才看得见**，写清单的 AI 不必、也不该扛下所有压力。把执行者当**勤快的外包**（给方向与边界，不是给施工图纸）。执行中发现清单前提不成立 / 与既有实现冲突 / 有更好的拆法 ⇒ **自行决断或停下登记 `CURRENT_ISSUES.md` I-##**，不硬扛、也不擅自扩大范围 |

---

## 任务

> 每任务必含**四要素 + 回写**；状态取值：待执行 / 执行中 / 已完成 / 搁置（搁置**必须**注明去向）。
> 登记时复制下面模板，编号顺延。
>
> 必须每任务在入口线索强调任务可被AI质疑，可能任务前提不成立/需求被已有实现覆盖/执行任务时机不对/想达成目标可能引入更大的问题

```
### T1 <任务名>（来源 N# / R-## / 能力 一期-x）【待执行】

* **入口线索**：
* **目标**：
* **红线边界**：
* **强制探索**：
* **验收**：
* **回写**：（完成后三句内写清改了什么、验证结果；探索过程不写）
```

> 📌 下面的清单是**粗颗粒**（G13）：给方向与红线，不给施工步骤。执行期发现更合理的拆法，
> 自己拆（G1）并在回写里说一句即可。

### T1 建表 + 域骨架（来源 N1 / 能力 一期-1）【已完成】

* **入口线索**：`src/main/resources/db/migration/V1__baseline_tv_schema.sql`（V2 从哪起步）；
  `ArchitectureTests.java:54` 的 `DOMAINS`；`like` 域的包结构作参照。
  ⚠️ **前提可被质疑**：一期真的需要 `content.favorite_count` 列吗？"列先加零成本"若与 V2 落地冲突，
  **只建两表也是正当结论**。
* **目标**：V2 建 `favorite_folder` + `favorite_item`；`favorite` 域骨架（controller / service / dao）落地。
* **红线边界**：V1 冻结不动；**不建 `cache` 包**（二期）；`favorite_count` 列加了也**一期不读不写**。
* **强制探索**：先读 `architecture/tech/数据与Schema演进.md`；确认 `is_deleted` 三态（0/1/2）；
  `favorite_item` 的 `uk_folder_content` 与 `idx_user_content` 是否都必要（后者服务于"按人去重"）。
* **验收**：起库通过；**`ArchitectureTests.DOMAINS` 含 `favorite`**（不是"规则通过"—— 那是永远绿的假绿）；
  表结构可 `SHOW CREATE TABLE` 复核。
* **回写**：V2 落地 `favorite_folder` / `favorite_item` / `content.favorite_count`（列**一期只建不用**）
  + `favorite` 域骨架（controller / service / 2 DAO，**刻意无端点无 SQL** —— 端点归 T2~T6）
  + `ArchitectureTests.DOMAINS` 登记。验证：活库已到 **v2**（`flyway_schema_history` = `0/1/2`、18 张表）
  且应用 4.4s 起库（health `DOWN` 仅因本机 Redis/RabbitMQ 未启动）；`run_tests.py` **328 例全绿**
  + `--group resilience` **34 例全绿**；★ 反向验证两条 —— 唯一键降级为普通索引 + 删 `is_private`
  ⇒ `FavoriteSchemaTests` 3 例按预期变红（已还原）；藏掉 V2 ⇒ 结构锚点变红（旧写法会全绿，见 **I-02**）。
  ★ 执行期发现骨架里缺"每用户至多一个默认夹"的唯一键（**正是 T2 红线所依赖的那个键**），
  已落地生成列 `default_uniq` + `uk_user_default`（**R-09** / **I-01**），分期篇 §3.1 同步回填。

### T2 收藏夹 CRUD（来源 R-01 / R-02 / 能力 一期-2）【已完成】

* **入口线索**：`/like/*` 的 controller 风格作参照。
  ⚠️ **前提可被质疑**：默认夹"懒建"与"列表返回空态"是否自洽？执行期若发现更稳的做法（如读时补建）可自行决断，
  但**并发重复建夹**必须靠唯一键兜住。
* **目标**：新建 / 改名 / 删除 / 我的夹列表（含每夹视频数）；**默认夹拒绝删除**。
* **红线边界**：**不动注册流程**（R-01 懒建）；删夹**一并删条目**（R-02）；一期不做封面；
  并发重复建夹靠 **`uk_user_default`** 兜住（R-09，V2 已建该键 —— 别退回"先 SELECT 后 INSERT"）。
* **强制探索**：删除默认夹返回什么错误码（对齐既有 `NotFoundException` / `ConflictException` 语义）；
  列表在无夹时的空态形态。
* **验收**：删默认夹返回明确错误；无夹时列表返回空**而非报错**；每夹视频数用
  **独立 oracle**（`SELECT COUNT(*) FROM favorite_item WHERE folder_id=?`）复算。
* **回写**：落地夹 CRUD **四端点**（`POST /favorite/folder/{add,update,remove}` + `GET /favorite/folder/list`；
  写 = POST + form、读 = GET + query，**逐端点** `@RequiresLogin` —— 收藏域**不能**用类级，见
  `SecurityContractTests.favorite夹CRUD端点需登录`）。新增 `model/entity/FavoriteFolder` + `model/vo/FavoriteFolderVO`
  + 两 DAO 共**六**条 SQL（`FavoriteFolderDao` 5 条 + `FavoriteItemDao` 1 条）；
  列表一条 `LEFT JOIN favorite_item` + `COUNT(i.id)`（空夹留行、**失效记录照算**）。
  ★ 关键口径：**默认夹拒删 409**（判 `is_default`、不判名字）、**删夹一并删条目同事务**（R-02）、
  **空态返回 `[]` 而非报错**（R-01 懒建的自洽性论证写在 `FavoriteService` 类注释）、
  404/403 归属口径、名称空白/超长 400（且 **400 判在 403 之前**，顺序已写进用例钉死）。
  验证：`FavoriteFolderCrudTests` **12 例** + `SecurityContractTests` 新增 1 例全绿；
  **回归基线 `run_tests.py` = 341 例全绿**（默认组）。
  **未做（各有归属）**：`is_private` 开关归 **T3**（T2 只把它读进 VO）、简介在需求篇是「待定」、
  重名与容量不限制 → 新增 **F-11**；`static/js/api.js` **未动**（新端点尚无前端消费方，T7 统一接）。
  执行期自查：**懒建 + 空列表自洽**（"读时补建"会让 GET 带副作用、还把公开读能力绑死在写权限上 ⇒ 不采纳）；
  默认夹的前提只能用 DB 夹具造（一期没有"建默认夹"的端点，懒建落点在 T4）。
  ★ **收尾派了独立 subagent 审查**，它查出两处**自我声明不成立**并已修正：① 测试类注释原写
  "`COUNT(DISTINCT content_id)` 是有效注入点" —— 错，同一夹内它**恒等于** `COUNT(*)`（唯一键
  `uk_folder_content`），真注入点应为 `COUNT(DISTINCT user_id)`；② 原写"删掉 `@RequiresLogin` ⇒
  未登录用例变红" —— 错，四个端点都收 `@CurrentUserId`（取不到即 401），该 HTTP 用例对机制是**假绿**，
  判别力只在 `SecurityContractTests`。同时补两处缺口：VO 布尔键的**正向断言**（`path().asBoolean()`
  对**缺失键**恒 false ⇒ 只断 `isFalse()` 证明不了键名）+ "默认夹**改名后**仍拒删"（把"判列不判名"钉死）；
  删夹事务的判别力缺口登记为 **I-04** 交 T8。★ **反向验证已实测（`temp-script/t2_injections.sh`，逐条注入→跑用例→`git checkout` 还原）**：
  ① 去掉 `deleteFolder` 的 `is_default` 判断 ⇒ **1 例红**（默认夹拒绝删除）；
  ② `LEFT JOIN` → `INNER JOIN` ⇒ **4 例红**（凡有"0 条记录的夹"的列表用例都红）；
  ③ `COUNT(i.id)` → `COUNT(DISTINCT i.user_id)` ⇒ **1 例红**（每夹条目数 3→1）；
  ④ 删掉四个端点的 `@RequiresLogin` ⇒ `SecurityContractTests` **1 例红**、而
  `FavoriteFolderCrudTests` **12 例全绿** —— 这条实测正好证实审查的结论：**那个 HTTP 用例对机制是假绿**。
  四轮结束后还原、两根全绿（14 + 12 例）。

### T3 私密开关 + 他人公开夹端点（来源 R-05 / R-08 / 能力 一期-3）【已完成】

* **入口线索**：新增 `GET /favorite/folder/public?userId=X`（他人视角只返回公开夹）。
  ⚠️ **前提可被质疑**：这个端点**要不要允许匿名**？先查 `JwtAuthFilter` 的放行规则与
  `@CurrentUserId(required = false)` 的既有用法再定，别照抄 `/profile`。
* **目标**：`is_private` 可改；他人视角只返回公开夹 —— **这就是"私密"一期的可观察落点**。
* **红线边界**：**"我的"与"他人的"必须两个端点**（R-08）；**`/profile` 不动**（避免 `content → favorite` 新依赖边）。
* **强制探索**：鉴权口径（见 `architecture/tech/安全与鉴权.md`）；默认夹能否设为私密（若 B 站允许则允许）。
* **⚠️ T2 交接（执行期发现，2026-10-08）**：
  ① `POST /favorite/folder/update` 在 T2 是「`name` **必填**」，而分期篇 §3.3 把它定义成**部分更新** ⇒
  本任务要把它改成"给了才改"，`name` 一并变可选（否则"文档说可部分更新、实现要求 name"会长期漂移）；
  ② 改名的错误码顺序已在 T2 钉死并写进用例（**400 判在 403 之前**），加可选字段时别打乱；
  ③ ★ **别新增探测面**：`rename`/`remove` 现按既有口径对"他人的夹"返回 **403**、对"不存在"返回 404
  （沿用 `CommentService`/`ContentService`，T2 有意不改成一律 404）⇒ 公开端点不得让"私密与否"影响错误码，
  否则等于给"这个夹存不存在/是不是私密"开了个探测口。
* **验收**：★ **反向验证 —— 去掉 `is_private = 0` 过滤，测试必须变红**；不变红就是假绿，重写。
* **回写**：落地两条交付 —— ① `POST /favorite/folder/update` 升级为**部分更新**（`name` 变可选 +
  新增可选 `isPrivate`，`0/1/true/false` 在 Controller 解析、与 `/content/commentEnabled` 同值集；
  "给了才改"由**一条**动态 `<set>` SQL 完成 ⇒ 单写即原子、不加事务；两个都不给 ⇒ 400，且
  **400 仍判在 403/404 之前** —— 空更新与非法 `isPrivate` 对"他人的夹"同样 400，已用例钉死（T2 交接①②）；
  ② 新增 `GET /favorite/folder/public?userId=X` —— 逐端点声明（**无** `@RequiresLogin`、**无** `@CurrentUserId`）
  ⇒ 匿名 / 坏 token 均 200；SQL 只返回 `is_private = 0`（写 `=0` 而非 `!=1`，将来值域扩了也不泄露）；
  **不校验 userId 存在性**（空态 `[]` 不 404）；条目**只有 `name` + `itemCount`**（R-08「一期只给名称 + 视频数」，
  刻意无 id/isPrivate/isDefault，键集由 `propertyNames` 用例钉死）；顺序复用"默认夹置顶 + 创建序"。
  ★ 执行期决断（G13）：**默认夹也可设私密**（需求篇只禁删除）；**本人视角同款只返回公开**（不加"是本人就显示私密"
  分支 —— 分支写错即泄露，R-08 也没这需求）；匿名结论 = **允许**（设计篇 §3.3 冻结，端点与登录态完全解耦）。
  `/profile` 未动、rename/remove 错误码未动（交接③：无新探测面）。拆 **4 个 commit**（T3-1 feat / T3-2~T3-4 test）。
  验证：`FavoritePrivacyTests` **10 例** + `SecurityContractTests` 15 例（+1 反向断言）+ T2 `FavoriteFolderCrudTests`
  12 例全绿；**回归基线 `run_tests.py` = 352 例全绿**（默认组）。
  ★ **反向验证已实测（`temp-script/t3_injections.sh`，逐条注入→跑用例→`git checkout` 还原）**：
  ① 去掉公开 SQL 的 `AND f.is_private = 0` ⇒ **3 例红**（正是本任务验收点名的注入点）；
  ② service 把 `isPrivate` 恒当 null 传 ⇒ **4 例红**；③ 去掉"两个都没给 ⇒ 400"守卫 ⇒ **1 例红**
  （该守卫还挡住空 `<set>` 的 SQL 语法错 500）；④ Controller 误加类级 `@RequiresLogin` ⇒ 机制级 **1 例红**
  + HTTP **5 例红** —— 实测修正了 T2 时代"HTTP 侧抓不到误标"的表述（那只在公开端点还没有 HTTP 用例时成立）。
  ★ **收尾派了独立 subagent 审查**，它查出：两处**自我声明不成立**（上述④的注释措辞 / `FavoriteService` 声称
  "非法 isPrivate 覆盖对他人的夹"而用例未覆盖）+ 一处假绿路径（空夹 `itemCount` 只断 `== 0`，
  `asLong()` 对缺失键返回 0），均已在 **T3-4** 修正（补断言 + 改注释，并重跑两根全绿）。
  **未做（各有归属）**：`static/js/api.js` **未动**（新端点尚无前端消费方，T7 统一接）；简介仍未实现（需求篇「待定」）；
  公开面将来若要加 `id`（如打开他人公开夹），必须同步改键集断言 —— 已写进 `PublicFavoriteFolderVO` 注释。
  ⚠️ 另记一笔执行期踩的坑（详情见 **I-05**）：`git checkout --` 式注入脚本**必须先提交基线**，
  否则"还原"会把未提交改动一并抹掉（本任务首跑实测丢过 3 个文件的改动，重做后改为"先 commit 再注入"）。

### T4 收藏 / 取消 / 移动 / 批量移出（来源 R-07 / 能力 一期-4）【已完成】

* **入口线索**：`/like/*` 写路径（先校验存在性 → 再校验状态 → 改行 → 计数）作参照。
  ⚠️ **前提可被质疑**：四处写操作是否都需要独立端点？`move` 能否就是 `remove` + `add`？
  若执行期发现合并更简洁，**自行决断**（但事务边界要讲清楚）。
* **目标**：`add` / `remove`（支持批量）/ `move` 三个写端点；重复收藏**幂等拒绝**（409）。
* **红线边界**：🚫 **`remove` 不得校验内容存在性**（G11 —— 一加，失效条目就永远清不掉）；
  `add` / `move` **照常校验**（"失效不可移入"由此自然产生，别写专门分支）；
  **不接 feed**（G10）。
* **强制探索**：批量移出的**事务边界**怎么切（见 `architecture/tech/事务边界.md`）；
  `DuplicateKeyException → 409` 的既有映射是否已覆盖新表。
* **验收**：失效条目**能移出**、**move 被拒**；重复收藏 409；计数以 **DB 终态**为准（不信接口回显）。
* **回写**：落地三端点（`POST /favorite/add|remove|move`，写 = POST + form、逐端点
  `@RequiresLogin`）。**强制探索②已确认**：`GlobalExceptionHandler` 的
  `DuplicateKeyException → 409` 是全局映射，新表自动覆盖，无需新增。★ 执行期决断（G13）：
  ① **`move` 保持独立端点** —— remove+add 两连击无原子性：add 失败时条目已从源夹消失，
  正好违反 R-07「失效不可移动但**保留原位**」；实现为 `@Transactional` **先插后删**，
  409（目标夹已有同内容 / from==to）时源夹必然未动，无专门分支；
  ② **`add` 单夹单内容**（分期篇 §3.3 草图"收藏到指定夹"）—— "一次可进多夹"（需求篇 §三）
  由前端多次调用表达；批量 add 会让一个 409 连坐整批（部分成功语义会漂移），
  且唯一键本就按 `(folder_id, content_id)` 逐对拒绝；`folderId` **可缺省** = 收进默认夹
  （**R-01 懒建落点**，新用户无途径拿默认夹 id）：`ON DUPLICATE KEY UPDATE id=id` 撞
  `uk_user_default` 跳过（沿用 R-09 结论，**不退回先查后插**），懒建路径**不加事务**
  （空默认夹是合法终态、与收藏记录无完整性耦合；内容校验先行 ⇒ 404/409 不留任何行）；
  ③ **`remove` 幂等**（记录不存在也 200）—— 不做记录存在性预检：批量下"查后删前被并发
  移走"会造假错；批量 = **单条 `DELETE ... IN`** 原生原子，不加事务；
  ④ **校验顺序钉死：内容存在性先于夹归属**（对齐 like 参照 + 懒建路径 404 无痕），
  已用例钉住（幽灵内容收进他人的夹 = 404 而非 403）；⑤ **move 后收藏时间取新值**
  （可读作"收进目标夹的时间"，需求篇无约定，取最简自洽实现）。
  新增 `ContentDao.isContentExist` **DAO 薄依赖**（合法通道之一，与 like 域同形态）；
  `favorite_item` 补 2 条 SQL（insertItem / deleteByFolderAndContentIds）、
  `favorite_folder` 补 2 条（懒建插入 + 按业务键回查 id —— 不用连接级 `LAST_INSERT_ID`，
  两次 mapper 调用可能各借各的连接）。拆 **3 个 commit**（T4-1 feat / T4-2 test / T4-3 docs）。
  验证：`FavoriteItemWriteTests` **11 例** + `SecurityContractTests` 1 例（机制级）全绿；
  **回归基线 `run_tests.py` = 364 例全绿**（默认组，352 + 12）。
  ★ **反向验证已实测（先 commit 再注入，逐条注入→跑→`git checkout` 还原）**：
  ① `removeItems` 注入内容存在性校验 ⇒ **2 红**（`批量移出_失效条目能移出` + `移出错误线`
  —— 后者因校验在归属之前把 403 变 404，判别力多余但真实）；② 删 `addItem` 的
  `isContentExist` ⇒ **2 红**（`失效内容不可收藏` + `收藏错误线`）；③ 删 `moveItem` 的
  `isContentExist` ⇒ **1 红**（`失效内容不可移动且留在原位`）；④ `moveItem` 改"先删后插 +
  去 `@Transactional`"（两段式）⇒ **1 红**（`移动目标重复409_两边保留`；⚠️ 只换顺序保留事务
  **不红** —— 回滚兜住，说明该用例的红依赖事务在场，钉的是"409 ⇒ 什么都不变"契约）；
  ⑤ 删空集守卫 ⇒ **1 红**（`IN ()` 语法错 500）；⑥ 懒建 `is_default` 误写 0 ⇒ **1 红**
  （500，回查不到默认夹）。六轮还原后两根全绿。
  **已知缺口（交 T8）**：`moveItem` 的 `@Transactional` 在**删除步失败**时的回滚语义
  HTTP 侧造不出注入点（先插后删让插入失败先于删除）—— I-04 同款，靠 review 或 T8 的
  失败注入处置；测试类注释已写明。
  **未做（各有归属）**：`static/js/api.js` 未动（T7 统一接）；`/favorite/status|count` 归 T5、
  `/favorite/list` 归 T6；批量 add / 容量上限按反面清单不做。

### T5 收藏状态 + 收藏数（来源 R-06 / 能力 一期-5 / 一期-6）【已完成】

* **入口线索**：`GET /favorite/status`（含所在夹）+ `GET /favorite/count`。
  ⚠️ **前提可被质疑**：`status` 要不要返回"所在夹列表"？若执行期发现前端用不上，
  可简化为布尔 —— **但需求篇写的是"知道收在了哪些夹"，改动要先回来改需求篇**。
* **目标**：状态与计数两个读端点；计数**按人去重**（同一人进多夹只算 1）。
* **红线边界**：**不上缓存、不写 `favorite_count` 列**（R-06，二期才启用）；
  计数走 `SELECT COUNT(DISTINCT user_id) FROM favorite_item WHERE content_id=?`。
* **强制探索**：把这条 SQL 的**耗时基线记下来**（二期要拿它当优化前的对照）；
  同一内容进两夹时 `status` 的表达形态。
* **验收**：**独立 oracle 查 `COUNT(DISTINCT user_id)`** 与接口一致；同一人进两夹仍算 1；
  全移出后回 0。
* **回写**：★ 开工前与用户**讨论并拍板了取数形态**（本题的"前提可被质疑"）—— 用户问"收藏状态
  该独立端点、还是随内容详情一起查？"。结论 **一期走独立端点**；"详情页内联 `isFavorited`"
  （能把登录用户浏览内容页的收藏域请求从 2 降到 1，与详情页既有 `isLiked`/`isFollowed` 同形）
  **登记为 `CURRENT_NEEDS.md` R-10 待拍板**（用户 2026-10-09 决定"简单登记、真要排任务时再调查复核"）
  —— 不采纳理由：收益落在"请求数"而**一期判据 ③ 明写"不看 QPS"**，且它会把 content 域拉进本期。
  落地两条读端点：
  ① `GET /favorite/status`（**需登录**，`@RequiresLogin` + `@CurrentUserId`）→
  `data = {isFavorited, folders:[{id,name}]}`；★ `isFavorited` 由 `folders.isEmpty()` **推导**
  （收藏必有归属 ⇒ 已收藏 ⟺ 至少落在一个夹里，**结构性成立** ⇒ 无第二处事实源、无漂移余地），
  不另发 `EXISTS` 查询；`folders` 顺序复用"默认夹置顶 + 创建序"（与夹列表同一契约）；
  ② `GET /favorite/count`（**匿名可访问** —— 公开展示数，需求篇 §三；★ 与 `/like/content/count`
  的"需登录"**刻意不同**：那条是 TV `/like` 前缀保护惯性，收藏是新域无此包袱）。
  **两条端点都不 404**（内容不存在 / 从未被收藏 ⇒ "未收藏 / 0"，不给内容存在性开探测口）。
  新增 `model/vo/FavoriteStatusVO` + `model/vo/FavoriteFolderBriefVO`（brief **只给 id + name**，
  刻意不复用 `FavoriteFolderVO` —— 免得把 `itemCount`/`isPrivate` 噪声带进状态端点）；
  `FavoriteItemDao` 补 2 条 SQL（`findFoldersByUserAndContent` 命中 `idx_user_content` 两列等值；
  `countDistinctUserByContentId`）；两条读路径均为单 SELECT ⇒ **不加事务**。
  ✅ **强制探索①（耗时基线）★ 现场纠了一处认知错**：初始假设"无 `content_id` 前导索引 ⇒ 全表扫"
  被实测**推翻** —— 2 万行 / 目标内容 2000 个不同 user 下 `EXPLAIN` = `type=range`、
  `key=idx_user_content`、`Extra="Using where; Using index for group-by (scanning)"`（MySQL 8 的
  **loose index scan**，`DISTINCT` 列 `user_id` 恰是索引最左前缀；**不是** `INDEX_SKIP_SCAN`，那个官方不含 GROUP BY/DISTINCT）；`EXPLAIN ANALYZE` ≈17ms、
  20 次实测 **best≈14ms / avg≈19ms**。已就地更正 DAO/XML 注释并登记 **I-06**；数字记入分期篇 §3.2。
  ✅ **强制探索②（进两夹的表达形态）**：`folders` 列出两个夹、`isFavorited=true`、`count` 仍为 **1**
  —— 由 `同一人进两夹状态含两夹计数仍为一` 钉死（同时断言 DB 里是 2 条记录，故"去 DISTINCT"必红）。
  验证：`FavoriteStatusAndCountTests` **8 例** + `SecurityContractTests` 新增 1 例全绿；
  **回归基线 `run_tests.py` = 373 例全绿**（默认组，364 + 9）+ `--group resilience` **34 例全绿**。
  ★ **反向验证已实测（`temp-script/t5_injections.sh` + `t5_failing.py`；先 commit 再注入，逐条注入→跑→`git checkout` 还原）**：
  ① 计数 SQL 去掉 `DISTINCT`（`COUNT(DISTINCT user_id)` → `COUNT(*)`）⇒ **`同一人进两夹状态含两夹计数仍为一` 1 例红**；
  ② `findFoldersByUserAndContent` 去掉 `i.user_id = #{userId}` ⇒ **`两人收藏计数加一且状态互不影响` 1 例红**（别人的夹混进我的状态）；
  ③ 给 `/favorite/count` 误加 `@RequiresLogin` ⇒ `SecurityContractTests.favorite状态与计数鉴权` + 本类
  `状态需登录计数匿名可访问` / `参数缺失400与不存在内容不404` **共 3 例红**。三轮还原后两根全绿。
  拆 **4 个 commit**（T5-1 feat / T5-2 test / T5-3 docs / T5-4 审查修正）。
  ★ **收尾派了独立 subagent 审查（T5-4）**，查出**两处必改**（均为注释/文档级、不影响运行行为）并已修正：
  ① `FavoriteItemDao` / `FavoriteItemMapper.xml` 里残留半句"计数那条**用不上索引**"，与 I-06 的更正
  **同文件自相矛盾**（"就地更正不彻底"的典型 —— 只改了被点名那处）；② `FavoriteStatusVO` 把
  "已收藏 ⟺ 至少落在一个夹里"说成"**结构性成立、没有可漂移的余地**"—— **字段级**恒等成立（同源推导），
  但**语义级**前提（每条记录都指向存在的夹）**无外键兜底**、只靠 I-04 指出的零覆盖事务；
  孤儿行会让 `count` **计入**而 `status` **漏报**（已在 VO / JOIN 注释里限定作用域并写明该症状，
  I-04 已补记这是"用户可见的跨端分歧"、优先级上调）。另采纳两条措辞修正：`count` 的 `Long` 与
  `/like/content/count` 的 `Integer` 类型不同（原写"同形"）；`loose index scan` **不**写作 "skip scan"
  （MySQL 的 `INDEX_SKIP_SCAN` 官方不含 GROUP BY / DISTINCT）。审查另核：无假绿（键集两端都拦、
  计数走独立 oracle 复算）、无红线触碰（G7/G10 干净）、契约口径实现且覆盖到位。
  **未做（各有归属）**：`static/js/api.js` **未动**（新端点尚无前端消费方，T7 统一接）；
  "详情内联 `isFavorited`" 未做 ⇒ **R-10**（待拍板）。

### T6 夹内列表分页（含失效占位）（来源 R-03 / R-07 / 能力 一期-7）【已完成】

* **入口线索**：`GET /favorite/list?folderId=&page=&pageSize=`；失效判据 = `c.id IS NULL OR c.is_deleted != 0`。
  ⚠️ **本条已被 T6-5 残留提交（2026-10-09）取代**：现判据 = content 装载契约"取不到"
  （口径 R-11"看不了 ≈ 失效"）。**本任务正文中涉及旧机制（LEFT JOIN / SQL 判失效）的语句均属历史记录，
  勿再按它实现**；现行实现见段尾回写与 R-11。
* **目标**：收藏时间倒序分页；失效条目返回**脱敏占位**（无封面 / 标题位「内容已失效」/ **不给原标题与作者**）。
* **红线边界**：**LEFT JOIN content**（⚠️ 已被 T6-5 取代）；**分页按 `favorite_item` 行**（不是按"内容"）；
  `total` **包含**失效条目；一整页都是失效条目**也要返回满页**（G12）；
  ⚠️ **不要复用 `ProfileService` 的填充逻辑**（它是"跳过、total 不变"，会把分页搞出空洞）。
* **强制探索**：分页边界（不重不漏、顺序与全量一致）；占位 VO 保留哪些字段（至少要有 id / contentId 供移出）。
* **验收**：失效条目 VO **不含原标题 / 封面 / 作者**（直接断言字段）；`total == COUNT(收藏记录)`（含失效）；
  失效条目**能移出**（与 T4 联动）。
* **回写**：落地 `GET /favorite/list?folderId=&page=&pageSize=`（逐端点 `@RequiresLogin` +
  `@CurrentUserId`）。★ **参数名用 `pageSize`**，任务清单速记的 `size` **不采用** ——
  与 `PageResult.pageSize` 及全项目其余分页端点（`/start` / `/profile` / `/search` / `/feed` /
  `/comment/*`）一致，两个名字表达同一件事会让契约分裂；分期篇 §3.3 原写"（page/size）"
  已就地更正为 `page/pageSize`（免得与实现长期矛盾）。新增 `model/vo/FavoriteItemVO`
  （一身两职：Mapper 行类型 + API 载荷，**7 键**：`id / contentId / favoriteTime / invalid /
  title / coverUrl / authorName`；刻意不含 `type` / `authorId` —— 键集一旦扩大即契约，
  T7 若确需先回来改注释与键集断言）+ `FavoriteItemDao` 2 条 SQL（`countByFolderId` /
  `findPageByFolderId` 含 `LEFT JOIN content`）。
  ★ 执行期决断（G13）：**数据全部由一条 LEFT JOIN SQL 出**，不走
  `ContentService.loadContentVOs` —— 后者把"媒体损坏 / 未知类型"也按"装载不出来 ⇒ 跳过"处理，
  与 R-03「失效 = `is_deleted != 0`」不是同一批内容；混用会让"列表说失效"与
  `add`/`move` 照收（T4 的 `isContentExist` 带 `is_deleted = 0`）**自相矛盾**，且它依赖 content
  缓存 ⇒ 测试要清 Redis、直接改 DB 的 `is_deleted` 不会被缓存感知。代价是本域多持有一份
  封面选取规则副本（`content_media` type=3 首条）+ 自己用 `MediaProperties` 拼 URL 前缀
  ⇒ 已登记 **I-07**。⚠️ **以上决断与理由已被 T6-5 残留提交推翻**（用户拍板改走
  `loadContentVOs`，可接受的边缘态取舍见 R-11）——本段属历史记录，勿再按它实现。
  ★ 分层：**SQL 只判失效**（`CASE ... AS invalid`）、**Java 决定失效长什么样**
  （`maskIfInvalid`：标题 = 「内容已失效」、封面与作者置 null）—— 两件事不在一层做，
  反向验证两个注入点各自独立。**不复用 `ProfileService` 的"跳过 null、total 不变"口径**
  （`ProfileService.java:109`，那会让分页出空洞）。顺序 `create_time DESC, id DESC`：
  `create_time` 是秒精度，同秒并列只按它排会让页间顺序不确定（重漏）⇒ 补 `id DESC` 兜底。
  验证：`FavoriteItemListTests` **13 例**（审查后补了「分页越界与极大页码」一条）
  + `SecurityContractTests` 新增 1 例全绿；
  **回归基线 `run_tests.py` = 387 例全绿**（默认组，373 + 14）。
  ★ **反向验证已实测（`temp-script/t6_injections.sh` + `t6_injections2.sh`；先 commit 再注入，
  逐条注入→跑→`git checkout` 还原）**：
  ① 去掉 `maskIfInvalid` 的失效分支 ⇒ **2 红**（脱敏两条：原标题 / 封面 / 作者泄漏）；
  ② SQL 的 `invalid` 恒为 0 ⇒ **3 红**（判失效在 SQL 侧这一半也有判别力）；
  ③ `LEFT JOIN` → `INNER JOIN` ⇒ **1 红 + 1 错**（条目整行消失 ⇒ 分页空洞，正是 G12 防的）；
  ④ `c.is_deleted != 0` 写成 `= 1` ⇒ **2 红**（三态覆盖）；
  ⑤ `ORDER BY` 把 `i.create_time DESC` 换成 `i.id DESC` ⇒ **首跑 0 红 —— 假绿**（见下）⇒
     修夹具后 **2 红**；⑥ 去掉 `, i.id DESC` ⇒ **1 红**（同秒 tie-breaker 有判别力，
     实测 MySQL 并列时按索引序回行、恰与 `id DESC` 相反；⚠️ 属"实测成立"非"规范保证"）；
  ⑦ `countByFolderId` 加 `JOIN content ... is_deleted = 0` ⇒ **4 红**（total 变小）；
  ⑧ 去掉 `/favorite/list` 的 `@RequiresLogin` ⇒ `SecurityContractTests` **1 红**、而
  `FavoriteItemListTests` **13 例全绿** —— 又一次证实 T2 教训：HTTP 侧 401 由
  `@CurrentUserId` 参数解析器兜出，对 `@RequiresLogin` 机制是**假绿**。
  ⑨ **（T6-4 审查实测、已修）** 把 `jointMediaUrl` 改成 `"http://cdn.invalid/" + url` ⇒
  **修前 12 例全绿 / 修后 1 红** —— 原断言只断"封面非空白"，而 `base-url` 默认空串让这段
  代码一期是**恒等变换、零覆盖**；已改为钉住整个 URL 值（详见审查 M4）。
  ★ **⑤ 的教训（可迁移）**：断言"顺序"的用例必须让**排序键序与 id 序反向** ——
  首轮夹具按"先插最早收的"造数据 ⇒ id 序与收藏时序同向 ⇒ 实现改成按 id 排也照样绿。
  **夹具的插入顺序本身就是判别力的一部分，不是中性的实现细节**（已修：倒序用例倒着插、
  分页用例从新到旧插，两处互为对照）。另加 `requireItem()`：注入 INNER JOIN 时条目整行消失，
  原写法下游直接 NPE、失败信息里看不到"少了哪一条"。
  **未做（各有归属）**：`static/js/api.js` **未动**（新端点尚无前端消费方，T7 统一接）；
  他人夹内列表一期不做（R-08 只给名称 + 视频数，
  `PublicFavoriteFolderVO` 无 id ⇒ 打不开别人的夹，故 `/favorite/list` 恒为"我的"视角）。
  ★ **T6-5 残留提交（2026-10-09）：I-07 已根治** —— 口径按用户拍板扩为"**看不了 ≈ 失效**"
  （**R-11**；原"只认 is_deleted"降级为已接受的边缘态取舍）。`findPageByFolderId` 只查本域三列；
  展示字段与失效判定整体走 `ContentService.loadContentVOs`（批量 + content 缓存 + 失效链）；
  删掉封面规则副本与 `jointMediaUrl` 第二份实现（`MediaProperties` 依赖一并移除）；
  夹具补视频行使"正常内容"可装配，新增「装载不出来的内容也占位」钉子用例。
  实测：单类 **14 例全绿**、**全量 388 例全绿**（387 基线 + 1）。反向验证（逐条注入→确认变红→还原）：
  ① 不渲染占位（`content == null` 也当正常行）⇒ **4 红**（含新钉子与脱敏 / 满页 / total 三条）；
  ② 契约调用返回空 ⇒ **3 红**（正常卡片字段确实来自契约）；还原后全绿。提交 `5c89a22`。
  旧注入点已作废（机制不在本域）："LEFT JOIN 改 INNER JOIN" / "SQL invalid 恒 0" / "is_deleted 写成 = 1"。

### T7 前端点亮（来源 能力 一期-8）【已完成】

* **入口线索**：`src/main/resources/static/static/js/main.js:78`（`class="drawer-item disabled"`）
  + `static/js/router.js` 的导航机制 + `static/js/api.js`。
  ⚠️ **前提可被质疑**：现有前端是原生 JS 模块，是否有既有的"页面/弹窗"模式可套？
  先读 `router.js` 与 `api.js`，别另起一套。
* **目标**：点亮收藏导航；收藏弹窗（选夹）+ 收藏夹页（含他人公开夹入口）；失效卡片显示占位。
* **红线边界**：**同步 `static/js/api.js`**（项目纪律）；失效卡片**只有占位**——无封面、标题位「内容已失效」。
* **强制探索**：失效卡片拿不到封面时怎么渲染（本地占位图）。
* **回写**：
  ★ **开工前与用户拍板了"占位封面怎么来"**（本题的"前提可被质疑"）：**代码合成**（灰底 +
  内联 SVG 破图图标），**不往媒体资源仓库加素材**。理由：① 后端按 R-03 契约就返回
  `coverUrl=null` ⇒ **前端本就必须有这条分支**，加素材只换来"换画笔"、省不掉分支；
  ② 媒体根（`WebMvcConfig.addResourceHandlers` 的 `/upload/**`）是内容域**运行时数据**、不进 git，
  把 UI 占位图放进去 = 让前端依赖"每套环境手动拷文件"；③ 既有惯例就是合成
  （`utils.js` 的 `avatarColor`/`cover-fallback`，且 `static/` 下**零图片素材**）；
  ④ 三期要重做失效卡样式（`CURRENT_NEEDS §四`），代码画改一行就变。
  若三期真要设计稿插图，也应放 `static/`（随前端版本化），**不放 `/upload`**。
  落地五个文件：① `main.js` 摘 `disabled` + `href="#/favorite" data-nav` + 注册路由 + 高亮；
  ② 新建 `views/favorite.js` —— 夹列表（建 / 改名 / 私密开关 / 删，默认夹不渲染删除）
  + 夹内分页（接既有 `chunkedList`，`keyOf = 收藏记录 id`）+ 两种卡片（正常 /
  `invalid-card` 灰底 SVG 占位、**不可点开**、保留「移出」）+ 移出后整页刷新（夹计数同步）；
  ③ `views/detail.js` —— 「⭐ 收藏」按钮（`onclick` 赋值防监听叠加）+ 「收藏到」弹窗
  （勾选多夹 = add/remove，409 按幂等成功；弹窗内可新建夹并自动收进去）+ 统计行补 ⭐ 收藏数
  （`renderStats()` 收敛原本三处各写一份的统计行）；④ `views/user.js` —— 本人只给
  「我的收藏夹」入口、**他人只列公开夹**（R-08 无 id ⇒ 纯展示）；⑤ `api.js` —— 新增
  **收藏域端点清单注释**（红线"同步 api.js"的落点：**不新增封装层**，各视图沿用
  `request('路径')` 直调，与 like/follow/comment 同款）。
  **未做（各有归属）**：`move` 无前端消费方（T7 目标未含，弹窗多选用 add/remove 表达）；
  批量移出 UI 未做（端点支持，界面一条一条移出）。
  ★ **收尾后被用户实测打回一处（I-08，2026-10-10）**：首版弹窗**必选夹**（每行都带 `folderId`），
  而默认夹只能由 `add` 不传 `folderId` 懒建 ⇒ **互锁**，默认夹在前端永远建不出来
  （用户原话"找不到把视频收藏到默认夹的方式，DataGrip 查库也没有默认夹"）。
  这不是"未做/各有归属"——`收藏功能-需求.md:26` 明写「默认收藏夹……收藏时没选夹，内容自动进这里」＝**纳入**，
  属**T7 漏做**（原回写表述已更正）。已补：弹窗顶部加占位行「默认收藏夹 · 首次收藏时创建」
  （`id=null`），勾它即 `add` **不带 `folderId`** ⇒ 懒建落成后换回真实行。**不改懒建为注册建**
  （R-01 否决理由仍成立），登记见 `CURRENT_ISSUES.md` **I-08**。
  验证：★ **浏览器实测（三个 subagent 回合，全程真库真服务）** —— ① 抽屉「收藏」可点并跳
  `#/favorite`；② 失效占位卡实测 DOM：`v-card invalid-card` + `invalid-cover`，**`imgCount=0`**、
  灰底 `linear-gradient(135deg,#e9ebee,#dfe2e6)` + 内联 SVG、`up-name` 为空（**无作者**）、
  标题「内容已失效」、「移出」后卡片消失且夹计数 2→1；③ 详情页统计行 `⭐ 1`、按钮「⭐ 已收藏」；
  弹窗两夹**均勾选**（进多夹），新建夹 → toast「已创建并收藏」且新行勾选，取消/重勾 → toast
  「已取消收藏」/「已收藏」、⭐ 仍为 1（按人去重）；④ 未登录访问 `#/user/228` → 「公开收藏夹」
  只有 `公开夹A`/`临时夹C`，**私密夹A 不出现**；⑤ 收藏夹页 新建/改名/设为私密/设为公开/删除
  （原生 confirm 文案 + toast「已删除」）全通过，无控制台报错。
  后端契约侧另用真库跑通一轮（建夹 → 收藏 → 进两夹 → 夹内列表 → 移出 → 公开面过滤），并
  以"幽灵 contentId"（DB 直插 `favorite_item`）造出失效条目，确认 `invalid=true / title=内容已失效 /
  coverUrl=null / authorName=null` 与前端渲染一致。**验证数据已清理**（测试账号 228 及其
  3 夹 3 条记录全部删除，`favorite_*` 两表回到 0 行）。

### T8 契约测试的反向验证收口（来源 G5 / 能力 一期-9）【已完成】

* **入口线索**：`AGENTS.md` 纪律④「写证明某机制的用例前先自问：去掉那行实现会红吗」。
  ⚠️ **前提可被质疑**：如果某个契约**根本没有实现可去掉**（如"收藏不进 feed"），
  它就不是契约测试而是红线（G10）—— **这类测试要删，不要留**。
* **目标**：★ **不是把 T2~T6 的注入"再做一遍"** —— 那是重复劳动，且注入点由作者自选，
  天然偏向"我明知会红的分支"（**2026-10-10 与用户拍板**：旧注入有脚本 + 回写留痕、
  执行期有人在场，可信度足够；本项目为**个人项目**，不套大厂级全量复算流程 ⇒ **不做抽样重跑**）。
  收口只做三件事：
  ① **补齐从未被验证的用例**：T2~T6 的注入只覆盖了各任务自认的"关键分支"，
  六个测试类里约**一半用例从未被任何注入命中**（删夹 / 改名的错误线、移出幂等、移动终态、
  公开端点键集与空态、分页参数归一……）—— 这些是"**从没人验过**"，不是"验过但没记"；
  ② **结清两笔机制欠账**（见强制探索①②）；
  ③ **执行红线**：不变红的用例删 / 改写；HTTP 层注定造不出注入点的机制
  ⇒ **明确标注"靠 review 不靠测试"**（同 G10 口径），**不留假绿**。
* **红线边界**：★ **注入后不变红的测试一律删掉** —— 假绿比没有测试更坏。
* **强制探索**：
  ★ **审查方式 = 只读审查派独立 subagent**（沿用 T2~T6 收尾审查的做法 —— 隔离上下文、
  避开作者自证偏见），产出「**疑似零判别力用例**」清单（文件:行 + 断言内容 + 怀疑理由 + 置信度）；
  **主会话只对清单里 flagged 的用例 + 下述已知欠账做注入复核**，不重跑已记录的条。
  ① **`deleteFolder` 的事务边界**（`CURRENT_ISSUES.md` **I-04**）—— R-02「删夹一并删条目」的
  "同进同退"目前**零判别力**（两条顺序 DELETE 都成功 ⇒ 去掉 `@Transactional` 仍绿，
  已核：`FavoriteFolderCrudTests.删除夹一并删除夹内条目` 只断言两次写都成功的终态）；
  处置 = **失败注入**（临时让第二条 DELETE 抛异常 ⇒ 断言第一条被回滚）；造不出就标注"靠 review"。
  ② **`moveItem` 删除步失败时的回滚**（T4 审查遗留）—— 先插后删让**插入失败先于删除**，
  HTTP 侧造不出注入点 ⇒ 同 I-04 处置（失败注入 or 标注"靠 review"）。
  ③ **（T4 审查遗留）`moveItem` 对"内容不在源夹"的请求**：现为"目标夹插入 + 200、源夹 0 行删除"
  （变相 add）—— 需求篇/设计篇均未约定此情形，非契约偏差；收口时**补一条用例**把现行为**钉死**
  （勿为此加"源夹存在性预查"，那属无主校验；若要改成 404 须先回需求篇拍板）。
  ④ ⚠️ **不要拿 `COUNT(DISTINCT content_id)` 当夹内计数的注入点** —— 同一夹内同一内容至多一条
  （唯一键 `uk_folder_content`）⇒ 它与 `COUNT(*)` **恒等**，注入后不变红，会得出"测试没判别力"的**假结论**；
  该注入点应换成 **`COUNT(DISTINCT user_id)`**（夹具里 3 条记录同属一人 ⇒ 3 → 1 才红）。
* **验收**：只读审查交出 flagged 清单（含"无异常"结论）；逐条记录「注入 X → 变红 → 还原 → 绿」；
  通不过的写明**删除理由**或 **"靠 review" 标注**。
* **回写**：★ **本轮不重跑 T2~T6 的旧注入**（2026-10-10 与用户拍板：旧注入有脚本 + 回写留痕、
  执行期有人在场，可信度足够；个人项目不上大厂级全量复算）—— 收口只做"补齐从未验证的用例 + 结清机制欠账"。
  ① **只读审查派独立 subagent**（隔上下文、避作者自证偏见），产出 **6 条**"疑似零判别力"；
  **主会话逐条注入复核**，**2 条假警报**：★ `findFoldersByUserAndContent` 去 `content_id`（F2）实测
  **1 例红**（靠"同夹多条 ⇒ 夹重复"副作用），subagent 判"仍绿"**是错的**（不复核就会盲改）；
  `未收藏时状态为空` 的负向断言已被同域 `状态形状契约`（`propertyNames`）兜住、非缺口；
  `移出幂等` 无实现可去掉、但能挡 G11 禁止的"加内容存在性预检" ⇒ 两条均**保留**
  （红线"不变红即删"只适用于**确证**的假绿）。
  ② **三处真缺口（夹具只造单夹/单内容 ⇒ 过滤条件零判别力）已修**：
  F1 = `countDistinctUserByContentId` 去 `content_id`、F3 = `countByFolderId`/`findPageByFolderId` 去
  `folder_id` —— 各注入**实测首轮全绿**（坐实缺口），补对照后**变红**：F1 靠 `两人收藏` 新增
  "另一个内容被另一人收藏"的对照、F3 靠 `total包含失效条目` 新增"干扰夹"；F2 同款加固
  （`status(c, content)` 改为**语义级**打断言：丙收的是别的内容 ⇒ 不得混进 content 的状态）。
  ③ **两笔机制欠账终结（I-04 + T4 遗留），"靠 review"被替换为永久判别力**：
  新增 `FavoriteTransactionTests`，用 `@MockitoSpyBean` 把事务**最后一步**（`deleteFolder` 的**删夹行** /
  `moveItem` 的**删源夹**）打桩抛错 ⇒ 断言前一步已写入的写被回滚。★ 打桩点必须在**最后一步**
  （首步失败时"什么都没写"在有/无事务两个世界都成立、无鉴别力；照 `AdminTransactionTests` 范式）。
  实测：**去掉两处 `@Transactional` ⇒ 该类 2 例红**。
  ④ **补一条钉子**：`moveItem` 对"源夹里没有该内容"的**现行为**（变相 add + 200）——原 T8 强制探索③ 遗留，
  现以 `FavoriteItemWriteTests.移动源夹无该内容_现行为钉子` 钉死（勿为此加"源夹存在性预查"）。
  验证：收藏域 **63 例全绿**（60 + 1 钉子 + 2 事务）；**全量 `run_tests.py` = 391 例全绿**（388 基线 + 3）。
  注入脚本三份：`temp-script/t8_injections.sh`（首轮"仍绿"= 坐实缺口）、`t8_isolate_f1f2.sh`（隔离定位）、
  `t8_injections_round2.sh`（修复后"变红"）。
  ⑤ **有意不动**：`未收藏时` / `移出幂等` 两条保留（见①）；"夹具补对照"不铺满全库 ——
  收口判据取"**每条机制至少一条有判别力的用例**"，不是"每条用例都注入一遍"。

---

## 结转与留池（周期收尾时核销）

> 未纳入本张清单的已登记项，**逐行显式处理**：进下一张清单 / 登 `CURRENT_NEEDS.md` 留池 / 转 `FURTHER_ISSUES.md` / 废弃。
> **不允许静默跳过**。

| 来源 | 事项 | 去向（决定人 + 日期） |
|------|------|---------------------|
| R-04 | 已失效内容的媒体仍可直连下载 | 转 `FURTHER_ISSUES.md` **F-10**（用户 2026-10-08 拍板：一期不做） |
| 分期篇 §一 | 二期（缓存 + 冗余计数）/ 三期（周边） | 留池待评审，一期收尾时复核 |
