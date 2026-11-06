-- ============================================================================
-- V12：收藏夹封面（R-33）
-- ============================================================================
-- 口径（V1–V11 冻结，只增不改；见 architecture/tech/data-evolution/数据与Schema演进.md）：
--   · 只存**自定义封面**的应用内相对 URL：/upload/favorite-cover/...
--   · NULL = 未设自定义 ⇒ 读取时走「自动封面 = 夹内最新一条收藏的 coverUrl」；
--     取不到（失效 / 图文无封面 / 空夹）⇒ 出参 coverUrl = NULL，前端按灰底 TV 占位。
--   · 自动封面不落库、不进缓存（folders 本来就不缓存）—— 读取时现算。
--   · 落盘目录 favorite-cover 与内容媒体 cover/ 分家：tools/devclean/media_clean.py 只扫
--     content_media 引用的 video/image/cover，复用 cover/ 会把收藏夹封面判成孤儿。
--
-- 幂等：单条 ALTER TABLE ADD COLUMN。MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS
-- （与 V2 的 content.favorite_count 同一例外），本文件只由 Flyway 应用一次。
--
-- 归属域：favorite（不新增域，ArchitectureTests.DOMAINS 不动）。
-- ============================================================================
ALTER TABLE `favorite_folder`
  ADD COLUMN `cover_ref` varchar(255) DEFAULT NULL
    COMMENT '自定义封面：应用内相对 URL（/upload/favorite-cover/...）；NULL=未设自定义（走自动封面）';
