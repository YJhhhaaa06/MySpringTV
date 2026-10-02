package io.github.yjhhhaaa06.videoweb.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 内容缓存配置（S5）。
 *
 * <p>对应 TV {@code AppConfig.getContentTtlMillis()}（原读 {@code cache.content.ttlMinutes}，默认 30）。
 * TV 的取值理由（原注释）：<i>"content 30min（热读域：启动全量重建后恒 100% hitData + 滑动续期后
 * 热点常驻，miss≈0 无穿透风险）"</i>。
 *
 * <p>⚠️ TV 的理由里有一半依赖"**启动全量重建**"（{@code ContentCache.init()}），而本切片**不搬**
 * 该重建（决策表 G-6 有专门说明与补回位置）⇒ 首读会 miss 并回源 DB。TTL 值本身不变（30 分钟）。
 *
 * <p>{@code AppConfig}（847 行多级覆盖链）是《迁移参照系》§三的整体退役项，故改为标准配置绑定。
 * 位置约定同 {@link LikeCacheProperties}。
 *
 * @param contentTtl 内容 key 的存活时长，TV 原值 30 分钟
 */
@ConfigurationProperties(prefix = "video.cache")
public record ContentCacheProperties(Duration contentTtl) {

    public ContentCacheProperties {
        if (contentTtl == null || contentTtl.isZero() || contentTtl.isNegative()) {
            throw new IllegalArgumentException("video.cache.content-ttl 必须为正");
        }
    }
}
