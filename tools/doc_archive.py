#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把已完成阶段的历史明细从正文抽到 `.docs/archive/`，正文只留指针。

## 为什么需要它

SOP §七.2 的纪律是"**正文只留当前有效结论**，被推翻/已兑现的历史交给 git 或 archive"。
但手工搬 1,400 行是高风险动作（容易漏一段、错位一行），所以做成工具。

实测背景：`.docs/事务边界决策表.md` 1,637 行里 **1,491 行（91%）是已完成切片的明细**
（U/C/CM/L/F/G 六个系列），而它们的价值已经被
「代码 + 测试 + 《决策留痕表》+《遗留台账》+《端点对照表》」吸收掉了。
正文真正还需要留的只有：硬约束、图例、模式库、状态表 —— 约 150 行。

## 用法

先写一份"手术计划"（JSON 数组），再预演，最后执行：

    python tools/doc_archive.py --plan temp-script/doc-plan.json            # 预演（默认，不落盘）
    python tools/doc_archive.py --plan temp-script/doc-plan.json --apply    # 真正落盘

计划里每个操作二选一：

```json
[
  {
    "op": "extract",
    "from": ".docs/事务边界决策表.md",
    "match": "^二[、·]",
    "to": ".docs/archive/事务边界明细-S1-S5.md",
    "archive_title": "事务边界明细（S1–S5 已完成切片）",
    "archive_note": "从《事务边界决策表》正文抽出……",
    "pointer": "> 各已完成切片的**逐条明细**（U/C/CM/L/F/G 六个系列）已归档："
  },
  {
    "op": "move",
    "from": ".docs/切片计划.md",
    "to": ".docs/archive/切片计划-第一批.md"
  }
]
```

- `extract`：把正文里**标题匹配 `match`（正则）的章节**搬进归档文件，
  并在原位置留一行 `pointer` 指过去。
  默认只抽 **H2**（S6 起的既有口径）；`levels` 可指定标题级别，例如 `[3]` 抽 H3、
  `[2, 3]` 两者都抽。一个章节在**下一个同级或更高级**标题处结束——所以抽 H3 时
  不会把后面的 H2 一起吞掉（`切片计划` 的工单是 H3，就是为此加的）。
- `move`：整份文件移到归档目录。

## 安全设计

- 默认 `--dry-run` 语义：不加 `--apply` 就**只打印不落盘**。
- `extract` 会校验"抽走 + 留下 == 原文总行数"，对不上就中止，不写文件。
- 目标归档文件已存在时**拒绝覆盖**（除非 `--force`），避免二次运行把归档冲掉。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

# ── Windows 中文环境必备：强制 stdout/stderr 用 UTF-8（见 tools/README.md §3.1）
for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent
HEADING_RE = re.compile(r"^(#{1,6})\s+(.*)$")


def find_sections(
    lines: list[str], pattern: re.Pattern[str], levels: set[int]
) -> list[tuple[int, int]]:
    """返回匹配的章节的 [start, end) 行区间（0-based，end 不含）。

    `levels` 是要匹配的标题级别（2=H2，3=H3…）。一个章节在**下一个同级或更高级**
    标题处结束，所以抽 H3 时不会把后面的 H2 一起吞掉。

    ⚠️ `pattern` 作用于**标题文字**（`##` 前缀已剥掉），所以计划里写 `^二[、·]` 是对的；
    早期实现误把它作用于带前缀的整行，导致锚定 `^` 的正则永远匹配不上（2026-10-03 修正）。
    """
    headings: list[tuple[int, int, str]] = []  # (行号, 级别, 标题文字)
    for i, line in enumerate(lines):
        if m := HEADING_RE.match(line):
            headings.append((i, len(m.group(1)), m.group(2)))

    starts = [i for i, lvl, text in headings if lvl in levels and pattern.search(text)]
    if not starts:
        return []

    ranges: list[tuple[int, int]] = []
    for s in starts:
        s_level = next(lvl for i, lvl, _ in headings if i == s)
        nxt = [i for i, lvl, _ in headings if i > s and lvl <= s_level]
        e = nxt[0] if nxt else len(lines)
        ranges.append((s, e))

    # 合并相邻区间（`^二[、·]` 会命中连续多节）
    merged: list[tuple[int, int]] = []
    for s, e in ranges:
        if merged and s <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(merged[-1][1], e))
        else:
            merged.append((s, e))
    return merged


def do_extract(op: dict, apply: bool, force: bool) -> bool:
    src = (ROOT / op["from"]).resolve()
    dst = (ROOT / op["to"]).resolve()
    pattern = re.compile(op["match"])
    levels = {int(x) for x in op.get("levels", [2])}  # 默认只抽 H2（S6 起的既有口径）

    if not src.is_file():
        print(f"  ✗ 源文件不存在：{op['from']}")
        return False
    if dst.exists() and not force:
        print(f"  ✗ 归档目标已存在（拒绝覆盖，用 --force 强行为之）：{op['to']}")
        return False

    lines = src.read_text(encoding="utf-8").splitlines(keepends=True)
    ranges = find_sections([ln.rstrip("\n") for ln in lines], pattern, levels)
    if not ranges:
        print(f"  ✗ 没有 H{levels} 标题匹配 /{op['match']}/：{op['from']}")
        return False

    extracted: list[str] = []
    for s, e in ranges:
        extracted.extend(lines[s:e])

    # 从后往前删，避免行号漂移
    remaining = list(lines)
    for s, e in reversed(ranges):
        del remaining[s:e]

    # 在第一个被抽走的位置插指针
    pointer = op.get("pointer")
    if pointer:
        insert_at = ranges[0][0]
        remaining.insert(insert_at, pointer.rstrip("\n") + "\n\n")

    # 安全校验：抽走的 + 留下的 + 指针 == 原文
    expected = len(lines) + (1 if pointer else 0)
    actual = len(extracted) + len(remaining)
    if expected != actual:
        print(f"  ✗ 行数校验失败：原文 {len(lines)}，抽出 {len(extracted)} + 留下 {len(remaining)} = {actual}")
        return False

    moved_titles = [
        HEADING_RE.match(lines[s]).group(2).strip() for s, _ in ranges
    ]
    print(f"  抽出 {len(ranges)} 个章节 / {len(extracted)} 行 → {op['to']}")
    for t in moved_titles:
        print(f"      · {t}")
    print(f"  正文剩余 {len(remaining)} 行（原 {len(lines)}）")

    if not apply:
        return True

    dst.parent.mkdir(parents=True, exist_ok=True)
    header = [
        f"# {op.get('archive_title', '归档明细')}\n",
        "\n",
        f"> 归档时间：{op.get('archived_at', '')}\n",
        "> **这是历史存档，不是当前事实源。** 它记录的是当时的论证过程，\n",
        "> 其中的状态/计数**可能已经过期**，不要据此判断当前进度。\n",
        "> 当前结论看：《决策留痕表.md》《遗留台账.md》《端点对照表.md》。\n",
    ]
    if note := op.get("archive_note"):
        header.append(f">\n> {note}\n")
    header.append("\n---\n\n")

    dst.write_text("".join(header) + "".join(extracted), encoding="utf-8")
    src.write_text("".join(remaining), encoding="utf-8")
    print(f"  ✓ 已写入 {op['to']}（{len(extracted)} 行）与 {op['from']}（{len(remaining)} 行）")
    return True


def do_move(op: dict, apply: bool, force: bool) -> bool:
    src = (ROOT / op["from"]).resolve()
    dst = (ROOT / op["to"]).resolve()
    if not src.is_file():
        print(f"  ✗ 源文件不存在：{op['from']}")
        return False
    if dst.exists() and not force:
        print(f"  ✗ 目标已存在（拒绝覆盖）：{op['to']}")
        return False

    size = src.stat().st_size
    print(f"  移动 {op['from']} → {op['to']}  ({size / 1024:.1f} KB)")
    if not apply:
        return True

    dst.parent.mkdir(parents=True, exist_ok=True)
    src.replace(dst)
    print("  ✓ 已移动")
    return True


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="把已完成阶段的历史明细抽到 archive，正文留指针。"
    )
    parser.add_argument("--plan", required=True, help="手术计划 JSON 路径")
    parser.add_argument(
        "--apply",
        action="store_true",
        help="真正落盘。**不加此开关只预演**（默认行为）。",
    )
    parser.add_argument("--force", action="store_true", help="允许覆盖已存在的目标文件")
    args = parser.parse_args(argv)

    plan_path = Path(args.plan)
    if not plan_path.is_absolute():
        plan_path = ROOT / plan_path
    if not plan_path.is_file():
        print(f"计划文件不存在：{plan_path}", file=sys.stderr)
        return 2

    ops = json.loads(plan_path.read_text(encoding="utf-8"))
    mode = "执行（落盘）" if args.apply else "预演（不落盘）"
    print("=" * 74)
    print(f"文档归档 —— {mode}    共 {len(ops)} 个操作")
    print("=" * 74)

    ok = True
    for i, op in enumerate(ops, 1):
        print(f"\n[{i}/{len(ops)}] {op['op']}: {op['from']}")
        kind = op.get("op")
        if kind == "extract":
            ok &= do_extract(op, args.apply, args.force)
        elif kind == "move":
            ok &= do_move(op, args.apply, args.force)
        else:
            print(f"  ✗ 未知操作：{kind}")
            ok = False

    print("\n" + "=" * 74)
    print("全部操作预演通过" if (ok and not args.apply) else ("全部完成" if ok else "有操作失败"))
    print("=" * 74)
    if ok and not args.apply:
        print("加 --apply 才会真正落盘。")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
