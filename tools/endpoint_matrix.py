#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""复算端点对照：老项目端点 × 新项目实现状态。

## 为什么需要它

SOP §七.3 的纪律是「**状态列必须能被复算**」。
《端点对照表.md》里"已迁 31 / 剩余 12"这种数字，手工维护必然过期——
本项目已经吃过一次亏：决策表里 U-5~U-7 标了 🔴 待决策，实际代码早已交付并测试。

本脚本从**两边的源码**重新推导端点集合，让那张表可以被随时校验。

## 用法

    python tools/endpoint_matrix.py                 # 全部：矩阵 + 剩余 + 规模
    python tools/endpoint_matrix.py --missing       # 只看还没搬的
    python tools/endpoint_matrix.py --size          # 附带剩余模块的规模

## 老项目端点怎么识别（三种路由写法，实测都有）

1. `@WebServlet("/x/*")` + `switch (action) { case "/y": ... }`
2. `@WebServlet("/x/*")` + `if ("/y".equals(action)) { ... }`
3. `@WebServlet("/x")`（无分支）⇒ 该前缀自身即端点

`default` / `null` 分支不算端点（它们是错误出口，不是功能）。

## 局限（诚实声明）

这是**正则启发式**，不是编译器。已知它读不出的情况：
- 前缀里带通配符语义的（`/api/upload/*` 的 `*` 是 servlet 通配，不是路径段）
- action 由变量拼接而非字面量
命中数会打印出中间结果，**与《端点对照表》对不上时以人核为准**，
然后把差异补进对照表——脚本是校验器，不是事实源。
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

# ── Windows 中文环境必备：强制 stdout/stderr 用 UTF-8（见 tools/README.md §3.1）
for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent

OLD_SRC = ROOT / "old-project" / "TVhomework1" / "src" / "main" / "java" / "com" / "itheima"
NEW_SRC = ROOT / "src" / "main" / "java" / "io" / "github" / "yjhhhaaa06" / "videoweb"

RE_WEBSERVLET = re.compile(r'^\s*@WebServlet\(\s*"([^"]+)"\s*\)', re.MULTILINE)
# 写法 1：case "/y":  也兼容 case ("/y"):（老项目两种都出现过）
RE_CASE = re.compile(r'case\s*\(?\s*"([^"]+)"\s*\)?\s*:')
# 写法 2：if ("/y".equals(action))
RE_IF_EQUALS = re.compile(r'"([^"]+)"\s*\.equals\(\s*\w+\s*\)')
# doGet / doPost / doPut / doDelete 的方法头（只在这些方法体内找路由分支，
# 否则会误吃 helper 方法里的 switch —— 老项目 ContentController 的
# parseBoolean 就有一个 switch("1"/"true"/"0"/"false")，那不是路由）
RE_DO_METHOD = re.compile(r'protected\s+void\s+(do(?:Get|Post|Put|Delete))\s*\(')

RE_MAPPING = re.compile(
    r'^\s*@(Get|Post|Put|Delete|Patch)Mapping(?:\(\s*(?:value\s*=\s*)?"([^"]*)"\s*\))?', re.MULTILINE
)
RE_CLASS_MAPPING = re.compile(r'^\s*@RequestMapping\(\s*"([^"]+)"\s*\)', re.MULTILINE)

# ⚠️ 注解正则**必须锚定行首**（`^\s*@`）。
# 本项目的类注释里大量引用注解原文（例如 ProfileController 的说明表格里就写了
# {@code @GetMapping("/profile")}），不锚定会把它们当成真端点，凭空多出十几个。
#
# 也**不要**试图"先去掉注释再匹配"：servlet 通配符 `@WebServlet("/content/*")`
# 里含有 `/*`，会被块注释正则 `/\*.*?\*/` 当成注释开头，一路吃到下一个 `*/`，
# 把整段路由代码吞掉（实测让老项目端点从 43 掉到 26）。锚行首既准确又不会误伤。

# 不属于端点的 action（错误出口）
NOT_ENDPOINT = {"default", "/"}


@dataclass
class Endpoint:
    method: str  # GET / POST / ANY
    path: str
    source: str  # 哪个文件

    @property
    def key(self) -> str:
        return f"{self.method} {self.path}"


@dataclass
class Domain:
    name: str
    endpoints: list[Endpoint] = field(default_factory=list)


def method_bodies(text: str) -> list[str]:
    """取出所有 doGet/doPost/doPut/doDelete 的方法体（花括号配对）。

    只在这些体内找路由分支：helper 方法里的 switch 不是路由。
    """
    bodies: list[str] = []
    for m in RE_DO_METHOD.finditer(text):
        i = text.find("{", m.end())
        if i < 0:
            continue
        depth = 0
        for j in range(i, len(text)):
            c = text[j]
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    bodies.append(text[i : j + 1])
                    break
    return bodies


def old_endpoints() -> list[Endpoint]:
    """从老项目 servlet 提取端点。"""
    out: list[Endpoint] = []
    if not OLD_SRC.is_dir():
        print(f"⚠️  老项目源码不存在（已 gitignore，仅本地）：{OLD_SRC}", file=sys.stderr)
        return out

    for path in sorted(OLD_SRC.rglob("*Controller.java")):
        text = path.read_text(encoding="utf-8", errors="replace")
        m = RE_WEBSERVLET.search(text)
        if not m:
            continue
        prefix = m.group(1)

        bodies = method_bodies(text)
        actions: list[str] = []
        for body in bodies:
            actions += RE_CASE.findall(body)
            actions += RE_IF_EQUALS.findall(body)
        # 去重保序
        seen: set[str] = set()
        actions = [a for a in actions if not (a in seen or seen.add(a))]
        actions = [a for a in actions if a.lower() not in NOT_ENDPOINT]

        if not actions:
            # 无分支 ⇒ 前缀自身即端点（/start、/profile、/feed 这类）
            out.append(Endpoint("ANY", prefix.rstrip("*").rstrip("/") or prefix, path.name))
        else:
            for a in actions:
                full = (prefix.rstrip("*").rstrip("/") + a) if a.startswith("/") else prefix + a
                out.append(Endpoint("ANY", full, path.name))
    return out



def new_endpoints() -> list[Endpoint]:
    """从新项目 controller 提取端点（类级 @RequestMapping 作为前缀）。"""
    out: list[Endpoint] = []
    if not NEW_SRC.is_dir():
        return out

    for path in sorted(NEW_SRC.rglob("*Controller.java")):
        text = path.read_text(encoding="utf-8", errors="replace")
        cm = RE_CLASS_MAPPING.search(text)
        base = cm.group(1) if cm else ""
        for verb, sub in RE_MAPPING.findall(text):
            if sub is None:
                continue
            full = (base + sub) if sub.startswith("/") else (base + "/" + sub if sub else base)
            out.append(Endpoint(verb.upper(), full or base, path.name))
    return out


def norm(path: str) -> str:
    """归一化用于比对：去掉 servlet 通配符与结尾斜杠。"""
    p = path.replace("*", "").rstrip("/")
    return p or "/"


def match(old: Endpoint, news: list[Endpoint]) -> bool:
    """老端点是否已在新项目实现（路径归一后相等；老的一律 ANY 故只比路径）。"""
    return any(norm(n.path) == norm(old.path) for n in news)


def report(show_missing: bool, show_size: bool) -> int:
    olds = old_endpoints()
    news = new_endpoints()

    if not olds:
        print("无法提取老项目端点（old-project 不在此机器或被删）。")
        print(f"新项目端点：{len(news)} 个（仅列出新项目侧）\n")
        for n in news:
            print(f"  {n.method:<5} {n.path}")
        return 0

    missing = [o for o in olds if not match(o, news)]
    done = len(olds) - len(missing)

    print("=" * 76)
    print(f"端点对照 —— 老项目 {len(olds)} 个 / 已迁 {done} / 剩余 {len(missing)}")
    print("=" * 76)

    if show_missing:
        print("\n【剩余端点】\n")
        by_domain: dict[str, list[Endpoint]] = {}
        for o in missing:
            by_domain.setdefault(o.source.replace("Controller.java", ""), []).append(o)
        for dom, eps in sorted(by_domain.items()):
            print(f"  ■ {dom}")
            for e in eps:
                print(f"      {e.path}    (老入口 {e.source})")
    else:
        print("\n【全部老端点】\n")
        for o in olds:
            flag = "✅" if match(o, news) else "⏭ "
            print(f"  {flag} {o.path:<38} {o.source}")

    print("\n【新项目端点】", len(news), "个（含有意新增）")
    for n in news:
        print(f"      {n.method:<5} {n.path}")

    if show_size:
        print("\n【剩余模块规模（老项目）】\n")
        print(f"  {'模块':<12} {'文件':>4} {'行数':>7} {'事务':>5}")
        print("  " + "-" * 40)
        modules = sorted({o.source.replace("Controller.java", "").lower() for o in missing})
        # 把 servlet 名映射回包名（LoginController→user 这类靠前缀猜不准，直接扫目录）
        pkgs: list[str] = []
        for pkg_dir in sorted(p for p in OLD_SRC.iterdir() if p.is_dir()):
            files = list(pkg_dir.rglob("*.java"))
            if not files:
                continue
            lines = sum(len(f.read_text(encoding="utf-8", errors="replace").splitlines()) for f in files)
            tx = sum(
                f.read_text(encoding="utf-8", errors="replace").count("transactionTemplate.execute")
                for f in files
            )
            pkgs.append(f"  {pkg_dir.name:<12} {len(files):>4} {lines:>7} {tx:>5}")
        print("\n".join(pkgs))

    print("\n" + "=" * 76)
    print("注：这是正则启发式（见文件头「局限」）。对不上时以人核为准，并回填《端点对照表.md》。")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="复算端点对照：老项目端点 × 新项目实现状态。")
    parser.add_argument("--missing", action="store_true", help="只列还没搬的端点")
    parser.add_argument("--size", action="store_true", help="附带老项目各模块规模")
    args = parser.parse_args(argv)
    return report(args.missing, args.size)


if __name__ == "__main__":
    raise SystemExit(main())
