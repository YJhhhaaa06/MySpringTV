# 后端期登记台（归档 2026-10-30）

> ⚠️ **冻结历史**：本目录是**后端期**（截至 2026-10-25 收口）的登记台原貌，**默认不读、不改**。
> 里面的一切都停在归档日：编号、路径、配置值、任务状态**都可能与今天的代码不符** —— 需要"现在如何"时，
> 去看 `architecture/` 与 `task/CURRENT_INTEGRATION.md`。
>
> **为什么归档**：「照着 B 站写前端」阶段结束，进入**前后端联调 + 有条件补回功能**期（2026-10-30）。
> 后端登记台（需求 / 任务 / 问题三件套 + 两份模板）已完成使命，整体退场；**编号不回收、不复用**。

## 一、目录里是什么

| 文件 | 原名 | 是什么 |
|---|---|---|
| `CURRENT_NEEDS.md` | `task/CURRENT_NEEDS.md` | 后端期需求与决策（`N#` / `R-##`）+ **通用约定 `C-#`** |
| `CURRENT_TASKS.md` | `task/CURRENT_TASKS.md` | 后端期任务执行态（`T1`–`T69`）+ **运行约定 `G#`** |
| `CURRENT_ISSUES.md` | `task/CURRENT_ISSUES.md` | 后端期问题（`I-01`–`I-30`） |
| `FURTHER_ISSUES.md` | `task/FURTHER_ISSUES.md` | 跨期结转池（`F-01`–`F-29`；**含已核销 22 条全文** + 当时在池的 7 条） |
| `CURRENT_NEEDS模板.md` / `CURRENT_TASKS模板.md` | 同名 | 上面两件的空白模板（四件套形态；该形态已退役） |

## 二、编号迁移（**这一节最重要**）

| 旧 | 现 | 说明 |
|---|---|---|
| `I-27` | **`F-28`** | 上传超限 / 畸形 multipart 仍 500（待裁决 413）。核定**未完成**后转号 |
| `I-28` | **`F-29`** | 并发投币撞封顶偶发 500。核定**未完成**后转号 |
| `I-##` 空间 | **后端期这一段冻结** | `I-01`–`I-30` 留在本目录、**不回收不复用**；联调期**同日重开** `task/CURRENT_ISSUES.md`，**从 `I-31` 续**（中途曾一度宣布整段冻结、当日内即撤销，见下注） |
| `I-23` | 收编进 [history.md 附录「编号速查」](../../architecture/domain/history/history.md) | 归档前补的收编（`SecurityContractTests` 引用了它，而它只在本文件里有一行） |
| `C-1`–`C-6`（通用约定） | 收编进 [`AGENTS.md`「跨期元约定」](../../../AGENTS.md) | 代码与文档都在引 `C-6`，不能随文件归档而消失 |
| `G#`（运行约定） | 同上（`AGENTS.md`「跨期元约定」） | 代码引用了 `G5` / `G7` / `G8` / `G10`–`G14` |
| `R-##` | 续用 `R-25` 起 | 决策号不换空间 |
| `T##` | 续用 `T71` 起 | 任务号不换空间 |
| `F-##` | 续用 `F-30` 起 | 结转池是新文件，但**编号连续**（`F-01`–`F-29` 已用，本目录留有全文） |

> ★ 本文件里 `CURRENT_TASKS.md` 行 28 写的「下一编号 = `T70` / `R-25` / `I-31` / `F-28`」**已过期**：
> `T70` 已交付、`F-28`/`F-29` 已被占用；`I-31` 当时被判为"不再存在"，**当晚撤销**（联调期重开问题台，`I-31` 重新成为起点）。
> ⚠️ `F##` 的迁移口径变化过一次：2026-10-30 上午先宣布"`I-##` 冻结、新问题一律 `F-##`"，当晚改为
> "**问题仍用 `I-##`（`I-31` 起）、结转仍用 `F-##`**" —— 引用时以 `task/` 下的活文件为准，本目录只反映当时形态。

## 三、口径承接（历史指向由这里改道）

- **本文件内**的 `CURRENT_NEEDS.md` / `CURRENT_TASKS.md` / `CURRENT_ISSUES.md` 互相引用 —— 就地解析，无需处理。
- **活文档**里原本写 `task/CURRENT_{NEEDS,TASKS,ISSUES}.md` 的指针，已在归档同批改为
  `task/CURRENT_INTEGRATION.md`（"本期做什么 / 任务怎么拆"）或 `AGENTS.md`（`G#` / `C#`），逐条见 git。
- **个别"过程 commit 在哪个区间"** 之类**纯历史**指向，仍指本目录（冻结是对的）。
- ⚠️ **`db/migration` 里的旧路径改不了**：`V2__favorite_tables.sql:18`（写 `.docs/task/CURRENT_NEEDS.md` `R-01~R-08`）、
  `V5__coin.sql:30`（写 `CURRENT_TASKS T38`）—— 迁移文件**逐字节冻结**（Flyway checksum 覆盖全文），
  这些指向**按本目录解读**，**不许改**（同 `favorite-2026-10-15/README.md` §三 的先例）。

## 四、留在这里没搬走的东西（有意）

- 后端期**逐条论证 / 失败尝试 / 中间稿** —— 那段历史在 **git**，不在本目录（本目录只有收口时的形态）。
- 已核销的 `F-##` **全文** —— 在本目录 `FURTHER_ISSUES.md`「已核销」表（22 条，含逐条论证）；
  ★ **活的一行结论不在这里**（2026-10-30 定：结转池只放"还没做的事"）⇒ 见下方 §五。

## 五、已核销 `F-##` 的活落点（**查"这个号是什么"走这里**）

> 2026-10-30 起：`task/FURTHER_ISSUES.md` **不再保留已核销表**，一行结论搬进各自的**稳定层**；
> 本表是"号 → 稳定层文档"的映射，只为追溯。代码注释已同步改指右列（不再指 `task/`）。

| 编号 | 活落点（一行结论在哪） |
|---|---|
| `F-01` · `F-02` | [安全与鉴权-已知代价](../../architecture/tech/security/安全与鉴权-已知代价.md)（不迁 Spring Security / 透传配置终态） |
| `F-03` | [模块边界与依赖](../../architecture/tech/module-boundary/模块边界与依赖.md)（ArchUnit 规则 5：任何域不得依赖 `feed`） |
| `F-04` · `F-11` · `F-16` · `F-17` · `F-24` | [favorite.md 附录「已核销结转 `F-##`」](../../architecture/domain/favorite/favorite.md) |
| `F-05`–`F-09` | 仅本目录（E 类存量数据债，活文档已无引用） |
| `F-12` | 仅本目录（规则本体在 `INDEX.md` §五.4） |
| `F-13` | [coin.md 附录](../../architecture/domain/coin/coin.md) |
| `F-15` | [favorite-需求](../../architecture/domain/favorite/favorite-需求.md)（互动态维度，前端已落地） |
| `F-19` · `F-23` | [favorite-已知代价](../../architecture/domain/favorite/favorite-已知代价.md) |
| `F-20` | [接口契约 附录](../../architecture/tech/api/接口契约.md)（分页 / 巡检 6 处） |
| `F-22` | [限流 附录](../../architecture/tech/rate-limit/限流.md) |
| `F-25` | [缓存与数据一致性 §5.1](../../architecture/tech/cache/缓存与数据一致性.md) |
| `F-27` | [history-已知代价](../../architecture/domain/history/history-已知代价.md) |

> ⚠️ 在池的 7 条（`F-10` / `F-14` / `F-18` / `F-21` / `F-26` / `F-28` / `F-29`）**不在本表** —— 它们仍是活的，
> 去 `task/FURTHER_ISSUES.md` 看。
- 各域 / 横切面的**已知代价** —— 早已在 `architecture/**/*-已知代价.md`（本目录不含任何"当前口径"）。
