# tools/ —— 脚本总入口

> 按用途分目录：文档门禁 → `docs/`，开发期数据清理 → `devclean/`，各自带 README；
> 根级只留**跨用途**的两个脚本（回归 / 备份）。**脚本语言统一为 Python**（理由见 §六）。
>
> ★ 本文件是**全仓脚本约定的唯一事实源**（§三）。子目录 README 只写"这一族脚本自己的事"，约定一律指上来。

---

## 一、脚本放哪

| 类型 | 位置 | 是否入库 |
|------|------|---------|
| **可复用·跨用途**（回归 / 备份） | `tools/` 根级 | ✅ 入库 |
| **可复用·单一用途**（≥2 个脚本服务同一件事） | `tools/<用途>/` ＋ 该目录 `README.md` | ✅ 入库 |
| **一次性**（本次任务用完即弃） | `temp-script/` | ❌ 不入库（在本机 `.git/info/exclude`；新克隆请自行忽略，或丢进已忽略的 `scratch/`） |
| ★ **已归档**（迁移期专用，2026-10-06 随迁移文档迁出） | `.docs/archive/migration/tools/` | ✅ 入库（在 `.docs` 下） |

判据：**"下一个切片还会跑它吗？"** 会 ⇒ `tools/`；不会 ⇒ `temp-script/`。
**"它有自己的一套纪律要说吗？"** 有（默认行为不同、红线不同、要解释边界）⇒ 开子目录 + README，别往根级堆。

---

## 二、目录索引

```
tools/
├── run_tests.py          ← 一键回归（默认 clean verify，排除 resilience 组）
├── backup.py             ← DB 一键备份（mysqldump，产物带 sha256）
├── docs/                 ← ★ 文档门禁（只读）
│   ├── README.md         ←   **本族手册**：用法 / 检测面与假阴性 / 加检查的纪律
│   ├── common.py         ←   ROOT（向上找 pom.xml）+ setup_stdio()
│   ├── doc_stats.py      ←   体量度量（防膨胀）
│   ├── doc_links.py      ←   `.docs` 交叉引用门禁
│   └── doc_refs.py       ←   `src/` 注释引用门禁
└── devclean/             ← ★ 开发期数据清理 + 派生数据修复（**默认 dry-run**）
    ├── README.md         ←   **本族手册**：红线 / 顺序依赖 / 刻意不做
    ├── common.py         ←   连接参数（复用 backup.py）+ mysql 客户端定位
    ├── db_clean.py       ←   顺悬挂引用清 DB
    ├── media_clean.py    ←   孤儿媒体移到 trash（只移动不删除）
    ├── recount.py        ←   7 个计数列重算
    ├── backfill_duration.py ← 存量视频时长回填（一次性；只填 NULL）
    └── devclean.py       ←   一键编排（备份 → DB → 媒体 → 重算）
```

| 子目录 | 干什么 | 手册 | 默认行为 |
|--------|--------|------|----------|
| `docs/` | 让"文档引用指得到 / 体量不膨胀"变成**会失败的命令** | [docs/README.md](docs/README.md) | ✅ **只读**（三个脚本都不写文件） |
| `devclean/` | 清掉开发 / 测试期造的数据 + **派生数据修复**（计数重算 / 时长回填），**不删游玩数据** | [devclean/README.md](devclean/README.md) | ⚠️ **dry-run**（`--apply` 才落库 / 落盘） |

> ★ **`tools/devclean/` 与 `tools/docs/` 是两种不同的东西，别混**：前者会改数据（所以默认只预演、红线最多）；
> 后者只是门禁（所以只读、可以随便跑）。**"默认只预演"是写操作脚本的纪律，不是所有脚本的纪律**。

---

## 三、写 Python 脚本的硬性约定（**唯一事实源**）

### 3.1 必须强制 UTF-8 输出（**最容易踩、必加**）

Windows 默认控制台编码是 **GBK (cp936)**；本项目文档、变量名、输出全是中文，不强制 UTF-8 会得到**乱码**，
输出里一旦有 emoji / 制表符还会直接 `UnicodeEncodeError` 崩溃。

```python
for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")
```

`tools/docs/` 已把它收成 `common.setup_stdio()`（各脚本 import 后**显式调用一次**）——
新脚本若在子目录里、又已有 `common.py`，照抄这个形态；否则直接把上面三行写进脚本。
读文件同样显式编码：`path.read_text(encoding="utf-8")`，**永远不要**依赖 `open()` 的默认编码。

### 3.2 路径从仓库根解析，不依赖当前工作目录

```python
ROOT = Path(__file__).resolve().parent.parent   # 脚本在 tools/ 根级时
```

**脚本在子目录里时不要数层级**（`parent.parent` 会从仓库根变成 `tools/`，而症状是**静默扫 0 份**）：
用**向上找 `pom.xml`**（模板 = `tools/docs/common.py` 的 `_find_repo_root()`），或 `parents[N]` 但**必须注释层级依据**
（先例 = `tools/devclean/common.py` 的 `parents[2]`）。这样"仓库根"与 cwd 无关；
但 `python tools/xxx.py` 这条**相对命令**仍要在仓库根敲，跨目录请用绝对路径。

### 3.3 用 `argparse`，给 `--help` 与"默认只预演"

**会改数据 / 文件的脚本默认必须只预演**（先打印"将要做什么"，不落盘），落盘要显式加开关
（`--apply`）。这是本项目"先看结论再执行"的纪律在脚本层的体现。
**唯一例外**：**纯增量、绝不覆盖**的脚本可以默认真跑（先例 = `backup.py`：它只新建 `<时间戳>/` 目录）。

> ⚠️ 别图省事把开关叫 `--dry-run` 却让它默认落盘——**名字与默认值对不上时，照文档敲命令的人会直接改到文件**。

### 3.4 只读优先

能只读就别写。度量 / 校验类脚本应当**只读**，把判断留给人和文档（`tools/docs/` 三个脚本都是这个形态）。

### 3.5 同一子目录 ≥3 个脚本共用的样板抽 `common.py`，**不跨目录 import**

抽的是"基建"（UTF-8 / 仓库根 / 连接参数），**不抽检查逻辑**——
三个文档门禁的判定口径差异极大，硬抽公共层会把口径变成"推断出来的东西"。
跨目录复用已有先例：`devclean/common.py` 直接 `import backup`（同仓 `tools/` 下的模块），
**不复制**连接参数解析 —— 免得同一个库出现两套连接逻辑。

---

## 四、根级脚本

| 脚本 | 作用 | 读写 |
|------|------|------|
| `run_tests.py` | **一键跑测试**：全量 `clean verify` / `--group` 选跑 / `--test` 单类；完整输出落盘 + 摘要回显 | ✅ 不改源码（脚本只写 `target/test-reports/`；Maven 的 `clean` 会重建 `target/**`） |
| `backup.py` | **DB 一键备份**（`mysqldump`），产物 = `<时间戳>/db.sql` + `manifest.txt`（含 sha256） | ❌ 会写（**纯增量**：只新建 `<时间戳>/` 目录，**绝不覆盖**；故**默认真跑**，`--dry-run` 才预演） |

要点（用法以 `--help` 为准）：

- `run_tests.py` 三个产物落 `target/test-reports/`：`run-<时间戳>.log`（完整输出）、`latest.json`（机器可读）、
  `report-<时间戳>.md`（按类汇总 + 失败栈摘要）。**结果一律从 surefire XML 取，不解析控制台** ——
  控制台只用于阶段耗时打点（日志按**子进程实际编码**解码：Windows 上 JVM 写管道用 ANSI 代码页 `GetACP()`，不是 UTF-8）。
  必须带 `clean`（否则旧 class 会掩盖编译错误），并用 `-Dmaven.test.failure.ignore=true` 跑完全部失败后再**按 surefire 结果合成退出码**
  （测试失败 → `1`；构建失败 → Maven 的码；子进程没起来 → `127`；全绿 → `0`）。
  两条防呆：**指定 `--group` / `--test` 却零命中 ⇒ 按失败退出**；**早于本次运行起点的 surefire XML 一律不采信**。
  ⚠️ **默认排除 `resilience` 组**（`DEFAULT_EXCLUDED_GROUPS`；故障注入组不进默认回归集），
  且会把显式 `--group` 的 tag 从排除集里剔除，避免 `-Dgroups=X` 与 `-DexcludedGroups=X` 交叠导致**零用例**。
- `backup.py` 的连接参数**只有一处来源**：`src/main/resources/application.yaml` 的
  `spring.datasource.{url,username,password}`（dev 默认值），环境变量 `DB_URL` / `DB_USERNAME` /
  `DB_PASSWORD` / `DB_HOST` / `DB_PORT` / `DB_NAME` 优先覆盖（变量名与归档的 `tvconf.py` 一致）。
  退出码：`0` 成功 / `1` 其它错误（含 mysqldump 非零退出、超时）/ `2` 无 mysqldump / `3` 产物空或校验失败。
  ★ 为什么需要它：`application.yaml` 自己写着「**Flyway 管结构演进，不是备份；业务数据仍靠 dump**」，
  而 `task/FURTHER_ISSUES.md` 的处置纪律是「**先备份再动**」—— 没有它，这条纪律没有执行手段。
  与老仓 `backup.py` 的差异列在脚本 docstring 里；那份是**只读行为规格书**，不要改它。

---

## 五、常用命令

```bash
# 回归
python tools/run_tests.py                              # 默认回归（mvnw -B clean verify，排除 @Tag("resilience")）
python tools/run_tests.py --group resilience            # 只跑 @Tag("resilience")
python tools/run_tests.py --group a --group b           # 多组（并集）
python tools/run_tests.py --exclude-group slow          # 排除某组
python tools/run_tests.py --test SecurityContractTests  # 只跑一个测试类

# 备份（动库前先跑一遍留底；媒体另备份，脚本不管）
python tools/backup.py                                  # -> D:\data\projects\MySpringTV\backups\<时间戳>\
python tools/backup.py --out backups                    # 输出到仓库内 backups/（.gitignore 已排除）
python tools/backup.py --dry-run                        # 只打印连接与将执行的命令

# 文档门禁 → 见 tools/docs/README.md
python tools/docs/doc_links.py
python tools/docs/doc_refs.py
python tools/docs/doc_stats.py

# 开发期数据清理 → 见 tools/devclean/README.md（默认全链 dry-run）
python tools/devclean/devclean.py

# 存量视频时长回填（一次性；只填 NULL，先备份再 --apply）
python tools/devclean/backfill_duration.py
python tools/devclean/backfill_duration.py --apply
```

> 注：本项目**没有** `tv.py` 那样的统一入口——脚本少，直接调即可。
> 脚本多了再考虑加（rule of three）——`docs/` 与 `devclean/` 各自有编排需要时**先加目录 README**，别先造总入口。

---

## 六、为什么是 Python（而不是 PowerShell / bat）

原话是"powershell、bat 对 LLM 不友好"，具体在哪：

1. **语法噪声高**：`Select-String -Path X -Pattern Y | ForEach-Object { $_.Line }`
   里的 `$_`、`-Path`、管道语义，LLM 每次都要重新推理一遍；Python 的
   `path.read_text()` / `re.findall()` 是通用知识。
2. **错误模式不通用**：PowerShell 的 `$LASTEXITCODE`、`-ErrorAction`、非终止错误不抛异常……
   这些都是 PowerShell 专有陷阱，模型容易写错**且不自知**。
3. **无法被别的工具复用**：Python 脚本能被 pytest、CI、其它 Python 调用；`.ps1` 基本只能手跑。
4. **跨平台**：同一份脚本在 Linux CI 上直接可用。

> ⚠️ 这条**只约束本项目（新项目）**。`old-project/TVhomework1` 有自己的 `AGENTS.md` 约束，
> 本项目只把它当**只读的行为规格书**，不向其写入任何文件。
>
> ★ **迁移期的 5 个脚本已归档**到 `.docs/archive/migration/tools/`（`endpoint_matrix` / `migration_matrix` /
> `flyway_parity` / `realdata_probe` / `tvconf`），日常开发用不到它们。需要**复算迁移结论**时，
> 用法与前提（需本机有 `old-project/` / 子集不给整体结论 / `--strict` / mysql 客户端位置）见
> [该目录的 README](../.docs/archive/migration/tools/README.md)。
