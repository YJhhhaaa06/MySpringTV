# 迁移期脚本（`.docs/archive/migration/tools/`）—— 用法与前提

> **这是历史存档，不是日常工具。** 本目录的 5 个脚本（`endpoint_matrix` / `migration_matrix` /
> `flyway_parity` / `realdata_probe` / `tvconf`）于 **2026-10-06** 随迁移文档一并归档，
> 原位置 = `tools/`。**日常开发用不到它们**；只有**复算迁移期的结论**时才需要：
> 「43 个端点搬完了吗」「每个老资产都有去向吗」「活库结构与 V1 还一致吗」。
>
> 需要的是**当前**门禁（`doc_links` / `doc_refs` / `doc_stats`）时，去 `tools/docs/`，不要来这里。

---

## 一、位置无关（可直接从本目录跑）

归档时它们的"仓库根"推导已从**固定层级**改为**向上找 `pom.xml`** ——
所以在本目录直接 `python .docs/archive/migration/tools/xxx.py` 即可，不需要 `cd`、也不需要改路径。

## 二、四条前提（**误读这几条就会得出错误结论**）

0. **需要本机存在 `old-project/TVhomework1`**：`endpoint_matrix` / `migration_matrix` 要拿老项目源码做对照，
   而该目录被 `.gitignore` 排除、**不在仓库里**（`tvconf.py` 直接拼这个路径）⇒ 没有它就无法复算。
1. **老仓没有任何 mapper XML**：老项目 SQL 是 DAO 里的**内联字符串**（`String sql = "..."`，9 个 DAO / 89 条）。
   迁移时新仓是 9 个 mapper XML / 102 条，且是**语义重写**、与老侧**不是 1:1** ——
   **语句数下降不是缺口**（连接参数与事务管道本就要删掉）。⚠️ 这两个数字是**当时的快照**，
   mapper 早已增长，别拿它当现状。
2. **`flyway_parity.py` 需要 mysql 客户端**：本机它在
   `C:\Program Files\MySQL\MySQL Server 8.0\bin\`（**不在 PATH**），`tvconf.mysql_exe()` 会自动探测，
   也可用 `--exe` 或 `MYSQL_EXE` 指定。连接参数默认取自 `application.yaml` 的 dev 默认值。
   ⚠️ 它**只读**，但会连**dev 活库**（`spring_tv`——2026-10-06 分家后新项目自己的库，
   见 `../决策留痕表.md` **K-7**）——任何 schema 变更前先读
   `.docs/architecture/tech/data-evolution/数据与Schema演进.md`（V1 冻结，增量演进一律走后续版本，版本清单以该篇为准）。
3. **子集运行不会给整体结论**：`--coverage` / `--domain` 只跑一部分，结论句会写明本次跑了几个域；
   "腿 2 成立"只在**全量跑**时给出。默认退出码不含「发现项」（未登记的口径差异）——
   **收口时要用 `--strict` 跑一次**，否则那些差异会一直躺在报告里没人认领。

## 三、复算入口

```bash
python .docs/archive/migration/tools/endpoint_matrix.py    # 端点完整性 43/43/0
python .docs/archive/migration/tools/migration_matrix.py   # 反面盘点：未表态 = 0
python .docs/archive/migration/tools/flyway_parity.py      # 结构连续性：活库 vs V1
python .docs/archive/migration/tools/realdata_probe.py     # 真实数据探针
```
