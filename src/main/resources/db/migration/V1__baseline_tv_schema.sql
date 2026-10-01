-- ============================================================================
-- V1 baseline：TV 当前离线库（TVDatabase @ mysql 8.0.45）的完整结构
-- ============================================================================
-- 口径：决策⑥「先搬后改」——V1 = TV 现结构的忠实快照，不做任何设计改进。
-- 来源：**从活库导出**（information_schema + SHOW CREATE TABLE），
--       非 old-project/.docs/temp/DATABASE_SCHEMA_2026-08-08.sql（那份已过期：
--       只有 12 张表，缺 auto_bigv / feed_inbox / feed_inbox_sync）。
--
-- 表序：按外键依赖排序（coupon → coupon_order）。目前仅 coupon_order 有外键。
--
-- 幂等性：全部使用 CREATE TABLE IF NOT EXISTS。这不是风格偏好，而是配置使然：
--   flyway.baseline-version=0 + baseline-on-migrate=true 使 V1 在**已有库**上也会被执行
--   （已有库结构已存在 ⇒ 整个 V1 成为 no-op），在**空库/测试容器**上则真正建结构。
--   一条 SQL 同时服务两种环境，无需为已有库手工 baseline 到 1。
--
-- 注意：Flyway ≠ 备份。本文件只负责结构演进，业务数据不进 git（决策⑥）。
-- ============================================================================


-- ---------------------------------------------------------------------------
-- auto_bigv：自动大V状态表（feed3-T28-A 滞回判定：存在即自动大V）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `auto_bigv` (
  `user_id` bigint NOT NULL COMMENT '自动升级为大V的用户ID（存在即自动大V）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '自动升级时间（只作诊断，不参与判定）',
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='自动大V状态表（feed3-T28-A 滞回判定：存在即自动大V）';


-- ---------------------------------------------------------------------------
-- comment：评论主表（含楼中楼 @引用与回复计数）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `comment` (
  `comment_id` bigint NOT NULL AUTO_INCREMENT,
  `content_id` bigint NOT NULL COMMENT '视频或动态id',
  `user_id` bigint NOT NULL,
  `content` text COLLATE utf8mb4_general_ci NOT NULL,
  `parent_id` bigint DEFAULT NULL,
  `reply_to_user_id` bigint DEFAULT NULL COMMENT '楼中楼@引用:被回复评论的作者id;NULL=主楼或回复主楼',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `like_count` int DEFAULT '0',
  `is_deleted` tinyint DEFAULT '0',
  `reply_count` int NOT NULL DEFAULT '0' COMMENT '主楼楼中楼回复数（is_deleted=0 口径，T10-A 建字段/T10-B 消费）',
  PRIMARY KEY (`comment_id`),
  KEY `idx_content_parent` (`content_id`,`parent_id`)
) ENGINE=InnoDB AUTO_INCREMENT=366 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;


-- ---------------------------------------------------------------------------
-- comment_like：评论点赞表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `comment_like` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `comment_id` bigint NOT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_comment` (`user_id`,`comment_id`),
  KEY `idx_comment_id` (`comment_id`)
) ENGINE=InnoDB AUTO_INCREMENT=66 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='评论点赞表';


-- ---------------------------------------------------------------------------
-- comment_media：评论资源表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `comment_media` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `comment_id` bigint NOT NULL,
  `url` varchar(255) COLLATE utf8mb4_general_ci NOT NULL,
  `type` tinyint DEFAULT '2' COMMENT '默认图片',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_comment_id` (`comment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='评论资源表';


-- ---------------------------------------------------------------------------
-- content：内容主表（视频/图文统一）
-- 注意 ft_search 依赖 ngram 全文解析器（MySQL 内置，空库同样可用）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `content` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '上传者ID',
  `type` tinyint NOT NULL DEFAULT '1' COMMENT '内容类型: 1-视频, 2-图文',
  `title` varchar(100) COLLATE utf8mb4_general_ci NOT NULL COMMENT '标题',
  `description` text COLLATE utf8mb4_general_ci NOT NULL COMMENT '文本内容',
  `category_id` int NOT NULL DEFAULT '1' COMMENT '分区ID',
  `comment_count` int unsigned DEFAULT '0' COMMENT '评论数',
  `like_count` int unsigned DEFAULT '0' COMMENT '点赞数',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除: 0-正常, 1-删除',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `file_exists` tinyint NOT NULL DEFAULT '0' COMMENT '媒体完整性聚合：0缺失/未扫描 1完整或无媒体',
  `last_verify_time` datetime DEFAULT NULL COMMENT '最近一次文件校验时间',
  `comment_enabled` tinyint NOT NULL DEFAULT '1' COMMENT '评论区开关: 1-开, 0-关',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_category_id` (`category_id`),
  KEY `idx_del_time` (`is_deleted`,`create_time`),
  FULLTEXT KEY `ft_search` (`title`,`description`) /*!50100 WITH PARSER `ngram` */
) ENGINE=InnoDB AUTO_INCREMENT=257 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='内容主表';


-- ---------------------------------------------------------------------------
-- content_like：内容点赞表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `content_like` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '点赞用户',
  `content_id` bigint NOT NULL COMMENT '内容ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_content` (`user_id`,`content_id`),
  KEY `idx_content_id` (`content_id`)
) ENGINE=InnoDB AUTO_INCREMENT=228 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='内容点赞表';


-- ---------------------------------------------------------------------------
-- content_media：内容资源表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `content_media` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `content_id` bigint NOT NULL COMMENT '所属内容ID',
  `url` varchar(255) COLLATE utf8mb4_general_ci NOT NULL COMMENT '资源地址',
  `type` tinyint NOT NULL COMMENT '类型: 1视频 2图片 3封面',
  `sort` int DEFAULT '0' COMMENT '排序',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `file_exists` tinyint NOT NULL DEFAULT '0' COMMENT '文件是否存在：0缺失/未扫描 1存在',
  `last_verify_time` datetime DEFAULT NULL COMMENT '最近一次文件校验时间',
  PRIMARY KEY (`id`),
  KEY `idx_content_id` (`content_id`)
) ENGINE=InnoDB AUTO_INCREMENT=454 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='内容资源表';


-- ---------------------------------------------------------------------------
-- coupon：优惠券（被 coupon_order 外键引用，须先建）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `coupon` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '优惠券ID',
  `title` varchar(100) NOT NULL COMMENT '优惠券标题',
  `stock` int NOT NULL DEFAULT '0' COMMENT '库存',
  `begin_time` datetime NOT NULL COMMENT '抢购开始时间',
  `end_time` datetime NOT NULL COMMENT '抢购结束时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  CONSTRAINT `coupon_chk_1` CHECK ((`stock` >= 0))
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------------
-- coupon_order：抢券订单（唯一外键：→ coupon.id，ON DELETE CASCADE）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `coupon_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '订单ID',
  `coupon_id` bigint NOT NULL COMMENT '优惠券ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `coupon_code` varchar(64) NOT NULL COMMENT '优惠券码',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态 1成功 2已使用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '抢购时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_coupon_user` (`coupon_id`,`user_id`),
  UNIQUE KEY `uk_coupon_code` (`coupon_code`),
  KEY `idx_user_id` (`user_id`),
  CONSTRAINT `fk_coupon_order_coupon` FOREIGN KEY (`coupon_id`) REFERENCES `coupon` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB AUTO_INCREMENT=175 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;


-- ---------------------------------------------------------------------------
-- feed_inbox：收件箱窗口（feed 二期 T21；时间线真相源）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `feed_inbox` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '收件箱归属者（粉丝）',
  `content_id` bigint NOT NULL COMMENT '内容ID',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_content` (`user_id`,`content_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='收件箱窗口（feed 二期 T21：写扩散与窗口重建的产物；时间线真相源）';


-- ---------------------------------------------------------------------------
-- feed_inbox_sync：收件箱窗口同步状态（存在即已同步）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `feed_inbox_sync` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '收件箱归属者',
  `sync_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '窗口同步（重建/回填）完成时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='收件箱窗口同步状态（存在即已同步；feed 二期 T22 起写入）';


-- ---------------------------------------------------------------------------
-- follow：关注关系表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `follow` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '关注者',
  `followed_user_id` bigint NOT NULL COMMENT '被关注者',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_follow` (`user_id`,`followed_user_id`) COMMENT '防止重复关注',
  KEY `idx_followed_user_id` (`followed_user_id`) COMMENT '查粉丝用索引',
  KEY `idx_followed_user_user` (`followed_user_id`,`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=187 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='关注关系表';


-- ---------------------------------------------------------------------------
-- users：用户表（role: 0=普通 1=管理员，对应决策③的 RBAC 起步）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `users` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(50) COLLATE utf8mb4_general_ci NOT NULL,
  `hashed_password` varchar(100) COLLATE utf8mb4_general_ci NOT NULL,
  `phone` varchar(11) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `follower_count` int unsigned DEFAULT '0',
  `follow_count` int unsigned DEFAULT '0',
  `role` tinyint NOT NULL DEFAULT '0' COMMENT '0=普通 1=管理员',
  PRIMARY KEY (`id`),
  UNIQUE KEY `username` (`username`),
  UNIQUE KEY `phone` (`phone`),
  FULLTEXT KEY `ft_idx_username` (`username`)
) ENGINE=InnoDB AUTO_INCREMENT=227 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;


-- ---------------------------------------------------------------------------
-- video：视频资源（TV 遗留表，videoID 非自增）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `video` (
  `videoID` bigint NOT NULL,
  `video_url` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`videoID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;


-- ---------------------------------------------------------------------------
-- videoinfo：视频元信息（TV 遗留表）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `videoinfo` (
  `videoID` bigint NOT NULL AUTO_INCREMENT,
  `uploadID` bigint DEFAULT NULL,
  `videoTitle` varchar(50) COLLATE utf8mb4_general_ci DEFAULT NULL,
  `briefIntroduction` varchar(500) COLLATE utf8mb4_general_ci DEFAULT '-',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `like_count` int unsigned DEFAULT '0',
  PRIMARY KEY (`videoID`)
) ENGINE=InnoDB AUTO_INCREMENT=42 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
