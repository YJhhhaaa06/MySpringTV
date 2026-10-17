package io.github.yjhhhaaa06.videoweb.common.web;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 限流打点（限流专项 <b>T28</b>；口径的唯一事实源 = {@code architecture/tech/限流.md} §五）。
 *
 * <h2>两个指标</h2>
 * <table>
 *   <caption>指标面</caption>
 *   <tr><th>指标</th><th>维度</th><th>含义</th></tr>
 *   <tr><td>{@code rate.limit.decisions}</td>
 *       <td>{@code scope=user|server}、{@code result=allowed|rejected}</td>
 *       <td><b>逐桶</b>判定计数</td></tr>
 *   <tr><td>{@code rate.limit.acquire}</td><td>无</td>
 *       <td>一次过闸命令（一次 Lua 往返）的耗时 = "限流器自身耗时"</td></tr>
 * </table>
 *
 * <h2>★ 为什么是"逐桶两条"，而不是"一次请求一条决策"</h2>
 * 两桶的判定可以不同（用户桶够、服务器桶不够）——运维要回答的是
 * "**该扩服务器还是该管这个用户**"，把两者并成一条就答不了。
 * 故一次请求记**两条**样本：各自 {@code allowed} / {@code rejected}。
 * ⚠️ 由此而来的一条读法：{@code sum(result="rejected")} 是**桶级**失败数、**不是**请求数
 * （一次被拒可能只有一个桶记 rejected；两桶都不够时两个桶各记一条）。
 *
 * <h2>★ 维度必须"知道才记"（同 {@code CacheMetrics} 的取向）</h2>
 * {@code scope} 的真值来自脚本带出的逐桶判定（{@code RateLimitRedisOps.Decision} 的两枚布尔，
 * 登记 {@code I-21}）——不是由调用方猜的。猜出来的维度比没有维度更坏。
 *
 * <h2>失败不得影响业务</h2>
 * 与 {@code CacheMetrics} 同款：{@code counter(...).increment()} / {@code timer.record(...)} 只是
 * 进程内对象上的加一，不存在"打点失败"的正常路径，调用点不 try/catch。
 * <p>⚠️ <b>fail-open 不记决策指标</b>（判都没判出来，记什么都是假的）：那两条路径由
 * {@code RateLimitFilter} 的 WARN / ERROR 日志承载（设计稿 §十 的立场——本仓无监控消费方，
 * 日志才是唯一会被看到的出口）。
 */
@Component
public class RateLimitMetrics {

    /** 决策计数（唯一源；测试与 Actuator 路径都引用它）。 */
    public static final String METRIC_DECISIONS = "rate.limit.decisions";

    /** 过闸命令耗时（唯一源）。 */
    public static final String METRIC_ACQUIRE = "rate.limit.acquire";

    /** 维度键：哪一个桶的判定。 */
    public static final String TAG_SCOPE = "scope";

    /** 维度键：判定结果。 */
    public static final String TAG_RESULT = "result";

    /** 判定结果取值（逐字冻结，测试与文档都引用）。 */
    public static final String RESULT_ALLOWED = "allowed";
    public static final String RESULT_REJECTED = "rejected";

    private final MeterRegistry registry;

    public RateLimitMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 记一次逐桶判定（{@code scope} = 哪个桶，{@code result} = 它自己过没过）。 */
    public void recordDecision(Scope scope, boolean passed) {
        registry.counter(METRIC_DECISIONS,
                TAG_SCOPE, scope.tag(),
                TAG_RESULT, passed ? RESULT_ALLOWED : RESULT_REJECTED).increment();
    }

    /**
     * 记一次过闸命令的耗时（纳秒，由调用方用 {@code System.nanoTime()} 量）。
     *
     * <p>样本口径 = <b>真的做出了判定</b>的那次调用；fail-open（Redis 不可达 / 脚本异常）不采样——
     * 那不是"限流器耗时"，混进来会把"Redis 挂了的 1s 超时"读成限流器变慢。
     */
    public void recordAcquire(long nanos) {
        Timer timer = registry.timer(METRIC_ACQUIRE);
        timer.record(nanos, TimeUnit.NANOSECONDS);
    }

    /** 桶的作用域（tag 值逐字冻结）。 */
    public enum Scope {
        USER("user"),
        SERVER("server");

        private final String tag;

        Scope(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }
}
