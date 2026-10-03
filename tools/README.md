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
