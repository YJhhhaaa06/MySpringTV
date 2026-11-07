-- V10：播放量去重账本（R-26，2026-10-31）
--
-- 为什么必须独立成表：`R-26` 把播放量口径从"一人一次（永久）"改成"一人·一内容·一自然日一次"，
-- 同时开放历史删除。旧口径把去重标记 `watch_history.play_counted` 长在历史行上 ——
-- 一行只能存**一个**标记位，而"每人每内容每天一次"是**多值**事实（同一对 (user, content)
-- 可以有 N 天 × 1 次）⇒ 标记无法再依附历史行；且删历史会连标记一起删掉 ⇒ 再播被判首播、播放量重复 +1。
--
-- 设计要点（口径唯一事实源 = architecture/domain/history/history.md §五）：
--   ① `play_date` 是**业务时区（Asia/Shanghai）的日历日**，由 Java 算好传参，**不用 CURDATE()**：
--      CURDATE() 取 MySQL 的 time_zone（容器里常是 UTC），与《接口契约》§2.7 的 +08:00 口径差 8 小时；
--   ② 只有"今天"的行会拦住今天；更早的行**按语义就永远无用** ⇒ 将来清理是纯容量优化，
--      漏跑不影响正确性（这是它和"每天把标记复位"的根本区别）；
--   ③ 账本**只增**、不进任何读路径；`content.play_count` 仍是唯一的对外计数列；
--   ④ 本版**不回填**旧数据：`watch_history.update_time` 是"最后上报时间"、不是"计数的那个自然日"，
--      拿它回填就是往账本写假日期 ⇒ 存量内容的 `play_count` 与账本行数之间允许有"历史余额"。

CREATE TABLE IF NOT EXISTS `content_play_dedup` (
  `user_id`    bigint   NOT NULL COMMENT '计过数的用户',
  `content_id` bigint   NOT NULL COMMENT '被计数的内容',
  `play_date`  date     NOT NULL COMMENT '计过数的自然日（业务时区 Asia/Shanghai）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记账时刻（审计用；★ 取 DB 会话时区 ⇒ 与 play_date 可能不同日，勿用它判日）',
  PRIMARY KEY (`user_id`,`content_id`,`play_date`),
  KEY `idx_date` (`play_date`) COMMENT '只服务将来的运维清理脚本（WHERE play_date < ?），不在运行时读路径上'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='播放量去重账本（每人·每内容·每天至多一行；只增）';

-- 去重标记搬走后，这两者都成了死物：
--   `play_counted`：不再是任何判定的依据（留着会被下一个人当成"真相"，而它永远不会再被写）；
--   `idx_content_id`：唯一用途是"不变量复算（play_count == COUNT(play_counted=1)）与按内容清理"，
--                     复算已搬到账本、内容删除本来就不碰本表 ⇒ 它变成每次上报都付费的死索引。
ALTER TABLE `watch_history`
  DROP KEY `idx_content_id`,
  DROP COLUMN `play_counted`;
