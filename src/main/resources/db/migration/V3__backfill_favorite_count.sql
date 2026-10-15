-- ============================================================================
-- V3：content.favorite_count 存量回填（收藏二期 T12 / 能力 二期-3）
-- ============================================================================
-- V2 落地时该列全是 0（V2 头注释：一期只加、不读不写，R-06）。本脚本把全表的
-- favorite_count 一次性回填成 favorite_item 的实时聚合值（COUNT(DISTINCT user_id)，
-- 按人去重），使不变量
--   content.favorite_count == COUNT(DISTINCT user_id) FROM favorite_item
-- 从「启用列读」的时刻起即成立（口径见 收藏功能-分期与设计.md §3.2；
-- 此后由各写路径的「事务内批量重算」维护，不做增量）。
--
-- 幂等：UPDATE 写的是聚合复算值，重复执行结果相同；测试容器（空库）上为 no-op。
-- 规模：落笔前实测 dev 库 content 51 行 / favorite_item 4 行（2026-10-12），
--   单条 UPDATE 即可，无需分批；相关子查询逐行聚合，成本与 content 行数线性。
--
-- ⚠️ 本文件一经任何库应用即**逐字节冻结**（连注释都不能改，
--    见 数据与Schema演进.md §2.4 的 checksum 血泪教训）。
-- ============================================================================

UPDATE content
SET favorite_count = (
    SELECT COUNT(DISTINCT user_id)
    FROM favorite_item
    WHERE favorite_item.content_id = content.id
);
