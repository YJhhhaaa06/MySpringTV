package io.github.yjhhhaaa06.videoweb.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 收藏缓存配置（收藏二期 T11）。
 *
 * <p>与 {@link LikeCacheProperties} / {@link FollowCacheProperties} 同构：标准配置绑定 +
 * 环境变量可覆盖（决策⑨），放在 {@code common.config}（本项目的 {@code @ConfigurationProperties}
 * 记录都收在这里，与域代码分离）。
 *
 * <p>★ <b>为什么收藏与点赞同档 15 分钟</b>：两域缓存形态是同一类——"我的状态"类**用户维度成员集**
 * （{@code user:*Set:{id}}）+ 内容维度计数，写路径都在提交后条件写/失效，读路径都热在**详情页**。
 * 刻意比 follow / content 的 30 分钟短：收藏的失效驱动多（add / remove / move / 删夹四个写点），
 * 短 TTL 让"万一漏了一处失效"的最坏影响面更小。
 *
 * @param favoriteTtl 收藏缓存 key（成员集 + 计数）的存活时长，与点赞同为 15 分钟
 */
@ConfigurationProperties(prefix = "video.cache")
public record FavoriteCacheProperties(Duration favoriteTtl) {

    public FavoriteCacheProperties {
        if (favoriteTtl == null || favoriteTtl.isZero() || favoriteTtl.isNegative()) {
            throw new IllegalArgumentException("video.cache.favorite-ttl 必须为正");
        }
    }
}
