#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""开发期数据清理 · **胶水编排**（T33）。

把三个脚本按**硬依赖顺序**串起来 —— DB 清理 → 媒体清理 → 计数重算：

* **顺序不是偏好而是依赖**：DB 不先清，被删内容的 `content_media` 行还在引用 URL，磁盘文件
  就不会被判成孤儿；重算不后置，就还在给即将删掉的明细行计数。
* 本脚本**只编排**，不复制任何逻辑 —— 三个子脚本各自可**单独跑**（`--only` / `--skip`）。

## 用法

    python tools/devclean/devclean.py                    # 默认全链 dry-run（只读预演）
    python tools/devclean/devclean.py --only media       # 只预演媒体步骤
    python tools/devclean/devclean.py --apply            # 真跑（先备份 DB，媒体步骤需确认关卡）
    python tools/devclean/devclean.py --apply --only db  # 只真跑 DB 步骤
    python tools/devclean/devclean.py --apply --no-backup # 跳过 DB 备份（自担风险）

## 安全关卡（`--apply` 时）

1. **先备份 DB**（调 `tools/backup.py`，**DB-only** —— 媒体目录不备份，由你手动）；
2. **媒体手动备份确认**：含媒体步骤时，必须显式加 `--media-backed-up`，否则**直接中止**
   （媒体目录很大，脚本绝不代劳；这一步是唯一不可逆门槛）；
3. **脚本不引用本仓目录之外的任何盘符**（跨盘备份由人手动做）。

## 退出码

    0  成功    1  中止（备份失败 / 缺确认关卡 / 子脚本失败）
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
TOOLS = HERE.parent
BACKUP_PY = TOOLS / "backup.py"

ORDER = ["db", "media", "recount"]
SCRIPTS = {
    "db": HERE / "db_clean.py",
    "media": HERE / "media_clean.py",
    "recount": HERE / "recount.py",
}
TITLES = {
    "db": "DB 清理（db_clean.py）",
    "media": "媒体清理（media_clean.py）",
    "recount": "计数重算（recount.py）",
}


def run(script: Path, extra: list[str]) -> int:
    cmd = [sys.executable, str(script), *extra]
    print(f"\n$ {' '.join(cmd)}", flush=True)  # 先冲刷，免得父进程缓冲把顺序打乱
    return subprocess.run(cmd, cwd=str(TOOLS.parent)).returncode


def main() -> int:
    parser = argparse.ArgumentParser(description="开发期数据清理·一键编排（默认 dry-run 预演全链）")
    parser.add_argument("--apply", action="store_true", help="真跑（默认只预演）")
    parser.add_argument("--only", action="append", choices=ORDER, default=[],
                        help="只跑给定步骤（可重复）")
    parser.add_argument("--skip", action="append", choices=ORDER, default=[],
                        help="跳过给定步骤（可重复）")
    parser.add_argument("--no-backup", action="store_true", help="--apply 时跳过 DB 备份")
    parser.add_argument("--media-backed-up", action="store_true",
                        help="--apply 时含媒体步骤的必备确认：你已手动备份媒体目录")
    parser.add_argument("--out", metavar="DIR", default=None, help="备份输出目录（透传给 backup.py）")
    args = parser.parse_args()

    steps = [s for s in ORDER if (not args.only or s in args.only) and s not in args.skip]
    if not steps:
        print("错误: 步骤集为空（--only / --skip 把三步全排除了）", file=sys.stderr)
        return 1

    print(f"计划步骤: {' -> '.join(TITLES[s] for s in steps)}")
    if steps and steps[0] != "db":
        print("⚠️ 提示: 未先跑 DB 步骤 —— 媒体/重算步骤依赖它先行（否则被删内容的媒体行还在引用 URL，"
              "相关文件不会被判成孤儿）。")

    if not args.apply:
        for s in steps:
            rc = run(SCRIPTS[s], [])  # 子脚本默认即 dry-run
            if rc != 0:
                print(f"错误: {TITLES[s]} 预演失败（退出码 {rc}）", file=sys.stderr)
                return 1
        print("\n[dry-run] 全链预演完成，未改任何数据 / 文件。加 --apply 才真跑。")
        return 0

    # ── --apply ──
    if not args.no_backup:
        extra = ["--out", args.out] if args.out else []
        rc = run(BACKUP_PY, extra)
        if rc != 0:
            print(f"错误: DB 备份失败（退出码 {rc}），已中止清理。", file=sys.stderr)
            return 1
        print("✔ DB 备份完成（媒体目录请确认已自行备份）。")
    else:
        print("⚠️ 已按 --no-backup 跳过 DB 备份。")

    if "media" in steps and not args.media_backed_up:
        print("错误: 含媒体步骤的 --apply 需显式确认 `--media-backed-up`（媒体目录很大、由人手动备份；"
              "该步不可逆）。已中止。", file=sys.stderr)
        return 1

    for s in steps:
        rc = run(SCRIPTS[s], ["--apply"])
        if rc != 0:
            print(f"错误: {TITLES[s]} 执行失败（退出码 {rc}），已中止后续步骤。", file=sys.stderr)
            return 1

    print("\n[apply] 全链完成。★ 媒体孤儿已进 trash —— 请人工复核后送回收站；"
          "跨盘备份与 trash 清理由你手动。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
