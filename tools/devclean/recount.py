#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""开发期数据清理 · **计数重算脚本**（T32）。

## 口径（用户 2026-10-17 拍板）

**计数不含软删行** —— 只落在两处 `is_deleted`：`comment`（已在聚合里体现）与 `content` 行本身
（下面统一对所有行重算，不产生可观察差异）。

★ 重算口径必须与**运行时写路径**逐字一致，否则重算当天就会重新制造 `F-07` 那类"计数 vs 明细不一致"。
各列运行时来源见 `mapper/ContentMapper.xml` / `mapper/CommentMapper.xml` / `mapper/UserMapper.xml`。

## 覆盖的 7 列

    content.comment_count   = COUNT(comment WHERE content_id=? AND is_deleted=0)      （含楼中楼）
    content.like_count      = COUNT(content_like WHERE content_id=?)
    content.favorite_count  = COUNT(DISTINCT user_id) favorite_item WHERE content_id=?  （按人去重）
    users.follow_count      = COUNT(follow WHERE user_id=?)
    users.follower_count    = COUNT(follow WHERE followed_user_id=?)
    comment.like_count      = COUNT(comment_like WHERE comment_id=?)
    comment.reply_count     = COUNT(comment WHERE parent_id=? AND is_deleted=0)

## 用法

    python tools/devclean/recount.py            # 默认 dry-run：逐列报"当前漂移行数"（= F-07 探针）
    python tools/devclean/recount.py --apply    # 单事务重算，跑完复核漂移归零

## 实现要点

* UPDATE 一律 `LEFT JOIN 派生表 + COALESCE`：既躲开 MySQL **1093**（目标表自引用，`comment.reply_count`
  就踩这个），又用 `COALESCE` 把"零个明细"的行也**显式写回 0**（不变量对 0 成立）；
* dry-run 的漂移查询与 apply 的聚合**共用同一段派生表 SQL**（`AGG`）——口径只有一处。

## 退出码

    0  成功    1  连接 / SQL 失败    2  未找到 mysql 客户端
"""

from __future__ import annotations

import argparse
import sys

from common import load_conn, run_mysql, parse_pairs

# ─────────────────────────────────────────────────────────────────────────────
# 聚合派生表（口径的唯一落点）：每段都以「被聚合的列」为分组键，输出 `(键, n)`。
# ─────────────────────────────────────────────────────────────────────────────
AGG_COMMENT_BY_CONTENT = ("SELECT content_id, COUNT(*) AS n FROM comment"
                          " WHERE is_deleted = 0 GROUP BY content_id")
AGG_LIKE_BY_CONTENT = "SELECT content_id, COUNT(*) AS n FROM content_like GROUP BY content_id"
AGG_FAV_BY_CONTENT = ("SELECT content_id, COUNT(DISTINCT user_id) AS n FROM favorite_item"
                      " GROUP BY content_id")
AGG_FOLLOW_BY_USER = "SELECT user_id, COUNT(*) AS n FROM follow GROUP BY user_id"
AGG_FOLLOWER_BY_USER = ("SELECT followed_user_id AS user_id, COUNT(*) AS n FROM follow"
                        " GROUP BY followed_user_id")
AGG_CLIKE_BY_COMMENT = ("SELECT comment_id, COUNT(*) AS n FROM comment_like"
                        " GROUP BY comment_id")
AGG_REPLY_BY_PARENT = ("SELECT parent_id, COUNT(*) AS n FROM comment"
                       " WHERE parent_id IS NOT NULL AND is_deleted = 0 GROUP BY parent_id")

# 每项 = (标签, 漂移计数 SQL, [apply 语句…])
RECOUNTS: list[tuple[str, str, list[str]]] = [
    (
        "content.comment_count",
        f"SELECT COUNT(*) FROM content c LEFT JOIN ({AGG_COMMENT_BY_CONTENT}) a"
        f" ON a.content_id = c.id WHERE c.comment_count IS NULL"
        f" OR c.comment_count <> COALESCE(a.n, 0)",
        [f"UPDATE content c LEFT JOIN ({AGG_COMMENT_BY_CONTENT}) a ON a.content_id = c.id"
         f" SET c.comment_count = COALESCE(a.n, 0)"],
    ),
    (
        "content.like_count",
        f"SELECT COUNT(*) FROM content c LEFT JOIN ({AGG_LIKE_BY_CONTENT}) a"
        f" ON a.content_id = c.id WHERE c.like_count IS NULL"
        f" OR c.like_count <> COALESCE(a.n, 0)",
        [f"UPDATE content c LEFT JOIN ({AGG_LIKE_BY_CONTENT}) a ON a.content_id = c.id"
         f" SET c.like_count = COALESCE(a.n, 0)"],
    ),
    (
        "content.favorite_count",
        f"SELECT COUNT(*) FROM content c LEFT JOIN ({AGG_FAV_BY_CONTENT}) a"
        f" ON a.content_id = c.id WHERE c.favorite_count <> COALESCE(a.n, 0)",
        [f"UPDATE content c LEFT JOIN ({AGG_FAV_BY_CONTENT}) a ON a.content_id = c.id"
         f" SET c.favorite_count = COALESCE(a.n, 0)"],
    ),
    (
        "users.follow_count",
        f"SELECT COUNT(*) FROM users u LEFT JOIN ({AGG_FOLLOW_BY_USER}) a"
        f" ON a.user_id = u.id WHERE u.follow_count IS NULL"
        f" OR u.follow_count <> COALESCE(a.n, 0)",
        [f"UPDATE users u LEFT JOIN ({AGG_FOLLOW_BY_USER}) a ON a.user_id = u.id"
         f" SET u.follow_count = COALESCE(a.n, 0)"],
    ),
    (
        "users.follower_count",
        f"SELECT COUNT(*) FROM users u LEFT JOIN ({AGG_FOLLOWER_BY_USER}) a"
        f" ON a.user_id = u.id WHERE u.follower_count IS NULL"
        f" OR u.follower_count <> COALESCE(a.n, 0)",
        [f"UPDATE users u LEFT JOIN ({AGG_FOLLOWER_BY_USER}) a ON a.user_id = u.id"
         f" SET u.follower_count = COALESCE(a.n, 0)"],
    ),
    (
        "comment.like_count",
        f"SELECT COUNT(*) FROM comment c LEFT JOIN ({AGG_CLIKE_BY_COMMENT}) a"
        f" ON a.comment_id = c.comment_id WHERE c.like_count IS NULL"
        f" OR c.like_count <> COALESCE(a.n, 0)",
        [f"UPDATE comment c LEFT JOIN ({AGG_CLIKE_BY_COMMENT}) a ON a.comment_id = c.comment_id"
         f" SET c.like_count = COALESCE(a.n, 0)"],
    ),
    (
        "comment.reply_count",
        f"SELECT COUNT(*) FROM comment c LEFT JOIN ({AGG_REPLY_BY_PARENT}) a"
        f" ON a.parent_id = c.comment_id WHERE c.parent_id IS NULL"
        f" AND c.reply_count <> COALESCE(a.n, 0)",
        # ⚠️ reply_count 只对主楼有意义（parent_id IS NULL）；楼中楼自身恒为 0（默认值）⇒ 只更新主楼。
        [f"UPDATE comment c LEFT JOIN ({AGG_REPLY_BY_PARENT}) a ON a.parent_id = c.comment_id"
         f" SET c.reply_count = COALESCE(a.n, 0) WHERE c.parent_id IS NULL"],
    ),
]


def _sql_str(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def drift_sql() -> str:
    return " UNION ALL ".join(
        f"SELECT {_sql_str(label)} AS k, {query.replace('SELECT ', '', 1)}"
        for label, query, _ in RECOUNTS
    )


def apply_sql() -> str:
    lines = ["START TRANSACTION;"]
    for _label, _drift, stmts in RECOUNTS:
        for stmt in stmts:
            lines.append(stmt + ";")
    lines.append("COMMIT;")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="开发期数据清理·计数重算（默认 dry-run）")
    parser.add_argument("--apply", action="store_true", help="真重算（默认只报漂移，不落库）")
    args = parser.parse_args()

    conn = load_conn()
    print(f"目标库: {conn.describe()}")

    try:
        before = dict(parse_pairs(run_mysql(conn, drift_sql())))
    except RuntimeError as e:
        print(f"错误: {e}", file=sys.stderr)
        return 1

    print("\n当前漂移（计数列 ≠ 明细口径的行数；dry-run 同时是 F-07 探针）：")
    for label, _q, _s in RECOUNTS:
        print(f"  {label:<24} {before.get(label, 0)}")

    if not args.apply:
        print(f"\n[dry-run] 未改写任何列。加 --apply 才会执行（单事务）。"
              f"合计漂移 {sum(before.values())} 行。")
        return 0

    print("\n[apply] 单事务重算……")
    try:
        run_mysql(conn, apply_sql())
        after = dict(parse_pairs(run_mysql(conn, drift_sql())))
    except RuntimeError as e:
        print(f"错误: {e}", file=sys.stderr)
        return 1

    remaining = sum(after.values())
    print(f"[apply] 完成。复核：漂移仅剩 {remaining} 行" + ("（应为 0）" if remaining else ""))
    for label, _q, _s in RECOUNTS:
        n = after.get(label, 0)
        if n:
            print(f"  ⚠️ 仍有漂移: {label} = {n}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
