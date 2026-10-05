# tools/ —— 可复用脚本

> 建立时间：2026-10-02（S6 收口批次）
> **脚本语言统一为 Python**（决策见下方"为什么是 Python"）。

---

## 一、脚本放哪

| 类型 | 位置 | 是否入库 |
|------|------|---------|
| **可复用**（会反复跑、别人也会跑） | `tools/` | ✅ 入库 |
| **一次性**（本次任务用完即弃） | `temp-script/` | ❌ 已在 `.gitignore` / `.git/info/exclude` |

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
| `endpoint_matrix.py` | 复算老项目 43 端点 × 新项目实现状态，校验《端点对照表》 | ✅ 只读 |
| `migration_matrix.py` | ★ **反面盘点**（第四批 T7-0）：老项目 `src/main` 每个资产的**去向**（声明覆盖 → 未表态 = 0）+ 10 个域级对账（含**配置逐键对照**） | ✅ 只读 |
| `flyway_parity.py` | ★ **结构连续性**（第四批 T7-0）：活库列集 vs `V1__baseline_tv_schema.sql` 列集，差异非空即退出非 0 | ✅ 只读 |
| `realdata_probe.py` | ★ **真数据通路探针**（第四批 T7-1）：只读检查存量库/媒体根（域约束 / 媒体匹配 / 计数列对账 / feed 孤儿 / 可选 HTTP 读接口探活），**双趟**产出三张清单（① 代码缺口 / ② 存量脏数据 E 类 / ③ 口径未定） | ✅ 只读（`--apply` 只写 `target/realdata_probe/`） |
| `tvconf.py` | **共用小工具**（非可执行脚本，无 CLI）：强制 UTF-8 / 仓库根 / 极简 YAML 扁平化 / 调 mysql 客户端。上面三个脚本 import 它 | ✅ 只读 |
| `run_tests.py` | **一键跑测试**：全量 `clean verify` / `--group` 选跑 / `--test` 单类；完整输出落盘 + 摘要回显 | ✅ 只读（仅写 `target/test-reports/`） |

## 五、常用命令

```bash
python tools/doc_stats.py                  # 文档体量报告
python tools/doc_stats.py --top 6          # 对最大的 6 份做章节拆解
python tools/doc_links.py                  # 校验文档交叉引用（归档后必跑）
python tools/doc_links.py --strict         # 把"尚未创建"也计为失败
python tools/doc_archive.py --plan temp-script/doc-plan.json            # 归档预演（不落盘）
python tools/doc_archive.py --plan temp-script/doc-plan.json --apply     # 归档落盘
python tools/endpoint_matrix.py            # 复算端点对照
python tools/migration_matrix.py           # 反面盘点：未表态 = 0（含 11 个域对账）
python tools/migration_matrix.py --coverage        # 只看覆盖率与未表态项
python tools/migration_matrix.py --domain config   # 只看某一域（--list-domains 列出）
python tools/migration_matrix.py --strict          # 把「未登记的口径差异」也计入退出码
python tools/flyway_parity.py              # 结构连续性：活库 vs V1（差异非空 ⇒ 退出非 0）
python tools/flyway_parity.py --v1 target/x.sql    # 换一份 baseline（演示"能变红"用）
python tools/realdata_probe.py --baseline --apply  # 真数据探针：基线趟（★ 唯一一次机会，须先备份库）
python tools/realdata_probe.py                     # 当前趟（读 baseline.json 做差 → 三张清单；默认只预演）
python tools/realdata_probe.py --base-url http://localhost:8080   # 追加第 5 项 HTTP 读接口探活
python tools/run_tests.py                  # 默认回归（mvnw -B clean verify，排除 @Tag("resilience")）
python tools/run_tests.py --group resilience           # 只跑 @Tag("resilience")
python tools/run_tests.py --group a --group b          # 多组（并集）
python tools/run_tests.py --exclude-group slow         # 排除某组
python tools/run_tests.py --test SecurityContractTests # 只跑一个测试类
```

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

## 六、`migration_matrix.py` / `flyway_parity.py` 的两个前提（**读代码前先读这段**）

1. **老仓没有任何 mapper XML**：老项目 SQL 是 DAO 里的**内联字符串**（`String sql = "..."`，9 个 DAO / 89 条）。
   新仓的 9 个 mapper XML / 102 条是**语义重写**，与老侧**不是 1:1** ——
   **语句数下降不是缺口**（连接参数与事务管道本就要删掉）。
2. **`flyway_parity.py` 需要 mysql 客户端**：本机它在
   `C:\Program Files\MySQL\MySQL Server 8.0\bin\`（**不在 PATH**），`tvconf.mysql_exe()` 会自动探测，
   也可用 `--exe` 或 `MYSQL_EXE` 指定。连接参数默认取自 `application.yaml` 的 dev 默认值。
   ⚠️ 它**只读**，但会连**真库**（`TVDatabase`）——任何 schema 变更前先读《决策留痕表》K-1。
3. **子集运行不会给整体结论**：`--coverage` / `--domain` 只跑一部分，结论句会写明本次跑了几个域；
   "腿 2 成立"只在**全量跑**时给出。默认退出码不含「发现项」（未登记的口径差异）——
   **收口时要用 `--strict` 跑一次**，否则那些差异会一直躺在报告里没人认领。
