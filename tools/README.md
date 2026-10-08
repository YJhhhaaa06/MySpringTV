# tools/ —— 可复用脚本

> 建立时间：2026-10-02（S6 收口批次）
> **脚本语言统一为 Python**（决策见下方"为什么是 Python"）。

---

## 一、脚本放哪

| 类型 | 位置 | 是否入库 |
|------|------|---------|
| **可复用**（会反复跑、别人也会跑） | `tools/` | ✅ 入库 |
| **一次性**（本次任务用完即弃） | `temp-script/` | ❌ 已在 `.gitignore` / `.git/info/exclude` |
| ★ **已归档**（迁移期专用，2026-10-06 随迁移文档迁出） | `.docs/archive/migration/tools/` | ✅ 入库（在 `.docs` 下） |

判据：**"下一个切片还会跑它吗？"** 会 ⇒ `tools/`；不会 ⇒ `temp-script/`。

---

## 二、为什么是 Python（而不是 PowerShell / bat）

原话是"powershell、bat 对 LLM 不友好"，具体在哪：

1. **语法噪声高**：`Select-String -Path X -Pattern Y | ForEach-Object { $_.Line }`
   里的 `$_`、`-Path`、管道语义，LLM 每次都要重新推理一遍；Python 的
   `path.read_text()` / `re.findall()` 是通用知识。
2. **错误模式不通用**：PowerShell 的 `$LASTEXITCODE`、`-ErrorAction`、
   非终止错误不抛异常…… 这些都是 PowerShell 专有陷阱，模型容易写错**且不自知**。
3. **无法被别的工具复用**：Python 脚本能被 pytest、CI、其它 Python 调用；
   `.ps1` 基本只能手跑。
4. **跨平台**：同一份脚本在 Linux CI 上直接可用。

> ⚠️ 这条**只约束本项目（新项目）**。`old-project/TVhomework1` 有自己的
> `AGENTS.md` 约束，本项目只把它当**只读的行为规格书**，不向其写入任何文件。

---

## 三、写 Python 脚本的硬性约定

### 3.1 必须强制 UTF-8 输出（**最容易踩、必加**）

Windows 的默认控制台编码是 **GBK (cp936)**。本项目文档、变量名、输出全是中文，
不强制 UTF-8 会得到 **乱码**；输出里一旦有 emoji/制表符，还会直接
`UnicodeEncodeError` 崩溃。每个脚本开头都要有这三行：

```python
import sys
for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")
```

读文件时也要显式编码：`path.read_text(encoding="utf-8")`，
**永远不要**依赖 `open()` 的默认编码。

### 3.2 路径从仓库根解析，不依赖当前工作目录

```python
ROOT = Path(__file__).resolve().parent.parent   # tools/ 的上一级
```

这样 `python tools/xxx.py` 在任何目录下跑都对。

### 3.3 用 `argparse`，给 `--help` 与"默认只预演"

会改文件的脚本**默认必须只预演**（先打印"将要做什么"，不落盘），落盘要显式加开关
（`doc_archive.py` 用 `--apply`）。这是本项目"先看结论再执行"的纪律在脚本层的体现。

> ⚠️ 别图省事把开关叫 `--dry-run` 却让它默认落盘——**名字与默认值对不上时，
> 照文档敲命令的人会直接改到文件**。（`doc_archive.py` 早期文档就犯过：写了
> `--dry-run` 但脚本根本没有这个参数，默认即预演。）

### 3.4 只读优先

能只读就别写。度量/校验类脚本应当**只读**，把判断留给人和文档。

---

## 四、现有脚本

| 脚本 | 作用 | 只读？ |
|------|------|--------|
| `doc_stats.py` | 度量 `.docs` 体量，按文件与 H2 章节归因，找出膨胀源 | ✅ 只读 |
| `doc_links.py` | **校验 `.docs` 交叉引用是否指得到**（悬空指针 / 改名遗留 / 旧章节引用） | ✅ 只读 |
| `doc_archive.py` | 把已完成阶段的历史明细从正文抽到 `.docs/archive/`，正文留指针 | ❌ 会写（**默认只预演**，加 `--apply` 才落盘） |
| ★ **迁移期 5 个**（`endpoint_matrix` / `migration_matrix` / `flyway_parity` / `realdata_probe` / `tvconf`） | **已归档**到 `.docs/archive/migration/tools/`（2026-10-06）——用途见 §六；**需要复算迁移结论时才去那里跑** | ✅ 只读 |
| `run_tests.py` | **一键跑测试**：全量 `clean verify` / `--group` 选跑 / `--test` 单类；完整输出落盘 + 摘要回显 | ✅ 只读（仅写 `target/test-reports/`） |
| `backup.py` | **数据库一键备份**（`mysqldump`），产物 = `<时间戳>/db.sql` + `manifest.txt`（含 sha256）；连接参数取自 `application.yaml` 的 dev 默认值 + `DB_*` 覆盖 | ❌ 会写（**纯增量**：只新建 `<时间戳>/` 目录，**绝不覆盖**；故**默认真跑**，`--dry-run` 才预演） |

> ★ **`backup.py` 是对迁移期一处裁剪的收回**（2026-10-08）。迁移收口时它被列为「丢」——
> `.docs/archive/migration/测试策略与阶段验收.md` §6.3 给的理由是"其功能已被新体系吸收"，
> 但该处举证的 §6.4 **三条实例没有一条是备份**；而 `application.yaml` 自己写着
> 「**Flyway 管结构演进，不是备份；业务数据仍靠 dump**」。没有它，`.docs/task/FURTHER_ISSUES.md`
> F-04~F-09 的第一条处置纪律「**先备份再动**」就没有执行手段。
> 与老仓 `backup.py` 的 5 处差异（连接参数来源 / 默认目录 / `--out` 解析根 / 口令走 `MYSQL_PWD` / 新增 `--dry-run`）
> 逐条列在脚本 docstring 里；老仓那份是**只读行为规格书**，不要改它。

## 五、常用命令

```bash
python tools/doc_stats.py                  # 文档体量报告
python tools/doc_stats.py --top 6          # 对最大的 6 份做章节拆解
python tools/doc_links.py                  # 校验文档交叉引用（归档后必跑）
python tools/doc_links.py --strict         # 把"尚未创建"也计为失败
python tools/doc_archive.py --plan temp-script/doc-plan.json            # 归档预演（不落盘）
python tools/doc_archive.py --plan temp-script/doc-plan.json --apply     # 归档落盘
# ★ 迁移期的复算命令（endpoint_matrix / migration_matrix / flyway_parity / realdata_probe）
#   已随脚本归档，改从归档目录跑、**用法不变**。例（其余选项见 §六）：
python .docs/archive/migration/tools/migration_matrix.py      # 反面盘点：未表态 = 0
python .docs/archive/migration/tools/flyway_parity.py         # 结构连续性：活库 vs V1
python tools/run_tests.py                  # 默认回归（mvnw -B clean verify，排除 @Tag("resilience")）
python tools/run_tests.py --group resilience           # 只跑 @Tag("resilience")
python tools/run_tests.py --group a --group b          # 多组（并集）
python tools/run_tests.py --exclude-group slow         # 排除某组
python tools/run_tests.py --test SecurityContractTests # 只跑一个测试类
python tools/backup.py                     # 备份 dev 库 spring_tv -> D:\data\projects\MySpringTV\backups\<时间戳>\
python tools/backup.py --out backups       # 输出到仓库内 backups/（.gitignore 已排除，沙箱会话友好）
python tools/backup.py --dry-run           # 只打印连接与将执行的命令，不落盘
```

> `backup.py` 的连接参数**只有一处来源**：`src/main/resources/application.yaml` 的
> `spring.datasource.{url,username,password}`（dev 默认值），环境变量 `DB_URL` / `DB_USERNAME` /
> `DB_PASSWORD` / `DB_HOST` / `DB_PORT` / `DB_NAME` 优先覆盖（变量名与归档的 `tvconf.py` 一致）。
> 典型用法：**动库前先跑一遍**留底（媒体另备份，脚本不管）；临时指向别的实例用 `DB_NAME=...` 覆盖。
> 退出码：`0` 成功 / `1` 其它错误（含 mysqldump 非零退出、超时）/ `2` 无 mysqldump / `3` 产物空或校验失败。

> `doc_archive.py` 的计划格式见脚本头部 docstring：`extract`（按标题抽节，`levels` 可指定 H2/H3）
> 与 `move`（整份移入归档）两种操作。
>
> ⚠️ **`doc_archive.py` 之后必跑 `doc_links.py`**：归档会把章节搬走，正文里指向它的引用就悬空了
> ——「**归档要连同引用修复一起做**」（INDEX §五.3）。悬空指针比不引用更坏，它让人以为查过了。

> `run_tests.py` 的三个产物落在 `target/test-reports/`：`run-<时间戳>.log`（完整输出）、
> `latest.json`（机器可读）、`report-<时间戳>.md`（按类汇总 + 失败栈摘要）；stdout 常态只回显约 6 行摘要
> （避免测试输出灌进主会话上下文）。**结果一律从 surefire XML 取，不解析控制台**——
> 控制台只用于"阶段耗时"打点（日志按**子进程实际编码**解码：Windows 上 JVM 写管道用
> ANSI 代码页 `GetACP()`，不是 UTF-8，写死 UTF-8 会让中文日志成一片 `�`）。
> 它必须带 `clean`（否则旧 class 会掩盖编译错误），
> 且用 `-Dmaven.test.failure.ignore=true` 跑完全部失败后再由脚本透传退出码。
> 两条防呆：**指定 `--group`/`--test` 却零命中 ⇒ 按失败退出**（不静默绿灯）；
> **早于本次运行起点的 surefire XML 一律不采信**（防拿上一轮结果冒充本次）。
> ⚠️ **默认排除 `resilience` 组**（`DEFAULT_EXCLUDED_GROUPS`，T1 的设计约定：故障注入组不进默认回归集），
> 需显式 `--group resilience` 才跑；脚本会把显式 `--group` 的 tag 从排除集里剔除，
> 避免 `-Dgroups=X` 与 `-DexcludedGroups=X` 交叠导致**零用例**。
> 用法以 `python tools/run_tests.py --help` 为准。

> 注：本项目**没有** `tv.py` 那样的统一入口——脚本少，直接调即可。
> 脚本多了再考虑加（rule of three）。

---

## 六、已归档的迁移脚本的前提（**追溯时才读这段**）

> 本节讲的 4 个脚本（`endpoint_matrix` / `migration_matrix` / `flyway_parity` / `realdata_probe`）
> 已在 2026-10-06 随迁移文档**归档到 `.docs/archive/migration/tools/`**。日常开发用不到它们；
> 只有**复算迁移期的结论**（"43 端点搬完了吗""每个老资产都有去向吗""活库结构与 V1 还一致吗"）时才需要。
> ⚠️ 归档时它们的"仓库根"推导已从**固定层级**改为**向上找 `pom.xml`** ——
> 所以从归档目录直接跑即可，不需要 cd 或改路径。


1. **老仓没有任何 mapper XML**：老项目 SQL 是 DAO 里的**内联字符串**（`String sql = "..."`，9 个 DAO / 89 条）。
   新仓的 9 个 mapper XML / 102 条是**语义重写**，与老侧**不是 1:1** ——
   **语句数下降不是缺口**（连接参数与事务管道本就要删掉）。
2. **`flyway_parity.py` 需要 mysql 客户端**：本机它在
   `C:\Program Files\MySQL\MySQL Server 8.0\bin\`（**不在 PATH**），`tvconf.mysql_exe()` 会自动探测，
   也可用 `--exe` 或 `MYSQL_EXE` 指定。连接参数默认取自 `application.yaml` 的 dev 默认值。
   ⚠️ 它**只读**，但会连**dev 活库**（`spring_tv`——2026-10-06 分家后新项目自己的库，
   见 `.docs/archive/migration/决策留痕表.md` **K-7**）——任何 schema 变更前先读
   `.docs/architecture/tech/数据与Schema演进.md`（V1 冻结，演进走 V2）。
3. **子集运行不会给整体结论**：`--coverage` / `--domain` 只跑一部分，结论句会写明本次跑了几个域；
   "腿 2 成立"只在**全量跑**时给出。默认退出码不含「发现项」（未登记的口径差异）——
   **收口时要用 `--strict` 跑一次**，否则那些差异会一直躺在报告里没人认领。
