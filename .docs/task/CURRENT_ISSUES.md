# 当期问题（Current Issues）

> **定位**：执行过程中**当场发现、且本期或下期要处理**的问题 —— 阻塞、偏差、待裁决、踩到的坑。
> **与 `FURTHER_ISSUES.md` 的分工**：**本期/下期解决** → 本文件；**暂时不会排期解决**（结转 / 留池）→ `FURTHER_ISSUES.md`。
> **边界**：需求与痛点 → `CURRENT_NEEDS.md`；任务执行态 → `CURRENT_TASKS.md`；
> **膨胀控制**：处理完即标状态（已解决 / 已转 `F-##`）；跨期未决结转时**保留原编号**，并在 FURTHER 中标注来源。

---

## 登记格式

问题 + 现象 + 证据（可复核）+ 影响 + 处置 + 去向。编号 `I-##` 顺延；从别处结转进来的保留原编号。

**分类取值**：阻塞 / 偏差（与计划不符）/ 缺陷 / 环境 / 决策 / 债务。

---

## 问题登记（I-##）

> **状态取值**：待处理 / 已纳入NEEDS / 已解决 / 待裁决（须停下问用户）/ 已转 `F-##`。

| 编号 | 发现于（任务 / 日期） | 分类 | 现象 | 证据（文件:行 / 命令 / 日志） | 影响 | 处置 | 状态 |
|------|---------------------|------|------|------------------------------|------|------|------|
| I-01 | T1 / 2026-10-08 | 偏差 | 计划要求"默认夹懒建的并发重复**必须靠唯一键兜住**"，但分期篇 §3.1 给的 `favorite_folder` 骨架里**没有任何唯一键**能表达"每用户至多一个默认夹"（`UNIQUE (user_id, is_default)` 会连坐自建夹：它们都是 `is_default = 0`，等于限制用户只能有一条自建夹） | ① `收藏功能-分期与设计.md` §3.1（V1 版骨架：唯一键只给了 `favorite_item.uk_folder_content`）；② `CURRENT_TASKS.md` T2 红线原话"并发重复建夹必须靠唯一键兜住"；③ 缺口是**执行期**才看出来的：写 V2 时才发现"`is_default` 是布尔，唯一键无处可挂" | 若留到 T2：schema 级东西后补要走 **V3 + 改活库**，正是分期篇 §一「错位 2」要避免的；且 T2 会被迫退回"先 SELECT 后 INSERT"（并发下两个事务都能查到"不存在"） | T1 建表时落地 `default_uniq`（VIRTUAL 生成列）+ `uk_user_default`，理由与备选见 `CURRENT_NEEDS.md` **R-09**；分期篇 §3.1 已回填；判别力由 `FavoriteSchemaTests.每用户至多一个默认夹` 反向验证（把唯一键降级为普通索引 ⇒ 该用例变红） | 已解决 |
| I-02 | T1 / 2026-10-08 | 债务 | 结构锚点 `InfrastructureConnectivityTests.Flyway_在空库上跑V1建出全部15张业务表` 对 `flyway_schema_history` **只断言首行**（`rs.next()` 读一次即断 `version=1`），故"新增的迁移没被应用"**察觉不到** —— 锚点对"加迁移"这个最常见的演进动作没有判别力 | 迁移前 `InfrastructureConnectivityTests.java:70-74`（`assertThat(rs.next()).isTrue(); … isEqualTo("1")`）；表计数写死 `16` 同理 | 假绿风险：V2 未落地时该锚点仍全绿（"结构连续性锚点"名不副实） | 改为读**全部行**后 `containsExactly("1","2")`、表计数 `18`；判别力用"把 V2 藏起来"反向验证（见 T1 回写） | 已解决 |
| I-03 | T2 / 2026-10-08 | 债务 | 测试助手 `support/Envelope.str(resp, field)` 走的是 **`data.<field>`** 路径 ⇒ 对 **`data` 本身是纯字符串**的端点（如 `/favorite/folder/add` 的 `"创建成功"`）**恒返回 `null`** —— 断言会把"取值路径用错"误读成"接口没返回内容" | `support/Envelope.java:30-36`（`parse(response).path("data").path(field)`；其 `data()` 方法的注释已写明"对纯字符串 data 取不到值"）。T2 实测：`assertThat(Envelope.str(resp,"data")).isEqualTo("创建成功")` ⇒ `expected: "创建成功" but was: null`，同一响应改用 `Envelope.data(resp).asString()` 即通过 | 写"读字符串型 data"的断言时**会先怀疑实现**（浪费时间），且若随手把断言改成 `isNull()` 就是一条**假绿** | 本期**不改** `Envelope`（改它会动到既有 8 个域的用例，超出 T2 范围）；在 `FavoriteFolderCrudTests` 就地注明"字符串型 data 用 `Envelope.data(resp).asString()`"。⚠️ **T4/T5 的写端点同样是字符串 data，照此办** | 已解决 |
| I-04 | T2 / 2026-10-08（**审查发现**） | 债务 | ★ **`FavoriteService.deleteFolder` 的 `@Transactional`（R-02「删夹一并删条目」的同进同退）没有任何用例能判别它**：两条顺序 DELETE 都会成功 ⇒ 去掉注解、锁照样绿 | ① `FavoriteService.deleteFolder`（注解位置本身**正确**：public 入口 + controller 跨 bean 调用，代理生效）；② `FavoriteFolderCrudTests.删除夹一并删除夹内条目` 只断言两次写都生效的**终态**；③ T8 的「强制探索」清单（`CURRENT_TASKS.md`）未列事务边界 | R-02 是**一期红线级口径**，而"回滚语义"目前零覆盖 —— 一旦有人把 `@Transactional` 删掉（例如"看起来只有一条写"的误重构），症状是**静默留孤儿记录**（夹没了、条目还在 ⇒ 用户看不见但收藏数把它算进去）。★ **T5 补充（2026-10-09）**：这些孤儿会被 T5 的两条读端点暴露成**跨端口径不一致** —— `/favorite/count`（不 JOIN 夹）**计入**孤儿、`/favorite/status`（INNER JOIN 夹）**漏报**为"未收藏"，是**用户可见**的分歧（同一用户同一内容：数说"有人收"、状态说"没收藏"）⇒ 本条不再是纯"静默"债务，优先级应上调 | 已登记给 **T8**：要么补"失败注入"（临时让第二条写抛异常 ⇒ 断言第一条写被回滚），要么明确标注"该机制靠 review 不靠测试"（同 G10 的处置）。⚠️ 已在测试类注释里写明本类覆盖不到它；**T5-4 审查**另建议根治路径：给 `favorite_item.folder_id` 加外键（与 V1 无外键的现状冲突，须先表态） | 待处理 |
| I-05 | T3 / 2026-10-08 | 缺陷 | **`git checkout --` 式注入脚本会连"未提交的改动"一起还原**：T2 的注入模式（注入→跑用例→`git checkout -- <file>` 还原）隐含前提是"被注入文件已在 git 提交里"。T3 首跑时实现尚未提交，四条注入的"还原"把 3 个文件（`FavoriteController` / `FavoriteService` / `FavoriteFolderMapper.xml`）的 T3 改动**整体抹掉**（无备份） | `temp-script/t2_injections.sh:18,24,30,36`（4 处 `git checkout --`）；T3 实测症状：注入 3 起编译失败（`FavoriteService.updateFolder` 被还原消失）；`git status` 只剩部分文件被改，**看起来像"注入只动了这几个"** | 反向验证是本期/T8 的高频动作；照此模式对未提交代码做注入 ⇒ 静默丢改动，且丢的是"最后一次 Edit 的全部内容"（未进任何 commit） | 处置：**先 commit 再注入**（T3 改为提交 T3-1 / T3-2 后重跑，四条注入全部按预期变红、还原后全绿）；已写进 `CURRENT_TASKS.md` T3 回写给 T8 前车之鉴；脚本本身在 `.git/info/exclude`（不入库，无需改） | 已解决 |
| I-06 | T5 / 2026-10-09（**T5-4 独立审查复核**） | 偏差 | ★ **性能基线的初始假设被实测推翻**：T5 落笔时按"表上没有 `content_id` 前导索引 ⇒ 收藏数查询走**全表扫**（`EXPLAIN` 的 `type=ALL`）"的推断写了注释，但实测**不是**全表扫 | 一次性探针（已删）在测试容器造 2 万行、目标内容 2000 个不同 user ⇒ `EXPLAIN` = `type=range`、`key=idx_user_content`、`key_len=16`、`Extra="Using where; Using index for group-by (scanning)"`；`EXPLAIN ANALYZE` 实际 ≈17ms，20 次实测 best≈14ms / avg≈19ms | 若把"全表扫"当事实写进注释/文档，**二期**会据此作错误判断（例如以为"加 `content_id` 前导索引必然大幅提速"），而真实执行路径是 **loose index scan**（`DISTINCT` 列 `user_id` 恰是索引最左前缀；**不是** `INDEX_SKIP_SCAN` —— 那个官方明确不含 GROUP BY / DISTINCT）—— 瓶颈在跳扫次数而非"扫了全表" | **已就地更正**：`FavoriteItemDao` / `FavoriteItemMapper.xml` 注释改为"实测：loose index scan，**非**全表扫"，并把精确 EXPLAIN 与耗时记入 `收藏功能-分期与设计.md` §3.2 与 T5 回写。⚠️ **首改不彻底**：同文件 `findFolders` 注释里残留半句"计数那条用不上索引"（与更正结论**同文件自相矛盾**）—— 由 **T5-4 独立审查**抓出并补正（见 T5 回写与审查记录）。★ 教训（可迁移）：① 写性能基线前先**实测** `EXPLAIN`，别按索引形状推断访问计划；② **"就地更正"要扫同文件同主题的全部出现处**，只改被点名那一处会留下自相矛盾的半句话 | 已解决 |

---

## 待裁决（G3：须停下问用户）

> 按 [CURRENT_TASKS.md](CURRENT_TASKS.md) 的运行约定 G3：**需要用户拍板的，登记在此并停下问**，
> 不擅自决定、不硬扛、不超范围。

| 编号 | 事项 | 为什么必须问 | 问于（日期） | 结论 |
|------|------|-------------|-------------|------|
| 待登记 | | | | |

---

## 已解决（本期内）

> 本期内已解决的可留一行摘要（细节走 git），收尾时统一清出。

| 编号 | 一句话结论 | 解决于（任务 / 日期） |
|------|-----------|---------------------|
| 待登记 | | |

