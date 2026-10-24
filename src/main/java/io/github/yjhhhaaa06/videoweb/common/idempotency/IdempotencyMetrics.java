package io.github.yjhhhaaa06.videoweb.common.idempotency;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 幂等打点（写接口幂等专项 `T66`；口径的唯一事实源 =
 * {@code architecture/tech/idempotency/写接口幂等.md} §十一「可观测」）。
 *
 * <h2>一个指标，七个互斥取值</h2>
 * {@code idempotency.decisions{result=...}}——<b>一次请求记一条</b>（判定结果天然互斥，
 * 与限流的"逐桶两条"相反：那边两条桶判定可以不同，这边一次请求只有一个结论）。
 *
 * <table>
 *   <caption>result 取值</caption>
 *   <tr><th>result</th><th>含义</th></tr>
 *   <tr><td>{@code miss}</td><td>无记录 ⇒ 占了位、执行了业务（最常见的正常路径）</td></tr>
 *   <tr><td>{@code hit}</td><td>命中 {@code DONE} ⇒ 重放了快照</td></tr>
 *   <tr><td>{@code pending}</td><td>短轮询耗尽仍 {@code PENDING} ⇒ 409</td></tr>
 *   <tr><td>{@code conflict}</td><td>同 key 不同载荷 ⇒ 422（零副作用）</td></tr>
 *   <tr><td>{@code missing_key}</td><td>没带 {@code Idempotency-Key} ⇒ 放行、不占位</td></tr>
 *   <tr><td>{@code invalid_key}</td><td>key 不合法 ⇒ 400（零副作用）</td></tr>
 *   <tr><td>{@code degraded}</td><td>Redis 故障 / 脚本异常 / 快照超限 / 收尾失败 ⇒ 放行但不幂等</td></tr>
 * </table>
 *
 * <p>★ <b>不记</b> {@code clientKey} 原文与快照 body（前者是客户端可控串、后者含业务数据）；
 * 维度只有 {@code result}。
 * <p>★ {@code missing_key} 是"服务端分不清忘了带与选择不带"这条已知代价的<b>唯一观测口</b>
 * （设计篇 §十三 第 6 条）——它的量级决定将来要不要讨论强制开关。
 *
 * <h2>失败不得影响业务</h2>
 * 与 {@code RateLimitMetrics} 同款：{@code counter(...).increment()} 只是进程内对象上的加一，
 * 不存在"打点失败"的正常路径，调用点不 try/catch。
 */
@Component
public class IdempotencyMetrics {

    /** 决策计数（唯一源；测试与文档都引用它）。 */
    public static final String METRIC_DECISIONS = "idempotency.decisions";

    /** 维度键：判定结果。 */
    public static final String TAG_RESULT = "result";

    private final MeterRegistry registry;

    public IdempotencyMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 记一次判定（一次请求一条）。 */
    public void recordDecision(Decision decision) {
        registry.counter(METRIC_DECISIONS, TAG_RESULT, decision.tag()).increment();
    }

    /** 判定结果（tag 值逐字冻结，对外契约见 {@code architecture/tech/idempotency/写接口幂等-测试与契约.md}）。 */
    public enum Decision {
        MISS("miss"),
        HIT("hit"),
        PENDING("pending"),
        CONFLICT("conflict"),
        MISSING_KEY("missing_key"),
        INVALID_KEY("invalid_key"),
        /** 降级：放行但不幂等（见类注释：这是"降级决定"的唯一载体）。 */
        DEGRADED("degraded");

        private final String tag;

        Decision(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }
}
