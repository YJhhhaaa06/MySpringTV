#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验 `src/` 里**代码注释的文档引用**能不能指到 —— 只读门禁，退出码 0/1。

## 为什么需要它（与 `doc_links.py` 的分工）

`doc_links.py` 只扫 `.docs/**`（**不扫 `src/`**），且只认 5 种形态。实测代价：
2026-10-15 收藏专项收口，把四份设计文档从 `task/` 挪进 `archive/favorite-2026-10-15/` 时，
`src/` 里**约 180 处**注释引用（含 `需求篇` / `设计篇` / `骨架 §x` 这类**别名**）**全部在它的检测面外**，
只能靠手工 grep（`.docs/INDEX.md` §四 那条）—— 那正是"**静默断链**"的温床。

本工具把那条手工 grep 升级为**会失败的命令**（本仓的"可复算"哲学）。

## 检查什么（扫 `src/`，**跳过 `db/migration/**`**）

1. **`.md` 引用可解析**：注释里出现的文档名（`xxx.md`）必须能指到**真实文件** ——
   带路径的按路径解析；只有基名的按**基名**在 `.docs/` 全树与仓库根查找。
2. **别名黑名单**：`需求篇` / `设计篇` / `分期篇` / `三期设计` / `骨架 §` ——
   别名**不在任何文件名里**，工具天然测不到 ⇒ 一律**禁入代码**（见 `.docs/INDEX.md` **§五.5③**）。

⚠️ `src/main/resources/db/migration/**` 里的 **Flyway 迁移逐字节冻结**（checksum 覆盖全文，**改不了**）
⇒ 那里的旧路径由**归档 README** 承接重定向（`.docs/INDEX.md` **§五.3**）⇒ 本工具**跳过**该目录。

## 用法

    python tools/doc_refs.py             # 列出违规（0 处则退出码 0）
    python tools/doc_refs.py --verbose   # 附行号

退出码：0 = 无违规；1 = 有违规。
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# ── Windows 中文环境必备：强制 stdout/stderr 用 UTF-8（见 tools/README.md §3.1）
for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src"
SKIP_PARTS = {"migration"}  # src/main/resources/db/migration/** —— 冻结，跳过
SCAN_SUFFIXES = {".java", ".xml", ".yaml", ".yml", ".js", ".json", ".properties", ".html", ".sql"}
MD_TOKEN = re.compile(r"(?<![\w./-])([\w./-]+\.md)")
# 别名黑名单：这些词**不是文件名**，任何工具都测不到 ⇒ 写进代码即静默断链
ALIASES = re.compile(r"需求篇|设计篇|分期篇|三期设计|骨架\s*§")
PLACEHOLDER = re.compile(r"(xxx|Xxx|\.\.\.|<[^>]*>)")


def md_index() -> tuple[dict[str, str], set[str]]:
    """返回 (`基名 → 相对路径`（**优先活文档**、归档副本垫底）, 相对路径集)。
    搜索面 = 仓库根 + `.docs/` 全树。"""
    by_name: dict[str, str] = {}
    rels: set[str] = set()
    for r in (ROOT, ROOT / ".docs"):
        if not r.is_dir():
            continue
        for p in sorted(r.rglob("*.md")):
            if ".git" in p.parts:
                continue
            try:
                rel = p.relative_to(ROOT).as_posix()
            except ValueError:
                continue
            rels.add(rel)
            cur = by_name.get(p.name)
            if cur is None or (cur.startswith(".docs/archive/") and not rel.startswith(".docs/archive/")):
                by_name[p.name] = rel  # 活文档覆盖归档副本
    return by_name, rels


def resolve(name: str, by_name: dict[str, str], rels: set[str]) -> str | None:
    if name.startswith("./"):  # ⚠️ 别用 lstrip("./")：它按字符集剥，会把 `.docs/x.md` 削成 `docs/x.md`
        name = name[2:]
    if "/" in name:  # 带路径：按仓库根 / `.docs` 根两条解析
        if name in rels:
            return name
        return f".docs/{name}" if f".docs/{name}" in rels else None
    return by_name.get(name)  # 只有基名：全树查（优先活文档）


OK, DANGLING, ARCHIVE_ONLY = "ok", "dangling", "archive-only"


def pick(raw: str, by_name: dict[str, str], rels: set[str]) -> tuple[str, str]:
    """中文不做分词 ⇒ 正则常把前面的汉字粘进来（`数字见favorite.md`）。
    从长到短试点后缀，取**第一个能指到的**。
    ★ **裸文件名（无路径）却只解析到 `archive/` 副本** ⇒ 判违规 —— 归档是**冻结历史**，
    代码必须写**显式路径**或改指**活文档**（INDEX §五.5②）。"""
    if PLACEHOLDER.search(raw):
        return raw, OK
    for i in range(len(raw)):
        cand = raw[i:]
        hit = resolve(cand, by_name, rels)
        if hit is None:
            continue
        if "/" not in cand and hit.startswith(".docs/archive/"):
            return cand, ARCHIVE_ONLY
        return cand, OK
    return raw, DANGLING


def scan() -> list[tuple[Path, int, str, str]]:
    by_name, rels = md_index()
    bad: list[tuple[Path, int, str, str]] = []
    for p in sorted(SRC.rglob("*")):
        if not p.is_file() or p.suffix.lower() not in SCAN_SUFFIXES:
            continue
        if SKIP_PARTS & set(p.parts):  # 命中 db/migration ⇒ 冻结（checksum 改不了），跳过
            continue
        for i, line in enumerate(p.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
            for m in MD_TOKEN.finditer(line):
                raw = m.group(1)
                if raw.startswith(("http", "www")):
                    continue
                ref, status = pick(raw, by_name, rels)
                if status == DANGLING:
                    bad.append((p, i, ref, "文档引用指不到（改名 / 挪家 / 删了？）"))
                elif status == ARCHIVE_ONLY:
                    bad.append((p, i, ref, "裸文件名只解析到 archive 冻结副本 ⇒ 改指活文档，或写显式 `archive/` 路径（§五.5②）"))
            if a := ALIASES.search(line):
                bad.append((p, i, a.group(0), "别名禁入代码（INDEX §五.5③）"))
    return bad


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="校验 src/ 的文档引用与别名（只读门禁）")
    parser.add_argument("--verbose", "-v", action="store_true", help="附行号")
    args = parser.parse_args(argv)

    bad = scan()
    if not bad:
        print("=" * 64)
        print("✅ src/ 的文档引用全部指得到，且无别名（INDEX §五.5 四条不变式成立）")
        print("=" * 64)
        return 0

    print("=" * 64)
    print("src/ 代码引用违规")
    print("=" * 64)
    seen: set[tuple[str, str]] = set()
    for path, lineno, ref, why in bad:
        key = (str(path.relative_to(ROOT)), ref)
        if key in seen:
            continue
        seen.add(key)
        loc = f"  L{lineno:<5}" if args.verbose else "        "
        print(f"\n■ {path.relative_to(ROOT)}")
        print(f"{loc} ❌ {ref}   ← {why}")
    print("\n" + "=" * 64)
    print(f"共 {len(seen)} 处（去重后）。按 INDEX §五.5「文档引用与挪家纪律」处理。")
    print("=" * 64)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
