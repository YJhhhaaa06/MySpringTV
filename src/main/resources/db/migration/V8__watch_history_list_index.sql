-- ============================================================================
-- V8：历史列表索引改造 —— `idx_user_update` 替换为**覆盖复合索引**
-- ============================================================================
-- 口径（设计见 .docs/architecture/domain/history/history.md §二 / §七.1；
--       演进纪律见 .docs/architecture/tech/data-evolution/数据与Schema演进.md §2.4：
--       V1~V7 已应用 ⇒ **逐字节冻结、连注释都不能改**；本文件是**增量**）。
--
-- 为什么改（可复算，2026-10-21 实测；造数 = scratch 库、单用户 5 万行）：
--   列表查询的 `ORDER BY update_time DESC, content_id DESC` 里 **`content_id` 不在旧索引上**
--   ⇒ 排序键不是索引序 ⇒ 每页都要对该用户**全部行** filesort：
--     `EXPLAIN ANALYZE` 实测：页 1（LIMIT 20 OFFSET 0）= 37.8ms；OFFSET 49900 = 62.3ms。
--   换成覆盖复合索引后：页 1 = 0.2ms（50k 行）/ 0.10ms（5k 行）——
--   且 `SELECT content_id, update_time, progress_seconds` 三列被索引**全覆盖**（免回表）。
--
-- 形态：**替换**而非新增 —— 新索引的 `(user_id, update_time, content_id)` 前缀已完全覆盖
--   旧 `idx_user_update (user_id, update_time)`（含 `countByUserId` 的覆盖需求）
--   ⇒ 旧索引留着重叠前导列只是冗余；索引总数不变（仍 4 个：PRIMARY / uk_user_content /
--   本索引 / idx_content_id）。
--   ⚠️ `DESC` 关键字与 ORDER BY 同向（前向扫描）；去掉它靠反向扫描也成立，此处按实测形态落。
--   ⚠️ `progress_seconds` 进索引是**刻意的**（覆盖三列）；代价是每条上报都要重写该索引项
--   —— 已实测写路径无回归（同一批 1000 次 ODKU：旧 4 索引 5.52/5.81s vs 新索引 5.93/5.55s，
--   噪声内；`ALTER ADD INDEX` 在 51k 行表上耗时 276ms，INPLACE、允许并发 DML）。
--
-- ⚠️ MySQL 8 的 `ALTER TABLE` 不支持 `IF EXISTS` 形式的键名判断（同 V6 的 `ADD COLUMN` 口径）
--   ⇒ 本语句是裸 ALTER，只由 Flyway 应用一次。
--
-- 归属域：**history**（本表自有；不新增表、不动其它表 ⇒ 表计数 19 不变）。
-- ============================================================================


ALTER TABLE `watch_history`
  DROP KEY `idx_user_update`,
  ADD KEY `idx_user_update_content` (`user_id`, `update_time` DESC, `content_id` DESC, `progress_seconds`)
  COMMENT '历史列表倒序（update_time DESC, content_id DESC）+ 三列覆盖；每条上报改 update_time ⇒ 一并维护';
