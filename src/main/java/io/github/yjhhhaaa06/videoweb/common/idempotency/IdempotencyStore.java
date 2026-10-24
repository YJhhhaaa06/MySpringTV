package io.github.yjhhhaaa06.videoweb.common.idempotency;

import org.springframework.stereotype.Component;

/**
 * 幂等状态机的**语义层**（写接口幂等专项 `T66`）——把 {@link IdempotencyRedisOps} 的三个原语
 * 包成"占位 / 固化 / 墓碑"三个动作，并把 TTL 的来源收在这里
 * （{@code PENDING} 来自注册表，{@code DONE} / {@code FAILED} 来自配置）。
 * 口径的唯一事实源 = {@code architecture/tech/idempotency/写接口幂等.md} §五。
 *
 * <h2>★ 这里<b>不</b> catch 任何异常（刻意的）</h2>
 * 过滤器要按类型分档打日志：{@code CacheUnavailableException}（外部故障）⇒ WARN 放行；
 * {@link IdempotencyScriptException}（自身缺陷）⇒ ERROR 持栈放行。若在本层归一成一种，
 * "<b>幂等保护静默消失</b>"就会被伪装成"Redis 挂了"。
 * ⇒ <b>"降级"不是本层的返回类型，而是过滤器把异常映射成的判定</b>
 * （{@link IdempotencyMetrics.Decision#DEGRADED}）——"降级"这个决定属于过滤器，
 * 因为它同时要决定"这次请求还进不进 chain"。
 *
 * <h2>为什么还要这一层</h2>
 * <ul>
 *   <li>TTL 取值点唯一：端点级 {@code PENDING} TTL 跟着注册表走，另两个跟着配置走，
 *       混在过滤器里会让"哪个 TTL 是哪来的"变成隐知识；</li>
 *   <li>{@code ops} 的三态（{@code ACQUIRED} / {@code PENDING} / {@code DONE}）是**命令层语汇**，
 *       过滤器要的是**业务语汇**（进 chain / 409 / 重放）——转换只在这里发生一次。</li>
 * </ul>
 */
@Component
public class IdempotencyStore {

    private final IdempotencyRedisOps ops;
    private final IdempotencyProperties properties;

    public IdempotencyStore(IdempotencyRedisOps ops, IdempotencyProperties properties) {
        this.ops = ops;
        this.properties = properties;
    }

    /**
     * 占位（或发现"别人占着" / "已有结果"）。
     *
     * @param key               {@code idem:{slug}:{uid}:{clientKey}}
     * @param owner             本请求的随机 token（后续 {@link #complete} / {@link #release} 的 CAS 凭据）
     * @param pendingTtlSeconds 端点级占位 TTL（{@link IdempotencyScope.Entry#pendingTtlSeconds()}）
     */
    public AcquireResult acquire(String key, String owner, int pendingTtlSeconds) {
        IdempotencyRedisOps.Acquired acquired = ops.acquire(key, owner, pendingTtlSeconds);
        return switch (acquired.state()) {
            case ACQUIRED -> new AcquireResult(Kind.ACQUIRED, null);
            case PENDING -> new AcquireResult(Kind.BUSY, null);
            case DONE -> new AcquireResult(Kind.DONE, acquired.snapshot());
        };
    }

    /**
     * 固化首次 2xx 响应。TTL 取 {@code ttl.done-seconds}。
     *
     * @return {@code true} = 本次 CAS 生效；{@code false} = 占位已不属于本请求（<b>不是错误</b>，
     *         最常见成因是处理时长超过 {@code PENDING} TTL、被后来的请求接管）
     */
    public boolean complete(String key, String owner, int status, String contentType,
                            String fingerprint, String body) {
        return ops.complete(key, owner, status, contentType, fingerprint, body,
                properties.ttl().doneSeconds());
    }

    /**
     * 写墓碑（{@code PENDING → FAILED}）。TTL 取 {@code ttl.failed-seconds}。
     *
     * <p>★ 必须走 CAS：成功路径上刚写下 {@code DONE} 时本方法返回 {@code false}——
     * 那正是"一次客户端断连不会把写好的 DONE 覆盖成 FAILED"的实现（否则下一次重发会重复写）。
     */
    public boolean release(String key, String owner) {
        return ops.release(key, owner, properties.ttl().failedSeconds());
    }

    /** 占位结果类别（业务语汇，见类注释）。 */
    public enum Kind {
        /** 占到了 ⇒ 进 chain 执行，收尾时 {@link #complete} / {@link #release}。 */
        ACQUIRED,
        /** 已有固化结果 ⇒ 重放快照（不执行）。 */
        DONE,
        /** 同一个 key 的上一次仍在处理 ⇒ 短轮询后仍占着就 409。 */
        BUSY
    }

    /**
     * @param kind     结果类别
     * @param snapshot 仅 {@link Kind#DONE} 非 {@code null}
     */
    public record AcquireResult(Kind kind, IdempotencyRedisOps.Snapshot snapshot) {
    }
}
