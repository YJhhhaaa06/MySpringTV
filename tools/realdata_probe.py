#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""真数据通路探针（第四批 **T7-1**）：只读地检查存量库/媒体根里的数据，产出**三张清单**。

## 它要回答什么

第三批 T6 的浏览器走查证明了"真库 + 真媒体根 + 跨层一致性 + 存量内容读出 + 磁盘断言"。
本探针补上**只有真数据能暴露**的三处缺口，并把结论变成**可复算产物**：

| # | 检查 | 为什么只有真数据能暴露 |
|---|------|---------------------|
| 1 | 存量行是否违反新代码假设（type/category 越界、时间倒挂……） | 容器数据是现造的，天然合法 |
| 2 | `content_media.url` → 媒体根磁盘文件的匹配率（含孤儿媒体行） | 存量孤儿/缺失的**分布**只在真数据上存在 |
| 3 | 计数列 vs 明细表实际行数（content.like/comment_count、users.follow/follower_count、comment.like_count） | 老项目直改库测试，明细/计数**可能本就不一致** |
| 4 | `feed_inbox` / `feed_inbox_sync` 孤儿行 | 读侧假设"有同步行就得有窗口" |
| 5 | 读接口对**全部存量 id** 不 500（`/start`、`/search/IdSearch`、`/search/keywordSearch`、`/profile`、`/comment/show`、`/feed`） | 这才是"存量数据能被业务规则读出" |

## ★ 为什么必须**双趟**（单跑一遍什么都证明不了）

同一份发现，可能是"迁移引入的回归"，也可能是"老库本来就烂"——**没有任何东西能区分这两者**。
方法（`工单-第四批.md` §三）：

    ① 备份 TVDatabase + 保全 stone 目录/文件清单
    ② 探针对着【备份】跑  → baseline.json   ← ★ 唯一一次机会（此后新项目开始写库就拿不回了）
    ③ 探针对着【活库】跑  → now.json
    ④ 做差 → 三张清单

| 集合关系 | 结论 | 归属 |
|---|---|---|
| 发现 ∈ baseline | **存量脏数据**，不是迁移引入 | 《遗留台账》**E 类**（②） |
| 发现 ∈ now \ baseline | ★ **新项目引入的** | **迁移缺口，T7 内必须处置**（①） |
| 探针**无法判断对错**的 | 口径未定 | 《决策留痕表》（③） |

## 三张清单（输出的形态，不是"问题数"）

- **① 代码缺口**：500 / 400 / 解析失败 / 空指针 / **基线后新增的数据不变量违反** ⇒ **T7 内修代码 + 补测试**，**必须清零**；
- **② 存量脏数据**：baseline 就有、不致命 ⇒ 《遗留台账》E 类，分「可对账 / 不可对账」；
- **③ 口径未定**：探针无法判断对错的 ⇒ 先定口径，再谈对账。

> ★ **① 必须最先清零**：在"会 500 的状态"上跑对账 = 在不稳的地基上做修复。

## 用法

    # 基线趟（必须先做，只有一次机会）——默认只预演，落盘要显式 --apply
    python tools/realdata_probe.py --baseline --apply
    python tools/realdata_probe.py --baseline "TVDatabase_backup" --apply          # 备份库在同一个 server 上
    python tools/realdata_probe.py --baseline "127.0.0.1:3307/TVDatabase_backup" --apply

    # 当前趟（需要已存在 baseline.json；默认只预演）
    python tools/realdata_probe.py
    python tools/realdata_probe.py --apply
    python tools/realdata_probe.py --base-url http://localhost:8080 --token <JWT>   # 追加第 5 项 HTTP 探活

`--baseline [SPEC]` 的 SPEC 语法：`[user[:pass]@][host[:port]]/database`（省略的段沿用主连接）；
只写 `--baseline`（不带值）= 对着**主连接**做基线（仅当该库此刻尚未被新项目写过才有意义）。

**退出码**：`0` = ① 清零且第 5 项已执行；`1` = 有**代码缺口**（①）；`2` = 前置/连接错误
（缺 baseline、连不上库、媒体根不可读）；`3` = ① 已清零但**第 5 项 HTTP 未执行**（缺 `--base-url`
或应用不可达）——**不完整，不能算通过**。

**双趟的行级身份**：diff 不只看"问题类别名"，还要求 `now 的行集 ⊆ 基线行集`；
否则"同一类问题在基线后**新长出的行**"会被误判成存量（评审指出的一处真漏洞）。

## 只读保证（验收第 4 条）

- 对库：**只发 SELECT**（`_q()` 会拒绝任何非 SELECT 语句）；不 INSERT/UPDATE/DELETE/DDL。
- 对盘：只 `os.walk` + `stat`；不创建/删除/修改任何文件。
- 落盘：**默认只预演**（什么都不写）；只有 `--apply` 才写 `target/realdata_probe/`（不污染仓库）。
  ⚠️ 本脚本**没有** `--dry-run` 开关——按 `tools/README.md` §3.3，「默认即预演」，不搞名字与默认值对不上。

## 局限（诚实声明，别让探针假装全面）

- **url→磁盘路径**是正则启发式（形状 `/upload/{video|image|cover}/{文件名}` + `..` 守卫），
  与 `FileUploadService.resolveAbsolutePath` 同口径，但只在**本仓当前约定**下成立。
- **`file_exists` 不作为"文件是否存在"的依据**——V1 建表注释是 `0缺失/未扫描`（E-2），
  探针一律**自己查磁盘**，避免把"从未扫描"误报成"文件缺失"。
- **计数口径未定**：`content.comment_count` 是否含软删行需人拍板（《遗留台账》E-1/E-4 的处置前提）。
  探针按"非软删(active)"口径给差异，并把「口径未定」单列 ③，**不替人下结论**。
- **`feed_inbox_sync` 有行但窗口为空**是否算脏由人定（E-5：可能合法——用户确实没关注人或全是大V），故列入 ③。
- **探针测不出**：前端行为（T6 已走查）、并发/时序类脏（丢更新/脏读，有既有并发测试）、
  数据"业务上不合理"（只有不变量、没有业务真值）、媒体文件**内容**是否正确（只验存在性）。
- **第 5 项 HTTP 探活**需应用在跑，默认**不跑**（不给 `--base-url` 即在报告里显式登记「未执行」，不假装全面）。
- 服务在**沙箱外**运行时媒体根才可读/可写；本脚本只读，但仍建议在能读到媒体根的进程环境里跑。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field, replace
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import tvconf  # noqa: E402

for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")

# 与 FileUploadService.UPLOAD_URL_PATTERN 同形（含 ".." 逃逸守卫在 url_to_rel 里做）
UPLOAD_URL_PATTERN = re.compile(r"^/upload/(video|image|cover)/([A-Za-z0-9._-]+)$")

# 三张清单的稳定键（也是产物 JSON 的键）
LIST_CODE_GAP = "code_gap"
LIST_DIRTY = "dirty_data"
LIST_UNDECIDED = "undecided"
LIST_TITLES = {
    LIST_CODE_GAP: "① 代码缺口（T7 内必须清零）",
    LIST_DIRTY: "② 存量脏数据（E 类；可对账/不可对账）",
    LIST_UNDECIDED: "③ 口径未定（决策留痕表）",
}


# ══════════════════════════════════════════════════════════════════════════
# 数据模型
# ══════════════════════════════════════════════════════════════════════════

@dataclass
class Finding:
    """一条探针发现。`check` + `key` 构成双趟 diff 的稳定标识；`rows` 是**行级身份**。

    `rows` 存在的原因（评审指出的一处真漏洞）：只按 `(check,key)` 集合做差，
    无法发现"同一类问题里**新长出来的行**"（如 `content_like` 不一致从 3 行变 10 行会仍判 ②）。
    故凡能给出行的检查都填 `rows`（稳定排序的 id 串），diff 时要求 `now.rows ⊆ baseline.rows`。
    """

    check: str          # 归类：domain / counter / media / feed / http
    key: str            # 稳定标识（同一问题在双趟里必须同名）
    detail: str         # 人读描述
    severity: str = "中"    # 高 / 中 / 低
    subclass: str = ""      # E 类处置口径：可对账 / 不可对账 / 可重算 / 待定
    klass: str = ""         # "" = 参与双趟 diff；"undecided" = 直接进 ③
    rows: list[str] = field(default_factory=list)   # 行级身份（稳定排序）

    def as_dict(self) -> dict:
        return {
            "check": self.check, "key": self.key, "detail": self.detail,
            "severity": self.severity, "subclass": self.subclass,
            "klass": self.klass, "rows": self.rows,
        }


@dataclass
class Probe:
    """一趟采集的完整结果。"""

    findings: list[Finding] = field(default_factory=list)
    summary: dict = field(default_factory=dict)
    inventory: dict[str, int] = field(default_factory=dict)   # 仅基线趟：stone 相对路径→字节数
    notes: list[str] = field(default_factory=list)

    def add(self, *findings: Finding) -> None:
        self.findings.extend(findings)


# ══════════════════════════════════════════════════════════════════════════
# 只读查询封装
# ══════════════════════════════════════════════════════════════════════════

def _q(conn: tvconf.Conn, sql: str) -> list[list[str]]:
    """只读地跑一条 SELECT。**非 SELECT 一律拒绝**（验收第 4 条的硬保证）。"""
    if not sql.lstrip().upper().startswith("SELECT"):
        raise AssertionError(f"探针只允许 SELECT，收到：{sql[:60]!r}")
    return tvconf.query(conn, sql)


def _qint(conn: tvconf.Conn, sql: str) -> int:
    rows = _q(conn, sql)
    return int(rows[0][0]) if rows and rows[0][0] not in ("", "NULL") else 0


def _cell(value: str) -> str:
    return "" if value == "NULL" else value


def _int(value: str) -> int | None:
    return None if value in ("", "NULL") else int(value)


# ══════════════════════════════════════════════════════════════════════════
# 检查 1：存量行是否违反新代码假设（域约束）
# ══════════════════════════════════════════════════════════════════════════

# (描述, 稳定键, SQL：返回违反行)。范围依据：type 1..2 / category_id 0..9（旧 ContentCache:526）、
# role 0..1、status 1..2、is_deleted 0..1；时间倒挂 = update_time < create_time。
DOMAIN_CHECKS: list[tuple[str, str, str]] = [
    ("content.type ∉ {1,2}", "content.type",
     "SELECT id, type FROM content WHERE type NOT IN (1,2)"),
    ("content.category_id ∉ 0..9", "content.category_id",
     "SELECT id, category_id FROM content WHERE category_id < 0 OR category_id > 9"),
    ("content.is_deleted ∉ {0,1}", "content.is_deleted",
     "SELECT id, is_deleted FROM content WHERE is_deleted NOT IN (0,1)"),
    ("content 时间倒挂(update_time<create_time)", "content.time",
     "SELECT id, create_time, update_time FROM content WHERE update_time < create_time"),
    ("content_media.type ∉ {1,2,3}", "content_media.type",
     "SELECT id, type FROM content_media WHERE type NOT IN (1,2,3)"),
    ("content_media.sort<0", "content_media.sort",
     "SELECT id, sort FROM content_media WHERE sort < 0"),
    ("users.role ∉ {0,1}", "users.role",
     "SELECT id, role FROM users WHERE role NOT IN (0,1)"),
    ("comment.is_deleted ∉ {0,1}", "comment.is_deleted",
     "SELECT comment_id, is_deleted FROM comment WHERE is_deleted NOT IN (0,1)"),
    ("comment.reply_count<0", "comment.reply_count",
     "SELECT comment_id, reply_count FROM comment WHERE reply_count < 0"),
    ("comment 时间倒挂(update_time<create_time)", "comment.time",
     "SELECT comment_id, create_time, update_time FROM comment "
     "WHERE update_time < create_time"),
    ("coupon_order.status ∉ {1,2}", "coupon_order.status",
     "SELECT id, status FROM coupon_order WHERE status NOT IN (1,2)"),
]


def check_domains(conn: tvconf.Conn) -> list[Finding]:
    out: list[Finding] = []
    for desc, key, sql in DOMAIN_CHECKS:
        rows = _q(conn, sql)
        if not rows:
            continue
        sample = "; ".join(",".join(_cell(c) for c in r) for r in rows[:5])
        out.append(Finding(
            check="domain", key=key,
            detail=f"{desc}：{len(rows)} 行违反（示例 {sample}）",
            severity="中", subclass="待定",
            rows=sorted(_cell(r[0]) for r in rows),
        ))
    return out


# ══════════════════════════════════════════════════════════════════════════
# 检查 2：媒体 URL → 磁盘匹配率（+ 孤儿媒体行）
# ══════════════════════════════════════════════════════════════════════════

def url_to_rel(url: str) -> str | None:
    """`/upload/{dir}/{file}` → `dir/file`（相对媒体根）；非法/逃逸 ⇒ None。"""
    m = UPLOAD_URL_PATTERN.match(url or "")
    if not m:
        return None
    d, fn = m.group(1), m.group(2)
    if ".." in fn:
        return None
    return f"{d}/{fn}"


def scan_media(root: Path) -> dict[str, int]:
    """媒体根全量清单：相对 posix 路径 → 字节数（`-1` = stat 失败）。"""
    inv: dict[str, int] = {}
    for dirpath, _dirs, files in os.walk(root):
        for fn in files:
            p = Path(dirpath) / fn
            try:
                inv[p.relative_to(root).as_posix()] = p.stat().st_size
            except OSError:
                inv[p.relative_to(root).as_posix()] = -1
    return inv


def check_media(conn: tvconf.Conn, inventory: dict[str, int]) -> tuple[list[Finding], dict]:
    rows = _q(conn,
              "SELECT m.id, m.content_id, m.url, m.type, m.sort, m.file_exists, "
              "(m.last_verify_time IS NULL) FROM content_media m ORDER BY m.id")
    content_ids = {_cell(r[0]) for r in _q(conn, "SELECT id FROM content")}
    findings: list[Finding] = []
    total = len(rows)
    if total and not inventory:
        raise RuntimeError(
            f"media 根下清单为空但 content_media 有 {total} 行 ⇒ 媒体根不可读/不可达；"
            "为免把整盘子虚乌有地报成「文件缺失」，拒绝继续（请确认 --root 可读）")
    invalid: list[str] = []
    invalid_ids: list[str] = []
    missing: list[str] = []
    missing_ids: list[str] = []
    orphan: list[str] = []
    orphan_ids: list[str] = []
    never_verified = 0

    for mid, cid, url, _type, _sort, _flag, null_verify in rows:
        mid, cid, url = _cell(mid), _cell(cid), _cell(url)
        if null_verify == "1":
            never_verified += 1
        rel = url_to_rel(url)
        if rel is None:
            invalid.append(f"mediaId={mid} url={url!r}")
            invalid_ids.append(mid)
        elif rel not in inventory:
            missing.append(f"mediaId={mid} contentId={cid} url={url}")
            missing_ids.append(mid)
        if cid not in content_ids:
            orphan.append(f"mediaId={mid} contentId={cid}")
            orphan_ids.append(mid)

    if invalid:
        findings.append(Finding(
            "media", "invalid_url",
            f"URL 形状非法（非 /upload/{{video|image|cover}}/文件 或含 ..）：{len(invalid)} 条"
            f"；示例 {invalid[:3]}",
            severity="中", subclass="不可对账", rows=sorted(invalid_ids),
        ))
    if missing:
        findings.append(Finding(
            "media", "file_missing",
            f"URL 合法但磁盘无此文件：{len(missing)} 条；示例 {missing[:3]}",
            severity="中", subclass="可重算（scanAll 回写，不修数据）", rows=sorted(missing_ids),
        ))
    if orphan:
        findings.append(Finding(
            "media", "orphan_media",
            f"孤儿媒体行（content_id 不在 content）：{len(orphan)} 条；示例 {orphan[:3]}",
            severity="低", subclass="不可对账", rows=sorted(orphan_ids),
        ))

    resolved = total - len(invalid)
    exists = resolved - len(missing)
    rate = (exists / total * 100) if total else 100.0
    summary = {
        "media_rows": total, "media_resolved": resolved, "media_exists": exists,
        "media_missing": len(missing), "media_invalid": len(invalid),
        "media_orphan": len(orphan), "media_never_verified": never_verified,
        "media_match_rate": round(rate, 2), "media_files_on_disk": len(inventory),
    }
    return findings, summary


# ══════════════════════════════════════════════════════════════════════════
# 检查 3：计数列 vs 明细表（含 E-1 下溢前置）
# ══════════════════════════════════════════════════════════════════════════

# (列名, 稳定键前缀, 是否 unsigned, SQL：返回 <id, stored, computed> 不一致行)
COUNTER_CHECKS: list[tuple[str, str, bool, str]] = [
    ("content.like_count", "content_like", True,
     "SELECT c.id, c.like_count, COALESCE(d.n,0) FROM content c LEFT JOIN "
     "(SELECT content_id, COUNT(*) n FROM content_like GROUP BY content_id) d "
     "ON d.content_id=c.id WHERE c.like_count <> COALESCE(d.n,0)"),
    ("content.comment_count", "content_comment", True,
     "SELECT c.id, c.comment_count, COALESCE(d.n,0) FROM content c LEFT JOIN "
     "(SELECT content_id, COUNT(*) n FROM comment WHERE is_deleted=0 GROUP BY content_id) d "
     "ON d.content_id=c.id WHERE c.comment_count <> COALESCE(d.n,0)"),
    ("comment.like_count", "comment_like", False,
     "SELECT cm.comment_id, cm.like_count, COALESCE(d.n,0) FROM comment cm LEFT JOIN "
     "(SELECT comment_id, COUNT(*) n FROM comment_like GROUP BY comment_id) d "
     "ON d.comment_id=cm.comment_id WHERE cm.like_count <> COALESCE(d.n,0)"),
    ("users.follow_count", "users_follow", True,
     "SELECT u.id, u.follow_count, COALESCE(d.n,0) FROM users u LEFT JOIN "
     "(SELECT user_id, COUNT(*) n FROM follow GROUP BY user_id) d "
     "ON d.user_id=u.id WHERE u.follow_count <> COALESCE(d.n,0)"),
    ("users.follower_count", "users_follower", True,
     "SELECT u.id, u.follower_count, COALESCE(d.n,0) FROM users u LEFT JOIN "
     "(SELECT followed_user_id, COUNT(*) n FROM follow GROUP BY followed_user_id) d "
     "ON d.followed_user_id=u.id WHERE u.follower_count <> COALESCE(d.n,0)"),
]

# 明细侧孤儿行（计数差异的常见成因；与 E-3 同族）
ORPHAN_DETAIL_CHECKS: list[tuple[str, str, str]] = [
    ("content_like → content", "orphan_content_like",
     "SELECT COUNT(*) FROM content_like x LEFT JOIN content c ON c.id=x.content_id "
     "WHERE c.id IS NULL"),
    ("comment_like → comment", "orphan_comment_like",
     "SELECT COUNT(*) FROM comment_like x LEFT JOIN comment cm ON cm.comment_id=x.comment_id "
     "WHERE cm.comment_id IS NULL"),
    ("comment → content", "orphan_comment",
     "SELECT COUNT(*) FROM comment x LEFT JOIN content c ON c.id=x.content_id "
     "WHERE c.id IS NULL"),
    ("follow → users(关注者)", "orphan_follow_user",
     "SELECT COUNT(*) FROM follow x LEFT JOIN users u ON u.id=x.user_id WHERE u.id IS NULL"),
    ("follow → users(被关注者)", "orphan_follow_target",
     "SELECT COUNT(*) FROM follow x LEFT JOIN users u ON u.id=x.followed_user_id "
     "WHERE u.id IS NULL"),
    ("content → users(作者)", "orphan_content_author",
     "SELECT COUNT(*) FROM content x LEFT JOIN users u ON u.id=x.user_id WHERE u.id IS NULL"),
]


def check_counters(conn: tvconf.Conn) -> tuple[list[Finding], dict]:
    findings: list[Finding] = []
    summary: dict = {}
    for col, key, unsigned, sql in COUNTER_CHECKS:
        rows = _q(conn, sql)
        summary[key] = len(rows)
        if not rows:
            continue
        underflow = 0   # 会 500（unsigned 下溢）或已为负（signed）
        for _id, stored_s, computed_s in rows:
            stored, computed = int(stored_s), int(computed_s)
            # unsigned：只要「存 < 实」就存在下溢风险——扣减量按明细行数走（删主楼一次扣 1+楼内回复），
            # 不必非到 stored==0（评审指出：stored=3/实=5、删主楼带 3 条回复 ⇒ 3-4 仍下溢报 500）。
            if unsigned and stored < computed:
                underflow += 1
            elif not unsigned and stored < 0:
                underflow += 1
        sev = "高" if underflow else "中"
        sample = "; ".join(f"id={_cell(r[0])} 存={r[1]} 实={r[2]}" for r in rows[:5])
        extra = f"；其中 {underflow} 行「存<实」（unsigned 下溢 / signed 已负，写操作会 500 或静默变负）" \
            if underflow else ""
        findings.append(Finding(
            "counter", key,
            f"{col} 与明细行数不一致：{len(rows)} 行{extra}；示例 {sample}",
            severity=sev, subclass="可对账（须先定口径）",
            rows=sorted(_cell(r[0]) for r in rows),
        ))
    for desc, key, sql in ORPHAN_DETAIL_CHECKS:
        n = _qint(conn, sql)
        if n:
            findings.append(Finding(
                "counter", key,
                f"明细孤儿行（{desc}）：{n} 行",
                severity="中", subclass="不可对账", rows=[str(n)],
            ))
    return findings, summary


# ══════════════════════════════════════════════════════════════════════════
# 检查 4：feed 孤儿行
# ══════════════════════════════════════════════════════════════════════════

def check_feed(conn: tvconf.Conn) -> tuple[list[Finding], dict]:
    findings: list[Finding] = []

    sync_only = _q(conn,
                   "SELECT s.user_id FROM feed_inbox_sync s "
                   "WHERE NOT EXISTS (SELECT 1 FROM feed_inbox i WHERE i.user_id=s.user_id)")
    inbox_only = _q(conn,
                    "SELECT i.user_id, COUNT(*) FROM feed_inbox i "
                    "WHERE NOT EXISTS (SELECT 1 FROM feed_inbox_sync s WHERE s.user_id=i.user_id) "
                    "GROUP BY i.user_id")
    orphan_content = _qint(conn,
                           "SELECT COUNT(*) FROM feed_inbox i LEFT JOIN content c ON c.id=i.content_id "
                           "WHERE c.id IS NULL")
    summary = {
        "feed_sync_without_inbox": len(sync_only),
        "feed_inbox_without_sync": len(inbox_only),
        "feed_inbox_orphan_content": orphan_content,
    }

    if sync_only:
        findings.append(Finding(
            "feed", "sync_without_inbox",
            f"有同步行但窗口为空（feed_inbox_sync 有、feed_inbox 无）：{len(sync_only)} 个用户"
            f"；示例 {[_cell(r[0]) for r in sync_only[:5]]}",
            severity="低", subclass="待定",
            klass="undecided",   # 可能合法（用户确实没关注 / 全是大V）⇒ 口径未定
            rows=sorted(_cell(r[0]) for r in sync_only),
        ))
    if inbox_only:
        findings.append(Finding(
            "feed", "inbox_without_sync",
            f"有窗口行但无同步行（feed_inbox 有、feed_inbox_sync 无）：{len(inbox_only)} 个用户"
            f"；示例 {[(_cell(r[0]), r[1]) for r in inbox_only[:5]]}",
            severity="中", subclass="待定",
            klass="undecided",   # E-5 同族：「待探针确认」，双向都先归口径未定
            rows=sorted(_cell(r[0]) for r in inbox_only),
        ))
    if orphan_content:
        findings.append(Finding(
            "feed", "inbox_orphan_content",
            f"feed_inbox.content_id 不在 content：{orphan_content} 行",
            severity="中", subclass="不可对账", rows=[str(orphan_content)],
        ))
    return findings, summary


# ══════════════════════════════════════════════════════════════════════════
# 检查 5：读接口对全部存量 id 不 500（HTTP，可选）
# ══════════════════════════════════════════════════════════════════════════

def _http_get(url: str, token: str | None, timeout: int = 5) -> int | None:
    """返回 HTTP 状态码；连不上返回 None（不是"代码缺口"，是环境问题）。"""
    req = urllib.request.Request(url)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status
    except urllib.error.HTTPError as exc:
        return exc.code
    except Exception:  # URLError / timeout / ConnectionRefused……
        return None


def check_http(conn: tvconf.Conn, base_url: str, token: str | None) -> tuple[list[Finding], dict]:
    """第 5 项：读接口对全部存量 id 不 500。

    - 状态码 == code（D-14），故 5xx / 400 直接是**代码缺口**；404 / 401 另有语义（见下）；
    - **可达性预检**：`/start` 连不上 ⇒ 整项登记「未执行」并立即返回（评审：避免 741 次串行超时）；
    - `auth` 类（`/feed`）返回 401/403 ⇒ token 无效，登记「未执行」而**不**污染 ①。
    """
    base = base_url.rstrip("/")
    findings: list[Finding] = []

    first = _http_get(base + "/start", token)
    if first is None:
        findings.append(Finding(
            "http", "app_unreachable",
            f"GET /start 连不上（{base}）：应用未启动或地址不对 —— 第 5 项**未执行**",
            severity="低", klass="undecided",
        ))
        return findings, {"http_checked": 0, "http_executed": False,
                          "http_reason": "app unreachable"}

    checked = 0
    unreachable = 0
    aborted = False

    def probe(path: str, kind: str = "public") -> None:
        nonlocal checked, unreachable, aborted
        if aborted:
            return
        code = _http_get(base + path, token)
        checked += 1
        if code is None:
            unreachable += 1
            if unreachable >= 5:        # 早停：连不上就别再串行几百次
                aborted = True
            return
        if code >= 500:
            findings.append(Finding(
                "http", f"500 {path}",
                f"GET {path} → {code}（读接口 500 = 代码缺口，T7 内修）",
                severity="高",
            ))
        elif code == 400:
            findings.append(Finding(
                "http", f"400 {path}",
                f"GET {path} → 400（有效读请求不应 400）",
                severity="高",
            ))
        elif code in (401, 403):
            if kind == "auth":
                findings.append(Finding(
                    "http", "feed_auth_failed",
                    f"GET {path} → {code}（token 无效/未授权）——第 5 项对 /feed **未执行**",
                    severity="低", klass="undecided",
                ))
            else:
                findings.append(Finding(
                    "http", f"{code} {path}",
                    f"GET {path} → {code}（公开读接口不应 401/403）",
                    severity="高",
                ))
        elif code == 404:
            # 404 可能是"内容不可用"（视频行缺失）⇒ 合法语义，登记为待核，不进 ①
            findings.append(Finding(
                "http", f"404 {path}",
                f"GET {path} → 404（内容/资源不可用；需人核是否合法）",
                severity="低", subclass="待定", klass="undecided", rows=[path],
            ))

    probe("/start")

    content_ids = [_cell(r[0]) for r in _q(
        conn, "SELECT id FROM content WHERE is_deleted=0 ORDER BY id")]
    keywords: list[str] = []
    for row in _q(conn, "SELECT title FROM content WHERE is_deleted=0 ORDER BY id LIMIT 5"):
        title = _cell(row[0]).strip()
        if title:
            keywords.append(title[:4])
    for cid in content_ids:
        probe(f"/search/IdSearch?contentId={cid}")
        probe(f"/comment/show?contentId={cid}")
        if aborted:
            break

    for kw in (keywords or ["测试"]):
        probe("/search/keywordSearch?keyword=" + urllib.parse.quote(kw))

    user_ids = [_cell(r[0]) for r in _q(conn, "SELECT id FROM users ORDER BY id")]
    for uid in user_ids:
        probe(f"/profile?userId={uid}")
        if aborted:
            break

    if token:
        probe("/feed", kind="auth")
    else:
        findings.append(Finding(
            "http", "feed_skipped",
            "/feed 未探（未提供 --token；该端点需登录）",
            severity="低", klass="undecided",
        ))

    if aborted:
        findings.append(Finding(
            "http", "app_unreachable",
            f"HTTP 中途连不上（连续 {unreachable} 次失败后早停）—— 第 5 项**未执行完整**",
            severity="低", klass="undecided",
        ))

    executed = checked > 0 and unreachable == 0 and not aborted
    summary = {
        "http_checked": checked,
        "http_unreachable": unreachable,
        "http_content_ids": len(content_ids),
        "http_user_ids": len(user_ids),
        "http_feed_probed": bool(token),
        "http_executed": executed,
    }
    return findings, summary


# ══════════════════════════════════════════════════════════════════════════
# 采集与双趟 diff
# ══════════════════════════════════════════════════════════════════════════

def collect(conn: tvconf.Conn, root: Path, *, with_inventory: bool,
            base_url: str = "", token: str | None = None) -> Probe:
    probe = Probe()
    summary: dict = {}

    probe.add(*check_domains(conn))
    inv = scan_media(root)
    media_findings, media_summary = check_media(conn, inv)
    probe.add(*media_findings)
    summary.update(media_summary)

    counter_findings, counter_summary = check_counters(conn)
    probe.add(*counter_findings)
    summary.update(counter_summary)

    feed_findings, feed_summary = check_feed(conn)
    probe.add(*feed_findings)
    summary.update(feed_summary)

    if base_url:
        http_findings, http_summary = check_http(conn, base_url, token)
        probe.add(*http_findings)
        summary.update(http_summary)
    else:
        summary["http_executed"] = False      # 显式：第 5 项未跑，不得算"通过"

    summary["finding_total"] = len(probe.findings)
    probe.summary = summary
    if with_inventory:
        probe.inventory = inv
    return probe


FAST_FAIL_NOTE = (
    "★ 单跑一趟证明不了任何事：同一份发现分不清「迁移引入」与「老库本就烂」。"
    "先跑 --baseline --apply 生成 baseline.json，再跑当前趟做差。"
)


def classify(now: Probe, baseline: Probe) -> dict[str, list[Finding]]:
    """按双趟差分类成三张清单。

    - `klass == "undecided"` ⇒ ③ 口径未定；
    - HTTP 发现（`check == "http"`）⇒ ① 代码缺口（备份库上无从探 HTTP，无法与基线比）；
    - 数据发现：`(check,key)` 不在基线 **或** 出现**基线里没有的新行** ⇒ ①（新项目引入）；
      否则（同类问题、行集是基线子集）⇒ ② 存量脏数据。

    ⚠️ **行级身份**（评审修正）：只看 `(check,key)` 会把"同一类问题在基线后新长出的行"误判成 ②，
    掩盖迁移引入的回归；故数据发现必须满足 `now.rows ⊆ baseline.rows` 才算 ②。
    """
    base_by_key: dict[tuple[str, str], set[str]] = {
        (f.check, f.key): set(f.rows) for f in baseline.findings
    }
    lists: dict[str, list[Finding]] = {
        LIST_CODE_GAP: [], LIST_DIRTY: [], LIST_UNDECIDED: [],
    }
    for f in now.findings:
        if f.klass == "undecided":
            lists[LIST_UNDECIDED].append(f)
            continue
        if f.check == "http":
            lists[LIST_CODE_GAP].append(f)
            continue
        base_rows = base_by_key.get((f.check, f.key))
        if base_rows is None:
            lists[LIST_CODE_GAP].append(f)                 # 基线里根本没有这类发现 ⇒ 新增
        elif f.rows and not set(f.rows).issubset(base_rows):
            lists[LIST_CODE_GAP].append(f)                 # 同类问题里出现了新行 ⇒ 新增
        else:
            lists[LIST_DIRTY].append(f)
    return lists


# 固定的 ③ 口径未定项（不由数据行导出，而是"对账前必须先拍板"的前提）
UNDECIDED_NOTES: list[dict] = [
    {
        "check": "counter", "key": "口径:comment_count 含不含软删",
        "detail": "content.comment_count 的明细口径未定：本探针按「is_deleted=0(非软删)」计算；"
                  "对账前必须先拍板（CommentMapper 的「先软删会把楼内回复也置 1」注释证明这里真有过坑）",
        "severity": "高", "subclass": "待定", "klass": "undecided",
    },
]


# ══════════════════════════════════════════════════════════════════════════
# 渲染与落盘
# ══════════════════════════════════════════════════════════════════════════

def _fmt_findings(findings: list[Finding], limit: int) -> list[str]:
    lines = []
    for i, f in enumerate(findings):
        if i >= limit:
            lines.append(f"  …… 其余 {len(findings) - limit} 条见产物 JSON")
            break
        sub = f"［{f.subclass}］" if f.subclass else ""
        lines.append(f"  [{f.severity}]{sub} {f.detail}")
    return lines


def render(probe: Probe, lists: dict[str, list[Finding]] | None, *, limit: int,
           mode: str, media_root: Path, conn: tvconf.Conn) -> str:
    out: list[str] = []
    out.append("=" * 78)
    out.append(f"realdata_probe —— 真数据通路探针（只读）  [{mode}]")
    out.append(f"库：{conn.database} @ {conn.host}:{conn.port}    媒体根：{media_root}")
    out.append("=" * 78)

    s = probe.summary
    out.append("\n【采集摘要】")
    for k, v in s.items():
        out.append(f"  {k} = {v}")
    if probe.inventory:
        out.append(f"  stone 文件清单：{len(probe.inventory)} 个文件（已存入 baseline.json）")

    if lists is None:
        out.append("\n【基线趟发现】（尚未分类；分类需与当前趟做差）")
        by_check: dict[str, int] = {}
        for f in probe.findings:
            by_check[f.check] = by_check.get(f.check, 0) + 1
        for f in probe.findings:
            out.append(f"  · [{f.check}/{f.severity}] {f.detail}")
        out.append(f"\n  合计 {len(probe.findings)} 条：" +
                   ", ".join(f"{k}={v}" for k, v in sorted(by_check.items())))
        return "\n".join(out)

    totals = {k: len(v) for k, v in lists.items()}
    out.append("\n" + "-" * 78)
    out.append(f"三张清单：① 代码缺口={totals[LIST_CODE_GAP]}  "
               f"② 存量脏数据={totals[LIST_DIRTY]}  ③ 口径未定={totals[LIST_UNDECIDED]}")
    out.append("-" * 78)
    for key in (LIST_CODE_GAP, LIST_DIRTY, LIST_UNDECIDED):
        out.append(f"\n【{LIST_TITLES[key]}】共 {totals[key]} 条")
        if not lists[key]:
            out.append("  （空）")
        else:
            out.extend(_fmt_findings(lists[key], limit))

    out.append("\n【结论】")
    if totals[LIST_CODE_GAP]:
        out.append(f"  ✗ 代码缺口 {totals[LIST_CODE_GAP]} 条 —— T7 内必须清零（在会 500 的地基上不能对账）")
    else:
        out.append("  ✓ 代码缺口 = 0（① 类已清零）")
    out.append(f"  ② 存量脏数据 {totals[LIST_DIRTY]} 条 —— 逐条按「可对账/不可对账」登记进《遗留台账》E 类")
    out.append(f"  ③ 口径未定 {totals[LIST_UNDECIDED]} 条 —— 先定口径再对账")
    if not probe.summary.get("http_executed", False):
        out.append("  ⚠ 第 5 项 HTTP 探活**未执行**（缺 --base-url 或应用不可达）——不能算通过")
    out.append("\n注：探针只读、只查不变量；局限（测不出并发/业务真值/媒体内容）见文件头。")
    return "\n".join(out)


def dump_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


# ══════════════════════════════════════════════════════════════════════════
# CLI
# ══════════════════════════════════════════════════════════════════════════

def parse_conn_spec(spec: str, base: tvconf.Conn) -> tvconf.Conn:
    """解析 `[user[:pass]@][host[:port]]/database`（省略段沿用 base）。"""
    conn = replace(base)
    if not spec:
        return conn
    loc, _sep, db = spec.rpartition("/")
    if not db:                      # 只给了 db 名
        conn.database = spec
        return conn
    conn.database = db
    if loc:
        userinfo, _at, hostport = loc.rpartition("@")
        if userinfo:
            user, _c, pwd = userinfo.partition(":")
            conn.user = user
            if pwd:
                conn.password = pwd
        if hostport:
            host, _c, port = hostport.rpartition(":")
            if port.isdigit():
                conn.host, conn.port = host, int(port)
            else:
                conn.host = hostport
    return conn


def resolve_media_root(arg: str) -> str:
    if arg:
        return arg
    flat = tvconf.flat_yaml(tvconf.NEW_YAML)
    return tvconf.yaml_value(flat, "video.upload.root") or ""


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="真数据通路探针（T7-1）：只读检查存量库/媒体根，双趟产出三张清单。",
        epilog="基线趟：--baseline --apply；当前趟：直接跑（需已有 baseline.json）。"
               "本脚本只发 SELECT、不写库不写盘；默认只预演，--apply 才写 target/realdata_probe/。",
    )
    parser.add_argument("--baseline", nargs="?", const="", default=None, metavar="SPEC",
                        help="基线趟；SPEC=[user[:pass]@][host[:port]]/db（省略则用主连接）")
    parser.add_argument("--apply", action="store_true",
                        help="落盘产物到 --out（默认只预演，什么都不写）")
    parser.add_argument("--base-url", default="", help="启用第 5 项 HTTP 探活的基址（默认不跑）")
    parser.add_argument("--token", default="", help="/feed 的 Bearer token（可选）")
    parser.add_argument("--root", default="", help="媒体根（默认取 application.yaml 的 video.upload.root）")
    parser.add_argument("--out", type=Path, default=tvconf.ROOT / "target" / "realdata_probe",
                        help="产物目录（默认 <仓库根>/target/realdata_probe）")
    parser.add_argument("--limit", type=int, default=50, help="每张清单打印的明细上限（默认 50）")
    # 主连接覆盖（与 flyway_parity.py 同口径）
    parser.add_argument("--exe", default="", help="mysql 客户端路径（默认自动探测）")
    parser.add_argument("--host", default="", help="库主机（覆盖 application.yaml）")
    parser.add_argument("--port", type=int, default=0, help="库端口")
    parser.add_argument("--user", default="", help="库用户")
    parser.add_argument("--password", default="", help="库口令")
    parser.add_argument("--database", default="", help="库名")
    args = parser.parse_args(argv)

    root_raw = resolve_media_root(args.root)
    if not root_raw:
        print("✗ 媒体根为空：--root 或 application.yaml 的 video.upload.root 必须配置", file=sys.stderr)
        return 2
    media_root = Path(root_raw)
    if not media_root.is_dir():
        print(f"✗ 媒体根不存在或不是目录：{media_root}", file=sys.stderr)
        print("  提示：媒体根在沙箱允许范围之外时，请在能读到它的环境里运行（只读不写）。", file=sys.stderr)
        return 2

    conn = tvconf.conn_from_yaml(args.exe)
    for fld in ("host", "user", "password", "database"):
        if getattr(args, fld):
            setattr(conn, fld, getattr(args, fld))
    if args.port:
        conn.port = args.port

    out_dir: Path = args.out
    baseline_path = out_dir / "baseline.json"

    try:
        if args.baseline is not None:      # ---------------- 基线趟 ----------------
            bconn = parse_conn_spec(args.baseline, conn)
            probe = collect(bconn, media_root, with_inventory=True)
            print(render(probe, None, limit=args.limit, mode="基线趟",
                         media_root=media_root, conn=bconn))
            if args.apply:
                dump_json(baseline_path, {
                    "generated_at": datetime.now().isoformat(timespec="seconds"),
                    "connection": {"host": bconn.host, "port": bconn.port, "database": bconn.database},
                    "media_root": str(media_root),
                    "summary": probe.summary,
                    "findings": [f.as_dict() for f in probe.findings],
                    "inventory": probe.inventory,
                })
                print(f"\n✓ 已写基线：{baseline_path}")
            else:
                print("\n⚠ 未加 --apply：基线**未落盘**。基线趟必须 --apply，否则当前趟无法做差。")
            return 0

        # ---------------- 当前趟 ----------------
        if not baseline_path.is_file():
            print(f"✗ 找不到基线：{baseline_path}\n  {FAST_FAIL_NOTE}", file=sys.stderr)
            return 2
        baseline_raw = json.loads(baseline_path.read_text(encoding="utf-8"))
        baseline = Probe(
            findings=[Finding(**d) for d in baseline_raw.get("findings", [])],
            inventory=baseline_raw.get("inventory", {}),
        )
        probe = collect(conn, media_root, with_inventory=False,
                        base_url=args.base_url, token=args.token or None)
        if not args.base_url:
            probe.notes.append(
                "第 5 项 HTTP 探活**未执行**（未给 --base-url）——此项不能算通过，须显式登记。")
        lists = classify(probe, baseline)

        # 媒体清单差异（基线后新增/消失的文件）
        if baseline.inventory:
            now_inv = probe.inventory or scan_media(media_root)
            added = sorted(set(now_inv) - set(baseline.inventory))
            removed = sorted(set(baseline.inventory) - set(now_inv))
            probe.summary["media_added_since_baseline"] = len(added)
            probe.summary["media_removed_since_baseline"] = len(removed)
            if removed:
                for rel in removed[:10]:
                    lists[LIST_UNDECIDED].append(Finding(
                        "media", f"removed:{rel}",
                        f"基线后媒体文件消失：{rel}（需人核是否为新项目合法删除）",
                        severity="中", klass="undecided"))

        for d in UNDECIDED_NOTES:
            lists[LIST_UNDECIDED].append(Finding(**d))

        print(render(probe, lists, limit=args.limit, mode="当前趟",
                     media_root=media_root, conn=conn))
        for note in probe.notes:
            print(f"⚠ {note}")

        if args.apply:
            dump_json(out_dir / "now.json", {
                "generated_at": datetime.now().isoformat(timespec="seconds"),
                "connection": {"host": conn.host, "port": conn.port, "database": conn.database},
                "media_root": str(media_root),
                "summary": probe.summary,
                "findings": [f.as_dict() for f in probe.findings],
                "notes": probe.notes,
            })
            dump_json(out_dir / "lists.json", {
                "generated_at": datetime.now().isoformat(timespec="seconds"),
                "baseline": str(baseline_path),
                "media_root": str(media_root),
                "http_executed": probe.summary.get("http_executed", False),
                "notes": probe.notes,
                "summary": probe.summary,
                **{k: [f.as_dict() for f in v] for k, v in lists.items()},
            })
            print(f"\n✓ 已写产物：{out_dir / 'now.json'}、{out_dir / 'lists.json'}")
        if lists[LIST_CODE_GAP]:
            return 1
        if not probe.summary.get("http_executed", False):
            return 3          # ① 已清零，但第 5 项没跑 ⇒ 检查不完整，不能给"通过"
        return 0

    except RuntimeError as exc:
        print(f"✗ 前置/采集失败：{exc}", file=sys.stderr)
        print("  提示：确认库在跑、mysql 客户端可执行（--exe 可指定；本机通常在 "
              "C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe）、媒体根可读。",
              file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
