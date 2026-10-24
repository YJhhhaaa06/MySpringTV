# .docs 文档索引（**唯一入口导航**）

> **开始任何任务前先读本文件**，再按"何时读"挑文档；不要一上来通读全部。
> 关联入口：仓库根 `AGENTS.md` 只指向本文件。
>
> **文档可能有滞后性，以实际代码为准。** 防滞后机制是**可复算**（§四）：凡是"还剩多少 / 欠什么 /
> 为什么这么定"这类结论，都必须能由一条命令或一处代码验证。
>
> ⚠️ **`archive/` 下全部是冻结历史**（按归档日冻结、只供追溯），**非必要勿读** ——
> 里面的结论不会跟着代码走，读到过时的论调比不读更坏。

---

## 一、这个仓是什么（30 秒）

类 bilibili 的视频站后端。技术栈：Spring Boot 4.1 / Java 25 + MyBatis + Jedis + Spring AMQP +
Flyway + Testcontainers。由 `old-project/TVhomework1`（Servlet + 手写连接池/事务/缓存/MQ + 自研 IoC）
迁移而来，**迁移已收口**（老端点 43 / 43 / 0）。

| 现在该知道什么 | 值 |
|---|---|
| **写代码读哪里** | `architecture/` 是**唯一活的事实源**（域设计 + 横切机制），主索引 `architecture/ARCHITECTURE.md` |
| **本期做什么** | `task/CURRENT_NEEDS.md`（需求 / 决策源）+ `task/CURRENT_TASKS.md`（任务）+ `task/CURRENT_ISSUES.md`（本期问题）+ `task/FURTHER_ISSUES.md`（结转池） |
| **已收口的专项** | 收藏（2026-10-15 收口）、限流（2026-10-17 收口）—— 结论已沉淀进 `architecture/`，过程文档留着只作追溯 |
| **回归基线** | `python tools/run_tests.py`（默认排除 `resilience`）＋ `--group resilience`；**最近一次 = 默认集 806 + resilience 41 = 847 例全绿**（2026-10-24，`T60-3` 后复跑）。数字会漂，以最近一次复跑为准。⚠️ 跑全量须设 `DB_POOL_MAX_SIZE=4` |
| **环境** | dev 库 `spring_tv`@3306、媒体根 `D:/data/projects/MySpringTV/media` |
| **还欠什么** | `task/FURTHER_ISSUES.md`（`F-##` 结转池）+ 各域 `*-已知代价.md`（**已拍板接受、不修**的账） |

> ⚠️ **三条必须知道的边界**：
> ① **前端**在 `src/main/resources/static/`（18 文件）。后续动 HTTP 层，**必须同步 `static/js/api.js`**。
> ② 鉴权**不迁 Spring Security**（2026-10-18 拍板，F-01/F-02 已核销）：自研 `JwtAuthFilter` + **默认拒绝**
> （`@Public` 是唯一放行口）+ 处理器级角色判定（`@RequiresAdmin`）—— `SecurityPassthroughConfig` 是**终态**，
> 不是临时态。口径 = `architecture/tech/security/安全与鉴权.md`，代价 = 同目录 `安全与鉴权-已知代价.md`。
> ③ ★ **`old-project/TVhomework1` 只读留存、禁止删除**（它是行为判据的原始规格书）；
> **V1 baseline 冻结**（改了会让测试库与活库分叉），增量演进已到 **V8**（V6 `watch_history` / V7 删遗留表 / V8 换列表索引），下一次走 **V9**。

---

## 二、目录结构

```
.docs/
├── INDEX.md              ← 本文件（唯一入口）
├── architecture/         ← ★ **活的事实源**（域设计 + 横切机制）—— 功能"沉淀"的落点；主索引 = `architecture/ARCHITECTURE.md`
│   ├── domain/           ← 业务域（**按域分目录**）：`favorite/` · `feed/`（每域 = `<域>.md` 设计 + 可选 `<域>-需求.md` / `<域>-已知代价.md` / `<域>-术语表.md`）
│   ├── tech/             ← 横切机制（**按面分目录**）：`security/` · `rate-limit/` · `idempotency/` · `observability/` · `cache/` ·
│   │                        `resilience/` · `mq/` · `events/` · `transaction/` · `module-boundary/` · `data-evolution/` · `api/`
│   └── 已知代价-模板.md   ← 代价台账**通用模板**（新建域 / 横切面复制即用；登记纪律与标签定义内嵌）
├── task/                 ← **执行登记台**（随期滚动）：`CURRENT_{TASKS,NEEDS,ISSUES}.md` + `FURTHER_ISSUES.md` + 两份模板
│                            ⚠️ 完成即清出（归宿 = git commit），文件**复用不改名**；**编号（T# / R-## / I-## / F-## / G#）不回收**
├── temp/                 ← 草稿（gitignore）
└── archive/              ← **冻结历史**（按归档日，只读追溯，**非必要勿读**）：收藏 / 限流 / 老项目迁移 三批
                            ⚠️ 其中**从老项目迁移而来时使用的文档，已严重滞后，非必要勿读**
```

---

## 三、按"我要做什么"选文档

| 我要… | 读这个 | 何时更新 |
|------|--------|---------|
| ★ **写新代码 / 了解架构** | **`architecture/ARCHITECTURE.md`**（模块地图 + "写代码前读哪篇" + 写作规范）→ 横切机制进 `architecture/tech/` 各篇、业务域进 `architecture/domain/` | 架构变更时 |
| ★ **执行中登记（任务 / 需求 / 问题）** | `task/CURRENT_NEEDS.md`（当期需求，决策源 `N#` / `R-##`）+ `CURRENT_TASKS.md`（四要素 + 回写）+ `CURRENT_ISSUES.md`（**本期**问题）+ `FURTHER_ISSUES.md`（结转池） | 每窗开 / 收时 |
| ★ **查某个编号是什么**（`T#` / `R-##` / `I-##` / `G#` / `F-##`） | **活速查**（每号**只许一行结论**）：`favorite.md` / `coin.md` / **`history.md`** 各篇 **附录「编号速查」**；限流的在 `architecture/tech/rate-limit/限流.md` 附录。★ **按专项找**，别在一个附录里找别的域的号 | 每期收尾把已终结的编号收编进速查 |
| ★ **加新功能 / 看设计** | `architecture/domain/favorite/`（`favorite.md` 设计 + `favorite-需求.md` 用户可见规则 + `favorite-已知代价.md` 台账）、`feed/feed.md`（域文档样板）、`history/history.md`（播放记录：写读路径 + 时长 + 编号速查）。本期做哪些 ⇒ `task/CURRENT_NEEDS.md` | 开工前登记 / 设计变更时 |
| ★ **改端点 / 加端点**（路径、HTTP 方法、状态码、错误码、入参出参形状） | [接口契约](architecture/tech/api/接口契约.md)（状态码分层 + 码表 + 路径/方法判据 + 载荷规范；`R-21` 起为唯一活事实源）；护栏与产物（端点登记簿 / `openapi.yaml` 的生成与更新纪律）→ [接口契约-护栏与Spec](architecture/tech/api/接口契约-护栏与Spec.md) | 端点或契约变更时 |
| ★ **让写端点"重试不重复"**（幂等键 / 响应重放 / 新增 `Idempotency-Key`） | [写接口幂等](architecture/tech/idempotency/写接口幂等.md)（设计 + 为什么不做 X）；用例与对外契约 → [写接口幂等-测试与契约](architecture/tech/idempotency/写接口幂等-测试与契约.md) | 幂等覆盖范围、状态机或 TTL 变更时 |
| ★ **某个词到底指什么**（页面名 / 会话 / 标签页这类指代） | 试点 = [history-术语表](architecture/domain/history/history-术语表.md)（页面↔端点矩阵 + 一物一名 + 错→对）。★ **只此一域**，推广判据见该篇 §五 | 该域发生新的术语歧义时 |
| **知道下一步做什么** | `task/CURRENT_NEEDS.md` §三 / §四 + `task/CURRENT_ISSUES.md` + `task/FURTHER_ISSUES.md` | 排期 / 收尾时 |
| **知道当初为什么这么定** | 结论在 `architecture/**`（域文档的「编号速查」+ `*-已知代价.md` 的四要素）；过程与逐条论证只留 git 历史（归档**非必要勿读**） | 新决策进活文档 |
| **改 schema / 加迁移** | `architecture/tech/data-evolution/数据与Schema演进.md`（V1 冻结、当前 **V8**、下次 **V9**、§2.4 冻结纪律） | 每次演进 |
| **处理事务边界** | `architecture/tech/transaction/事务边界.md`（写新事务开工前必须表态） | 事务边界变化时 |
| **动测试** | **仓库根 `AGENTS.md`「测试纪律」节**（防"测试迎合"四条 + 实测补充，**唯一事实源**） | 测试体系变更 |
| **写脚本** | `tools/README.md`（Python 硬性约定：UTF-8 / 路径 / 默认只预演）+ 分目录 `tools/docs/README.md`（文档门禁）/ `tools/devclean/README.md`（开发期清理） | 加脚本时 |
| **查老项目某个行为** | 直接 grep `old-project/TVhomework1`（**只读行为规格书，禁止写入、禁止删除**） | — |

---

## 四、可复算性（本仓的防滞后机制）

**任何"状态 / 计数"结论都必须能一条命令复算**。日常维护就这几条：

```bash
python tools/docs/doc_stats.py     # 文档体量（防膨胀；阈值 单文件 40 KB / 单章节 300 行）
python tools/docs/doc_links.py     # 文档交叉引用指得到吗（**归档 / 清出后必跑**）
python tools/docs/doc_refs.py      # ★ `src/` 代码注释的文档引用（含别名黑名单；**挪家后必跑**，见 §五.5）
.\mvnw.cmd -B clean verify         # 回归基线（⚠️ 必须带 clean，见下）
```

> ⚠️ **跑"全绿"必须带 `clean`**：`target/classes` 留着上一轮 class 时增量编译会跳过重编，
> "源码编译不过"会被旧 class 掩盖而测试照样绿（2026-10-03 实测到该路径）。
>
> ⚠️ **编号引用不在 `doc_links.py` 的检测面内**（不扫 `src/`、不认反引号裸文件名）——
> 清出 / 挪家时必须手工跑
> `grep -rnE "\b(R-[0-9]{2}|I-0[0-9]|G1[0-9]|T[0-9]{1,2})\b" src .docs`，逐条改指新家。
> ⚠️ **`src/main/resources/db/migration/**` 是例外** —— 已应用的迁移**逐字节冻结**（改不了），
> 其旧路径由**归档 README** 承接重定向（见 `archive/favorite-2026-10-15/README.md` §三）。

**环境前置**：Docker 必须在跑（Testcontainers 自起 mysql/redis/rabbitmq，**不需要**手工起容器）；测试素材在外部目录 `D:\dev\WorkSpace\VideoPlatform\TestResource`（`TV_TEST_RESOURCE_DIR` 可覆盖，
**缺失即显式失败**，有意设计）；测试落盘根被**无条件覆盖**到 `target/test-media`（`mvn clean` 回收，
**永不触碰** dev 媒体根）。

> ⚠️ **跑全量回归须设 `DB_POOL_MAX_SIZE=4`** —— 否则 Testcontainers MySQL 报 `Too many connections` ⇒ 若干测试类**上下文加载失败**（**不是代码缺陷**，2026-10-18 实测）。反向验证里 `git checkout --` 还原前**先 commit 基线**（否则会把未提交改动一起抹掉）。

⚠️ **dev 直连库** `spring_tv`@3306、媒体根 `D:/data/projects/MySpringTV/media` ——
**任何 schema 变更前先看 `architecture/tech/data-evolution/数据与Schema演进.md`**。

---

## 五、纪律（五条最容易被忽略的）

1. **裁剪即登记**：任何"这次先不做"的决定，先写进 `task/FURTHER_ISSUES.md` 再动代码；
   消化后**改状态不要删行**（删了就没人知道曾经裁剪过）。
2. **同一事实只写一处**：单一事实源见 §三"读这个"列；别处只能**链接**，不许复述
   （复述必然漂移 —— 2026-10-03 就发现 DoD 写了两份且已经不一致）。
3. **归档 / 清出要连同"引用修复"一起做**：移走或删掉一条内容后，正文与**代码注释**里指向它的引用
   必须改指新位置 —— **悬空指针比不引用更坏，它让人以为查过了**
   （`tools/docs/doc_links.py` 管文档侧；**编号侧靠 §四 那条 grep**）。
   ⚠️ **但 `src/main/resources/db/migration/**` 是例外：已应用的迁移逐字节冻结，连注释都不能改**
   （Flyway checksum 覆盖全文 ⇒ `Migration checksum mismatch`、应用起不来；详见
   `architecture/tech/data-evolution/数据与Schema演进.md` **§2.4**）。那里的历史指向由**目标文档**承接重定向。
4. ★ **编号纪律**：① **编号不回收**，且**每个前缀只服务一个编号空间**（`F-##` 是唯一活着的 F 空间；
   冻结前缀不得再生成新号）。② **引用编号必须同时给第二锚点**（`favorite.md §x` 或 `类#方法`）——
   "只有编号、没有第二锚点"的引用不合格，总账一挪家注释就废。③ 让代码引用的新编号，**先在编号速查登记**。
   ④ **方案代号（A / B / C / 甲 / 乙 …）一律不得进代码注释** —— 它们不在任何编号空间里，写进去即悬空引用
   （实测过与 `C-#` 文字编号撞字母）。⇒ 代码只引用**正式编号 + 第二锚点**。
5. ★ **文档引用必须"位置无关"（防"索引一挪家就断链"）** —— "挪家"本身是设计（按归档日冻结），
   要防的是**挪家时引用静默断裂**。四条不变式：
   ① **解析点生在稳定层**：新功能域**开工就建** `architecture/domain/<域>.md` + 编号速查附录 ⇒ 活解析入口终身不挪家。
   ② **代码注释只许引用"稳定层 / 归档层"路径**（`architecture/**`、`archive/**`）——
      **`task/` 下的登记台不得进代码引用**（每期重建、内容会消失）；裸文件名若只解析到 `archive/` 副本也算违规。
   ③ **别名一律禁入代码**（`需求篇` / `设计篇` / `分期篇` / `骨架 §` / `三期设计` 之类，工具测不到 ⇒ 静默断链）。
   ④ **第二锚点优先 `类#方法`**（不随文档重组漂移），章节号 `§x` 仅作补充。
   ★ **门禁**：`python tools/docs/doc_refs.py` 把 ②③ 变成**会失败的命令**，纳入每期收尾 checklist。
   ★ **专项收口 SOP（五步）**：① **先收编**（该期全部编号 → 活速查，只许一行结论）；
   ② **建带日期归档目录** `archive/<域>-<归档日>/` + README（承接冻结迁移文件的旧路径重定向）；
   ③ **按映射表清扫引用**（`src/` 逐条判性质：**口径 → 活文档**、**历史 → archive**；排除 `db/migration/**`）；
   ④ **三道校验**（`doc_links` = 0 / `doc_refs` = 0 / `doc_stats` 无新超阈值；＋ `mvnw clean test-compile`）；
   ⑤ **登记台重建**（完成态清出 → git；结论进速查）。
