-- ============================================================================
-- V6：播放记录（history）域 —— content_media 一列 + content 一列 + 新表 `watch_history`
-- ============================================================================
-- 口径（V1–V5 已应用 ⇒ **逐字节冻结、连注释都不能改**；本文件是**增量**，
--   见 .docs/architecture/tech/data-evolution/数据与Schema演进.md §2.4；
--   设计见 .docs/architecture/domain/history/history.md §二）：
--
--   · `content_media.duration_seconds` = 视频时长（秒）。**只有 type=1 的媒体行**有意义：
--     upload 域在发布 / 换源时解析 mp4 的 `moov → mvhd`（不引入 ffmpeg）；解析失败或图文
--     一律 NULL。**存量行不回填** ⇒ NULL = 未知（进度按秒展示、不算百分比），
--     这是刻意的口径，不是遗漏（设计 §六.2 / §十 #6）。
--   · `content.play_count` = 播放量（**按用户去重**，一人一次）。`unsigned` 且**只增**
--     （没有"取消播放"）⇒ 不需要 like_count / comment_count 那套
--     `CAST(col AS SIGNED) + delta >= 0` 防负守卫。这是口径差异、不是漏改（设计 §二）。
--   · `watch_history` = **一人一内容一行**（`uk_user_content`），不是逐次流水行：
--     重复上报只更新，列表里不出现同一视频两条（设计 §二 口径 1）。
--     `play_counted` 与 `progress_seconds` **解耦**：`/history/report` 只改进度，
--     `/history/play` 只改标记（拆端点就是为了不在热路径判首播，设计 §四）。
--
-- ⚠️ 幂等：建表沿用 V1 / V2 / V4 的 `CREATE TABLE IF NOT EXISTS` 约定；
--   但 MySQL 8.0 **不支持** `ALTER TABLE … ADD COLUMN IF NOT EXISTS`（MariaDB 扩展）
--   ⇒ 两条 ALTER 是裸语句，只由 Flyway 应用一次。落笔前已用 information_schema
--   复核活库 `spring_tv` 当前**无** `duration_seconds` / `play_count` / `watch_history`
--   （2026-10-21，V5 之后）。
--
-- ⚠️ `update_time` 的 `ON UPDATE CURRENT_TIMESTAMP` **不足以**承担"最后上报时间"：
--   重复上报同一秒时值没变 ⇒ `ON UPDATE` 不触发 ⇒ 该行不会移到列表最前。
--   故 `/history/report` 的 ODKU **显式** `SET update_time = CURRENT_TIMESTAMP`（设计 §二 口径 5）。
--
-- ⚠️ `idx_content_id` **不是**运行时读路径的索引：它只服务不变量复算
--   （`content.play_count == COUNT(*) … WHERE content_id = ? AND play_counted = 1`）
--   与将来的按内容清理工具。历史列表走 `idx_user_update`（设计 §二 口径 6）。
--
-- 归属域：**history**（新域，已登记进 ArchitectureTests.DOMAINS —— 不登记的话
--   规则 1/2/3/5 对它根本不扫描，"规则通过"是永远绿的假绿）。
-- ============================================================================


ALTER TABLE `content_media`
  ADD COLUMN `duration_seconds` int DEFAULT NULL
  COMMENT '视频时长（秒；仅 type=1，mp4 mvhd 解析；失败/图文为 NULL）';

ALTER TABLE `content`
  ADD COLUMN `play_count` int unsigned NOT NULL DEFAULT 0
  COMMENT '播放量（按用户去重，一人一次；只增）';

CREATE TABLE IF NOT EXISTS `watch_history` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `content_id` bigint NOT NULL,
  `progress_seconds` int NOT NULL DEFAULT 0 COMMENT '上次进度（秒）；0=刚打开',
  `play_counted` tinyint NOT NULL DEFAULT 0 COMMENT '播放量是否已计（一人一次的去重标记）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次观看时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后上报时间（列表排序键）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_content` (`user_id`,`content_id`) COMMENT '一人一内容一行：重复上报只更新，不追加',
  KEY `idx_user_update` (`user_id`,`update_time`) COMMENT '历史列表按最后观看倒序',
  KEY `idx_content_id` (`content_id`) COMMENT '不变量复算 / 按内容清理的候选索引'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='播放记录（一人一内容一行）';
