# 当期问题（Current Issues）

> **定位**：执行过程中**当场发现、且本期或下期要处理**的问题 —— 阻塞、偏差、待裁决、踩到的坑。
> **与 `FURTHER_ISSUES.md` 的分工**：**本期/下期解决** → 本文件；**暂时不会排期解决**（结转 / 留池）→ `FURTHER_ISSUES.md`。
> **边界**：需求与痛点 → `CURRENT_NEEDS.md`；任务执行态 → `CURRENT_TASKS.md`。
>
> ★ **膨胀控制（2026-10-11 起执行）**：处理完即标状态；**期收尾时把正文清出**（归宿 = git commit），本表只留一行摘要。
> 清出前**必须先把编号收进** [收藏功能-分期与设计.md](收藏功能-分期与设计.md) **附录「编号索引」**
> （代码 / 测试注释里有 `I-##` 引用）。**编号不回收**：跨期未决的结转时**保留原编号**并在 FURTHER 中标注来源。

---

## 登记格式

问题 + 现象 + 证据（可复核）+ 影响 + 处置 + 去向。编号 `I-##` 顺延（**不回收**）；从别处结转进来的保留原编号。

**分类取值**：阻塞 / 偏差（与计划不符）/ 缺陷 / 环境 / 决策 / 债务。

---

## 问题登记（I-##）

> **状态取值**：待处理 / 已纳入NEEDS / 已解决 / 待裁决（须停下问用户）/ 已转 `F-##`。

| 编号 | 发现于（任务 / 日期） | 分类 | 现象 | 证据 | 影响 | 处置 | 状态 |
|------|---------------------|------|------|------|------|------|------|
| I-09 | T10 探索期 / 2026-10-12 | 债务 | R-10 判据「content 域不再 import like.service / follow.service」**只兑现一半**：T10 后互动态填充路径（`ContentStatusFiller` / `ContentService` / `StartController`）已改走互动域，但 `ProfileService` 仍**内联点赞批量**（直连 `like.service.batchIsContentLiked`；另有主页维度的 `follow.service` 用法：主页关注态 + 关注/粉丝计数） | `grep "import.*videoweb\.(like\|follow)\.service" src/main/java/.../content/` ⇒ 仅剩 `ProfileService.java:10-11`（T10 交付后复算）；`ProfileService.java:114-127` 第 ⑤ 段 | content 域仍持有一条"我是否点过赞"的直接依赖（profile 路径）；R-10 的"content 域依赖 3 → 1"在 profile 路径上不成立 | **保留现状**（用户 2026-10-12 拍板"选择不改"）：改它要给 `/profile` 每页 **+2 趟批量读**（互动域只提供"三维一起答"的批量契约，无"单维"子契约；两趟结果在 profile 路径被丢弃）。**后续考虑**：① 互动域加"维度可选"的批量重载；② 或更优解（避免为低频路径扩大 T9 刚钉死的契约面）。⚠️ `follow.service` 的 profile 维度用法**不是**本议题：那是 `模块边界与依赖.md` §四 已登记的合法契约 | 待处理 |
| I-10 | T10 执行期（首跑测试）/ 2026-10-12 | 决策 | ★ **R-10 的"无环"只成立于直连边**：`content → interaction`（T10 新增）、`interaction → favorite`（T9）、`favorite → content`（T6-5 的 `loadContentVOs` 装载契约）在**传递闭包**上闭成环，并在 **Bean 装配期**表现为 `BeanCurrentlyInCreationException`（应用根本起不来） | 实测：`run_tests.py --test ContentReadTests` ⇒ 25 例 error，根因链 `contentService → interactionService → favoriteService → contentService`（日志 `target/test-reports/run-20261012_005149.log`）；`FavoriteService.java:544` 是 `favorite → content` 的唯一调用点 | 不修则应用无法启动；R-10 文档的"无环"表述在传递闭包读法下会失真 | **已解决（T10 内）**：在 `InteractionService` 的 `favoriteService` 注入点加 `@Lazy` **单点断环**（三条边全是运行期读依赖、装配期不需要对方实体；行为不变，只多一次代理跳转；删注解 = 启动期响亮失败）。**备选（留待将来）**：把 `loadContentVOs` 抽成窄装载组件，消掉 `favorite → content` 回指边。⚠️ 语义澄清已补进 `收藏功能-分期与设计.md` §3.6（"直连边 vs 传递闭包"） | 已解决 |

> 📌 **I-01 ~ I-08 的正文已于一期收尾清出**（2026-10-11，历史在 git）：一行摘要见下「已解决（本期内）」，
> 详细结论与理由见 [设计篇](收藏功能-分期与设计.md) **附录「编号索引」**。

---

## 待裁决（G3：须停下问用户）

> 按 [CURRENT_TASKS.md](CURRENT_TASKS.md) 的运行约定 G3：**需要用户拍板的，登记在此并停下问**，
> 不擅自决定、不硬扛、不超范围。

| 编号 | 事项 | 为什么必须问 | 问于（日期） | 结论 |
|------|------|-------------|-------------|------|
| 待登记 | | | | |

---

## 已解决（收藏一期，2026-10-08 → 2026-10-11）

> 本期内已解决的可留一行摘要（细节走 git + 设计篇附录），收尾时统一清出。

| 编号 | 一句话结论 | 解决于（任务 / 日期） |
|------|-----------|---------------------|
| I-01 | 分期篇 §3.1 骨架里**没有任何唯一键**能表达"每用户至多一个默认夹"（`UNIQUE(user_id,is_default)` 会连坐自建夹）⇒ T1 落地 `default_uniq` **VIRTUAL 生成列** + `uk_user_default` | T1 / 2026-10-08 |
| I-02 | 结构锚点只断言 `flyway_schema_history` **首行** ⇒ "新增迁移没被应用"**察觉不到**（假绿）；改为断言**全部版本行** + 表计数 18 | T1 / 2026-10-08 |
| I-03 | 测试助手 `Envelope.str(resp,field)` 走 `data.<field>` ⇒ 对**字符串型 data**（写端点的中文文案）**恒返回 null**；改用 `Envelope.data(resp).asString()`（本期不改 `Envelope` 本体） | T2 / 2026-10-08 |
| I-04 | `FavoriteService.deleteFolder` 的 `@Transactional` **零判别力**（两条 DELETE 都成功 ⇒ 去掉注解仍绿），后果是静默孤儿行、且被 T5 两条读端点暴露成**跨端不一致**；T8 用 `@MockitoSpyBean` 打桩"最后一步"获得**永久判别力** | T8 / 2026-10-10 |
| I-05 | `git checkout --` 式注入脚本会**连未提交的改动一起还原**（T3 首跑实测丢过 3 个文件的改动）⇒ 纪律：**先 commit 再注入** | T3 / 2026-10-08 |
| I-06 | 性能基线假设"无 `content_id` 前导索引 ⇒ 全表扫"被**实测推翻**（实为 **loose index scan**，2 万行 / 2000 人 ≈ 17ms）；教训：写基线前先实测 `EXPLAIN`，且"就地更正"要扫同主题**全部**出现处 | T5 / 2026-10-09 |
| I-07 | 夹内列表曾持**一份 content 域封面规则副本 + URL 前缀第二份实现**（且 URL 前缀一期是**恒等变换、零覆盖**），并首次跨域直连他域表；T6-5 改走 `ContentService.loadContentVOs` **整体根治** | T6-5 / 2026-10-09 |
| I-08 | 前端弹窗"**必选夹**" × 默认夹**只能懒建** ⇒ 两者**互锁**，默认夹在前端永远建不出来（需求篇「纳入」项零可达）；补"默认收藏夹 · 首次收藏时创建"占位行（`id=null`、`add` 不带 `folderId`） | T7 / 2026-10-10 |
