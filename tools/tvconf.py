#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""`tools/` 下脚本的**共用小工具**（非可执行脚本，无 CLI）。

为什么存在：`migration_matrix.py` 与 `flyway_parity.py` 都需要
「强制 UTF-8」「仓库根」「极简 YAML 扁平化」「调用 mysql.exe」四件事。
按本仓"**同一事实只写一处**"（INDEX §五.2）的纪律，它们不各写一份。

## 局限（诚实声明）

- `flat_yaml()` 是**极简 YAML 子集解析器**（只支持"嵌套 map + 标量"，
  **不支持列表 / 多行标量 / 锚点**），够读本仓 `application.yaml` 用。
  它不是 YAML 解析器，**换一份带列表的 yaml 会静默读少键**——故它只用于本仓自查脚本。
- 值里出现的 `#` 会被当作注释起点切掉（本仓没有这样的值）。
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent
OLD_ROOT = ROOT / "old-project" / "TVhomework1"
OLD_SRC_MAIN = OLD_ROOT / "src" / "main"
NEW_YAML = ROOT / "src" / "main" / "resources" / "application.yaml"
V1_SQL = ROOT / "src" / "main" / "resources" / "db" / "migration" / "V1__baseline_tv_schema.sql"

_RE_ENV_DEFAULT = re.compile(r"^\$\{([^:}]+)(?::(.*?))?\}(.*)$", re.DOTALL)


def env_default(raw: str) -> str | None:
    """把 `${ENV:default}<后缀>` 还原成 `default<后缀>`；无默认值（`${ENV}`）返回 None。

    ⚠️ **后缀必须保留**：本仓大量写成 `${REDIS_CONNECT_TIMEOUT_MS:1000}ms`
    ——`ms` 在 `}` **之外**，剥掉它会把 Duration 读成裸数字（曾经的假阳性来源）。
    ⚠️ **空默认值 `""` 与"无默认值"是两回事**：`${X:}` ⇒ `""`（键存在、值为空），
    `${X}` ⇒ `None`（键存在、值必须由环境给）。`feed.bigv.userIds` 是前者的实例。
    """
    v = raw.strip()
    m = _RE_ENV_DEFAULT.match(v)
    if m:
        default, suffix = m.group(2), m.group(3) or ""
        if default is None:
            return None
        v = default + suffix
    if len(v) >= 2 and v[0] == v[-1] and v[0] in "\"'":
        v = v[1:-1]
    return v


def flat_yaml(path: Path) -> dict[str, str]:
    """极简 YAML 扁平化：`{a: {b: 1}}` ⇒ `{"a.b": "1"}`（值保留原样，未经 env 还原）。"""
    out: dict[str, str] = {}
    stack: list[tuple[int, str]] = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].rstrip()
        if not line.strip() or ":" not in line:
            continue
        indent = len(line) - len(line.lstrip(" "))
        key, _, value = line.strip().partition(":")
        while stack and stack[-1][0] >= indent:
            stack.pop()
        if value.strip():
            prefix = ".".join(k for _, k in stack)
            out[f"{prefix}.{key.strip()}" if prefix else key.strip()] = value.strip()
        else:
            stack.append((indent, key.strip()))
    return out


def yaml_value(flat: dict[str, str], dotted: str) -> str | None:
    """取扁平化后的键值，并已还原 `${ENV:default}`。"""
    raw = flat.get(dotted)
    return None if raw is None else env_default(raw)


@dataclass
class Conn:
    host: str = "localhost"
    port: int = 3306
    user: str = "root"
    password: str = "MySQL"
    database: str = "spring_tv"
    exe: str = ""

    def args(self, sql: str) -> list[str]:
        return [
            self.exe,
            "-h",
            self.host,
            "-P",
            str(self.port),
            "-u",
            self.user,
            "--default-character-set=utf8mb4",
            "--batch",
            "--skip-column-names",
            self.database,
            "-e",
            sql,
        ]


def conn_from_yaml(exe: str = "") -> Conn:
    """连接参数默认值取自 `application.yaml` 的 dev 默认（与运行时同源），环境变量可覆盖。"""
    flat = flat_yaml(NEW_YAML)
    url = (
        os.environ.get("DB_URL")
        or yaml_value(flat, "spring.datasource.url")
        or "jdbc:mysql://localhost:3306/spring_tv"
    )
    m = re.search(r"//([^:/]+):(\d+)/([^?]+)", url)
    host = m.group(1) if m else "localhost"
    port = int(m.group(2)) if m else 3306
    database = m.group(3) if m else "spring_tv"
    return Conn(
        host=os.environ.get("DB_HOST", host),
        port=int(os.environ.get("DB_PORT", port)),
        user=os.environ.get("DB_USERNAME") or yaml_value(flat, "spring.datasource.username") or "root",
        password=os.environ.get("DB_PASSWORD") or yaml_value(flat, "spring.datasource.password") or "",
        database=os.environ.get("DB_NAME", database),
        exe=exe or mysql_exe(),
    )


def mysql_exe() -> str:
    """找 mysql 客户端。**本机它不在 PATH**（`C:\\Program Files\\MySQL\\...`），故显式兜底。"""
    for cand in (os.environ.get("MYSQL_EXE"), shutil.which("mysql")):
        if cand:
            return cand
    for pattern in (
        "Program Files/MySQL/MySQL Server */bin/mysql.exe",
        "Program Files (x86)/MySQL/MySQL Server */bin/mysql.exe",
    ):
        hits = sorted(Path("C:/").glob(pattern))
        if hits:
            return str(hits[-1])
    return "mysql"


def query(conn: Conn, sql: str) -> list[list[str]]:
    """跑一条只读 SQL，返回 TSV 行（每行按 `\\t` 切）。

    ⚠️ **客户端起不来要"响亮失败"**：`mysql_exe()` 找不到时会退回字符串 `"mysql"`，
    若不在这里转成 `RuntimeError`，调用方只 `except RuntimeError` 就会漏掉，
    用户看到的是裸 `FileNotFoundError` traceback 而不是可行动提示。
    """
    env = dict(os.environ, MYSQL_PWD=conn.password)
    try:
        proc = subprocess.run(
            conn.args(sql), capture_output=True, text=True, encoding="utf-8", errors="replace", env=env
        )
    except OSError as exc:
        raise RuntimeError(
            f"无法执行 mysql 客户端 {conn.exe!r}：{exc}\n"
            "  未在 PATH 上？用 --exe 或环境变量 MYSQL_EXE 指定。"
            "本机通常在 C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe"
        ) from exc
    if proc.returncode != 0:
        raise RuntimeError(f"mysql 退出码 {proc.returncode}：{proc.stderr.strip()}")
    return [ln.split("\t") for ln in proc.stdout.splitlines() if ln]
