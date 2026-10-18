#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""开发期数据清理 · **DB 清理脚本**（T30）。

> 定位 = **开发期脚本**（不是运维脚本）。目标是"清掉开发 / 测试期造出来的数据，且不碰游玩数据"。

## 它怎么知道该删什么

脚本**不判断**"测试数据 vs 游玩数据"——它只顺着**悬挂引用**清扫。删除的"根集"由**人**划定：
你手工删掉测试用户行之后，凡是指向已不存在的 `users` / `content` / `comment` / `favorite_folder`
的行，就是"根没了"的行，脚本把它们带走。这是"不碰游玩数据"的**结构性**保证：脚本永远没有
"自己决定删什么"的权力。

## 用法

    python tools/devclean/db_clean.py            # 默认 dry-run：报「将删 N / 保留 M」
    python tools/devclean/db_clean.py --apply    # 单事务执行删除

★ **dry-run 与 apply 走同一段 SQL**：dry-run = 在同一事务里**真跑删除再 `ROLLBACK`**。
  这不是图省事，而是**"报的和干的一致"**——若 dry-run 只静态 COUNT，就**算不出级联**
  （删主楼会连带带走它的楼中楼/点赞/媒体，同一快照上的静态 COUNT 会**系统性少报**，并把
  "保留 M"抬得虚高、护栏反被引向"看起来更安全"）。`ROLLBACK` 保证一个字节都不留。

## 红线（本轮共同约定）

1. **不删 `users` 行**——删用户由人手工做（本仓无"删除用户行"业务，未来也不会有）；
2. **不碰 `coupon` 表**——清 `coupon_order` 会让 `coupon.stock`（只在抢券时扣、无重算路径）与订单脱钩，
   `stock` 怎么办由人手动清 `coupon` 时一并定；
3. 破坏性谓词**一律 `NOT EXISTS`**，**禁 `NOT IN (子查询)`**——后者遇到 NULL 会**静默地"一行都不匹配"**，
   该删的没删你却发现不了；
4. 软删（`is_deleted=1`）的 `content` **仅在作者不存在时**才清（正常软删内容依旧保留）；
5. 脚本**不引用本仓目录之外的任何盘符**。

## 刻意不纳入的表（如实声明，不是漏项）

* `users` / `coupon` —— 见红线 1 / 2；
* `video` / `videoinfo` —— TV 遗留表，**在本仓无任何消费者**（用户 2026-10-17 确认），不属"开发期造的数据"。

## 已知边界

* 单趟收敛依赖 `comment.parent_id` **深度 ≤ 2**（楼中楼只有一层，app 口径）；出现更深链时
  **不会静默漏掉**——apply 末尾的复核会把残留**响亮报出**并让脚本以非零码退出，再跑一次即收敛。

## 退出码

    0  成功（dry-run 或 apply 均返回 0）
    1  解析 / 连接 / SQL 失败，或 apply 后仍有残留 / 护栏表被改动
    2  未找到 mysql 客户端
"""

from __future__ import annotations

import argparse
import sys

from common import load_conn, run_mysql, parse_pairs

# ─────────────────────────────────────────────────────────────────────────────
# 清扫步骤：顺序 = 「父先于子」（父被删 ⇒ 子在同一趟里变悬挂，被后续步骤带走）
#   每项 = (表名, 步骤说明, 谓词)
# ★ 谓词里的表名限定（如 `comment.content_id`）= 单表 DELETE 的外层表引用，MySQL 允许。
# ★ `comment` 的"父指针"步骤必须用**派生表**包一层 —— 目标表自引用会撞 MySQL 1093。
# ─────────────────────────────────────────────────────────────────────────────
STEPS: list[tuple[str, str, str]] = [
    ("content", "作者已不存在的内容行",
     "NOT EXISTS (SELECT 1 FROM users u WHERE u.id = content.user_id)"),

    ("favorite_folder", "归属用户已不存在的收藏夹",
     "NOT EXISTS (SELECT 1 FROM users u WHERE u.id = favorite_folder.user_id)"),

    ("favorite_item", "悬挂的收藏明细（夹 / 内容 / 用户任一不存在）",
     "NOT EXISTS (SELECT 1 FROM favorite_folder f WHERE f.id = favorite_item.folder_id)"
     " OR NOT EXISTS (SELECT 1 FROM content c WHERE c.id = favorite_item.content_id)"
     " OR NOT EXISTS (SELECT 1 FROM users u WHERE u.id = favorite_item.user_id)"),

    ("watch_later", "悬挂的稍后再看（内容 / 用户任一不存在）",
     "NOT EXISTS (SELECT 1 FROM content c WHERE c.id = watch_later.content_id)"
     " OR NOT EXISTS (SELECT 1 FROM users u WHERE u.id = watch_later.user_id)"),

    ("comment", "悬挂的评论（内容 / 作者 / 被回复人任一不存在）",
     "NOT EXISTS (SELECT 1 FROM content c WHERE c.id = comment.content_id)"
     " OR NOT EXISTS (SELECT 1 FROM users u WHERE u.id = comment.user_id)"
     " OR (comment.reply_to_user_id IS NOT NULL"
     "     AND NOT EXISTS (SELECT 1 FROM users r WHERE r.id = comment.reply_to_user_id))"),

    ("comment", "悬挂的楼中楼（父评论已不存在）",
     "comment.parent_id IS NOT NULL"
     " AND NOT EXISTS (SELECT 1 FROM (SELECT comment_id FROM comment) p"
     "                 WHERE p.comment_id = comment.parent_id)"),

    ("comment_like", "悬挂的评论点赞（评论 / 点赞人任一不存在）",
     "NOT EXISTS (SELECT 1 FROM comment c WHERE c.comment_id = comment_like.comment_id)"
     " OR NOT EXISTS (SELECT 1 FROM users u WHERE u.id = comment_like.user_id)"),

    ("comment_media", "悬挂的评论媒体（评论已不存在）",
     "NOT EXISTS (SELECT 1 FROM comment c WHERE c.comment_id = comment_media.comment_id)"),

    ("content_like", "悬挂的内容点赞（内容 / 用户任一不存在）",
     "NOT EXISTS (SELECT 1 FROM content c WHERE c.id = content_like.content_id)"
     " OR NOT EXISTS (SELECT 1 FROM users u WHERE u.id = content_like.user_id)"),

    ("content_media", "悬挂的内容媒体（内容已不存在）",
     "NOT EXISTS (SELECT 1 FROM content c WHERE c.id = content_media.content_id)"),

    ("follow", "悬挂的关注（关注者 / 被关注者任一不存在）",
     "NOT EXISTS (SELECT 1 FROM users u WHERE u.id = follow.user_id)"
     " OR NOT EXISTS (SELECT 1 FROM users f WHERE f.id = follow.followed_user_id)"),

    ("auto_bigv", "悬挂的自动大V状态",
     "NOT EXISTS (SELECT 1 FROM users u WHERE u.id = auto_bigv.user_id)"),

    ("feed_inbox", "悬挂的收件箱窗口（用户 / 内容任一不存在）",
     "NOT EXISTS (SELECT 1 FROM users u WHERE u.id = feed_inbox.user_id)"
     " OR NOT EXISTS (SELECT 1 FROM content c WHERE c.id = feed_inbox.content_id)"),

    ("feed_inbox_sync", "悬挂的收件箱同步状态",
     "NOT EXISTS (SELECT 1 FROM users u WHERE u.id = feed_inbox_sync.user_id)"),

    ("coupon_order", "悬挂的抢券订单（仅用户维度；coupon 表不在此列）",
     "NOT EXISTS (SELECT 1 FROM users u WHERE u.id = coupon_order.user_id)"),
]

# 脚本**绝不触碰**的表（护栏：apply 前后行数必须一致）。
PROTECTED_TABLES = ["users", "coupon"]


def _sql_str(value: str) -> str:
    """SQL 字符串字面量（转义单引号）——标签是脚本内置常量，不做注入防护也足够。"""
    return "'" + value.replace("'", "''") + "'"


def counts_sql() -> str:
    """静态悬挂计数（**不分级联**）——只用于 apply 后的"残留复核"。"""
    parts = [
        f"SELECT {_sql_str(label)} AS k, COUNT(*) AS n FROM {table} WHERE {pred}"
        for table, label, pred in STEPS
    ]
    return " UNION ALL ".join(parts)


def totals_sql(tables: list[str]) -> str:
    parts = [f"SELECT {_sql_str(t)} AS k, COUNT(*) AS n FROM {t}" for t in tables]
    return " UNION ALL ".join(parts)


def sweep_script(terminal: str) -> str:
    """一趟清扫：每步 DELETE 后紧跟 `SELECT ROW_COUNT()` 抓**该步真实删行数**。

    ★ 与 `counts_sql()` 的静态 COUNT 不同：本脚本里后一步能看见前一步的删除结果 ⇒ **级联被算进去**。
    `terminal` 取 `COMMIT`（真跑）或 `ROLLBACK`（dry-run 模拟，一个字节都不留）。
    """
    lines = ["START TRANSACTION;"]
    for table, label, pred in STEPS:
        lines.append(f"DELETE FROM {table} WHERE {pred};")
        lines.append(f"SELECT {_sql_str(label)} AS k, ROW_COUNT() AS n;")
    lines.append(terminal + ";")
    return "\n".join(lines)


def render(dangle: list[tuple[str, int]], totals: dict[str, int]) -> None:
    print("\n分步（将删 N，已含级联）：")
    for idx, ((label, n), (table, _label, _pred)) in enumerate(zip(dangle, STEPS), start=1):
        print(f"  {idx:02d}  {table:<16} {label:<40} 将删 {n}")

    print("\n分表汇总（护栏：清理后保留 M —— M 骤降说明根集划错了，停）：")
    deleted_by_table: dict[str, int] = {}
    for (label, n), (table, _l, _p) in zip(dangle, STEPS):
        deleted_by_table[table] = deleted_by_table.get(table, 0) + n
    for table in dict.fromkeys(t for t, _, _ in STEPS):
        total = totals.get(table, 0)
        gone = deleted_by_table.get(table, 0)
        print(f"  {table:<16} 总 {total:<8} 将删 {gone:<8} 保留 {total - gone}")

    print("\n保护表（脚本不碰，行数必须不变）：")
    for table in PROTECTED_TABLES:
        print(f"  {table:<16} {totals.get(table, 0)} 行")


def main() -> int:
    parser = argparse.ArgumentParser(description="开发期数据清理·DB 清理（默认 dry-run）")
    parser.add_argument("--apply", action="store_true", help="真删（默认只报数，不落库）")
    args = parser.parse_args()

    conn = load_conn()
    print(f"目标库: {conn.describe()}")

    protected = totals_sql(PROTECTED_TABLES)
    try:
        sweeps = parse_pairs(run_mysql(conn, sweep_script("ROLLBACK")))
        totals = dict(parse_pairs(run_mysql(conn, totals_sql(
            list(dict.fromkeys(t for t, _, _ in STEPS)) + PROTECTED_TABLES))))
        before_protected = dict(parse_pairs(run_mysql(conn, protected)))
    except RuntimeError as e:
        print(f"错误: {e}", file=sys.stderr)
        return 1

    render(sweeps, totals)

    if not args.apply:
        print("\n[dry-run] 已在事务内模拟并 ROLLBACK，未落库。加 --apply 才真删（COMMIT）。")
        return 0

    print("\n[apply] 单事务执行删除……")
    try:
        applied = parse_pairs(run_mysql(conn, sweep_script("COMMIT")))
        remaining = dict(parse_pairs(run_mysql(conn, counts_sql())))
        after_protected = dict(parse_pairs(run_mysql(conn, protected)))
    except RuntimeError as e:
        print(f"错误: {e}", file=sys.stderr)
        return 1

    print(f"[apply] 实删 {sum(n for _, n in applied)} 行。")
    for (label, n), (table, _l, _p) in zip(applied, STEPS):
        print(f"  {table:<16} {label:<40} 实删 {n}")

    rc = 0
    for table in PROTECTED_TABLES:
        if before_protected.get(table) != after_protected.get(table):
            print(f"  ⚠️ 护栏表被改动: {table} {before_protected.get(table)} -> "
                  f"{after_protected.get(table)}（脚本本应不碰！）", file=sys.stderr)
            rc = 1
    leftover = sum(remaining.values())
    if leftover:
        print(f"  ⚠️ 仍有悬挂 {leftover} 行（可能 = 更深链；脚本幂等，**再跑一次即收敛**）"
              " —— 逐项见下：", file=sys.stderr)
        for table, label, _p in STEPS:
            if remaining.get(label):
                print(f"      {table} / {label} = {remaining[label]}", file=sys.stderr)
        rc = 1
    if rc == 0:
        print("[apply] 完成，复核：无残留、保护表未变。")
    return rc


if __name__ == "__main__":
    sys.exit(main())
