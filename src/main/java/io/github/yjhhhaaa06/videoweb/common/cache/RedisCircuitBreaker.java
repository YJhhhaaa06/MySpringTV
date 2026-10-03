package io.github.yjhhhaaa06.videoweb.common.cache;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Redis 熔断器（第三批 T3 / 账 B4）——Resilience4j 状态机的领域封装。
 *
 * <h2>它替代了 TV 的什么</h2>
 * 老项目自研 {@code cache.RedisCircuitBreaker}（CLOSED→OPEN→HALF_OPEN + 连续失败计数 + CAS 探针）。
 * 《迁移参照系》§三 定的是"Resilience4j 接管"，但迁移期**从未接管** ⇒ 账 B4。
 * 本类把 Resilience4j 的 {@link CircuitBreaker} 包成缓存层能用的最小接口，
 * 状态机语义（阈值 / 冷却 / 单探针）全部交给 Resilience4j——不再手写状态迁移。
 *
 * <h2>接在哪：协议层 {@link RedisOps#guarded}</h2>
 * 全仓所有 Redis 访问都经 {@code RedisOps.guarded}（like/follow/content/comment/feed 六域命令层
 * 与共享 {@link CacheAside} 均如此），故熔断只需在这一处生效——
 * 与 TV"从 {@code RedisAccess.execute} 收口"完全同构。
 *
 * <h2>失败不得影响业务</h2>
 * 熔断"打开"时 {@link #tryAcquire()} 返回 false，{@code guarded} 立即抛
 * {@link CacheUnavailableException}（**未触达 Redis**，不再每请求等 Jedis 的 1000ms 超时）；
 * 各域 {@code *Cache} 早已 catch 该类型并降级直查 DB ⇒ 熔断期间接口仍返回正确结果，只是不读缓存。
 *
 * <h2>线程安全</h2>
 * 状态机由 Resilience4j 保证（内部原子操作）；本类无可变字段，无额外同步。
 */
@Component
public class RedisCircuitBreaker {

    /** 记录失败用的哨兵异常：{@code onError} 需要非空 throwable，其具体类型对熔断判定无影响。 */
    private static final Throwable REDIS_FAILURE = new CacheUnavailableException("Redis 访问失败");

    private final CircuitBreaker delegate;

    public RedisCircuitBreaker(CircuitBreaker delegate) {
        this.delegate = delegate;
    }

    /**
     * 取得一次 Redis 访问许可。
     *
     * @return {@code true} = 放行（CLOSED，或 OPEN 冷却期满后放行的单个探针）；
     *         {@code false} = 熔断中，调用方应立即降级、**不得访问 Redis**
     */
    public boolean tryAcquire() {
        return delegate.tryAcquirePermission();
    }

    /** 记一次 Redis 访问成功（HALF_OPEN 探针成功 ⇒ 立即闭合；CLOSED 下计入滑动窗口成功样本）。 */
    public void recordSuccess() {
        delegate.onSuccess(0, TimeUnit.NANOSECONDS);
    }

    /** 记一次 Redis 访问失败（达阈值即打开；探针失败则重置冷却重新打开）。 */
    public void recordFailure() {
        delegate.onError(0, TimeUnit.NANOSECONDS, REDIS_FAILURE);
    }

    /** 当前状态明文（CLOSED / OPEN / HALF_OPEN，观测与测试用）。 */
    public String stateName() {
        return delegate.getState().name();
    }
}
