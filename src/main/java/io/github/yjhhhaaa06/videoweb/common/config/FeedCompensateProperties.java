package io.github.yjhhhaaa06.videoweb.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * MQ **内存补偿缓冲**配置（第三批 T5 / 账 B9）。
 *
 * <p>承接 TV {@code app.properties} 的 {@code feed.compensate.bufferCapacity}（10000）；
 * {@code probeInterval} 在 TV 是 {@code MqDeliveryBuffer} 的**包内常量**
 * （{@code PROBE_INTERVAL_MILLIS = 30_000}，原注"非必要不入配置"）。本仓把它**入配置**的
 * 唯一理由：让"恢复后重放"这件事可被运维与故障注入测试**驱动**（默认值不变 = TV 口径）。
 *
 * <p>前缀与 {@code video.feed.delivery}（{@link FeedDeliveryProperties}）分家，理由同那里：
 * 关注点不同（投递线程池 vs 补偿缓冲），且独立前缀让 {@code FEED_COMPENSATE_*}
 * 可单独覆盖、不与投递线程池的参数混在一个命名空间里。
 * ⚠️ 这**不是**"record 只能绑自身前缀下的扁平键"——record **能**绑嵌套段（Boot 4.1.1 实测；
 * 本仓 {@link RateLimitProperties} 是"一个 record + 三段嵌套"的入库实例，口径见
 * {@code architecture/tech/rate-limit/限流.md} §二）⇒ **别再按这条约束设计**。
 *
 * @param bufferCapacity 待重放内存队列容量（满则丢弃 + 节流 WARNING）
 * @param probeInterval  恢复探测周期（同时是"不可达窗口"长度与恢复补偿的时延上界）
 */
@ConfigurationProperties(prefix = "video.feed.compensate")
public record FeedCompensateProperties(int bufferCapacity, Duration probeInterval) {

    public FeedCompensateProperties {
        if (bufferCapacity <= 0) {
            throw new IllegalArgumentException("video.feed.compensate.buffer-capacity 必须为正数: " + bufferCapacity);
        }
        if (probeInterval == null || probeInterval.isZero() || probeInterval.isNegative()) {
            throw new IllegalArgumentException(
                    "video.feed.compensate.probe-interval 必须为正的时长（且带单位后缀，如 30s）: " + probeInterval);
        }
    }
}
