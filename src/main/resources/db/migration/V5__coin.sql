-- ============================================================================
-- V5：投币（硬币）域 —— users 两列 + content 一列 + 新表 `content_coin`
-- ============================================================================
-- 口径（V1–V4 已应用 ⇒ **逐字节冻结、连注释都不能改**；本文件是**增量**，
--   见 .docs/architecture/tech/data-evolution/数据与Schema演进.md §2.4；
--   设计见 .docs/architecture/domain/coin/coin.md §二）：
--
--   · `users.coin_deci` = 硬币余额的**十分位整数**（值 = 币 × 10）。
--     ★ 有符号 `int`，**不是** `unsigned`：扣款靠条件守卫（`coin_deci >= cost`）拦负数；
--     给 unsigned 会让 `CAST(... AS SIGNED)` 那套技巧渗进来（设计 §四 / §十 #6）。
--   · `users.last_sign_date` = 最近签到日（**上海自然日**）。用 `date` 而非 `datetime`：
--     口径就是"哪一天"，与时刻无关；签到幂等靠
--     `last_sign_date IS NULL OR last_sign_date < :today` 的原子条件写（设计 §五.2）。
--   · `content.coin_count` = 累计被投币数。`unsigned` 但**只增不减**
--     （投币一经投出不可取消）⇒ **不需要** like_count / comment_count 那套
--     `CAST(col AS SIGNED) + delta >= 0` 防负守卫。这是口径差异、不是漏改（设计 §二 / §十 #3）。
--   · `content_coin` **单行聚合**，不是逐次流水行：单视频封顶 2 枚靠一条
--     `UPDATE … WHERE amount + ? <= 2` 原子守住；追加行要靠 `SUM` + 锁才能守上限，
--     并发下有竞态，而"明细页"本期已裁剪 ⇒ 追加行无收益（设计 §二）。
--
-- ⚠️ 幂等：建表沿用 V1 / V2 / V4 的 `CREATE TABLE IF NOT EXISTS` 约定；
--   但 MySQL 8.0 **不支持** `ALTER TABLE … ADD COLUMN IF NOT EXISTS`（MariaDB 扩展）
--   ⇒ 三条 ALTER 是裸语句，只由 Flyway 应用一次。落笔前已用 information_schema
--   复核活库 `spring_tv` 当前**无** `coin_deci` / `last_sign_date` / `coin_count` /
--   `content_coin`（2026-10-19，V4 之后）。
--
-- ⚠️ `content_coin.amount` 口径是 **1~2**（上限 2）。**不加**状态 / 软删列 ——
--   本期没有"取消投币"语义，行就是"此人对这条内容投过 amount 枚"这个事实。
--   被拒的投币（409 / 400）随事务回滚，**不落行**（零副作用，见设计 §五.1 与
--   CURRENT_TASKS T38 验收②）⇒ 不存在 `amount = 0` 的行。
--
-- ⚠️ `content_coin` 没有 `idx_user_id`：`uk_user_content` 的**前导列**就是 user_id，
--    "我投过哪些内容"（`WHERE user_id = ? AND amount > 0`）直接用它即可，
--    再挂一条同前导列的索引是纯冗余（对照 `favorite_item` 的取舍）。
--
-- 归属域：**coin**（新域，已登记进 ArchitectureTests.DOMAINS —— 不登记的话
--   规则 1/2/3/5 对它根本不扫描，"规则通过"是永远绿的假绿）。
-- ============================================================================


-- ---------------------------------------------------------------------------
-- users：硬币余额 + 最近签到日
-- ---------------------------------------------------------------------------
ALTER TABLE `users`
  ADD COLUMN `coin_deci` int NOT NULL DEFAULT 0
    COMMENT '硬币余额（单位=十分之一币，值=币×10；有符号，扣款靠条件守卫拦负数）',
  ADD COLUMN `last_sign_date` date DEFAULT NULL
    COMMENT '最近签到日（上海自然日；签到的幂等判据）';


-- ---------------------------------------------------------------------------
-- content：累计被投币数（不可取消 ⇒ 只增）
-- ---------------------------------------------------------------------------
ALTER TABLE `content`
  ADD COLUMN `coin_count` int unsigned NOT NULL DEFAULT 0
    COMMENT '累计被投币数（不可取消 ⇒ 只增，故无防负守卫）';


-- ---------------------------------------------------------------------------
-- content_coin：投币记录（一条 = 某人对某内容累计投了几枚）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `content_coin` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '投币用户',
  `content_id` bigint NOT NULL COMMENT '内容 id',
  `amount` tinyint NOT NULL COMMENT '累计投币枚数（口径 1~2；被拒的尝试随事务回滚、不落行）',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_content` (`user_id`,`content_id`) COMMENT '同一用户对同一内容至多一条：封顶走条件 UPDATE，重复行由它兜住',
  KEY `idx_content_id` (`content_id`) COMMENT '内容维度读：SUM(amount) 复算 / 公共计数回源'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='投币记录（单行聚合、不可取消）';
