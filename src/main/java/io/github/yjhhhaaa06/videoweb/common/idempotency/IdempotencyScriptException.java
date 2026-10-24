package io.github.yjhhhaaa06.videoweb.common.idempotency;

/**
 * 幂等脚本的**返回值解析失败**（写接口幂等专项 `T66`；口径见
 * {@code architecture/tech/idempotency/写接口幂等.md} §十一）。
 *
 * <h2>为什么与 {@code CacheUnavailableException} 分开</h2>
 * 与 {@code RateLimitScriptException} 同源的理由：过滤器对两者都放行（fail-open），
 * 但语义完全不同 ——
 * <ul>
 *   <li>{@code CacheUnavailableException} = Redis 外部故障（可自愈；日志 <b>WARN</b>）；</li>
 *   <li>本异常 = <b>幂等器自身缺陷</b>（脚本写错 / 返回值格式漂移 / 状态自相矛盾；日志 <b>ERROR</b> 持栈）。</li>
 * </ul>
 * 若把两者归一成一种，"<b>幂等保护已静默消失</b>"就会被伪装成"Redis 挂了"、无人察觉。
 * 故 {@code IdempotencyRedisOps} 把解析放在 {@code eval}（及其 {@code guarded}）<b>之外</b>，
 * 异常保持原类型上抛，调用方按类型分档。
 */
public class IdempotencyScriptException extends RuntimeException {

    public IdempotencyScriptException(String message) {
        super(message);
    }

    public IdempotencyScriptException(String message, Throwable cause) {
        super(message, cause);
    }
}
