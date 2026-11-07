-- ============================================================================
-- V9：用户级「记录浏览历史」开关 —— `users.history_recording`
-- ============================================================================
-- 口径（用户可见规则见 .docs/architecture/domain/history/history-需求.md §2.1；
--       演进纪律见 .docs/architecture/tech/data-evolution/数据与Schema演进.md §2.4：
--       V1~V8 已应用 ⇒ **逐字节冻结、连注释都不能改**；本文件是**增量**）。
--
-- 为什么挂在 `users` 上（而不是 localStorage / user_settings 表）：
--   · 开关是**账号**的属性 —— B站 实测跨端一致（电脑关掉、手机也不再记录）⇒ 设备级存不住；
--     前端"只许两个 localStorage key"的纪律（客户端静默机制 §3.10）也不允许存第三份。
--   · 一项布尔不值得单独建表：`user_settings` 表要 join / 二次查询，换来的只是"以后可能还有设置项"。
--     等真的出现第 3 个设置项再抽表，届时是一次可控迁移（与 V5 把硬币两列直接加在 users 上同口径）。
--
-- 默认 1（记录）：存量用户无感；"停用"是显式动作。
--
-- ⚠️ ★ 服务端**不**据此校验任何写端点（`/play`、`/report`、`/flush`、空闲轮询兜底一个都不读它）。
--   这是 `R-25` 的**刻意取舍**，不是漏做：本仓只有一个客户端，服务端强制的收益为 0，
--   而"轮询兜底"没有用户请求可拦 ⇒ 四个口子漏一个就静默破功。
--   代价与推翻条件（出现第二客户端 / 公开 API ⇒ 必须改服务端校验）见
--   .docs/architecture/domain/history/history-已知代价.md。
--   本列只由 `PUT /api/v1/users/me/history-recording` 写、由 `GET /api/v1/users/me` 读。
--
-- 归属域：**user**（列在 users 表 ⇒ 方法进 UserDao；先例 = V5 的 coin_deci / last_sign_date）。
--   不新增表 ⇒ 表计数仍 19；history 域**不新增读边**（"不记录"由客户端不发写请求保证）。
-- ============================================================================


ALTER TABLE `users`
  ADD COLUMN `history_recording` tinyint(1) NOT NULL DEFAULT 1
    COMMENT '是否记录观看历史（1=记录；0=停用）；服务端不据此拦截写端点，见 R-25';
