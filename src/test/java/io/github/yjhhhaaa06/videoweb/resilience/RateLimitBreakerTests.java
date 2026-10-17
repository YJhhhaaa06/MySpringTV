package io.github.yjhhhaaa06.videoweb.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import io.github.yjhhhaaa06.videoweb.common.cache.RateLimitRedisOps;
import io.github.yjhhhaaa06.videoweb.common.cache.RedisCircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 限流桶**复用全仓熔断**的行为契约（限流专项 T28 验收②；{@code architecture/tech/限流.md} §五）。
 *
 * <h2>要证明的一句话</h2>
 * 限流器经 {@code RedisOps.guarded} 发脚本 ⇒ 熔断打开时**不是**"再撞一次超时然后 fail-open"，
 * 而是**一次 Redis 都不访问**就快速失败——这条白拿的能力是 T24 选 {@code guarded} 的核心理由，
 * 但 T24/T25 的用例都没有**直接**证明过它（{@code RedisCircuitBreakerTests} 证明的是 {@code keyExists}
 * 那条路，不是限流脚本这条路）。
 *
 * <h2>★ 反向验证</h2>
 * 去掉 {@code RedisOps.guarded} 里的 {@code if (!breaker.tryAcquire())} ⇒
 * {@link #熔断打开后_过闸不再访问Redis()} 的"调用次数停在 2"断言必红（会变成 3）。
 *
 * <h2>为什么用计数替身而不是 Mockito</h2>
 * {@code execute} 是 varargs 签名，Mockito 匹配器语义随版本漂移（T24 已踩过并留痕）；
 * 本类只关心"到底调了几次"，覆写一个子类最直白。
 */
@Tag("resilience")
class RateLimitBreakerTests {

    private static final RateLimitRedisOps.BucketSpec USER_BUCKET = new RateLimitRedisOps.BucketSpec(10, 2);
    private static final RateLimitRedisOps.BucketSpec SERVER_BUCKET = new RateLimitRedisOps.BucketSpec(20, 10);

    private CountingStubRedis redis;
    private RedisCircuitBreaker breaker;
    private RateLimitRedisOps ops;

    /**
     * 每次新建一套替身 + 状态机：熔断状态是**有状态**的，跨用例共享会让"打开"状态泄漏到别的用例
     * （{@code RedisCircuitBreakerTests} 同款做法）。
     * <p>窗口 2 / 最小样本 2 / 阈值 50% ⇒ 前两次失败即打开；冷却 1 小时 ⇒ 本用例期间不会自愈成半开。
     */
    @BeforeEach
    void fresh() {
        redis = new CountingStubRedis();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofHours(1))
                .permittedNumberOfCallsInHalfOpenState(1)
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .build();
        breaker = new RedisCircuitBreaker(CircuitBreaker.of("t28-rate-limit", config));
        ops = new RateLimitRedisOps(redis, breaker);
    }

    @Test
    @DisplayName("★ 熔断打开后，过闸**不再访问 Redis**（快速失败，而不是每请求再撞一次超时）")
    void 熔断打开后_过闸不再访问Redis() {
        // 两次真实失败 ⇒ 窗口打满、熔断打开（每次都会真的触达 Redis）
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(this::acquire)
                    .as("Redis 失败被 guarded 归一成 CacheUnavailableException（fail-open 归过滤器）")
                    .isInstanceOf(CacheUnavailableException.class);
        }
        assertThat(redis.calls()).as("熔断打开前：两次调用都真的触达了 Redis").isEqualTo(2);
        assertThat(breaker.stateName()).as("阈值 50%/窗口 2 ⇒ 两次失败即 OPEN").isEqualTo("OPEN");

        // 第三次：熔断已打开 ⇒ 必须快速失败，且**一个 Redis 命令都不发**
        assertThatThrownBy(this::acquire).isInstanceOf(CacheUnavailableException.class);

        assertThat(redis.calls())
                .as("★ 熔断打开后仍在访问 Redis ⇒ 限流器白拿了熔断（每次请求要白等一次 Jedis 超时）")
                .isEqualTo(2);
    }

    private void acquire() {
        ops.tryAcquire("ratelimit:user:990001", "ratelimit:server",
                USER_BUCKET, SERVER_BUCKET, 1.0, 0.3);
    }

    /** 每次脚本执行都计数并抛错（模拟 Redis 不可达）。 */
    private static final class CountingStubRedis extends StringRedisTemplate {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            calls.incrementAndGet();
            throw new RuntimeException("模拟 Redis 不可达");
        }

        int calls() {
            return calls.get();
        }
    }
}
