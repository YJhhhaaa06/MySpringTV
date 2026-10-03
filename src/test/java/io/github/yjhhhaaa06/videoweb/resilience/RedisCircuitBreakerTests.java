package io.github.yjhhhaaa06.videoweb.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.yjhhhaaa06.videoweb.common.cache.CacheUnavailableException;
import io.github.yjhhhaaa06.videoweb.common.cache.RedisCircuitBreaker;
import io.github.yjhhhaaa06.videoweb.common.cache.RedisOps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis 熔断器（第三批 T3 / 账 B4）——协议层收口处的故障注入测试。
 *
 * <h2>这条账的代价（为什么不是纯优化）</h2>
 * Redis 故障时，每个请求都会各自撞一次 Jedis 的 connect/socket 超时（dev 各 1000ms），
 * 再降级走 DB——**每请求一次超时等待**。TV 用自研 {@code RedisCircuitBreaker} 快速失败把它收掉；
 * 本仓迁移期未接管 ⇒ 账 B4。本测试证明：连续失败达阈值后**不再访问 Redis**（快速失败），
 * 冷却期满后放行**单个探针**，探针成功自动闭合、失败则重开并重置冷却。
 *
 * <h2>为什么在 {@link RedisOps} 这一层注入</h2>
 * 全仓所有 Redis 访问都经 {@code RedisOps.guarded}（六域命令层 + 共享 {@code CacheAside}），
 * 熔断接在这里即"外层收口"，与 TV 从 {@code RedisAccess.execute} 收口同构。
 * 用 mock 的 {@link StringRedisTemplate} 抛异常来模拟"Redis 挂"：
 * <b>不真停容器</b>——测试 Redis 是 {@code Containers} 的进程级单例，停机波及其它测试类。
 *
 * <h2>★ 反向验证</h2>
 * 去掉 {@code guarded} 里的 {@code if (!breaker.tryAcquire())} 判定，
 * {@link #熔断后不再访问Redis()} 会因"后续调用仍触达 Redis"而变红。
 */
@Tag("resilience")
class RedisCircuitBreakerTests {

    private static final int WINDOW = 4;
    private static final int MIN_CALLS = 4;
    private static final float THRESHOLD = 60;
    private static final Duration OPEN_WAIT = Duration.ofMillis(200);

    private StringRedisTemplate redis;
    private RedisCircuitBreaker breaker;
    private RedisOps ops;

    /** 每次新建一套 mock + 状态机，避免用例间共享熔断状态。 */
    private void fresh() {
        redis = mock(StringRedisTemplate.class);
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(WINDOW)
                .minimumNumberOfCalls(MIN_CALLS)
                .failureRateThreshold(THRESHOLD)
                .waitDurationInOpenState(OPEN_WAIT)
                .permittedNumberOfCallsInHalfOpenState(1)
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .build();
        breaker = new RedisCircuitBreaker(CircuitBreaker.of("test", config));
        ops = new RedisOps(redis, breaker) {
        };
    }

    private void redisDown() {
        when(redis.hasKey(anyString())).thenThrow(new RuntimeException("模拟 Redis 不可达"));
    }

    /** 打满一个窗口的失败，使熔断打开。 */
    private void openCircuit() {
        redisDown();
        for (int i = 0; i < MIN_CALLS; i++) {
            assertThatThrownBy(() -> ops.keyExists("k")).isInstanceOf(CacheUnavailableException.class);
        }
        assertThat(breaker.stateName()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("★连续失败达阈值 ⇒ 熔断打开；之后不再访问 Redis（快速失败，不再等超时）")
    void 熔断后不再访问Redis() {
        fresh();
        openCircuit();
        verify(redis, times(MIN_CALLS)).hasKey("k");

        // 熔断打开后：立即抛 CacheUnavailableException，且 **不再触达 Redis**
        assertThatThrownBy(() -> ops.keyExists("k"))
                .isInstanceOf(CacheUnavailableException.class)
                .hasMessageContaining("熔断");
        verify(redis, times(MIN_CALLS)).hasKey("k"); // 调用次数未增加 = 未访问 Redis
    }

    @Test
    @DisplayName("对照：未达最小调用数不熔断（仍走 Redis，不误熔断）")
    void 未达最小调用数不熔断() {
        fresh();
        redisDown();
        for (int i = 0; i < MIN_CALLS - 1; i++) {
            assertThatThrownBy(() -> ops.keyExists("k")).isInstanceOf(CacheUnavailableException.class);
        }
        assertThat(breaker.stateName()).as("样本不足，不得熔断").isEqualTo("CLOSED");

        doReturn(true).when(redis).hasKey("k"); // Redis 恢复（doReturn：避免触发已有 throwing 桩）
        assertThat(ops.keyExists("k")).as("未熔断 ⇒ 仍访问 Redis").isTrue();
    }

    @Test
    @DisplayName("★冷却期满放行单个探针；探针成功 ⇒ 自动闭合（恢复缓存路径）")
    void 探针成功自动闭合() throws InterruptedException {
        fresh();
        openCircuit();

        Thread.sleep(OPEN_WAIT.toMillis() + 150); // 越过冷却窗口
        doReturn(true).when(redis).hasKey("k");   // Redis 已恢复（doReturn：避免触发已有 throwing 桩）

        assertThat(ops.keyExists("k")).as("探针成功，返回真值").isTrue();
        assertThat(breaker.stateName()).as("探针成功 ⇒ CLOSED").isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("★探针失败 ⇒ 重开并重置冷却（立即再拒，不连续放探针）")
    void 探针失败重开并重置冷却() throws InterruptedException {
        fresh();
        openCircuit();
        verify(redis, times(MIN_CALLS)).hasKey("k");

        Thread.sleep(OPEN_WAIT.toMillis() + 150);
        assertThatThrownBy(() -> ops.keyExists("k")).as("探针仍失败").isInstanceOf(CacheUnavailableException.class);
        assertThat(breaker.stateName()).isEqualTo("OPEN");

        // 冷却已重置：紧接着的调用仍被快速拒绝、不触达 Redis
        assertThatThrownBy(() -> ops.keyExists("k")).isInstanceOf(CacheUnavailableException.class);
        verify(redis, times(MIN_CALLS + 1)).hasKey("k"); // 仅探针那一次触达
    }
}
