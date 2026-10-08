#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""数据库一键备份（mysqldump）。

来源：迁移自 `old-project/TVhomework1/tools/backup.py`（老仓该脚本依赖 `tools/db_config.py`
+ `tools/env/` 体系，新仓没有这套东西，故此处连接参数改从 `application.yaml` 读）。

## 为什么它该存在

`application.yaml` 里写得很清楚：「**Flyway 管结构演进，不是备份；业务数据仍靠 dump**」。
但迁移收口时 `backup.py` 被一并判为「丢」（`archive/migration/测试策略与阶段验收.md` §6.3），
理由写的却是"已被新体系吸收"——而该处举证的 §6.4 **三条实例没有一条是备份**。
结果是：**业务数据留底的手段只剩"手敲 mysqldump"**，而 `FURTHER_ISSUES.md` F-04~F-09
（存量数据债）的第一条处置纪律就是「**先备份再动**」。本脚本即为此收回该裁剪。

## 用法

    python tools/backup.py                          # 默认输出 D:\\data\\projects\\MySpringTV\\backups\\<时间戳>\\
    python tools/backup.py --out backups            # 输出到仓库内 backups/（.gitignore 已排除）
    python tools/backup.py --dry-run                # 只打印将要执行的连接与命令，不落盘

## 退出码

    0  成功
    1  其它错误（异常 / mysqldump 非零退出 / 超时）
    2  mysqldump 不可用
    3  备份文件缺失 / 为空 / 内容校验失败

## 连接参数（**唯一来源 = `application.yaml` 的 dev 默认值**，环境变量可覆盖）

    DB_URL        整个 JDBC URL（优先；从中解析 host / port / database）
    DB_USERNAME   用户名          （对应 yaml 的 ${DB_USERNAME:root}）
    DB_PASSWORD   口令            （对应 yaml 的 ${DB_PASSWORD:MySQL}）
    DB_HOST / DB_PORT / DB_NAME   单独覆盖（bootstrap / 临时指向别的实例时用）

⚠️ 环境变量名与 `archive/migration/tools/tvconf.py`（迁移期只读工具）**保持一致**，
   免得同一个库出现两套变量名。

## 与老仓的差异（逐条）

    1. 连接参数不再来自 tools/env/{active,test,prod}.conf —— 改为 application.yaml + DB_* 覆盖。
    2. 默认输出目录 D:\\dev\\WorkSpace\\VideoPlatform\\auto_backup
       → D:\\data\\projects\\MySpringTV\\backups（本项目运行期数据已搬到 D:\\data\\projects\\MySpringTV\\，
         媒体根就在它下面；备份与媒体同根，仍在仓库外）。
    3. --out 相对路径**按仓库根解析**（老仓按 CWD，见 tools/README.md §3.2）。
    4. 口令改走 MYSQL_PWD 环境变量（不再出现在命令行/进程表里）。
    5. 新增 --dry-run。

## 为什么没有"默认只预演"

tools/README.md §3.3 要求"会改文件的脚本默认只预演"，但**本脚本不改任何已有文件**：
它只在 `--out` 下新建一个 `%Y%m%d_%H%M%S` 目录（存在即报错，绝不覆盖），属于**纯增量**。
此处若默认预演，危险方向恰好相反——**用户以为备份好了，实际一个字节都没落**。
故默认真跑，想看将执行什么用 `--dry-run`（名字与默认一致，不踩 README §3.3 那条坑）。
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path

# ── Windows 中文环境必备：强制 stdout/stderr 用 UTF-8（见 tools/README.md §3.1）
for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent
APP_YAML = ROOT / "src" / "main" / "resources" / "application.yaml"

# 默认输出根：与媒体根同属本项目的运行期数据根（仓库之外 ⇒ 不受 git / mvn clean 影响）。
DEFAULT_BACKUP_ROOT = Path(r"D:\data\projects\MySpringTV\backups")

COMMON_MYSQLDUMP_PATHS = [
    Path(r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe"),
    Path(r"C:\Program Files\MySQL\MySQL Server 8.4\bin\mysqldump.exe"),
    Path(r"C:\Program Files (x86)\MySQL\MySQL Server 8.0\bin\mysqldump.exe"),
]

# `${ENV:default}<后缀>` → `<default><后缀>`（后缀在 `}` 之外的情形本项目 DSN 里没有，仍按通用处理）
_RE_ENV_DEFAULT = re.compile(r"^\$\{([^:}]+)(?::(.*?))?\}(.*)$", re.DOTALL)
_RE_JDBC = re.compile(r"//([^:/]+):(\d+)/([^?]+)")


# ────────────────────────────── application.yaml（极简扁平化） ──────────────────────────────
def flat_yaml(path: Path) -> dict[str, str]:
    """极简 YAML 扁平化：`{a: {b: 1}}` ⇒ `{"a.b": "1"}`。

    ⚠️ 与 `archive/migration/tools/tvconf.flat_yaml()` 同源同局限：**只支持"嵌套 map + 标量"**，
    不支持列表 / 多行标量 / 锚点；值里的 `#` 会被当注释切掉。
    够读本仓 `application.yaml` 的 `spring.datasource.*`；**换带列表的 yaml 会静默读少键**。
    之所以不复用归档那份：归档目录是"只供追溯、勿作为运行期依赖"，见 `.docs/INDEX.md` §二。
    """
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


def env_default(raw: str) -> str:
    """把 `${ENV:default}` 还原成 `default`；无默认值（`${ENV}`）返回空串。"""
    v = raw.strip()
    m = _RE_ENV_DEFAULT.match(v)
    if m:
        default = m.group(2)
        suffix = m.group(3) or ""
        v = (default or "") + suffix
    if len(v) >= 2 and v[0] == v[-1] and v[0] in "\"'":
        v = v[1:-1]
    return v


class DbConn:
    """解析后的连接参数。口令只在内存里，除了进 MYSQL_PWD 不外泄。"""

    def __init__(self, host: str, port: str, user: str, password: str, database: str) -> None:
        self.host = host
        self.port = port
        self.user = user
        self.password = password
        self.database = database

    def describe(self) -> str:
        return f"{self.user}@{self.host}:{self.port}/{self.database}（口令已隐去）"


def load_conn() -> DbConn:
    """从 `application.yaml` 的 dev 默认值解析连接参数，环境变量优先。

    ⚠️ yaml 缺失 / 读不到 `spring.datasource.url` 时**不静默兜底**：宁可响亮失败——
    静默连到别的库上做备份，比没备份更坏（备份了个寂寞，人还以为有）。
    """
    if not APP_YAML.is_file():
        print(f"错误: 找不到 {APP_YAML}", file=sys.stderr)
        sys.exit(1)
    flat = flat_yaml(APP_YAML)
    raw_url = flat.get("spring.datasource.url")
    if raw_url is None:
        print(f"错误: {APP_YAML} 里没有 spring.datasource.url", file=sys.stderr)
        sys.exit(1)

    url = os.environ.get("DB_URL") or env_default(raw_url)
    m = _RE_JDBC.search(url)
    if not m:
        print(f"错误: 无法从 JDBC URL 解析 host/port/database: {url!r}", file=sys.stderr)
        sys.exit(1)
    host, port, database = m.group(1), m.group(2), m.group(3)

    return DbConn(
        host=os.environ.get("DB_HOST", host),
        port=os.environ.get("DB_PORT", port),
        user=os.environ.get("DB_USERNAME") or env_default(flat.get("spring.datasource.username", "root")),
        password=os.environ.get("DB_PASSWORD")
        or env_default(flat.get("spring.datasource.password", "")),
        database=os.environ.get("DB_NAME", database),
    )


# ────────────────────────────── mysqldump ──────────────────────────────
def find_mysqldump() -> Path | None:
    found = shutil.which("mysqldump")
    if found:
        return Path(found)
    for candidate in COMMON_MYSQLDUMP_PATHS:
        if candidate.exists():
            return candidate
    return None


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def build_cmd(mysqldump: Path, conn: DbConn, dump_path: Path) -> list[str]:
    return [
        str(mysqldump),
        f"--host={conn.host}",
        f"--port={conn.port}",
        f"--user={conn.user}",
        "--single-transaction",          # 不锁表的一致性快照（InnoDB）
        "--routines",
        "--triggers",
        "--default-character-set=utf8mb4",
        f"--result-file={dump_path}",    # 直接写文件，避开 Windows 文本模式的 CRLF 翻译
        conn.database,
    ]


def write_manifest(backup_dir: Path, dump_path: Path, conn: DbConn, started_at: str) -> None:
    lines = [
        f"started_at={started_at}",
        f"finished_at={datetime.now().strftime('%Y-%m-%d %H:%M:%S')}",
        f"host={conn.host}",
        f"port={conn.port}",
        f"database={conn.database}",
        f"dump_file={dump_path.name}",
        f"size_bytes={dump_path.stat().st_size}",
        f"sha256={sha256_of(dump_path)}",
        "",
        "说明：结构由 Flyway 管，本文件是**业务数据**留底（动库前先跑一遍，见 task/FURTHER_ISSUES.md"
        " F-04~F-09 的处置纪律）；媒体资源（D:/data/projects/MySpringTV/media）请自行打包备份。",
    ]
    (backup_dir / "manifest.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="数据库一键备份（--out 指定输出目录，相对路径按仓库根解析）",
    )
    parser.add_argument("--out", metavar="DIR", default=None,
                        help=f"备份输出目录（默认 {DEFAULT_BACKUP_ROOT}）")
    parser.add_argument("--dry-run", action="store_true",
                        help="只打印将执行的连接与命令，不落盘")
    args = parser.parse_args()

    mysqldump = find_mysqldump()
    if mysqldump is None:
        print("错误: 未找到 mysqldump，请确认 MySQL 已安装，或用 PATH / 环境变量让它可见")
        return 2

    conn = load_conn()
    if args.out:
        out_arg = Path(args.out)
        base_dir = out_arg if out_arg.is_absolute() else (ROOT / out_arg)
    else:
        base_dir = DEFAULT_BACKUP_ROOT

    print(f"目标库: {conn.describe()}")
    print(f"客户端: {mysqldump}")

    if args.dry_run:
        stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        preview = base_dir / stamp / "db.sql"
        print(f"[dry-run] 将创建: {preview}")
        print("[dry-run] 命令: " + " ".join(build_cmd(mysqldump, conn, preview)))
        print("[dry-run] 未执行任何备份（去掉 --dry-run 即真跑）")
        return 0

    base_dir.mkdir(parents=True, exist_ok=True)
    started_at = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    backup_dir = base_dir / stamp
    backup_dir.mkdir(parents=True, exist_ok=False)   # 同秒重复 ⇒ 报错，不覆盖
    dump_path = backup_dir / "db.sql"

    print(f"[{stamp}] 开始备份数据库 {conn.database} -> {dump_path}")
    try:
        proc = subprocess.run(
            build_cmd(mysqldump, conn, dump_path),
            capture_output=True, text=True, timeout=600,
            env=dict(os.environ, MYSQL_PWD=conn.password),
        )
    except subprocess.TimeoutExpired:
        print("错误: mysqldump 执行超时（600s）")
        return 1

    if proc.returncode != 0:
        print("错误: mysqldump 失败，退出码 " + str(proc.returncode))
        print((proc.stderr or proc.stdout).strip())
        return 1

    if not dump_path.exists() or dump_path.stat().st_size == 0:
        print("错误: 备份文件缺失或为空")
        return 3

    content = dump_path.read_text(encoding="utf-8", errors="replace")
    if "Dump completed" not in content:
        print("错误: 备份内容校验失败（未找到 Dump completed 标记）")
        return 3

    write_manifest(backup_dir, dump_path, conn, started_at)
    print(f"备份成功: {dump_path} ({dump_path.stat().st_size} bytes)")
    print(f"清单: {backup_dir / 'manifest.txt'}")
    print("媒体资源（D:/data/projects/MySpringTV/media）请自行打包备份。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
