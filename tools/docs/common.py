#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""`tools/docs/` 三个门禁脚本的公共基建（2026-10-18 立）。

历史上这三个脚本平铺在 `tools/` 下，各自抄一遍"仓库根 = `__file__.parent.parent`"与
"强制 stdout UTF-8"两段样板 —— 迁进 `tools/docs/` 时层级变了，两段样板**会同时抄错**
（`parent.parent` 变成 `tools/`，于是 `.docs` 找不到、脚本静默扫 0 份却报"✅ 全绿"）。
故把这两件事收到一处，脚本只 import。

本模块**只做两件事**，不承担任何检查逻辑：

1. `ROOT` —— 仓库根，**向上找 `pom.xml`**。不用 `parent.parent`：脚本在子目录里，
   以后若再挪一次目录，向上找的写法不用改（迁移期归档脚本就是为此改成这个写法的）；
2. `setup_stdio()` —— 强制 stdout/stderr 走 UTF-8（`tools/README.md` §3.1 的硬性约定）。

⚠️ 为什么**不**把三个门禁的公共部分再往上抽（比如"扫描 `.md` 语料"）：它们的判定口径差异很大
（`doc_links` 管 `.docs` 交叉引用、`doc_refs` 管 `src/` 注释、`doc_stats` 只度量不判定），
硬抽公共层会把口径搅在一起 —— 本仓宁可留一点重复，也不让"口径"变成推断出来的东西。
"""

from __future__ import annotations

import sys
from pathlib import Path


def _find_repo_root() -> Path:
    """从本文件所在目录逐级向上，返回第一份 `pom.xml` 的所在目录。

    找不到就**响亮失败**（不猜、不兜底到 cwd）：这三个脚本一旦跑在错误的"仓库根"上，
    后果是"扫 0 份文档 ⇒ 报全绿"，比直接崩掉危险得多。
    """
    here = Path(__file__).resolve()
    for parent in here.parents:
        if (parent / "pom.xml").is_file():
            return parent
    raise RuntimeError(f"找不到 pom.xml（从 {here} 逐级向上），本脚本必须在 MySpringTV 仓库内运行")


ROOT = _find_repo_root()


def setup_stdio() -> None:
    """强制 stdout/stderr 用 UTF-8（Windows 默认 GBK ⇒ 中文输出乱码 / emoji 直接崩）。

    由各脚本在 import 之后**显式调用一次**（不做 import 期副作用：谁需要谁调用，
    grep `setup_stdio` 就能看全"哪些脚本遵守了这条约定"）。
    """
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
