-- ============================================================================
-- V2：收藏功能（一期）—— 两张新表 + content.favorite_count 列
-- ============================================================================
-- 口径（V1 冻结，只增不改；见 .docs/architecture/tech/数据与Schema演进.md §2.3）：
--   · V1__baseline_tv_schema.sql 一字不改；本文件是**增量**，在
--     活库（baseline 0 + V1 已应用）与空库/测试容器（V1 → V2 顺序执行）上**都会生效**
--     —— 这正是"活库与测试库不分叉"的关键（§二 2.3）。
--
-- 幂等：建表沿用 V1 的 `CREATE TABLE IF NOT EXISTS` 约定（§七 检查清单 3）。
--   ⚠️ 唯一例外：MySQL 8.0 **不支持** `ALTER TABLE … ADD COLUMN IF NOT EXISTS`
--      （那是 MariaDB 的扩展），故 `content.favorite_count` 只有一条裸 ALTER。
--      它只由 Flyway 应用一次；落笔前已用 information_schema 复核活库当前**无**该列
--      （2026-10-08：`spring_tv` 只有 15 张业务表 + flyway_schema_history，无 favorite_*）。
--
-- 归属域：**favorite**（新域，已登记进 ArchitectureTests.DOMAINS）。
--   新域不登记 ⇒ ArchUnit 规则 1/2/3 根本不覆盖它，"规则通过"是永远绿的假绿。
--
-- 一期口径（决策见 .docs/task/CURRENT_NEEDS.md R-01~R-08）：
--   · `is_private` / `description` **必须一次到位**（R-05：后补要 V3 + 存量回填，
--     且"默认公开"一旦上线即成对外契约）；
--   · `favorite_count` 列**一期只加、不读不写**（R-06：二期上增量维护时，
--     一期的实时 `COUNT(DISTINCT user_id)` 就是它的验收 oracle）；
--   · 一期**不建** cache 表 / 包，也不建任何冗余计数（属二期，见分期篇 §一）。
--
-- 刻意**不做**的两件事（免得下游"顺手优化"）：
--   ① `favorite_item` 不加 `content_id` 前导索引 —— 一期收藏数走
--      `COUNT(DISTINCT user_id) WHERE content_id = ?`，T5 要**把它的耗时基线记下来**
--      供二期对照；先加索引就把这条基线毁了；
--   ② 不加外键 —— 与 V1 其余表一致（全库唯一外键只有 coupon_order → coupon）。
-- ============================================================================


-- ---------------------------------------------------------------------------
-- favorite_folder：收藏夹
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `favorite_folder` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '收藏夹ID',
  `user_id` bigint NOT NULL COMMENT '归属用户ID',
  `name` varchar(100) NOT NULL COMMENT '收藏夹名称',
  `description` varchar(255) DEFAULT NULL COMMENT '简介（一期建列、一期不消费；三期做展示时用）',
  `is_private` tinyint NOT NULL DEFAULT '0' COMMENT '私密: 0-公开 1-私密（R-05 一期就给；落点=他人视角端点 GET /favorite/folder/public）',
  `is_default` tinyint NOT NULL DEFAULT '0' COMMENT '默认夹: 0-自建 1-默认（R-01 懒建：首次收藏时才创建，故"无夹"是合法状态）',
  `default_uniq` tinyint GENERATED ALWAYS AS (IF(`is_default` = 1, 1, NULL)) VIRTUAL COMMENT '唯一键判别列（TECHNICAL）：默认夹=1、自建夹=NULL。MySQL 唯一索引**不约束 NULL** ⇒ 同一用户可有多条自建夹、至多一条默认夹',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_default` (`user_id`,`default_uniq`) COMMENT '每用户至多一个默认夹：懒建的并发重复由它兜住（T2；靠 INSERT ... WHERE NOT EXISTS 兜是兜不住的）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='收藏夹';

-- ⚠️ `favorite_folder` 刻意不加 `KEY idx_user (user_id)`：`uk_user_default` 的**前导列**
--    就是 user_id，"我的收藏夹列表"（`WHERE user_id = ?`）直接用它即可，
--    再加一条同前导列的索引是纯冗余（对照 follow 表：有 uk_user_follow 就不再单挂 user_id 索引）。


-- ---------------------------------------------------------------------------
-- favorite_item：收藏记录（一条 = 某内容被收进某个夹一次）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `favorite_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '收藏记录ID（夹内列表的**分页单位就是它**，不是"内容"）',
  `folder_id` bigint NOT NULL COMMENT '所属收藏夹ID',
  `user_id` bigint NOT NULL COMMENT '收藏人ID（冗余自 folder.user_id：判"同一人是否收藏过"必须按 (user_id, content_id)，不能按夹判）',
  `content_id` bigint NOT NULL COMMENT '内容ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '收藏时间（夹内列表按它倒序，故它与 id 同序）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_folder_content` (`folder_id`,`content_id`) COMMENT '同一夹内同一内容至多一条：重复收藏撞唯一键 ⇒ DuplicateKeyException ⇒ 409（T4 幂等，不靠"先查后插"）',
  KEY `idx_user_content` (`user_id`,`content_id`) COMMENT '按人去重：/favorite/status 是否收藏过 + 收藏数 COUNT(DISTINCT user_id) 的成员判定',
  KEY `idx_folder_time` (`folder_id`,`create_time`) COMMENT '夹内列表按收藏时间倒序分页（T6）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='收藏记录（含失效内容：内容软删后**不清理**，条目保留并返回脱敏占位，R-03）';


-- ---------------------------------------------------------------------------
-- content.favorite_count：收藏数（**按人去重**：同一人进多夹只算 1）
-- ---------------------------------------------------------------------------
-- ★ 一期只建列、**不读不写**（R-06）。二期上增量维护后必须满足不变式
--   `content.favorite_count == COUNT(DISTINCT user_id) FROM favorite_item WHERE content_id = ?`，
--   而一期的实时值就是它的验收 oracle。
-- ⚠️ 二期启用时该列全是 0，**须先存量回填**再切读路径（分期篇 §3.2）。
-- 与 like_count / comment_count 的差异：那两列是 V1 从 TV 照搬的
-- `int unsigned DEFAULT '0'`（**可空**），本列是 V2 新设计 ⇒ 取 `NOT NULL`（计数不该有 NULL 态）。
ALTER TABLE `content`
  ADD COLUMN `favorite_count` int unsigned NOT NULL DEFAULT 0
    COMMENT '收藏数（按人去重）：V2 建列，一期不读不写，二期启用增量维护（R-06）';
