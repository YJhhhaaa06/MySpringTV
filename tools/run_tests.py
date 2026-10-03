#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""一键跑测试：完整输出落盘，stdout 只回显摘要。

## 为什么需要它

第三批（可运行性与韧性）的验收门禁是「**每条能力必须能在故障注入下变红**」。
故障注入测试不能进默认回归集（否则回归集永远是红的）⇒ 必须有"按组选跑"的能力。
本脚本就是 T2–T5 的**验收工具**：T2 用 `--group observability`、T3 用 `--group resilience`……

## 用法

    python tools/run_tests.py                                  # 全量：mvnw -B clean verify（默认）
    python tools/run_tests.py --group resilience               # 只跑 @Tag("resilience")
    python tools/run_tests.py --group a --group b              # 多组（并集）
    python tools/run_tests.py --exclude-group slow             # 排除某组
    python tools/run_tests.py --test SecurityContractTests     # 只跑一个测试类（开发期用）

## 三个产物（都落在 target/test-reports/，已 gitignore）

    run-<时间戳>.log     完整控制台输出（不截断，供事后排查）
    latest.json          机器可读：exit code / 用例数 / 失败清单 / 各阶段耗时 / 日志路径
    report-<时间戳>.md   人类可读：按测试类汇总 + 失败用例逐条 + 失败栈摘要

## 三条实现口径（避开老项目的坑）

1. **结果一律取自 surefire XML**（`target/surefire-reports/TEST-*.xml`，结构化结果），
   **不解析控制台**——控制台里只要出现 "Tests run" 字样就会误判（老项目
   `parse_pytest_summary` 就是这么干的，因为它没有 XML 可依赖；本项目有）。
   控制台只用于"阶段耗时"这一个用途（见下）。
2. **必须拿到"全部失败"而不是第一条**：加 `-Dmaven.test.failure.ignore=true` 让 Maven 跑完，
   再由本脚本按 XML 判定退出码并**透传**（失败仍须 `exit != 0`，否则 CI 形同虚设）。
3. **必须带 `clean`**：`target/classes` 留着上一轮 class 时，增量编译会跳过重编，
   "源码编译不过"会被旧 class 掩盖，测试照样绿。

## 阶段耗时怎么来（诚实说明）

Maven 控制台不打印逐阶段耗时，但那不重要——本脚本在**读取子进程 stdout 时打点**：
每遇到一行 `[INFO] --- <plugin>:<version>:<goal> ---` 就记一次墙钟，相邻打点之差即该 goal 的耗时。
这是**观测到的真实边界**，不是推算。缺点：只覆盖 Maven 实际执行到的插件（跳过的阶段不出现）。

## 两条防呆（否则会给"假绿灯"）

- **过滤零命中不放行**：指定了 `--group`/`--test` 却**一个用例都没跑**（tag / 类名写错）⇒ 退出码非 0。
  在本批"必须能变红"的门禁下，静默绿灯是最危险的失败模式。
- **不采信上一轮的 surefire XML**：早于本次运行起点的 `TEST-*.xml` 一律跳过。
  `clean` 是本次构建的第一个目标，但若 Maven 根本没跑到它（JAVA_HOME 缺失、wrapper 下载失败），
  `target/surefire-reports/` 里仍是上一轮结果——照用就是拿旧结果冒充本次结果。

## 编码（不是小事）

控制台日志按**子进程实际使用的编码**解码（见 `_subprocess_encoding`）：Windows 上是 JVM 的
`native.encoding` = **ANSI 代码页**（`GetACP()`，中文 Windows 即 cp936），**不是 UTF-8**。
写死 UTF-8 会把 JVM 的中文日志变成一片 `�`——实测那一份日志里有 **3313 个**替换字符。
⚠️ 也别用 `locale.getpreferredencoding()` 当退路：本机 `PYTHONUTF8=1` + `LANG=en_US.UTF-8` 时
它返回 `'utf-8'`，退路等于没退（这个坑实测踩过）。

## ⚠️ 为什么产物"Maven 跑完才落盘"

产物目录在 `target/` 下，而本脚本默认跑的是 `clean verify`。如果一边跑 Maven 一边把日志
写进 `target/test-reports/`，`clean` 会因为**删不掉自己被占用的日志文件**而直接 BUILD FAILURE
（实测踩到：`Failed to delete ...\target\test-reports\run-*.log`）。
⇒ 先把输出**缓存在内存**，等 Maven 结束、`clean` 早已执行完之后，再统一落盘。
副作用要知情：每次 `clean` 会清掉**上一轮**的产物，故 `latest.json` 只反映**最近一次**运行。

## 只读声明

本脚本**只读**源码与配置，只在 `target/test-reports/` 落盘（见 tools/README.md §3.4）。
它不改任何源码或配置，故没有 `--apply`。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

# ── Windows 中文环境必备：强制 stdout/stderr 用 UTF-8（见 tools/README.md §3.1）
for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent
MVNW = ROOT / ("mvnw.cmd" if os.name == "nt" else "mvnw")
REPORT_DIR = ROOT / "target" / "test-reports"
SUREFIRE_DIR = ROOT / "target" / "surefire-reports"

# 默认排除的 tag 组：第三批 T3 起把 `resilience` 加进来——故障注入测试不进默认回归集，
# 只能用 `--group resilience` 显式选跑（T1 的设计约定）。
DEFAULT_EXCLUDED_GROUPS: tuple[str, ...] = ("resilience",)

# stdout 里最多列出的失败用例数（一屏内可读；其余折叠为 "+N more"）。
MAX_FAILURES_PRINTED = 10
# 报告里每条失败保留的栈行数（完整栈在 run-*.log）。
STACK_LINES_IN_REPORT = 15

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def _subprocess_encoding() -> str:
    """子进程（JVM）控制台输出的编码。

    Windows：JVM 把 stdout 写进**管道**时用 `native.encoding` = **ANSI 代码页**（GetACP），
    与 `chcp 65001` 设的控制台代码页无关。
    ⚠️ **不要**用 `locale.getpreferredencoding()`：本机 `PYTHONUTF8=1` + `LANG=en_US.UTF-8`
    时它返回 `'utf-8'`，于是"UTF-8 失败就退回本机编码"的退路还是 UTF-8 —— 等于没退，
    实测中文日志照旧是一片 `�`（3313 个）。GetACP() 还能自动适配"Beta: UTF-8" 系统（返回 65001）。
    非 Windows：UTF-8。
    """
    if os.name == "nt":
        try:
            import ctypes

            return f"cp{ctypes.windll.kernel32.GetACP()}"
        except Exception:  # pragma: no cover - 兜底
            return "cp936"
    return "utf-8"


SUBPROCESS_ENCODING = _subprocess_encoding()

# `[INFO] --- maven-surefire-plugin:3.5.2:test (default-test) @ video-web ---`
RE_PHASE = re.compile(r"^\[INFO\] --- ([^:\s]+):[^:\s]+:([\w\-]+)")
RE_TOTAL_TIME = re.compile(r"^\[INFO\] Total time:\s*(.+?)\s*$")


@dataclass
class Failure:
    """一条失败/错误用例。"""

    class_name: str
    name: str
    kind: str  # failure | error
    message: str
    stack: str
    time: float


@dataclass
class Totals:
    tests: int = 0
    failures: int = 0
    errors: int = 0
    skipped: int = 0
    test_time: float = 0.0

    @property
    def passed(self) -> int:
        return max(0, self.tests - self.failures - self.errors - self.skipped)

    @property
    def bad(self) -> int:
        return self.failures + self.errors


@dataclass
class ClassRow:
    name: str
    tests: int
    failures: int
    errors: int
    skipped: int
    time: float


@dataclass
class ParseResult:
    totals: Totals = field(default_factory=Totals)
    classes: list[ClassRow] = field(default_factory=list)
    failures: list[Failure] = field(default_factory=list)
    xml_files: int = 0
    stale_skipped: int = 0  # 因早于本次运行起点而被判定为"上一轮遗留"的 XML 数


def decode_line(raw: bytes) -> str:
    """按**子进程实际使用的编码**解码一行（见 `_subprocess_encoding`）。

    Windows 上是 JVM 的 native encoding（ANSI 代码页），不是 UTF-8；写死 UTF-8
    会把中文日志变成一片 `�`（评审 M-3 / 实测 3313 个替换字符）。
    """
    return raw.decode(SUBPROCESS_ENCODING, errors="replace")


def fmt_duration(seconds: float) -> str:
    """把秒数格式化成人读字符串。"""
    if seconds < 60:
        return f"{seconds:.1f}s"
    minutes, sec = divmod(int(round(seconds)), 60)
    if minutes < 60:
        return f"{minutes}m{sec:02d}s"
    hours, minutes = divmod(minutes, 60)
    return f"{hours}h{minutes:02d}m{sec:02d}s"


def _int_attr(node: ET.Element, key: str) -> int:
    try:
        return int(float(node.get(key) or 0))
    except (TypeError, ValueError):
        return 0


def _float_attr(node: ET.Element, key: str) -> float:
    try:
        return float(node.get(key) or 0.0)
    except (TypeError, ValueError):
        return 0.0


def parse_surefire(surefire_dir: Path, fresh_after: float | None = None) -> ParseResult:
    """汇总所有 TEST-*.xml。找不到目录（如构建早期失败）时返回空结果。

    `fresh_after`（epoch 秒）：早于此刻的 XML 视为**上一轮遗留**并跳过。
    必要性：`clean` 是本次构建的第一个目标，但若 Maven 根本没跑到它
    （如 JAVA_HOME 缺失、wrapper 下载失败），`target/surefire-reports/` 里
    仍是上一轮的结果；不设这道闸，报告会**拿旧结果冒充本次结果**（退出码仍红，
    但诊断内容误导——评审 M-4）。
    """
    result = ParseResult()
    if not surefire_dir.is_dir():
        return result

    for xml in sorted(surefire_dir.glob("TEST-*.xml")):
        if fresh_after is not None:
            try:
                if xml.stat().st_mtime < fresh_after:
                    result.stale_skipped += 1
                    continue
            except OSError:
                pass
        try:
            root = ET.parse(xml).getroot()
        except ET.ParseError as exc:
            print(f"[run_tests] 警告：无法解析 {xml.name}：{exc}", file=sys.stderr)
            continue
        result.xml_files += 1
        suites = root.findall("testsuite") if root.tag == "testsuites" else [root]
        for suite in suites:
            name = suite.get("name") or xml.stem
            row = ClassRow(
                name=name,
                tests=_int_attr(suite, "tests"),
                failures=_int_attr(suite, "failures"),
                errors=_int_attr(suite, "errors"),
                skipped=_int_attr(suite, "skipped"),
                time=_float_attr(suite, "time"),
            )
            result.classes.append(row)
            result.totals.tests += row.tests
            result.totals.failures += row.failures
            result.totals.errors += row.errors
            result.totals.skipped += row.skipped
            result.totals.test_time += row.time

            for case in suite.iter("testcase"):
                for child in case:
                    if child.tag in ("failure", "error"):
                        result.failures.append(
                            Failure(
                                class_name=case.get("classname") or name,
                                name=case.get("name") or "?",
                                kind=child.tag,
                                message=(child.get("message") or "").strip(),
                                stack=(child.text or "").strip(),
                                time=_float_attr(case, "time"),
                            )
                        )
                        break
                    if child.tag == "skipped":
                        break
    return result


def rel(path: Path) -> str:
    """仓库相对路径（正斜杠），用于回显/落盘。"""
    try:
        return path.relative_to(ROOT).as_posix()
    except ValueError:
        return path.as_posix()


def stack_excerpt(stack: str, limit: int = STACK_LINES_IN_REPORT) -> str:
    lines = [ln.rstrip() for ln in stack.splitlines() if ln.strip()]
    if not lines:
        return "（无栈信息）"
    head = lines[:limit]
    if len(lines) > limit:
        head.append(f"...（省略 {len(lines) - limit} 行，完整栈见 run-*.log）")
    return "\n".join(head)


def write_markdown(
    md_path: Path,
    *,
    meta: dict,
    phases: list[dict],
    parsed: ParseResult,
) -> None:
    """人类可读报告：汇总 + 阶段 + 按类 + 失败逐条。"""
    totals = parsed.totals
    out: list[str] = []
    out.append("# 测试报告\n")
    out.append(f"- 生成时间：{meta['finished_at']}")
    out.append(f"- 命令：`{meta['command_display']}`")
    if meta["filters"]["groups"]:
        out.append(f"- 仅跑 tag：{', '.join(meta['filters']['groups'])}")
    if meta["filters"]["exclude_groups"]:
        out.append(f"- 排除 tag：{', '.join(meta['filters']['exclude_groups'])}")
    if meta["filters"]["tests"]:
        out.append(f"- 仅跑测试类：{', '.join(meta['filters']['tests'])}")
    out.append("")

    out.append("## 汇总\n")
    out.append("| 项 | 值 |")
    out.append("|----|----|")
    out.append(f"| 退出码 | **{meta['exit_code']}**（maven={meta['maven_exit_code']} / {meta['build_status']}） |")
    out.append(f"| 用例总数 | {totals.tests} |")
    out.append(f"| 通过 | {totals.passed} |")
    out.append(f"| 失败 | {totals.failures} |")
    out.append(f"| 错误 | {totals.errors} |")
    out.append(f"| 跳过 | {totals.skipped} |")
    out.append(f"| 测试耗时 | {fmt_duration(totals.test_time)} |")
    out.append(f"| 总墙钟 | {fmt_duration(meta['duration_seconds'])} |")
    if meta["maven_total_time"]:
        out.append(f"| Maven Total time | {meta['maven_total_time']} |")
    out.append(f"| surefire XML | {parsed.xml_files} 个 |")
    out.append("")

    out.append("## 阶段耗时（从子进程 stdout 打点）\n")
    if phases:
        out.append("| 阶段 | 耗时 |")
        out.append("|------|------|")
        for phase in phases:
            out.append(f"| {phase['phase']} | {fmt_duration(phase['seconds'])} |")
    else:
        out.append("（未观察到阶段标记）")
    out.append("")

    out.append("## 按测试类汇总\n")
    if parsed.classes:
        rows = sorted(parsed.classes, key=lambda r: (-(r.failures + r.errors), r.name))
        out.append("| 测试类 | 用例 | 失败 | 错误 | 跳过 | 耗时 |")
        out.append("|--------|------|------|------|------|------|")
        for row in rows:
            out.append(
                f"| {row.name} | {row.tests} | {row.failures} | {row.errors} | {row.skipped} | {fmt_duration(row.time)} |"
            )
    else:
        out.append("（没有产出 surefire XML —— 构建可能在测试前就失败了，见日志）")
    out.append("")

    out.append(f"## 失败用例（{len(parsed.failures)} 条）\n")
    if not parsed.failures:
        out.append("无。")
    else:
        for index, failure in enumerate(parsed.failures, start=1):
            out.append(f"### {index}. {failure.class_name}#{failure.name}  [{failure.kind}]\n")
            if failure.message:
                out.append(f"- message：`{failure.message}`")
            out.append("- 栈摘要：\n")
            out.append("```")
            out.append(stack_excerpt(failure.stack))
            out.append("```")
            out.append("")

    out.append("---\n")
    out.append(f"完整控制台输出：`{meta['reports']['log']}`")
    out.append(f"机器可读结果：`{meta['reports']['json']}`")
    md_path.write_text("\n".join(out) + "\n", encoding="utf-8")


def effective_excluded_groups(args: argparse.Namespace) -> list[str]:
    """实际生效的排除 tag 集。

    ⚠️ 必须把**显式 `--group` 的 tag 从排除集里剔除**：否则 `--group resilience`
    会同时生成 `-Dgroups=resilience` 与 `-DexcludedGroups=resilience`，两者交叠 ⇒ **零用例**
    （而 `DEFAULT_EXCLUDED_GROUPS` 含 `resilience`，正是这种情况）。
    """
    groups = set(args.group or [])
    requested = list(DEFAULT_EXCLUDED_GROUPS) + list(args.exclude_group or [])
    return [tag for tag in requested if tag not in groups]


def build_command(args: argparse.Namespace) -> tuple[list[str], list[str]]:
    """返回 (实际执行 cmd, 用于回显的 mvnw 参数)。"""
    groups = list(args.group or [])
    excluded = effective_excluded_groups(args)
    tests = list(args.test or [])

    mvn_args = ["-B", "clean", "verify", "-Dmaven.test.failure.ignore=true"]
    if groups:
        mvn_args.append("-Dgroups=" + ",".join(groups))
    if excluded:
        mvn_args.append("-DexcludedGroups=" + ",".join(excluded))
    if tests:
        mvn_args.append("-Dtest=" + ",".join(tests))

    display = [MVNW.name] + mvn_args
    if os.name == "nt":
        # 用 `cmd /c call` 拉起 .cmd：CreateProcess 不能直接执行批处理；
        # `call` 还能容忍路径含空格（旧项目同样写法）。
        cmd = ["cmd", "/c", "call", str(MVNW)] + mvn_args
    else:
        cmd = [str(MVNW)] + mvn_args
    return cmd, display


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="run_tests.py",
        description="一键跑测试：完整输出落盘，stdout 只回显摘要。默认全量（mvnw -B clean verify）。",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "示例：\n"
            "  python tools/run_tests.py                              # 全量\n"
            "  python tools/run_tests.py --group resilience           # 只跑 @Tag(\"resilience\")\n"
            "  python tools/run_tests.py --group a --group b          # 多组合并\n"
            "  python tools/run_tests.py --exclude-group slow         # 排除某组\n"
            "  python tools/run_tests.py --test SecurityContractTests # 只跑一个测试类\n"
            "\n"
            "产物：target/test-reports/{run-<时间戳>.log, latest.json, report-<时间戳>.md}\n"
        ),
    )
    parser.add_argument(
        "--group",
        action="append",
        metavar="TAG",
        help="只跑带该 @Tag 的测试（可重复，多个为并集；对应 -Dgroups=）",
    )
    parser.add_argument(
        "--exclude-group",
        action="append",
        metavar="TAG",
        help="排除带该 @Tag 的测试（可重复；对应 -DexcludedGroups=）",
    )
    parser.add_argument(
        "--test",
        action="append",
        metavar="CLASS",
        help="只跑指定测试类（可重复；对应 -Dtest=）。可与 --group 叠加（surefire 取交集）",
    )
    args = parser.parse_args(argv)

    cmd, display = build_command(args)
    filters = {
        "groups": list(args.group or []),
        "exclude_groups": effective_excluded_groups(args),
        "tests": list(args.test or []),
    }

    env = os.environ.copy()
    env.setdefault("PYTHONIOENCODING", "utf-8")
    if os.name == "nt" and not env.get("JAVA_HOME"):
        print("[run_tests] 警告：未检测到 JAVA_HOME，mvnw 需要它（或 PATH 上有 java）。", file=sys.stderr)

    started = datetime.now()
    stamp = started.strftime("%Y%m%d_%H%M%S")
    log_path = REPORT_DIR / f"run-{stamp}.log"
    md_path = REPORT_DIR / f"report-{stamp}.md"
    json_path = REPORT_DIR / "latest.json"

    phases: list[dict] = []
    maven_total_time: str | None = None
    build_status = "UNKNOWN"
    maven_exit = 127
    spawned = False
    # ⚠️ 输出先缓存在内存：默认跑 `clean verify`，若此刻把日志写进 target/ 下，
    #    clean 会删不掉自己被占用的日志文件而 BUILD FAILURE（实测）。跑完再落盘。
    output_lines: list[str] = []

    t0 = time.monotonic()
    cur_start = t0
    cur_name = "启动"

    try:
        proc = subprocess.Popen(
            cmd,
            cwd=str(ROOT),
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            bufsize=0,
            creationflags=NO_WINDOW,
        )
        spawned = True
        assert proc.stdout is not None
        for raw in proc.stdout:
            line = decode_line(raw)
            output_lines.append(line)
            marker = RE_PHASE.match(line)
            if marker:
                now = time.monotonic()
                phases.append({"phase": cur_name, "seconds": round(now - cur_start, 2)})
                cur_name = f"{marker.group(1)}:{marker.group(2)}"
                cur_start = now
                continue
            total = RE_TOTAL_TIME.match(line)
            if total:
                maven_total_time = total.group(1)
                continue
        maven_exit = proc.wait()
    except OSError as exc:
        output_lines.append(f"\n[run_tests] 启动子进程失败：{exc}\n")
        print(f"[run_tests] 启动子进程失败：{exc}", file=sys.stderr)

    elapsed = time.monotonic() - t0
    phases.append({"phase": cur_name, "seconds": round(time.monotonic() - cur_start, 2)})

    # 构建成败直接由 Maven 退出码判定（权威、不可被测试输出里的字样误导）：
    # `verify` 已带 failure.ignore，测试失败时 Maven 仍返回 0 ⇒ 那是"构建成功但测试红"，
    # 由下面的 exit_code 反映。
    build_status = "UNKNOWN" if not spawned else ("SUCCESS" if maven_exit == 0 else "FAILURE")

    parsed = parse_surefire(SUREFIRE_DIR, fresh_after=started.timestamp())
    totals = parsed.totals

    filters_active = bool(filters["groups"]) or bool(filters["tests"])
    zero_tests_with_filter = spawned and maven_exit == 0 and filters_active and totals.tests == 0

    if not spawned:
        exit_code = 127
    elif maven_exit != 0:
        exit_code = maven_exit
    elif totals.bad:
        exit_code = 1
    elif zero_tests_with_filter:
        # 指定了 --group/--test 却一个用例都没跑：多半是 tag / 类名写错。
        # 在本批"必须能变红"的门禁下，"静默绿灯"是最危险的失败模式（评审 M-1）。
        exit_code = 1
    else:
        exit_code = 0

    finished = datetime.now()
    meta = {
        "generated_at": finished.isoformat(timespec="seconds"),
        "started_at": started.isoformat(timespec="seconds"),
        "finished_at": finished.isoformat(timespec="seconds"),
        "duration_seconds": round(elapsed, 2),
        "command": cmd,
        "command_display": " ".join(display),
        "filters": filters,
        "maven_exit_code": maven_exit,
        "exit_code": exit_code,
        "build_status": build_status,
        "maven_total_time": maven_total_time,
        "counts": {
            "tests": totals.tests,
            "passed": totals.passed,
            "failures": totals.failures,
            "errors": totals.errors,
            "skipped": totals.skipped,
        },
        "test_time_seconds": round(totals.test_time, 2),
        "phases": phases,
        "failures": [
            {
                "class": f.class_name,
                "name": f.name,
                "kind": f.kind,
                "message": f.message,
            }
            for f in parsed.failures
        ],
        "surefire_xml_files": parsed.xml_files,
        "stale_surefire_skipped": parsed.stale_skipped,
        "reports": {
            "log": rel(log_path),
            "json": rel(json_path),
            "markdown": rel(md_path),
        },
    }
    notes: list[str] = []
    if zero_tests_with_filter:
        notes.append("指定了 --group/--test 但 0 用例被执行（tag/类名写错？），按失败处理")
    if parsed.stale_skipped:
        notes.append(f"跳过 {parsed.stale_skipped} 个早于本次运行起点的 surefire XML（上一轮遗留）")
    if notes:
        meta["notes"] = notes

    # ── Maven 已结束（clean 早已执行完），此时才在 target/test-reports/ 落盘
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    try:
        with log_path.open("w", encoding="utf-8", newline="") as fh:
            fh.write("".join(output_lines))
    except OSError as exc:
        print(f"[run_tests] 写 run-*.log 失败：{exc}", file=sys.stderr)
    try:
        json_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except OSError as exc:
        print(f"[run_tests] 写 latest.json 失败：{exc}", file=sys.stderr)
    try:
        write_markdown(md_path, meta=meta, phases=phases, parsed=parsed)
    except OSError as exc:
        print(f"[run_tests] 写 report-*.md 失败：{exc}", file=sys.stderr)

    # ── stdout 摘要（常态约 6 行）
    print(f"[run_tests] 命令：{meta['command_display']}")
    if zero_tests_with_filter:
        print("[run_tests] 结果：0 例 —— --group/--test 零命中（tag/类名写错？），按失败处理")
    elif totals.tests == 0 and parsed.xml_files == 0:
        print("[run_tests] 结果：没有产出 surefire XML（构建在测试前失败？）——见日志")
    else:
        print(
            f"[run_tests] 结果：{totals.tests} 例 | 通过 {totals.passed} | "
            f"失败 {totals.failures} | 错误 {totals.errors} | 跳过 {totals.skipped} | "
            f"测试耗时 {fmt_duration(totals.test_time)}"
        )
    print(f"[run_tests] 总耗时：{fmt_duration(elapsed)}（maven={maven_exit} / {build_status}）")
    if phases:
        print("[run_tests] 阶段：" + " → ".join(f"{p['phase']} {p['seconds']:.1f}s" for p in phases))
    if parsed.stale_skipped:
        print(f"[run_tests] 警告：跳过 {parsed.stale_skipped} 个上一轮遗留的 surefire XML（本次未跑到 clean/测试）")
    if parsed.failures:
        shown = parsed.failures[:MAX_FAILURES_PRINTED]
        for failure in shown:
            print(f"[run_tests]   ✗ {failure.class_name}#{failure.name} [{failure.kind}]")
        if len(parsed.failures) > len(shown):
            print(f"[run_tests]   …还有 {len(parsed.failures) - len(shown)} 条，见报告")
        print(f"[run_tests] 完整栈：{meta['reports']['markdown']} / {meta['reports']['log']}")
    print(f"[run_tests] exit={exit_code} | 报告：{meta['reports']['markdown']} | JSON：{meta['reports']['json']}")
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
