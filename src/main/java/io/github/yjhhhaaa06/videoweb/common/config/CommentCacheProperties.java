package io.github.yjhhhaaa06.videoweb.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 评论缓存配置（S5）。
 *
 * <p>对应 TV {@code AppConfig.getCommentTtlSeconds()}（原读 {@code cache.comment.ttlMinutes}，默认 10）。
 * TV 的取值理由（原注释）：<i>"comment 10min（命中 38%~42%，新鲜度敏感：评论增删/点赞失效驱动，
 * 保持短 TTL 最安全）"</i>——刻意比内容的 30 分钟**短得多**，因为评论的变更频率高。
 *
 * <p>⚠️ 单位口径：TV 的 {@code getCommentTtlSeconds()} 返回**秒**，而 like/follow 的对应方法返回**毫秒**。
 * 本实现统一用 {@code Duration}（Spring 的原生表达），消除这个历史不一致；
 * {@code video.cache.comment-ttl: 10m} 与 TV 的 {@code cache.comment.ttlMinutes=10} 等价。
 *
 * <p>⚠️ {@code Duration} 属性**必须带单位后缀**（坑 4：裸数字 10 会被解析成 PT10S）。
 *
 * @param commentTtl 评论缓存 key 的存活时长，TV 原值 10 分钟
 */
@ConfigurationProperties(prefix = "video.cache")
public record CommentCacheProperties(Duration commentTtl) {

    public CommentCacheProperties {
        if (commentTtl == null || commentTtl.isZero() || commentTtl.isNegative()) {
            throw new IllegalArgumentException("video.cache.comment-ttl 必须为正");
        }
    }
}
