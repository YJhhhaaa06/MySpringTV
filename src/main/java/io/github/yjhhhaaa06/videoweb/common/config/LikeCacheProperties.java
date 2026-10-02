package io.github.yjhhhaaa06.videoweb.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 点赞缓存配置。
 *
 * <p>对应 TV {@code AppConfig.getLikeTtlSeconds()}（原读 {@code cache.like.ttlMinutes}，默认 15）。
 * {@code AppConfig}（847 行多级覆盖链）是《迁移参照系》§三的**整体退役**项，
 * 故这里改为标准配置绑定 + 环境变量可覆盖（决策⑨）。
 *
 * <p>放在 {@code common.config} 是沿袭 {@link JwtProperties} 的位置约定——
 * 本项目的 {@code @ConfigurationProperties} 记录都收在这里，与域代码分离。
 *
 * @param likeTtl 缓存 key 的存活时长，TV 原值 15 分钟
 */
@ConfigurationProperties(prefix = "video.cache")
public record LikeCacheProperties(Duration likeTtl) {

    public LikeCacheProperties {
        if (likeTtl == null || likeTtl.isZero() || likeTtl.isNegative()) {
            throw new IllegalArgumentException("video.cache.like-ttl 必须为正");
        }
    }
}
