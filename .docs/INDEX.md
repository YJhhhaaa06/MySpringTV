# .docs 文档索引（**唯一入口导航**）

> **开始任何任务前先读本文件**，再按"何时读"挑文档；不要一上来通读全部。
> 关联入口：仓库根 `AGENTS.md` 只指向本文件。
>
> **文档可能有滞后性，以实际代码为准。** 本仓的防滞后机制是**可复算**（见 §四）：
> 凡是"还剩多少/欠什么/为什么这么定"这类结论，都必须能由一条命令或一处代码验证。
>
> ★ **迁移已收口并归档（2026-10-06）**：迁移期的文档与脚本整体移入
> `archive/migration/`（见 §二）。**本目录现在只服务"独立开发"** ——
> 只有"追溯当初为什么这么定"时才需要下钻到归档。

---

## 一、这个仓是什么（30 秒）

把老 JavaWeb 项目（`old-project/TVhomework1`，Servlet + 手写连接池/事务/缓存/MQ + 自研 IoC，
**禁用 Spring**）迁到 Spring Boot 4.1 / Java 25 的项目。技术栈：MyBatis + Jedis + Spring AMQP +
Flyway + Testcontainers。

| 现状（2026-10-06） | 值 |
|---|---|
| 迁移 | ✅ **已收口** —— 老端点 **43 / 43 / 0**；判据四条腿复算通过（《决策留痕表》**K-2**；过程与明细见 `archive/migration/`） |
| 回归基线 | `python tools/run_tests.py` → **323 例全绿**（默认排除 `resilience`）；`--group resilience` → **34 例全绿**（合计 **357**） |
| 事务边界 | **66 处全部表态**，无 🔴 |
| 环境 | ★ **dev 已与老项目"分家"**（**K-7**）：库 `spring_tv`@3306、媒体根 `D:/data/projects/MySpringTV/media` |
| 未结的账 | 《遗留台账》：**C3/C4**（Security 链，属"上线准备度"）+ **E 类 6 条**（继承的存量数据债，须逐条表态、不阻塞） |

> ⚠️ **三条必须知道的边界**：
> ① **前端**在 `src/main/resources/static/`（18 文件）。⚠️ 后续若再动 HTTP 层，须同步 `static/js/api.js`；
> 迁移期记下的契约差异看《决策留痕表》**D-13~D-15**。
> ② 鉴权**还没迁 Spring Security**，当前是自研 `JwtAuthFilter` + `@RequiresLogin`，
> `SecurityPassthroughConfig` 是**临时态**（台账 C3/C4）。
> ③ ★ **老项目已停用、只读留存**（**K-1** / **K-4**）：新项目用自己的 `spring_tv` 与
> `MySpringTV/media`（**K-7**，2026-10-06 分家），老项目那两份（`TVDatabase` / `stone`）自此是
> **只读来源快照**。⚠️ **V1 baseline 依旧冻结，不要改**（`spring_tv` 已带 V1 记录，改了仍会让
> 测试库与活库分叉）；⚠️ **老项目禁止删除** —— 它是迁移判据腿②的唯一事实源。

---

## 二、目录结构

```
.docs/
├── INDEX.md              ← 本文件（唯一入口）
├── 遗留台账.md             ← 事实源：现在还欠着什么（A/B/C/D/**E** 类）
├── 决策留痕表.md           ← 事实源：当初为什么这么定（A~K 类；★ **G/H/I/J = 新域照此写的规范**）
├── 事务边界决策表.md       ← 事务明细（状态总表 + 模式库；**写新事务前必读**）
├── 测试策略与阶段验收.md   ← 怎么测、DoD、防"测试迎合"
├── temp/                 ← 草稿（gitignore）
└── archive/
    └── migration/        ← ★ **迁移期全部归档**（2026-10-06 迁入；**勿通读**，只用来追溯）
        ├── 工单-第三批.md / 工单-第四批.md          ← 批次工单
        ├── 端点对照表.md / 迁移参照系.md             ← 迁移完整性 / 逐层对照依据
        ├── 切片计划.md / 迁移工作流-切片SOP.md       ← 切片切法 / **§二 18 个坑表**
        ├── 事务边界明细-S1-S5.md 等 7 份历史存档
        ├── report/        ← 迁移前的三份调研报告
        └── tools/         ← 迁移期脚本（endpoint/migration/flyway/realdata_probe + tvconf）
```

---

## 三、按"我要做什么"选文档

| 我要… | 读这个 | 何时更新 |
|------|--------|---------|
| ★ **写新代码 / 了解架构** | **`architecture/ARCHITECTURE.md`**（主索引：模块地图 + "写代码前读哪篇" + 写作规范）→ 横切机制见 `architecture/tech/` 各篇、业务域见 `architecture/domain/` | 架构变更时 |
| **知道下一步做什么** | **"上线准备度"清单**：《遗留台账》**C3/C4**（Security 链）+ `archive/migration/切片计划.md` §二（Dockerfile / 部署形态 / springdoc / 限流） | 排期/收尾时 |
| 知道**现在还欠着什么** | `遗留台账.md`（A/B/C/D/**E**；E = 存量数据债） | 每次裁剪**先登记再动代码** |
| 知道**当初为什么这么定** | `决策留痕表.md`（A 架构 / B 范围 / C 保真度 / D 契约差异 / E 事务指针 / F 选型 / **G 可观测 / H 韧性 / I 一致性 / J MQ** / K 收口判据） | 做决策时先加一行 |
| 处理**某个事务边界** | `事务边界决策表.md`（§四 状态总表；逐条论证在 `archive/migration/事务边界明细-*.md`） | 开工前必须表态 |
| 动**测试** | `测试策略与阶段验收.md`（§三 分层 / §四 防迎合） | 测试体系变更 |
| 写**脚本** | `tools/README.md`（**Python 硬性约定**：UTF-8 / 路径 / 默认只预演） | 加脚本时 |
| 查**老项目某个行为** | 直接 grep `old-project/TVhomework1`（**只读行为规格书，禁止写入**；★ 也禁止删除，见 K-4） | — |
| **踩到坑**（Duration 单位 / MyBatis 前缀 / record 绑定 / ArchUnit 扫测试类…） | `archive/migration/迁移工作流-切片SOP.md` **§二 坑表**（18 条，多数与迁移无关、写新代码同样适用） | 踩到新坑时 |
| 追溯**迁移期的进度与论证** | `archive/migration/`（端点对照表 / 各批次工单 / 切片计划 / 三份调研报告）；需要复算时跑 `archive/migration/tools/` 下的脚本 | — |

---

## 四、可复算性（本仓的防滞后机制）

**任何"状态/计数"结论都必须能一条命令复算**。日常维护只有这四条：

```bash
python tools/doc_stats.py          # 文档体量（防膨胀；阈值 单文件 40 KB / 单章节 300 行）
python tools/doc_links.py          # 文档交叉引用是否指得到（**归档后必跑**）
python tools/doc_archive.py --plan <计划.json>   # 归档已完成章节（默认只预演，--apply 落盘）
.\mvnw.cmd -B clean verify        # 回归基线（⚠️ 必须带 clean，见《测试策略》§五.1）
```

> ★ **迁移期的三条"判据腿"脚本已随文档一并归档**到 `.docs/archive/migration/tools/`：
> `endpoint_matrix.py`（端点完整性 43/43/0）、`migration_matrix.py`（反面盘点，未表态 = 0）、
> `flyway_parity.py`（结构连续性：活库 vs V1 列集）、`realdata_probe.py`（真实数据探针）。
> 需要复算迁移结论时从那里跑 —— 它们已改为**向上找 `pom.xml`** 定位仓库根，位置无关。
>
> ⚠️ **跑"全绿"必须带 `clean`**：`target/classes` 留着上一轮 class 时增量编译会跳过重编，
> "源码编译不过"会被旧 class 掩盖而测试照样绿（2026-10-03 实测到该路径）。

**环境前置**：Docker 必须在跑（Testcontainers 自起 mysql/redis/rabbitmq，**不需要**手工起容器）；
测试素材在外部目录 `D:\dev\WorkSpace\VideoPlatform\TestResource`（`TV_TEST_RESOURCE_DIR` 可覆盖，
**缺失即显式失败**，有意设计）；测试的落盘根被**无条件覆盖**到 `target/test-media`（`mvn clean` 回收，
**永不触碰** dev 媒体根）。

⚠️ **dev 直连库** `spring_tv`@3306、媒体根 `D:/data/projects/MySpringTV/media` ——
**任何 schema 变更前先读 K-1、K-3 与 K-7**（V1 冻结；下一次演进走 **V2**）。

---

## 五、纪律（三条最容易被忽略的）

1. **裁剪即登记**：任何"这次先不做"的决定，先写进《遗留台账》再动代码；
   补回后**移到 D 类不要删行**（删了就没人知道曾经裁剪过）。
2. **同一事实只写一处**：单一事实源见 §三"读这个"列；别处只能**链接**，不许复述
   （复述必然漂移——2026-10-03 就发现 DoD 写了两份且已经不一致）。
3. **归档要连同"引用修复"一起做**：移走文档后，正文里指向它的引用必须改指新位置——
   **悬空指针比不引用更坏，它让人以为查过了**（`tools/doc_links.py` 就是这条纪律的执行者）。
