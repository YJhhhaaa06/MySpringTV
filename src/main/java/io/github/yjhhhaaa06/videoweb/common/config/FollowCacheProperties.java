package io.github.yjhhhaaa06.videoweb.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 关注关系缓存配置。
 *
 * <p>对应 TV {@code AppConfig.getFollowTtlSeconds()}（原读 {@code cache.follow.ttlMinutes}，默认 30）。
 * TV 的注释给出取值理由：<i>"follow 30min（关系低频变 + MULTI 双写失效清晰；压测以空关系为主，
 * 依据写路径特性保守延长）"</i>——比 like 的 15 分钟更长，因为关注关系比点赞**更低频**。
 *
 * <p>{@code AppConfig}（847 行多级覆盖链）是《迁移参照系》§三的**整体退役**项，
 * 故这里改为标准配置绑定 + 环境变量可覆盖（决策⑨）。位置约定同 {@link LikeCacheProperties}。
 *
 * @param followTtl 缓存 key 的存活时长，TV 原值 30 分钟
 */
@ConfigurationProperties(prefix = "video.cache")
public record FollowCacheProperties(Duration followTtl) {

    public FollowCacheProperties {
        if (followTtl == null || followTtl.isZero() || followTtl.isNegative()) {
            throw new IllegalArgumentException("video.cache.follow-ttl 必须为正");
        }
    }
}
