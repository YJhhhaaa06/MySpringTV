#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验 `.docs` 里的**交叉引用能不能指到**——只读工具，不修任何文件。

## 为什么需要它

本仓的纪律是「归档要连同引用修复一起做」（INDEX §五.3），但实测这类问题**反复出现**：

- 2026-10-03 归档 §二·X / §四·S7-S9 后，正文留下 **18 处悬空指针**（照文档去查会落空）；
- 同一天发现 5 处 `《TV-Spring迁移选型决策记录.md》` 少了 `report/` 前缀；
- 还有 1 处**改名遗留**：`《迁移工作流-复盘与修订建议.md》` 早已改名为 `archive/复盘-S1至S3.md`。

**悬空指针比不引用更坏——它让人以为查过了。** 所以做成一条可复算的命令（`.docs/INDEX.md` §四、§五.3）。

## 检查什么

1. `《xxx.md》` 形态的文档引用；
2. `report/` / `archive/` / `.docs/` 形态的相对路径引用；
3. 正文里指向**已归档章节**的裸引用（`§二·X` / `§四·SX`；同一段落里出现过 `archive/` 路径即视为可指到）；
4. **markdown 链接** `[文字](x.md)`（按**引用方所在目录**相对解析）；
5. 仓库路径：`tools/` `src/` `.mvn/` 下的 `.py/.java/.xml/.yaml/.cmd/.sh` —— ★ **不要求反引号包裹**
   （`python tools/xxx.py` 这种最常见的形态也必须抓到，见下方 `check()` 里的历史注）。

支持 `*` 通配（如 `archive/migration/事务边界明细-*.md`）。**`*` 只在单个路径段内生效**，不跨目录。

**解析面 = `.docs/` 全树**：引用能在 `.docs/` 下**按路径段尾部对齐**找到文件就算指得到 ——
**没有"搜索目录清单"**，新建归档目录不用改脚本（见"判定口径"最后一条）。

## 判定口径（降噪设计，**改之前先读**）

第一版把所有命中都报出来，结果 **41 处里大半是误报**，工具会变成噪音。故：

- **跳过 `.docs/archive/`**：归档是**当时**的记录，里面提到旧章节名是**正确的历史**，不是悬空；
- **章节引用按"段落"判定**，不按行：引用与 `archive/` 路径常被表格列、续行拆开
  （`见 archive/xxx.md 的 S4 小节（§二·E）` 会跨行）。同一段落里出现过 `archive/` 即视为可指到；
- **允许占位名**：含 `xxx` / `Xxx` / `...` 的路径是文档里的示例模板（如 `<模块>`），不报；
- **区分"不存在"与"尚未创建"**：脚注写 ⏳，提示"若这是待创建的文件，可忽略"。
- ★ **解析面 = 全树索引 + 按段尾部对齐**（`_suffix_match`），**不是硬编码目录清单**：
  目录清单每加一个归档目录就要手工同步，漏一次就把"搬家"误报成"悬空"。
  支持的两种省略：**只写基名**（`《决策留痕表.md》`）、**省略前导目录**（`report/x.md` 实际在
  `archive/migration/report/x.md`）。**刻意不支持**：省略**中间**目录（`archive/x.md` 不匹配
  `archive/migration/x.md`）与 `*` 跨目录 —— 二者都会把"目录写错"变成"看起来对"。
  ⚠️ 它只回答"这个路径在 `.docs` 里指得到吗"，**不保证是读者想指的那一份**（同名文件请写全路径）。
- **只看本仓 `.docs`**：不对 `old-project/` 做任何兜底解析（迁移期那三份调研报告已归档，
  归档目录本就不扫 ⇒ 兜底不可达，却让门禁依赖老项目目录是否存在）。
- ★ **零样本必须报错**（`main()`）：扫到 0 份正文时以退出码 `2` 失败 ——
  "扫 0 份 ⇒ 报全绿"是这类门禁最危险假绿（多半是仓库根或目录解析错了）。

## 用法

    python tools/docs/doc_links.py            # 去重后的悬空引用
    python tools/docs/doc_links.py --verbose  # 附行号
    python tools/docs/doc_links.py --strict   # 把 ⏳（尚未创建）也算失败

退出码：0 = 无悬空引用；1 = 存在悬空引用；2 = 没扫到任何正文文档（路径/仓库根不对，不出"假绿"）。
"""

from __future__ import annotations

import argparse
import re
import sys
from fnmatch import fnmatch
from pathlib import Path

if __package__:  # `python -m tools.docs.doc_links`
    from .common import ROOT, setup_stdio
else:  # `python tools/docs/doc_links.py`（文档化用法：脚本目录自动进 sys.path）
    from common import ROOT, setup_stdio

setup_stdio()  # tools/README.md §3.1：中文输出必须强制 UTF-8

DOCS = ROOT / ".docs"
PLACEHOLDER = re.compile(r"(xxx|Xxx|\.\.\.|<[^>]*>)")
SECTION_REF = re.compile(r"《[^》]+》§[二三四五][·、][A-Za-z0-9]+|(?<![\w.])§[二四][·、][A-Za-z0-9]+")
ARCHIVE_MENTION = re.compile(r"archive/[^\s`）)、]+\.md")

_CORPUS: list[str] | None = None


def corpus() -> list[str]:
    """`.docs/` 下全部 `.md` 的相对路径（posix 形态，惰性建一次）。

    ★ **这份索引就是"解析面"本身** —— 没有目录清单，所以新建
    `archive/<域>-<归档日>/` 之类目录时**不需要改脚本**（旧版要手工加 `SEARCH_DIRS`）。
    """
    global _CORPUS
    if _CORPUS is None:
        _CORPUS = sorted(p.relative_to(DOCS).as_posix() for p in DOCS.rglob("*.md"))
    return _CORPUS


def _segments(path: str) -> list[str]:
    """按 `/` 切段并丢掉空段与 `.`（`./a.md`、`a//b.md` 归一化）。

    ⚠️ **不要用 `lstrip("./")`**：它按**字符集**剥离，会把 `.docs/x.md` 削成 `docs/x.md`。
    `..` 段**保留**（全树索引里没有它的语义，交给"引用方目录相对解析"那条分支处理）。
    """
    return [s for s in path.split("/") if s not in ("", ".")]


def _suffix_match(rel: str, name: str) -> bool:
    """`name` 是否指向 `rel`：**按路径段**做尾部对齐匹配（段内可用 `fnmatch` 通配）。

    支持两种省略（都是"从某个段边界起对齐"，不是字符串后缀）：
    ① 只写基名 —— `《决策留痕表.md》` ⇒ 任一段等于它就算；
    ② 省略**前导**目录 —— `report/x.md` 实际在 `archive/migration/report/x.md` 下。

    ⚠️ 两条**刻意不支持**（旧版也不支持，别以为是退化）：
    - **省略中间目录**：`archive/x.md` **不**匹配 `archive/migration/x.md` —— 那会让"目录写错"
      和"目录省略"无法区分，等于放弃检查；
    - **`*` 跨目录**：按段匹配 ⇒ `archive/*.md` 只命中 `archive/` **直属**的 `.md`
      （若按整串 `fnmatch`，`*` 会吃掉 `/`，指向"直属"的引用能在子目录里蒙对 ⇒ 假绿）。
    """
    rel_segs = rel.split("/")
    name_segs = _segments(name)
    if not name_segs or len(name_segs) > len(rel_segs):
        return False
    for start in range(len(rel_segs) - len(name_segs) + 1):
        if all(fnmatch(rel_segs[start + k], name_segs[k]) for k in range(len(name_segs))):
            return True
    return False


def try_resolve(name: str, base: Path | None = None) -> bool:
    """按两种形态试探：**引用方所在目录**相对解析 / `.docs` 全树尾部对齐匹配。

    ⚠️ `base` 是**引用方文档所在目录**（markdown 链接按它相对解析，不是按 `.docs` 根）。
    2026-10-08 第一版漏了它 ⇒ `.docs/architecture/tech/可观测.md` 里的 `./事务边界.md`
    被拿去跟 `.docs/事务边界.md` 比，一处不存在的路径报出 **70 处假红**。
    """
    name = name.strip()
    if not name:
        return False
    stripped = name[len(".docs/"):] if name.startswith(".docs/") else name
    if base is not None:
        rel = "/".join(_segments(stripped))
        if rel and (base / rel).is_file():
            return True
    return resolve(stripped)


def resolve(name: str) -> bool:
    """名字能否解析到 `.docs/` 下的某个真实文件（含 `*` 通配）。

    ⚠️ **不要用 `lstrip("./")`**：它按**字符集**剥离，会把 `.docs/x.md` 削成 `docs/x.md`
    （首字符 `d` 不在集合里就停，但 `.` 已被吃掉）。第一版就这么错过，报出一堆假阴性。
    """
    name = name.strip()
    if not name:
        return False
    return any(_suffix_match(rel, name) for rel in corpus())


def resolve_repo_path(p: str) -> bool:
    p = p.strip().replace("\\", "/")
    if p.startswith("./"):
        p = p[2:]
    if "*" in p:
        return bool(list(ROOT.glob(p)))
    if (ROOT / p).exists():
        return True
    # 老项目根的兜底解析已删除（迁移收口，2026-10-18）：它只对 `.docs/report/` 有用，
    # 而三份调研报告已归档、`main()` 又跳过 archive/ ⇒ 那条分支不可达，却让门禁依赖 `old-project/`。
    return False


def paragraphs(text: str) -> list[tuple[int, str]]:
    """切段落：返回 [(起始行号, 段落文本)]。空行分段——表格行因此各自成段，但续行会并回上一行。"""
    out: list[tuple[int, str]] = []
    buf: list[str] = []
    start = 1
    for i, line in enumerate(text.splitlines(), 1):
        if line.strip():
            if not buf:
                start = i
            buf.append(line)
        elif buf:
            out.append((start, "\n".join(buf)))
            buf = []
    if buf:
        out.append((start, "\n".join(buf)))
    return out


def check(doc: Path) -> list[tuple[int, str, str, bool]]:
    """返回 [(行号, 引用原文, 原因, 是否"尚未创建")]。"""
    bad: list[tuple[int, str, str, bool]] = []
    text = doc.read_text(encoding="utf-8")
    for start, para in paragraphs(text):
        has_archive = bool(ARCHIVE_MENTION.search(para))
        for offset, line in enumerate(para.splitlines()):
            lineno = start + offset

            # 1) 《xxx.md》
            for m in re.finditer(r"《([^》]+\.md)》", line):
                if not try_resolve(m.group(1)) and not PLACEHOLDER.search(m.group(1)):
                    bad.append((lineno, m.group(0), "文档引用指不到", False))

            # 2) report/ archive/ .docs/ 形态
            for m in re.finditer(r"`?((?:\.docs/|report/|archive/)[^\s`）)、]+\.md)", line):
                if not try_resolve(m.group(1)) and not PLACEHOLDER.search(m.group(1)):
                    bad.append((lineno, m.group(1), "相对路径指不到", False))

            # 3) 指向已归档章节的裸引用（**按段落**判定，故同一段的 archive/ 可"救"它）
            if not has_archive:
                for m in SECTION_REF.finditer(line):
                    bad.append((lineno, m.group(0), "章节引用可能已归档（本段无 archive/ 路径）", False))

            # 5) markdown 链接 `[文字](xxx.md)`
            #    ⚠️ 2026-10-08 补：`task/` 下的新功能文档与登记台**通篇用这种形态互链**
            #    （`[收藏功能-需求.md](收藏功能-需求.md)`），而旧检测面只认《》/相对路径/仓库路径
            #    ⇒ 2026-10-08 把 `收藏功能-分期与任务.md` 改名为 `-分期与设计.md` 时，
            #    6 处引用**全部在检测面外**，门禁照样报"悬空 0 处"（假绿，同 tools/ 那一族）。
            #    只收 `.md` 结尾且非 URL / 非锚点 / 非示例的链接，避免把 `[x](#锚)` `[x](https://…)`
            #    误报成悬空。
            for m in re.finditer(r"\]\(([^)\s]+\.md)\)", line):
                target = m.group(1)
                if target.startswith(("http://", "https://", "#", "mailto:")):
                    continue
                if not try_resolve(target, base=doc.parent) and not PLACEHOLDER.search(target):
                    bad.append((lineno, target, "markdown 链接指不到", False))

            # 4) 仓库路径（`tools/` `src/` `.mvn/`）
            #    ⚠️ 2026-10-06 修：原正则**只认反引号包裹、且要求 `tools/` 紧贴反引号** ⇒
            #    `python tools/xxx.py`（代码块与命令清单里最常见的形态）**一律漏检**。
            #    实测代价：脚本归档后 4 处命令已失效，却报"悬空 0 处"（门禁假绿的同一族）。
            #    现改为**不限反引号**；前置负向断言只为避免在长路径中途重复匹配。
            for m in re.finditer(
                r"(?<![\w/.-])((?:tools|src|\.mvn)/[^\s`）)、]+\.(?:py|java|xml|yaml|cmd|sh))", line
            ):
                if not resolve_repo_path(m.group(1)) and not PLACEHOLDER.search(m.group(1)):
                    bad.append((lineno, m.group(1), "仓库路径不存在", True))
    return bad


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="校验 .docs 交叉引用是否指得到（只读）")
    parser.add_argument("--verbose", "-v", action="store_true", help="附上行号")
    parser.add_argument("--strict", action="store_true", help='把"尚未创建"也计为失败')
    args = parser.parse_args(argv)

    # 跳过 archive/：归档是当时的记录，里面的旧章节名是**正确的历史**。
    # ⚠️ 判据按**相对 `.docs` 的路径段**：`p` 是绝对路径，若写成 `"archive" not in p.parts`，
    #    仓库恰好放在 `D:/archive/...` 之类位置时**所有文档都会被当成归档跳过**、
    #    扫 0 份却报绿（2026-10-18 复查实测）。
    docs = sorted(p for p in DOCS.rglob("*.md") if "archive" not in p.relative_to(DOCS).parts)
    if not docs:
        print(f"未找到任何正文文档：{DOCS}", file=sys.stderr)
        print("（这几乎一定是仓库根解析错了或目录被挪走 —— 不做'扫 0 份 ⇒ 报绿'的假绿。）", file=sys.stderr)
        return 2
    dangling = 0
    pending = 0
    for doc in docs:
        bad = check(doc)
        if not bad:
            continue
        print(f"\n■ {doc.relative_to(ROOT)}")
        seen = set()
        for lineno, ref, why, is_pending in bad:
            if (ref, why) in seen:
                continue
            seen.add((ref, why))
            if is_pending:
                pending += 1
                mark, note = "⏳", "（若为待创建的文件，可忽略）"
            else:
                dangling += 1
                mark, note = "❌", ""
            loc = f"  L{lineno:<4}" if args.verbose else "        "
            print(f"{loc} {mark} {ref}   ← {why}{note}")

    print("\n" + "=" * 64)
    print(f"悬空引用 {dangling} 处" + (f"；尚未创建 {pending} 处" if pending else "") + f"（扫描 {len(docs)} 份正文文档，已跳过 archive/）")
    if dangling:
        print("按 INDEX §五.3「归档要连同引用修复一起做」处理")
    else:
        print("✅ 正文引用全部指得到")
    print("=" * 64)
    return 1 if (dangling or (args.strict and pending)) else 0


if __name__ == "__main__":
    raise SystemExit(main())
