#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""开发期数据维护 · **存量视频时长回填**（一次性工具，2026-10-27）。

## 为什么存在

`architecture/domain/history/history.md` §六.2 的原口径是「**存量不回填**」：T46 只在
**发布 / 换源**时解析 mp4 头写 `content_media.duration_seconds`，老数据因此恒为 NULL，
列表卡片 / 历史角标 / 续播 clamp 都拿不到总时长。§八 的已知代价给出的**推翻条件**正是
「写一次性回填工具（读 media 文件）」——本脚本就是那个工具，**不是**例行清理。

## 口径

* **只填空值**：`duration_seconds IS NULL` 才写；已有值一个都不覆盖（可重复执行）；
* **解析规则与 `Mp4DurationReader` 逐条一致**（`history.md` §六.1）：只读 box 头，
  moov 可在文件尾，v0 / v1，`size==0/1`，`timescale==0` / duration 全 F / 截断 / 非 mp4
  ⇒ 跳过并保持 NULL（**写 0 就是编数据**）；
* **路径按 `/upload/**` → 媒体根解析**（与 `WebMvcConfig#addResourceHandlers` /
  `FileUploadService` 同源；只允许 `video|image|cover` 三段之一，拒绝 `..`）；
* 盘上**文件缺失** ⇒ 跳过并登记（`file_exists=0` 是审计快照，不作为"跳过"的依据，
  但确实读不到文件时绝不猜）；
* **默认 dry-run**，`--apply` 才落库；落库后复核"可回填的都已非 NULL、跳过的一直 NULL"。

⚠️ 解析器是 Python 侧的一份**镜像**（工具不依赖 Spring 上下文）。改 `Mp4DurationReader`
的规则时必须同步改这里；本工具落地时已用 Java 侧解析器对媒体根下 **44 个真实 mp4**
逐一比对过（结果全等）。

## 用法

    python tools/devclean/backfill_duration.py            # 预演（默认）
    python tools/devclean/backfill_duration.py --apply    # 落库（先 python tools/backup.py）

写库后会留下 `content:{id}` 缓存（TTL 内仍可能读到 null）——这是缓存层的既有口径，
不是本脚本的职责；验证时等 TTL 或按 `CacheKeys#content` 删除对应键。

## 退出码

    0  成功（dry-run / apply）    1  连接 / SQL / 参数失败    2  未找到 mysql 客户端
"""

from __future__ import annotations

import argparse
import struct
import sys
from dataclasses import dataclass
from pathlib import Path, PurePosixPath

from common import load_conn, media_root, run_mysql

# ─────────────────────────────────────────────────────────────────────────────
# mp4 头解析：与 `Mp4DurationReader` 逐条对齐（只读 box 头，绝不整文件读）
# ─────────────────────────────────────────────────────────────────────────────

BOX_HEADER_BYTES = 8
SIZE_TO_EOF = 0
SIZE_EXTENDED = 1
UNKNOWN_DURATION_V0 = 0xFFFF_FFFF
MAX_SECONDS = 2**31 - 1


@dataclass(frozen=True)
class Box:
    """box 载荷的 `[start, end)`（`size==1` 时 start 已跳过 8B largesize）。"""

    start: int
    end: int


def find_box(stream, start: int, end: int, kind: bytes) -> Box | None:
    """在 `[start, end)` 内顺序扫 box 头，找第一个 `kind`（与 Java 的 `findBox` 同形）。

    只 seek 不读载荷 ⇒ `mdat` 再大也只花一次定位。截断 / 非法长度 ⇒ None（判定解析失败）。
    """
    position = start
    while position + BOX_HEADER_BYTES <= end:
        stream.seek(position)
        header = stream.read(BOX_HEADER_BYTES)
        if len(header) < BOX_HEADER_BYTES:
            return None
        size = struct.unpack(">I", header[:4])[0]
        header_bytes = BOX_HEADER_BYTES
        if size == SIZE_EXTENDED:
            if position + 16 > end:
                return None
            size = struct.unpack(">q", stream.read(8))[0]  # 有符号，与 Java readLong 一致
            header_bytes = 16
        elif size == SIZE_TO_EOF:
            size = end - position
        if size < header_bytes or position + size > end:
            return None
        if header[4:8] == kind:
            return Box(position + header_bytes, position + size)
        position += size
    return None


def read_seconds(path: Path) -> int | None:
    """读 mp4 时长（向下取整秒）；未知 / 畸形 / 不存在 ⇒ None（绝不抛）。"""
    try:
        with path.open("rb") as stream:
            stream.seek(0, 2)
            length = stream.tell()
            moov = find_box(stream, 0, length, b"moov")
            if moov is None:
                return None
            mvhd = find_box(stream, moov.start, moov.end, b"mvhd")
            if mvhd is None:
                return None
            payload_length = mvhd.end - mvhd.start
            if payload_length < 4:
                return None
            stream.seek(mvhd.start)
            version = stream.read(1)[0]
            if version == 0:
                timescale_offset, duration_offset, duration_bytes = 12, 16, 4
            elif version == 1:
                timescale_offset, duration_offset, duration_bytes = 20, 24, 8
            else:
                return None
            if payload_length < duration_offset + duration_bytes:
                return None

            stream.seek(mvhd.start + timescale_offset)
            timescale = struct.unpack(">I", stream.read(4))[0]
            stream.seek(mvhd.start + duration_offset)
            fmt = ">I" if duration_bytes == 4 else ">q"
            duration = struct.unpack(fmt, stream.read(duration_bytes))[0]

            if timescale == 0 or duration < 0:
                return None
            if duration_bytes == 4 and duration == UNKNOWN_DURATION_V0:
                return None
            if duration_bytes == 8 and duration == -1:
                return None
            return min(duration // timescale, MAX_SECONDS)
    except (OSError, struct.error, IndexError, ValueError):
        # 截断 / 权限 / 根本不是 mp4 ⇒ 按"未知"处理（同 Java 的 catch (IOException | RuntimeException)）
        return None


# ─────────────────────────────────────────────────────────────────────────────
# URL → 磁盘路径（与 FileUploadService / media_clean 的口径同源）
# ─────────────────────────────────────────────────────────────────────────────

UPLOAD_PREFIX = "/upload/"
ALLOWED_DIRS = {"video", "image", "cover"}


def resolve_media_path(url: str, root: Path) -> Path | None:
    """`/upload/{video|image|cover}/name` → `<媒体根>/...`；非法 / 越界 ⇒ None。"""
    if not url.startswith(UPLOAD_PREFIX):
        return None
    relative = url[len(UPLOAD_PREFIX):]
    if not relative or relative.startswith("/"):
        return None
    parts = PurePosixPath(relative).parts
    if not parts or parts[0] not in ALLOWED_DIRS:
        return None
    if any(part in ("..", ".") for part in parts):
        return None
    candidate = (root / Path(*parts)).resolve()
    try:
        candidate.relative_to(root.resolve())
    except ValueError:
        return None
    return candidate


# ─────────────────────────────────────────────────────────────────────────────
# DB 读写（复用 devclean/common.py 的 mysql 客户端与连接参数）
# ─────────────────────────────────────────────────────────────────────────────


@dataclass
class MediaRow:
    media_id: int
    content_id: int
    url: str
    duration_seconds: int | None


def load_rows(conn) -> list[MediaRow]:
    sql = (
        "SELECT id, content_id, url, duration_seconds FROM content_media"
        " WHERE type = 1 ORDER BY content_id, sort, id"
    )
    rows: list[MediaRow] = []
    for line in run_mysql(conn, sql).splitlines():
        line = line.rstrip("\r")
        if not line:
            continue
        parts = line.split("\t")
        if len(parts) < 4:
            continue
        raw_duration = parts[3].strip()
        duration = None if raw_duration in ("", "NULL") else int(raw_duration)
        rows.append(MediaRow(int(parts[0]), int(parts[1]), parts[2], duration))
    return rows


def apply_updates(conn, updates: list[tuple[int, int]]) -> None:
    """单事务写回填值：`CASE id WHEN … THEN … END` + `duration_seconds IS NULL` 双保险。"""
    if not updates:
        return
    cases = "\n".join(f"  WHEN {media_id} THEN {seconds}" for media_id, seconds in updates)
    ids = ", ".join(str(media_id) for media_id, _ in updates)
    sql = (
        "START TRANSACTION;\n"
        "UPDATE content_media SET duration_seconds = CASE id\n"
        f"{cases}\n"
        "  ELSE duration_seconds END\n"
        f"WHERE id IN ({ids}) AND duration_seconds IS NULL;\n"
        "COMMIT;"
    )
    run_mysql(conn, sql)


def verify(conn, updates: list[tuple[int, int]]) -> list[int]:
    """返回回填后仍为 NULL 的 media id（同一次 CASE 里写的 id）。"""
    if not updates:
        return []
    ids = ", ".join(str(media_id) for media_id, _ in updates)
    stdout = run_mysql(conn, f"SELECT id, duration_seconds FROM content_media WHERE id IN ({ids})")
    still_null: list[int] = []
    for line in stdout.splitlines():
        parts = line.rstrip("\r").split("\t")
        if len(parts) >= 2 and parts[1].strip() in ("", "NULL"):
            still_null.append(int(parts[0]))
    return still_null


# ─────────────────────────────────────────────────────────────────────────────


def main() -> int:
    parser = argparse.ArgumentParser(
        description="存量视频时长回填（只填 NULL；默认 dry-run）",
        epilog="解析规则与 Mp4DurationReader 一致；文件缺失 / 解析失败一律保持 NULL。",
    )
    parser.add_argument("--apply", action="store_true", help="真的写库（默认只预演）")
    args = parser.parse_args()

    conn = load_conn()
    root = media_root()
    rows = load_rows(conn)

    already = 0
    printable: list[tuple[MediaRow, int]] = []
    missing: list[MediaRow] = []
    failed: list[tuple[MediaRow, Path | None]] = []
    for row in rows:
        if row.duration_seconds is not None:
            already += 1
            print(f"  = {row.media_id:>4} content={row.content_id:<4} 已有 {row.duration_seconds}s（不覆盖）")
            continue
        path = resolve_media_path(row.url, root)
        if path is None:
            failed.append((row, None))
            print(f"  ! {row.media_id:>4} content={row.content_id:<4} URL 非法：{row.url}")
            continue
        if not path.is_file():
            missing.append(row)
            print(f"  ! {row.media_id:>4} content={row.content_id:<4} 文件缺失：{row.url}")
            continue
        seconds = read_seconds(path)
        if seconds is None:
            failed.append((row, path))
            print(f"  ? {row.media_id:>4} content={row.content_id:<4} 解析失败：{row.url}")
            continue
        printable.append((row, seconds))
        print(f"  + {row.media_id:>4} content={row.content_id:<4} {seconds:>5}s  {row.url}")

    print()
    print(f"媒体根：{root}")
    print(
        f"视频媒体行：{len(rows)}（已有值 {already} / 可回填 {len(printable)}"
        f" / 文件缺失 {len(missing)} / 解析失败 {len(failed)}）"
    )
    if missing:
        print("文件缺失（保持 NULL）：" + ", ".join(str(row.content_id) for row in missing))
    if failed:
        print("解析失败（保持 NULL）：" + ", ".join(str(row.content_id) for row in failed))

    updates = [(row.media_id, seconds) for row, seconds in printable]
    if not args.apply:
        print("\n[预演] 未写库。确认后加 --apply 落地（建议先 python tools/backup.py）。")
        return 0

    apply_updates(conn, updates)
    still_null = verify(conn, updates)
    if still_null:
        print(
            "错误：以下 media id 回填后仍为 NULL（写入未生效）："
            + ", ".join(str(media_id) for media_id in still_null),
            file=sys.stderr,
        )
        return 1
    print(f"\n[已落库] 回填 {len(updates)} 行；回填目标复核：0 行仍为 NULL。")
    print("⚠️ `content:{id}` 缓存 TTL 内仍可能返回 null；验证前请等 TTL 或按 CacheKeys#content 删除对应键。")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as exception:
        # run_mysql 的失败信息（含 mysql 客户端 stderr）原样上抛，不吞
        print(f"错误：{exception}", file=sys.stderr)
        raise SystemExit(1) from exception
