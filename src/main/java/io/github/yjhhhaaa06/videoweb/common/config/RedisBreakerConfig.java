package io.github.yjhhhaaa06.videoweb.common.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redis 熔断器的 Resilience4j 装配（第三批 T3 / 账 B4）。
 *
 * <p>《迁移参照系》§三 把 TV 自研 {@code cache/RedisCircuitBreaker} 的处置写成"Resilience4j 接管"，
 * 但迁移期从未接管（缓存失败只靠 try/catch 降级）⇒ 账 B4。本类兑现该处置：
 * 用 Resilience4j 核心库装配一个**全局单熔断器**（粒度决策沿袭 TV，见 {@link RedisBreakerProperties}），
 * 由协议层 {@code common.cache.RedisOps#guarded} 在外层调用。
 *
 * <h2>为什么是 {@code COUNT_BASED} + {@code automaticTransition=false}</h2>
 * <ul>
 *   <li>{@code COUNT_BASED}：TV 的"连续失败"最接近按次数计的滑动窗口，且样本量小、判定确定；</li>
 *   <li>{@code automaticTransition(false)}：由 {@code tryAcquirePermission()} 在冷却期满时**同步**完成
 *       OPEN→HALF_OPEN 转换并放行探针——不依赖后台调度线程，行为与 TV 的 CAS 探针一致、对测试确定。</li>
 * </ul>
 *
 * <h2>失败口径</h2>
 * 只把"Redis 起源的失败"计入熔断：显式 {@code recordException(CacheUnavailableException.class)}。
 * 协议层已把 Redis 异常归一为该类型（DB 异常不经过这里），故谓词收窄与 TV"从
 * {@code RedisAccess.execute} 冒出的 CacheException 计一次失败"同口径，
 * 避免把业务回调里的意外异常误计入熔断。
 */
@Configuration(proxyBeanMethods = false)
public class RedisBreakerConfig {

    /** 熔断器名（全局唯一）。 */
    public static final String REDIS_BREAKER_NAME = "redis";

    @Bean
    public CircuitBreaker redisCircuitBreakerDelegate(RedisBreakerProperties props) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(props.slidingWindowSize())
                .minimumNumberOfCalls(props.minimumNumberOfCalls())
                .failureRateThreshold(props.failureRateThreshold())
                .waitDurationInOpenState(props.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(props.permittedNumberOfCallsInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .recordExceptions(CacheUnavailableException.class)
                .build();
        return CircuitBreaker.of(REDIS_BREAKER_NAME, config);
    }
}
