#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""文档膨胀度量 —— 按文件与按章节报告 .docs 的体量，让膨胀**可归因**。

## 为什么需要它

S6 收口时实测：`.docs` 12 份文档 / 3,900+ 行 / 257 KB，其中
《事务边界决策表.md》单文件 **1,637 行 / 111 KB —— 占全部文档的 43%**。

"文档膨胀"这句话本身不可行动。可行动的是"**哪一份的哪一节**该处理"。
本脚本把体量拆到 H2 章节一级，于是结论能落到具体位置。

## 用法

    python tools/doc_stats.py                 # 全量报告
    python tools/doc_stats.py --top 6         # 只对最大的 6 份做章节拆解
    python tools/doc_stats.py --min-kb 8      # 只列 >= 8KB 的文件

## 判据（与 SOP §七 文档纪律配套）

- **单文件 > 40 KB** ：该考虑拆分或归档。文档超过这个体量后，
  人（和 LLM）都很难在上下文里装下，只能靠 grep 找片段，离"可检索的事实源"越来越远。
- **已完成阶段的历史明细** ：其价值已被代码 + 测试 + 结论表吸收，
  应移入 `.docs/archive/`，正文只留**当前有效结论**（SOP §七.2）。
- **单章节 > 300 行** ：通常意味着它其实是另一份文档。

脚本**只度量、不判定**——判定要结合内容，见 `tools/README.md` 的口径说明。
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

# ── Windows 中文环境必备：强制 stdout/stderr 用 UTF-8 ────────────────────────
# Windows 的默认控制台编码是 GBK(cp936)。本项目文档与输出全是中文，
# 不强制 UTF-8 会得到**乱码**，遇到 emoji/制表符还会直接 UnicodeEncodeError 崩溃。
# 这是本项目所有 Python 脚本的**统一约定**（见 tools/README.md）。
for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")

# 只统计这些后缀
GLOB = "*.md"

# 章节标题：## / ### （## 是主要归因粒度）
HEADING_RE = re.compile(r"^(#{1,6})\s+(.*)$")

# 判据阈值（KB / 行），与文档里的说明保持一致
BIG_FILE_KB = 40.0
BIG_SECTION_LINES = 300


@dataclass
class Section:
    """一个 H2 章节及其行数。"""

    title: str
    start: int
    lines: int = 0
    subsections: list["Section"] = field(default_factory=list)

    @property
    def display(self) -> str:
        return self.title.strip() or "(无标题)"


@dataclass
class Doc:
    """一份文档的度量结果。"""

    path: Path
    rel: str
    lines: int
    size_kb: float
    sections: list[Section]

    @property
    def biggest_section(self) -> Section | None:
        return max(self.sections, key=lambda s: s.lines, default=None)


def measure(path: Path, root: Path) -> Doc:
    """读取一份 markdown，统计总行数与 H2 章节行数。"""
    text = path.read_text(encoding="utf-8", errors="replace")
    lines = text.splitlines()
    total = len(lines)

    # 先找出所有 H2 的起始行号
    h2_starts: list[tuple[int, str]] = []
    for idx, line in enumerate(lines):
        m = HEADING_RE.match(line)
        if m and len(m.group(1)) == 2:
            h2_starts.append((idx, m.group(2)))

    sections: list[Section] = []
    for i, (start, title) in enumerate(h2_starts):
        end = h2_starts[i + 1][0] if i + 1 < len(h2_starts) else total
        sec = Section(title=title, start=start + 1, lines=end - start)

        # 再拆 H3 子节，便于看清"这一节里哪块最肥"
        h3_starts: list[tuple[int, str]] = []
        for idx in range(start + 1, end):
            m = HEADING_RE.match(lines[idx])
            if m and len(m.group(1)) == 3:
                h3_starts.append((idx, m.group(2)))
        for j, (s3, t3) in enumerate(h3_starts):
            e3 = h3_starts[j + 1][0] if j + 1 < len(h3_starts) else end
            sec.subsections.append(Section(title=t3, start=s3 + 1, lines=e3 - s3))

        sections.append(sec)

    # 若有内容在第一个 H2 之前（前言），也算一段
    if h2_starts and h2_starts[0][0] > 0:
        sections.insert(0, Section(title="(前言/标题)", start=1, lines=h2_starts[0][0]))
    elif not h2_starts:
        sections.append(Section(title="(无 H2 章节)", start=1, lines=total))

    return Doc(
        path=path,
        rel=str(path.relative_to(root)).replace("\\", "/"),
        lines=total,
        size_kb=len(text.encode("utf-8")) / 1024,
        sections=sections,
    )


def collect(docs_dir: Path, root: Path) -> list[Doc]:
    files = sorted(docs_dir.rglob(GLOB))
    return [measure(p, root) for p in files]


def report(docs: list[Doc], top: int, min_kb: float) -> int:
    total_lines = sum(d.lines for d in docs)
    total_kb = sum(d.size_kb for d in docs)

    # ★ 关键区分：**正文** vs **归档**
    # 归档（.docs/archive/）是有意保留的历史，体量大不是问题；
    # 真正要盯的是"正文"——那才是新窗口要读、要装进上下文的部分。
    live = [d for d in docs if not d.rel.startswith(".docs/archive/")]
    archived = [d for d in docs if d.rel.startswith(".docs/archive/")]
    live_lines = sum(d.lines for d in live)
    arch_lines = sum(d.lines for d in archived)

    print("=" * 78)
    print(f"文档总量：{len(docs)} 份 / {total_lines:,} 行 / {total_kb:,.1f} KB")
    print("-" * 78)
    print(f"  ├─ 正文   ：{len(live):>2} 份 / {live_lines:,} 行   ← **要盯的是这个**")
    print(f"  └─ 归档   ：{len(archived):>2} 份 / {arch_lines:,} 行   （有意保留的历史，体量大不是问题）")
    print("=" * 78)

    shown = [d for d in docs if d.size_kb >= min_kb]
    shown.sort(key=lambda d: d.size_kb, reverse=True)

    print(f"\n【按体量排序】（仅列 >= {min_kb} KB）\n")
    print(f"{'行数':>6}  {'KB':>7}  {'类型':<6}  判据        路径")
    print("-" * 78)
    for d in shown:
        kind = "归档" if d.rel.startswith(".docs/archive/") else "正文"
        if d.size_kb >= BIG_FILE_KB:
            flag = "★ 偏大" if kind == "正文" else "（归档）"
        else:
            flag = "ok"
        print(f"{d.lines:>6}  {d.size_kb:>7.1f}  {kind:<6}  {flag:<10}  {d.rel}")

    if top > 0 and shown:
        print(f"\n\n{'=' * 78}")
        print(f"【章节拆解】最大的 {min(top, len(shown))} 份")
        print("=" * 78)
        for d in shown[:top]:
            print(f"\n■ {d.rel}    {d.lines} 行 / {d.size_kb:.1f} KB")
            for sec in sorted(d.sections, key=lambda s: s.lines, reverse=True):
                bar = "█" * max(1, int(sec.lines / max(1, d.lines) * 30))
                mark = "  ← 超阈值" if sec.lines > BIG_SECTION_LINES else ""
                print(f"   {sec.lines:>5} 行  {bar:<31} {sec.display}{mark}")
                if sec.lines > BIG_SECTION_LINES:
                    for sub in sorted(sec.subsections, key=lambda s: s.lines, reverse=True)[:4]:
                        print(f"            └ {sub.lines:>5} 行  {sub.display}")

    # 汇总提示
    over = [d for d in docs if d.size_kb >= BIG_FILE_KB]
    big_secs = [
        (d.rel, s)
        for d in docs
        for s in d.sections
        if s.lines > BIG_SECTION_LINES
    ]
    print(f"\n\n{'=' * 78}")
    print("【汇总提示】")
    print(f"  超 {BIG_FILE_KB:.0f} KB 的文件：{len(over)} 份")
    for d in over:
        print(f"    - {d.rel}  {d.size_kb:.1f} KB")
    print(f"  超 {BIG_SECTION_LINES} 行的章节：{len(big_secs)} 个")
    for rel, s in big_secs:
        print(f"    - {rel}  §{s.display}  {s.lines} 行")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="度量 .docs 的文档体量，按文件与章节报告，让膨胀可归因。"
    )
    parser.add_argument(
        "--docs",
        default=None,
        help="文档目录，默认 <仓库根>/.docs",
    )
    parser.add_argument(
        "--top",
        type=int,
        default=5,
        help="对最大的 N 份做章节拆解（默认 5；0 = 不拆解）",
    )
    parser.add_argument(
        "--min-kb",
        type=float,
        default=0.0,
        help="只列体量 >= 该值的文件（KB，默认全部）",
    )
    args = parser.parse_args(argv)

    root = Path(__file__).resolve().parent.parent
    docs_dir = Path(args.docs) if args.docs else root / ".docs"
    if not docs_dir.is_absolute():
        docs_dir = (root / docs_dir).resolve()

    if not docs_dir.is_dir():
        print(f"目录不存在：{docs_dir}", file=sys.stderr)
        return 2

    docs = collect(docs_dir, root)
    if not docs:
        print(f"未找到 {GLOB}：{docs_dir}", file=sys.stderr)
        return 2

    return report(docs, args.top, args.min_kb)


if __name__ == "__main__":
    raise SystemExit(main())
