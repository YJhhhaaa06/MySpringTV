#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""开发期数据清理 · **媒体清理脚本**（T31）。

## 它清什么

`content_media.url` 是**唯一权威**的磁盘引用源（用户 2026-10-17 确认：`comment_media` / `video`
已无消费者 ⇒ 它们的 URL 一律按孤儿处理）。于是：

    盘上某个文件，若没有任何 `content_media.url` 指向它 ⇒ 孤儿 ⇒ 移到 `<媒体根同级>/trash/`。

**只移动、不删除**：删由人来做（确认无误后进 Windows 回收站）。`trash` 目录**不参与扫描**，
也不被任何自动步骤清理。

## 用法

    python tools/devclean/media_clean.py            # 默认 dry-run：列出孤儿（不搬）
    python tools/devclean/media_clean.py --apply    # 搬走到 trash，并写 manifest

## 红线

1. **只扫** `media/{video,image,cover}` 三个子目录（用户确认的三目录）；
2. URL→磁盘路径的解析**逐字复用** `FileUploadService`（`/upload/(video|image|cover)/名称` + `..` 守卫）；
3. 脚本**不引用本仓目录之外的任何盘符**（trash 在媒体根的**同级**，不是别的盘）。

## 退出码

    0  成功    1  配置 / 连接 / 扫描失败    2  未找到 mysql 客户端
"""

from __future__ import annotations

import argparse
import re
import shutil
import sys
from datetime import datetime
from pathlib import Path

from common import load_conn, media_root, run_mysql

# 与 FileUploadService.UPLOAD_URL_PATTERN 逐字对应（含三个子目录与文件名合法字符集）。
URL_PATTERN = re.compile(r"^/upload/(video|image|cover)/([A-Za-z0-9._-]+)$")
MEDIA_DIRS = ["video", "image", "cover"]


def referenced_urls(conn) -> tuple[set[str], int, int]:
    """返回 (被引用的 URL 集合, 无效 URL 数)；无效 = 不匹配正则 / 含 `..`（无法定位磁盘路径）。"""
    stdout = run_mysql(conn, "SELECT url FROM content_media")
    refs: set[str] = set()
    invalid = 0
    for line in stdout.splitlines():
        url = line.rstrip("\r")
        if not url:
            continue
        m = URL_PATTERN.match(url)
        if m and ".." not in m.group(2):
            refs.add(url)
        else:
            invalid += 1
    return refs, invalid, len(stdout.splitlines())


def scan(root: Path) -> list[Path]:
    """扫描三个子目录下的**文件**（不递归、不跟随目录），返回文件路径列表。"""
    files: list[Path] = []
    for d in MEDIA_DIRS:
        sub = root / d
        if not sub.is_dir():
            continue
        for entry in sorted(sub.iterdir()):
            if entry.is_file():
                files.append(entry)
    return files


def url_of(root: Path, path: Path) -> str:
    """磁盘文件 → 应用内相对 URL（与 FileUploadService.saveFile 造 URL 的口径一致）。"""
    rel_dir = path.parent.name
    return f"/upload/{rel_dir}/{path.name}"


def human_bytes(n: int) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.1f} {unit}" if unit != "B" else f"{n} B"
        n /= 1024.0
    return f"{n:.1f} GB"


def unique_target(target: Path) -> Path:
    """trash 里同名已存在时，追加 `-1` / `-2`… 后缀，绝不覆盖。"""
    if not target.exists():
        return target
    stem, suffix, parent = target.stem, target.suffix, target.parent
    i = 1
    while True:
        cand = parent / f"{stem}-{i}{suffix}"
        if not cand.exists():
            return cand
        i += 1


def main() -> int:
    parser = argparse.ArgumentParser(description="开发期数据清理·媒体孤儿清理（默认 dry-run）")
    parser.add_argument("--apply", action="store_true", help="真搬（默认只列孤儿，不落盘）")
    args = parser.parse_args()

    root = media_root()
    if not root.is_dir():
        print(f"错误: 媒体根不存在: {root}", file=sys.stderr)
        return 1
    trash = root.parent / "trash"

    conn = load_conn()
    try:
        refs, invalid, total_rows = referenced_urls(conn)
        # 被排除的引用源：本仓无消费者的表（其 URL 一律按孤儿处理）。此处只**登记**，不据此判断。
        excluded_comment_media = int(run_mysql(conn, "SELECT COUNT(*) FROM comment_media").strip() or "0")
    except RuntimeError as e:
        print(f"错误: {e}", file=sys.stderr)
        return 1

    disk_files = scan(root)
    orphans = [p for p in disk_files if url_of(root, p) not in refs]
    orphan_bytes = sum(p.stat().st_size for p in orphans)

    # 被引用但盘上缺失（仅登记，**不据此判断** —— `file_exists=0` 也含"从未扫描"，见 F-05/E-2）。
    missing = [u for u in refs if not (root / u.split("/")[2] / u.split("/")[3]).is_file()]

    print(f"媒体根: {root}")
    print(f"目标库: {conn.describe()}")
    print(f"content_media 行数 {total_rows} / 有效引用 URL {len(refs)} / 无效 URL（无法定位）{invalid}")
    print(f"被排除的引用源（一律按孤儿处理）: comment_media {excluded_comment_media} 行"
          f" —— 若将来启用它，其图片会被误判为孤儿")
    print(f"磁盘三目录文件 {len(disk_files)} 个"
          f" / 孤儿 {len(orphans)} 个（{human_bytes(orphan_bytes)}）")
    print(f"被引用但盘上缺失 {len(missing)} 个"
          f"（⚠️ 仅登记：含'从未扫描'误报，勿据此判断，见 F-05/E-2）")

    if orphans:
        print("\n孤儿样例（最多 20 个）：")
        for p in orphans[:20]:
            print(f"  {p.relative_to(root)}")
        if len(orphans) > 20:
            print(f"  … 另有 {len(orphans) - 20} 个")

    if not args.apply:
        print(f"\n[dry-run] 未搬动任何文件。加 --apply 才会移到 {trash}。")
        return 0

    if not orphans:
        print("\n[apply] 无孤儿，无需搬动。")
        return 0

    trash.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    lines: list[str] = []
    for p in orphans:
        rel = p.relative_to(root)
        dst_dir = trash / rel.parent.name
        dst_dir.mkdir(parents=True, exist_ok=True)
        target = unique_target(dst_dir / p.name)
        shutil.move(str(p), str(target))
        lines.append(f"{p.relative_to(root).as_posix()} -> trash/{target.relative_to(trash).as_posix()}")

    manifest = trash / f"manifest-{stamp}.txt"
    manifest.write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(f"\n[apply] 已搬动 {len(orphans)} 个文件到 {trash}")
    print(f"清单: {manifest}")
    print("★ 复核无误后，由你手动把它们送进 Windows 回收站（脚本不做删除）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
