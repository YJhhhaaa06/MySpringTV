-- ============================================================================
-- V4：稍后再看（收藏三期 T17）—— 新增单表 `watch_later`
-- ============================================================================
-- 口径（V1 / V2 / V3 已应用 ⇒ **逐字节冻结、连注释都不能改**；本文件是**增量**，
--   见 .docs/architecture/tech/数据与Schema演进.md §2.4）：
--
--   · ★ **独立表，不共 `favorite_folder`**（用户 2026-10-14 拍板）：
--     "稍后再看"与"收藏"是两个语义（B 站 两个独立入口）。共表会让
--     `/favorite/status`（isFavorited）、`/favorite/count`、夹列表、公开夹
--     **四条读语义全要加"排除稍后再看"、漏一处即静默错答案**；
--     独立表后**收藏侧一行不改**，污染在结构上不可能发生。
--     决策与取舍见 .docs/task/收藏功能-三期设计.md §二 / §四。
--
--   · ★ **幂等靠唯一键 `uk_user_content`**：重复 add 撞它 ⇒
--     `INSERT … ON DUPLICATE KEY UPDATE create_time = CURRENT_TIMESTAMP`
--     （**不新增行、不改 id、只把时间刷成当前 ⇒ 移到最前**）。
--     B 站实测语义，见 .docs/task/收藏功能-需求.md §六。
--
--   · 表里**只存视频**（`content.type = 1`）：写入侧校验，不进表结构。
--
--   · 无外键（与 V1 / V2 其余表一致：全库唯一外键只有 coupon_order → coupon）。
--
-- 幂等：建表沿用 V1 / V2 的 `CREATE TABLE IF NOT EXISTS` 约定。
-- 归属域：**favorite**（不新开域 —— 它复用本域的装载契约与失效占位口径，见骨架 §四）。
-- ============================================================================


CREATE TABLE IF NOT EXISTS `watch_later` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '稍后再看记录ID（`create_time` 并列时的稳定 tie-breaker）',
  `user_id` bigint NOT NULL COMMENT '归属用户ID',
  `content_id` bigint NOT NULL COMMENT '内容ID（写入侧保证 type=1 视频且未软删）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间（重复 add 会被刷成当前时刻 ⇒ 排到最前）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_content` (`user_id`,`content_id`) COMMENT '同一用户同一内容至多一条：重复 add 撞它 ⇒ ON DUPLICATE KEY UPDATE create_time（移到最前），不靠"先查后插"',
  KEY `idx_user_time` (`user_id`,`create_time`) COMMENT '按加入时间倒序分页（GET /watchlater/list）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='稍后再看（独立于收藏夹；只存视频）';
