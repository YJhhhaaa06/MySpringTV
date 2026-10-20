package io.github.yjhhhaaa06.videoweb.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 投币缓存配置（投币专项 T39）。
 *
 * <p>与 {@link LikeCacheProperties} / {@link FavoriteCacheProperties} 同构：标准配置绑定 +
 * 环境变量可覆盖（决策⑨），放在 {@code common.config}（本项目的 {@code @ConfigurationProperties}
 * 记录都收在这里，与域代码分离）。
 *
 * <p>★ <b>为什么与 like / favorite 同为 15 分钟</b>：三者缓存形态是同一类——"我的状态"类
 * 用户维度成员集（{@code user:coinAmount:{id}} HASH）+ 内容维度计数
 * （{@code content:coinCount:{id}}），写路径都在提交后条件写、读路径都热在详情页。
 * 刻意比 follow / content 的 30 分钟短：投币的失效驱动是"投币写路径"这一类需要
 * "提交后立刻可见"的状态，短 TTL 让"万一漏了一处写"的最坏影响面更小。
 *
 * <p>⚠️ {@code coin-ttl} 并入既有 {@code video.cache} 段（与 {@code like-ttl} 等并列），
 * 不另起 {@code video.coin.*} 前缀——同一类缓存参数放在同一段，环境变量覆盖时也只有一组约定。
 *
 * @param coinTtl coin 两个数据 key（{@code user:coinAmount} / {@code content:coinCount}）的存活时长
 */
@ConfigurationProperties(prefix = "video.cache")
public record CoinCacheProperties(Duration coinTtl) {

    public CoinCacheProperties {
        if (coinTtl == null || coinTtl.isZero() || coinTtl.isNegative()) {
            throw new IllegalArgumentException("video.cache.coin-ttl 必须为正");
        }
    }
}
