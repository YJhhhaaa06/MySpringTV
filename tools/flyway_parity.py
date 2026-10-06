#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""结构连续性（"迁移完成"判据**腿 4**）：活库列集 vs `V1__baseline_tv_schema.sql` 列集。

## 为什么需要它

`flyway.baseline-version=0` + 全量 `CREATE TABLE IF NOT EXISTS` 的组合意味着
**V1 在已有库上是 no-op**。于是 **"Flyway success" 只证明它没报错，不证明两边结构相同**。
而全部集成测试跑在**由 V1 现建的 Testcontainers**上 —— 若 V1 与活库有偏差，
就是"**测一套结构、跑另一套**"，而且不会有任何测试变红。

本脚本把这条腿固化成命令：**差异非空即 `exit != 0`**。

## 它比对什么

| 侧 | 来源 |
|---|---|
| 活库 | `information_schema.TABLES` / `.COLUMNS`（库名取自 `spring.datasource.url` 的 dev 默认） |
| baseline | `V1__baseline_tv_schema.sql` 的 `CREATE TABLE` + 列声明（**先剥 `--` 注释再解析**） |
| 佐证 | 活库 `flyway_schema_history`（V1 未记录 / 失败 ⇒ 结构无依据，判失败） |

`flyway_schema_history` 是 **Flyway 自己建的**，不属 V1 声明，故从"仅活库有"里豁免。

## 用法

    python tools/flyway_parity.py                    # 默认对着 dev 活库
    python tools/flyway_parity.py --v1 target/x.sql  # 换一份 baseline（用于演示"能变红"）
    python tools/flyway_parity.py --host 127.0.0.1 --port 3307 --database other_db
    python tools/flyway_parity.py --exe "C:/path/to/mysql.exe"

连接参数默认**从 `application.yaml` 的 dev 默认值解析**（与运行时同源），
可用 `--host/--port/--user/--password/--database` 或环境变量 `DB_HOST/DB_PORT/DB_USERNAME/DB_PASSWORD/DB_NAME` 覆盖。

## 局限（诚实声明）

- 比的是**表名 / 列名 / 列数**（集合比对），**不比**类型、NULL、默认值、索引、外键、字符集。
  那些需要把 `SHOW CREATE TABLE` 逐字比对（本任务不承诺）。
- V1 解析是**正则启发式**：`CREATE TABLE` 必须写成 `CREATE TABLE [IF NOT EXISTS] \`name\` (`，
  列声明必须是**行首反引号标识符**。换一种写法会读少列 ⇒ 报"活库多列"。
  命中数会打印，**对不上时以人核为准**。
- 已处理的三类误读（均有**实测反例**）：字符串字面量里的 `(`/`)`/`--`（先抹字面量再解析）、
  块注释 `/* */`（V1 正文里真有）、括号不闭合（**记为该表不可信并判失败**，不静默吞）。
  ⚠️ 仍未处理：反引号标识符内含 `'`/`--`、`DELIMITER` 变更、条件 DDL —— 这些在 V1 里不存在。
- 只读：本脚本**不写库、不写磁盘**（`--v1` 除外，那是读）。
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import tvconf  # noqa: E402

# Flyway 自建的元表，不属 V1 声明
FLYWAY_TABLE = "flyway_schema_history"

_RE_CREATE = re.compile(
    r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?(\w+)`?\s*\(", re.IGNORECASE
)
_RE_COLUMN = re.compile(r"^\s*`(\w+)`")
_RE_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.DOTALL)
_NOT_A_COLUMN = ("PRIMARY", "UNIQUE", "KEY", "INDEX", "CONSTRAINT", "FOREIGN", "FULLTEXT", "SPATIAL")


def mask_string_literals(text: str) -> str:
    """把 `'...'` / `"..."` 字面量的内容抹成空格（**长度不变**，便于按位置切片）。

    ⚠️ **不做这一步会读错**（实测）：
    - `COMMENT '见 (注'` 里的 `(` 会让括号计数**永远回不到 0** ⇒ 把下一张表的列**吞进本表**；
    - `DEFAULT 'http://a--b'` 会被 `--` 切成半行 ⇒ **漏列**。
    """
    out = list(text)
    i, n, quote = 0, len(text), ""
    while i < n:
        ch = text[i]
        if quote:
            if ch == "\\":  # MySQL 默认识别反斜杠转义
                out[i] = " "
                if i + 1 < n:
                    out[i + 1] = " "
                i += 2
                continue
            if ch == quote:
                quote = ""
            out[i] = " "
            i += 1
        else:
            if ch in "'\"":
                quote = ch
                out[i] = " "
            i += 1
    return "".join(out)


def strip_sql_comments(text: str) -> str:
    """剥 `--` 行注释与 `/* */` 块注释。**必须先抹字符串字面量再剥**，否则引号里的标记会误伤。

    ⚠️ 两种注释都剥是必要的：V1 头部注释里出现过 `CREATE TABLE` 字样（只剥 `--` 时
    实测把 15 张表数成 17）；V1 正文里还有块注释标记（`/*!50100 WITH PARSER ... */`）。
    """
    masked = mask_string_literals(text)
    masked = _RE_BLOCK_COMMENT.sub(lambda m: "\n" * m.group(0).count("\n"), masked)
    return "\n".join(ln.split("--", 1)[0] for ln in masked.splitlines())


def parse_v1(path: Path) -> tuple[dict[str, list[str]], list[str]]:
    """解析 V1 的 `({表名: [列名]}, 告警列表)`。"""
    text = strip_sql_comments(path.read_text(encoding="utf-8"))
    out: dict[str, list[str]] = {}
    warnings: list[str] = []
    for m in _RE_CREATE.finditer(text):
        table = m.group(1)
        depth, end = 0, -1
        for idx in range(m.end() - 1, len(text)):
            ch = text[idx]
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0:
                    end = idx
                    break
        if end < 0:
            warnings.append(f"表 {table} 的括号未闭合 ⇒ 其列集不可信（解析已放弃该表）")
            continue
        body = text[m.end() - 1 : end]
        cols: list[str] = []
        for line in body.splitlines():
            stripped = line.strip()
            if not stripped or any(stripped.upper().startswith(k) for k in _NOT_A_COLUMN):
                continue
            cm = _RE_COLUMN.match(line)
            if cm:
                cols.append(cm.group(1))
        out[table] = cols
    return out, warnings


def live_schema(conn: tvconf.Conn) -> tuple[dict[str, list[str]], list[list[str]]]:
    """活库 `({表: [列]}, flyway 历史行)`。"""
    tables = [
        r[0]
        for r in tvconf.query(
            conn,
            "SELECT TABLE_NAME FROM information_schema.TABLES "
            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE' "
            "ORDER BY TABLE_NAME;",
        )
    ]
    cols: dict[str, list[str]] = {t: [] for t in tables}
    for table, column in tvconf.query(
        conn,
        "SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS "
        "WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME, ORDINAL_POSITION;",
    ):
        cols.setdefault(table, []).append(column)
    history = tvconf.query(
        conn,
        f"SELECT installed_rank, version, description, type, success, installed_on "
        f"FROM {FLYWAY_TABLE} ORDER BY installed_rank;",
    )
    return cols, history


def report(v1_path: Path, conn: tvconf.Conn) -> int:
    baseline, parse_warnings = parse_v1(v1_path)
    live, history = live_schema(conn)

    print("=" * 78)
    print(f"结构连续性 —— 活库 {conn.database} @ {conn.host}:{conn.port}  vs  {v1_path.name}")
    print("=" * 78)
    print(f"\nV1 baseline：{len(baseline)} 张表 / {sum(len(c) for c in baseline.values())} 列")
    print(f"活库      ：{len([t for t in live if t != FLYWAY_TABLE])} 张业务表 "
          f"/ {sum(len(c) for t, c in live.items() if t != FLYWAY_TABLE)} 列")

    live_biz = {t: c for t, c in live.items() if t != FLYWAY_TABLE}
    problems: list[str] = list(parse_warnings)

    only_v1 = sorted(set(baseline) - set(live_biz))
    only_live = sorted(set(live_biz) - set(baseline))

    print("\n【逐表列集比对】\n")
    for table in sorted(set(baseline) | set(live_biz)):
        if table in only_v1:
            problems.append(f"表仅 V1 有、活库没有：{table}")
            print(f"  ✗ {table:<20} 仅 V1 有（活库缺整张表）")
            continue
        if table in only_live:
            problems.append(f"表仅活库有、V1 没有：{table}（未进 baseline ⇒ 测试库上不存在）")
            print(f"  ✗ {table:<20} 仅活库有（V1 未声明 ⇒ 测试库上不存在）")
            continue
        b, l = baseline[table], live_biz[table]
        miss, extra = sorted(set(b) - set(l)), sorted(set(l) - set(b))
        if miss or extra:
            detail = []
            if miss:
                detail.append(f"活库缺列 {miss}")
            if extra:
                detail.append(f"V1 缺列 {extra}")
            problems.append(f"{table}：列集不一致（{'；'.join(detail)}）")
            print(f"  ✗ {table:<20} {len(b)} vs {len(l)} 列  {'；'.join(detail)}")
        else:
            print(f"  ✓ {table:<20} {len(b)} 列一致")

    print("\n【活库 Flyway 历史】（V1 的「是否记录」是结构比对的前提）\n")
    if not history:
        if live_biz:
            problems.append("flyway_schema_history 为空（库非空 ⇒ 结构无 V1 依据）")
            print("  ✗ 无历史行（库非空 ⇒ 结构无 V1 依据）")
        else:
            print("  · 无历史行（库为空，跳过）")
    else:
        for row in history:
            rank, version, desc, type_, success = row[:5]
            ok = success == "1"
            print(f"  {'✓' if ok else '✗'} rank={rank:<2} v{version:<3} {type_:<9} {desc} success={success}")
            if not ok:
                problems.append(f"Flyway 历史有失败行：rank={rank} v{version}")
        if not any(r[3] == "SQL" for r in history):
            problems.append("flyway_schema_history 无 SQL 迁移行 ⇒ V1 未曾真正执行")

    print("\n" + "=" * 78)
    if problems:
        print(f"结论：结构**不一致**，{len(problems)} 条差异（exit != 0）\n")
        for p in problems:
            print(f"  · {p}")
        print("\n" + "=" * 78)
        return 1
    print("结论：活库与 V1 **列集一致**（表名/列名/列数）——腿 4 成立。")
    print("注：这是正则启发式且只比「名/数」（见文件头「局限」），不是逐字 DDL 比对。")
    print("=" * 78)
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="复算结构连续性：活库列集 vs V1 baseline 列集（判据腿 4）。",
        epilog="差异非空即退出码非 0。连接参数默认取自 application.yaml 的 dev 默认值。",
    )
    parser.add_argument("--v1", type=Path, default=tvconf.V1_SQL, help="baseline SQL 路径（默认仓内 V1）")
    parser.add_argument("--exe", default="", help="mysql 客户端路径（默认自动探测）")
    parser.add_argument("--host", default="", help="库主机（覆盖 application.yaml）")
    parser.add_argument("--port", type=int, default=0, help="库端口")
    parser.add_argument("--user", default="", help="库用户")
    parser.add_argument("--password", default="", help="库口令")
    parser.add_argument("--database", default="", help="库名")
    args = parser.parse_args(argv)

    if not args.v1.is_file():
        print(f"✗ baseline SQL 不存在：{args.v1}", file=sys.stderr)
        return 2
    conn = tvconf.conn_from_yaml(args.exe)
    for field in ("host", "user", "password", "database"):
        if getattr(args, field):
            setattr(conn, field, getattr(args, field))
    if args.port:
        conn.port = args.port

    try:
        return report(args.v1, conn)
    except RuntimeError as exc:
        print(f"✗ 连库/查询失败：{exc}", file=sys.stderr)
        print("  提示：确认 dev 活库(spring_tv)在跑、mysql 客户端可执行（--exe 可指定）。", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
