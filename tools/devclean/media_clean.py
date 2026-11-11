#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""开发期数据清理 · **媒体清理脚本**（T31；`R-37` 起接管头像延迟回收）。

## 它清什么

引用源有**两处**：

- `content_media.url` —— 内容媒体的权威引用源（用户 2026-10-17 确认：`comment_media` / `video`
  已无消费者 ⇒ 它们的 URL 一律按孤儿处理）；
- `users.avatar_ref` —— **仅 `/upload/avatar/...`**（`R-37`：换头像不再立即删旧文件，
  旧 URL 要活过 5 分钟的读缓存窗口；真正的回收由本脚本做）。

于是：

    盘上某个文件，若没有任何引用源指向它 ⇒ 孤儿 ⇒ 移到 `<媒体根同级>/trash/`。

**只移动、不删除**：删由人来做（确认无误后进 Windows 回收站）。`trash` 目录**不参与扫描**，
也不被任何自动步骤清理。

## 用法

    python tools/devclean/media_clean.py            # 默认 dry-run：列出孤儿（不搬）
    python tools/devclean/media_clean.py --apply    # 搬走到 trash，并写 manifest
    python tools/devclean/media_clean.py --apply --min-age 300  # 只搬 5 分钟前的孤儿（默认）

## 红线

1. **只扫** `media/{video,image,cover,avatar}` 四个子目录；
2. URL→磁盘路径的解析**逐字复用** `FileUploadService`（`/upload/(video|image|cover|avatar)/名称`
   + `..` 守卫）；
3. 脚本**不引用本仓目录之外的任何盘符**（trash 在媒体根的**同级**，不是别的盘）。

## 退出码

    0  成功    1  配置 / 连接 / 扫描失败    2  未找到 mysql 客户端
"""

from __future__ import annotations

import argparse
import re
import shutil
import sys
import time
from datetime import datetime
from pathlib import Path

from common import load_conn, media_root, run_mysql

# FileUploadService.UPLOAD_URL_PATTERN 的**扫描子集**：不含 `favorite-cover`
# （收藏夹封面由 favorite 域自己清理，R-33 的已知代价；这里不扫就不会误搬）。
URL_PATTERN = re.compile(r"^/upload/(video|image|cover|avatar)/([A-Za-z0-9._-]+)$")
MEDIA_DIRS = ["video", "image", "cover", "avatar"]


def parse_refs(stdout: str) -> tuple[set[str], int]:
    """把 mysql 输出的 URL 行解析成（有效集合, 无效数）。"""
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
    return refs, invalid


def referenced_urls(conn) -> tuple[set[str], int, int]:
    """返回 (content_media 被引用的 URL 集合, 无效 URL 数, 行数)。"""
    stdout = run_mysql(conn, "SELECT url FROM content_media")
    refs, invalid = parse_refs(stdout)
    return refs, invalid, len(stdout.splitlines())


def avatar_refs(conn) -> tuple[set[str], int, int]:
    """返回 (users.avatar_ref 里的头像 URL 集合, 无效数, 行数)。`R-37` 的延迟回收引用源。"""
    stdout = run_mysql(
        conn, "SELECT avatar_ref FROM users WHERE avatar_ref LIKE '/upload/avatar/%'")
    refs, invalid = parse_refs(stdout)
    return refs, invalid, len(stdout.splitlines())


def scan(root: Path) -> list[Path]:
    """扫描四个子目录下的**文件**（不递归、不跟随目录），返回文件路径列表。"""
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
    parser.add_argument("--min-age", type=int, default=300,
                        help="只搬 mtime 早于 N 秒的孤儿（R-37 延迟回收窗口；默认 300）")
    args = parser.parse_args()

    root = media_root()
    if not root.is_dir():
        print(f"错误: 媒体根不存在: {root}", file=sys.stderr)
        return 1
    trash = root.parent / "trash"

    conn = load_conn()
    try:
        content_refs, invalid, total_rows = referenced_urls(conn)
        avatar_ref_set, avatar_invalid, avatar_rows = avatar_refs(conn)
        refs = content_refs | avatar_ref_set
        invalid += avatar_invalid
        # 被排除的引用源：本仓无消费者的表（其 URL 一律按孤儿处理）。此处只**登记**，不据此判断。
        excluded_comment_media = int(run_mysql(conn, "SELECT COUNT(*) FROM comment_media").strip() or "0")
    except RuntimeError as e:
        print(f"错误: {e}", file=sys.stderr)
        return 1

    disk_files = scan(root)
    now = time.time()
    candidates = [p for p in disk_files if url_of(root, p) not in refs]
    young = [p for p in candidates if now - p.stat().st_mtime < args.min_age]
    orphans = [p for p in candidates if now - p.stat().st_mtime >= args.min_age]
    orphan_bytes = sum(p.stat().st_size for p in orphans)

    # 被引用但盘上缺失（仅登记，**不据此判断** —— `file_exists=0` 也含"从未扫描"，见 F-05/E-2）。
    missing = [u for u in refs if not (root / u.split("/")[2] / u.split("/")[3]).is_file()]

    print(f"媒体根: {root}")
    print(f"目标库: {conn.describe()}")
    print(f"content_media 行数 {total_rows} / users.avatar_ref 行数 {avatar_rows}"
          f" / 有效引用 URL {len(refs)} / 无效 URL（无法定位）{invalid}")
    print(f"被排除的引用源（一律按孤儿处理）: comment_media {excluded_comment_media} 行"
          f" —— 若将来启用它，其图片会被误判为孤儿")
    print(f"磁盘四目录文件 {len(disk_files)} 个"
          f" / 孤儿 {len(orphans)} 个（{human_bytes(orphan_bytes)}）")
    print(f"跳过 {len(young)} 个「刚写入 < {args.min_age}s」的候选（R-37 延迟回收窗口）")
    print("未扫描目录：/upload/favorite-cover/（R-33 的已知代价，由 favorite 域自清）")
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
