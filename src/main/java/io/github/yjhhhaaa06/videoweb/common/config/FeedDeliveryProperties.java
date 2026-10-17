package io.github.yjhhhaaa06.videoweb.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * MQ **异步投递线程池**配置（第三批 T5 / 账 B8）。
 *
 * <p>承接 TV {@code app.properties} 的 {@code feed.delivery.queueCapacity}（1000）与
 * {@code feed.delivery.drainTimeoutMillis}（5000ms）。它们原先在 TV 是 {@code AppConfig} 的静态读取；
 * 本仓改为标准配置绑定。
 *
 * <h2>为什么单独成一个 record（不再塞进 {@link FeedProperties}）</h2>
 * 两条理由：① **关注点不同**——{@link FeedProperties} 是"feed 业务的窗口 / 批尺寸"，
 * 此处是"投递基建的容量 / 关停上界"；② **独立前缀便于环境变量覆盖**——
 * {@code FEED_DELIVERY_*} 与 {@code FEED_*} 一眼可辨、互不打架
 * （同款口径：{@code video.cache.redis-breaker} → {@link RedisBreakerProperties}）。
 *
 * <p>⚠️ 分家**不是**因为"record 绑不了嵌套键"——那说法经实测**不成立**：record **能**绑嵌套段
 * （Boot 4.1.1 实测；本仓 {@link RateLimitProperties} 就是"一个 record + 三段嵌套"的入库实例，
 * 口径见 {@code architecture/tech/限流.md} §二）。旧注释按"绑定能力限制"论证，已经误导过设计选型
 * ⇒ **别再按这条约束设计**；真要合并，理由只能是"关注点该不该合"。
 *
 * <h2>校验口径</h2>
 * {@code queueCapacity} 非正 ⇒ 投递永远无法入队（背压退化成"全丢"）；
 * {@code drainTimeout} 非正 ⇒ 关停等待无上界（可挂住应用关停）。两者都是"宁可启动即拒"。
 *
 * @param queueCapacity 投递队列容量（满则丢弃 + WARNING，降级不影响业务）
 * @param drainTimeout  关停时等待队列存量投完的上界（超时丢弃剩余）
 */
@ConfigurationProperties(prefix = "video.feed.delivery")
public record FeedDeliveryProperties(int queueCapacity, Duration drainTimeout) {

    public FeedDeliveryProperties {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("video.feed.delivery.queue-capacity 必须为正数: " + queueCapacity);
        }
        if (drainTimeout == null || drainTimeout.isZero() || drainTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "video.feed.delivery.drain-timeout 必须为正的时长（且带单位后缀，如 5s）: " + drainTimeout);
        }
    }
}
