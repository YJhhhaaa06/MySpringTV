package io.github.yjhhhaaa06.videoweb.common.idempotency;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 幂等配置（前缀 {@code video.idempotency}；口径的唯一事实源 =
 * {@code architecture/tech/idempotency/写接口幂等.md} §十 / §九）。
 *
 * <p>与 {@code RateLimitProperties} 同款：<b>嵌套 record + 紧凑构造器手写校验</b>
 * （不用 {@code @Validated}），由 {@code @ConfigurationPropertiesScan} 注册。
 * 原生类型键名写错不会报错、只会绑成 0 ⇒ 所有"必须为正"的量在构造期响亮失败。
 *
 * <pre>{@code
 * video.idempotency:
 *   ttl:    done-seconds / failed-seconds        →  Ttl
 *   poll:   attempts / interval-millis           →  Poll
 *   limits: fingerprint-bytes / snapshot-bytes   →  Limits
 * }</pre>
 *
 * <p>★ <b>端点级的 {@code PENDING} TTL 不在这里</b>：它是"该端点业务耗时"的属性，
 * 跟着 {@link IdempotencyScope.Entry#pendingTtlSeconds()} 走（设计篇 §十 末段）。
 *
 * @param ttl    已固化结果 / 墓碑的存活时间
 * @param poll   命中 {@code PENDING} 时的短轮询参数（⚠️ 占用容器工作线程，见 §十三 第 9 条）
 * @param limits 指纹与快照的上限（都按"超过即降级"，不做截断后计算）
 */
@ConfigurationProperties(prefix = "video.idempotency")
public record IdempotencyProperties(Ttl ttl, Poll poll, Limits limits) {

    public IdempotencyProperties {
        require(ttl, "video.idempotency.ttl");
        require(poll, "video.idempotency.poll");
        require(limits, "video.idempotency.limits");
    }

    /**
     * 状态存活时间。
     *
     * @param doneSeconds   {@code DONE}（已固化快照）的 TTL。<b>必须 ≥ 客户端的重试窗口</b>
     *                      （推翻条件：前端重试窗口 &gt; 3 min ⇒ 本值要跟着加长）
     * @param failedSeconds {@code FAILED}（墓碑）的 TTL。命中墓碑等同"无记录"，
     *                      故它只影响"多久之后才允许重发"——短是刻意的
     */
    public record Ttl(int doneSeconds, int failedSeconds) {
        public Ttl {
            requirePositive(doneSeconds, "video.idempotency.ttl.done-seconds");
            requirePositive(failedSeconds, "video.idempotency.ttl.failed-seconds");
        }
    }

    /**
     * 短轮询参数：命中别人的 {@code PENDING} 时等一小会儿再问一次，仍占着就 409。
     *
     * <p>⚠️ {@code attempts × intervalMillis} 就是**冲突路径上**多占的容器工作线程时间
     * （默认 2×100 ms）。本仓未启用虚拟线程 ⇒ 不要把这个乘积调大。
     *
     * @param attempts       首次占位失败后再问的次数（默认 2 ⇒ 最多 3 次 ACQUIRE）
     * @param intervalMillis 每次之间的休眠（毫秒）
     */
    public record Poll(int attempts, int intervalMillis) {
        public Poll {
            requirePositive(attempts, "video.idempotency.poll.attempts");
            requirePositive(intervalMillis, "video.idempotency.poll.interval-millis");
        }
    }

    /**
     * 请求指纹与响应快照的上限。
     *
     * @param fingerprintBytes 请求体缓存上限（默认 8 KB）：<b>达到上限即视为无指纹</b>
     *                         （不可用截断后的 body 算指纹），只影响 JSON 端点
     * @param snapshotBytes    响应快照上限（默认 64 KB）：超过则不写 {@code DONE}
     *                         （降级为不幂等，业务已提交 ⇒ 响应照常 2xx），日志 ERROR
     */
    public record Limits(int fingerprintBytes, int snapshotBytes) {
        public Limits {
            requirePositive(fingerprintBytes, "video.idempotency.limits.fingerprint-bytes");
            requirePositive(snapshotBytes, "video.idempotency.limits.snapshot-bytes");
        }
    }

    private static void requirePositive(int value, String key) {
        if (value <= 0) {
            throw new IllegalArgumentException(key + " 必须为正（当前值 " + value + "）");
        }
    }

    private static void require(Object segment, String key) {
        if (segment == null) {
            throw new IllegalArgumentException(key + " 整段缺失：请检查 application.yaml 里的键名与缩进"
                    + "（原生类型键名写错不会报错，只会绑成 0）");
        }
    }
}
