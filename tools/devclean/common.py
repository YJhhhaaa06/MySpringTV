#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""devclean 公共基建（T30）。

只做四件事，全部是**复用**而非重写：

1. 强制 stdout/stderr UTF-8（`tools/README.md` §3.1）；
2. 仓库根解析（§3.2，不依赖当前工作目录）；
3. 复用 `tools/backup.py` 的 `application.yaml` 解析与连接参数（**唯一来源**，不复制一份 —— 免得
   同一个库出现两套连接逻辑）；
4. `mysql` 客户端定位与执行（`--batch --skip-column-names`，输出可直接按行解析）。

⚠️ **本模块只引用本仓目录 / 媒体根 / DB，不感知任何其它盘符**（本轮红线②）—— 授权 agent 误操作时的
爆炸半径被锁在本项目目录内，跨盘备份由人手动完成。
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path

# ── Windows 中文环境必备：强制 stdout/stderr 用 UTF-8（tools/README.md §3.1）
for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parents[2]          # tools/devclean/common.py → 仓库根
TOOLS = ROOT / "tools"
APP_YAML = ROOT / "src" / "main" / "resources" / "application.yaml"

# 复用 tools/backup.py（同目录）——它已经有 flat_yaml / env_default / load_conn / DbConn。
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))
import backup as _backup  # noqa: E402

DbConn = _backup.DbConn
load_conn = _backup.load_conn
flat_yaml = _backup.flat_yaml
env_default = _backup.env_default

# `mysql` 客户端常见安装位置（与 backup.py 的 mysqldump 探测路径同源）。
COMMON_MYSQL_PATHS = [
    Path(r"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe"),
    Path(r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"),
    Path(r"C:\Program Files (x86)\MySQL\MySQL Server 8.0\bin\mysql.exe"),
]


def mysql_exe() -> Path | None:
    """定位 `mysql` 客户端：先 PATH，再常见安装目录。找不到返回 None。"""
    found = shutil.which("mysql")
    if found:
        return Path(found)
    for candidate in COMMON_MYSQL_PATHS:
        if candidate.exists():
            return candidate
    return None


def require_mysql() -> Path:
    exe = mysql_exe()
    if exe is None:
        print("错误: 未找到 mysql 客户端（请确认 MySQL 已安装，或用 PATH 让它可见）", file=sys.stderr)
        sys.exit(2)
    return exe


def run_mysql(conn: DbConn, sql: str, *, timeout: int = 600) -> str:
    """执行一段 SQL，返回 stdout（`--batch --skip-column-names` ⇒ 制表符分隔、无表头）。

    口令走 `MYSQL_PWD` 环境变量，不出现在命令行 / 进程表里（与 backup.py 同口径）。
    任何非零退出**响亮失败**（不静默吞错）——静默会让"以为清了其实没清"变成看不见的事故。
    """
    exe = require_mysql()
    cmd = [
        str(exe),
        f"--host={conn.host}",
        f"--port={conn.port}",
        f"--user={conn.user}",
        "--default-character-set=utf8mb4",
        "--batch",
        "--skip-column-names",
        f"--database={conn.database}",
        "--execute",
        sql,
    ]
    proc = subprocess.run(
        cmd, capture_output=True, text=True, timeout=timeout,
        env=dict(os.environ, MYSQL_PWD=conn.password),
    )
    if proc.returncode != 0:
        raise RuntimeError(
            f"mysql 执行失败（退出码 {proc.returncode}）:\n{(proc.stderr or proc.stdout).strip()}"
        )
    return proc.stdout


def parse_pairs(stdout: str) -> list[tuple[str, int]]:
    """把 `--batch --skip-column-names` 的两列输出（key<TAB>n）解析成 (key, int) 列表。"""
    pairs: list[tuple[str, int]] = []
    for line in stdout.splitlines():
        line = line.rstrip("\r")
        if not line:
            continue
        key, _, value = line.partition("\t")
        pairs.append((key, int(value) if value.strip() else 0))
    return pairs


def media_root() -> Path:
    """媒体磁盘根：`video.upload.root`（环境变量 `UPLOAD_ROOT` 优先）。

    与 `application.yaml` / `UploadProperties` 同源；解析失败**响亮失败**，不静默兜底。
    """
    env = os.environ.get("UPLOAD_ROOT")
    if env:
        return Path(env)
    if not APP_YAML.is_file():
        print(f"错误: 找不到 {APP_YAML}", file=sys.stderr)
        sys.exit(1)
    raw = flat_yaml(APP_YAML).get("video.upload.root")
    if raw is None:
        print(f"错误: {APP_YAML} 里没有 video.upload.root", file=sys.stderr)
        sys.exit(1)
    return Path(env_default(raw))
