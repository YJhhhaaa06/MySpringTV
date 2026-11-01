-- ============================================================================
-- V11：用户头像选择 —— `users.avatar_ref`
-- ============================================================================
-- 口径（用户可见规则见 .docs/task/CURRENT_INTEGRATION.md 的 R-27；
--       演进纪律见 .docs/architecture/tech/data-evolution/数据与Schema演进.md §2.4：
--       V1~V10 已应用 ⇒ **逐字节冻结、连注释都不能改**；本文件是**增量**）。
--
-- 为什么只存"选择"而不存最终 URL：
--   · `avatar_ref` 的取值是 `default:1` … `default:6` 或 `/upload/avatar/{uuid}.jpg`；
--     API 出口统一解析成生效 `avatarUrl`（未选过 ⇒ 按 `userId % 6` 回落默认）。
--   · 默认头像资源可改名 / 迁移而不必改库；自定义头像由 `/upload/avatar/` 承担，
--     **不复用 `image/`**（`tools/devclean/media_clean.py` 会把未被 `content_media`
--     引用的 image 文件判成孤儿）。
--
-- NULL = 从没选过（按 userId 回落），不是"没有头像"。
-- 归属域：**user**（列在 users 表 ⇒ 方法进 UserDao；先例 = V5 的 coin_deci、V9 的 history_recording）。
-- 不新增表 ⇒ 表计数仍 20。
-- ============================================================================


ALTER TABLE `users`
  ADD COLUMN `avatar_ref` varchar(128) COLLATE utf8mb4_general_ci DEFAULT NULL
    COMMENT '头像选择：default:1..6 或 /upload/avatar/...；NULL=按 userId 回落默认';
