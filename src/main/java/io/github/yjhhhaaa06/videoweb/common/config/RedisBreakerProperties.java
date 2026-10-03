package io.github.yjhhhaaa06.videoweb.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Redis 熔断器配置（第三批 T3 / 账 B4）。
 *
 * <p>对应 TV {@code cache.RedisCircuitBreaker} 的两个参数（{@code AppConfig.getRedisBreakerFailureThreshold()}
 * / {@code getRedisBreakerCooldownMillis()}），并补齐 Resilience4j 滑动窗口所需的参数。
 * TV 的粒度决策（执行定稿）：<b>全局单熔断</b>——单 Redis 实例宕机影响所有域，
 * 按域熔断只增加探针流量无收益；本实现沿用（一个 {@link org.springframework.context.annotation.Bean} 单例）。
 *
 * <p>口径说明：TV 是"<b>连续</b>失败计数 ≥ 阈值即熔断"（一次成功即清零）；Resilience4j 是
 * <b>滑动窗口失败率</b>。二者对"Redis 持续宕机"的判定一致（窗口内全失败 ⇒ 失败率 100% ≥ 阈值），
 * 但 Resilience4j 还多出"偶发失败不误熔断"的鲁棒性——这是采用它的收益之一。
 *
 * @param failureRateThreshold                    失败率阈值（百分比，0&lt;x≤100）
 * @param slidingWindowSize                       滑动窗口样本数（COUNT_BASED）
 * @param minimumNumberOfCalls                    触发判定前的最少调用数
 * @param waitDurationInOpenState                 OPEN 状态的冷却时长（期满后放行单个探针）
 * @param permittedNumberOfCallsInHalfOpenState   HALF_OPEN 允许的探针调用数（TV 口径：1）
 */
@ConfigurationProperties(prefix = "video.cache.redis-breaker")
public record RedisBreakerProperties(
        float failureRateThreshold,
        int slidingWindowSize,
        int minimumNumberOfCalls,
        Duration waitDurationInOpenState,
        int permittedNumberOfCallsInHalfOpenState) {

    public RedisBreakerProperties {
        if (failureRateThreshold <= 0 || failureRateThreshold > 100) {
            throw new IllegalArgumentException("video.cache.redis-breaker.failure-rate-threshold 必须在 (0,100]");
        }
        if (slidingWindowSize < 1) {
            throw new IllegalArgumentException("video.cache.redis-breaker.sliding-window-size 必须 ≥ 1");
        }
        if (minimumNumberOfCalls < 1 || minimumNumberOfCalls > slidingWindowSize) {
            throw new IllegalArgumentException(
                    "video.cache.redis-breaker.minimum-number-of-calls 必须在 [1, sliding-window-size]");
        }
        if (waitDurationInOpenState == null || waitDurationInOpenState.isZero() || waitDurationInOpenState.isNegative()) {
            throw new IllegalArgumentException("video.cache.redis-breaker.wait-duration-in-open-state 必须为正");
        }
        if (permittedNumberOfCallsInHalfOpenState < 1) {
            throw new IllegalArgumentException(
                    "video.cache.redis-breaker.permitted-number-of-calls-in-half-open-state 必须 ≥ 1");
        }
    }
}
