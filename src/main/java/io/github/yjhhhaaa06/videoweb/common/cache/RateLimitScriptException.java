package io.github.yjhhhaaa06.videoweb.common.cache;

/**
 * 限流脚本的**返回值解析失败**（限流专项 T24；口径见 {@code architecture/tech/rate-limit/限流.md}）。
 *
 * <h2>为什么必须与 {@link CacheUnavailableException} 分开（T28 的日志分档基础）</h2>
 * 过滤器对两者都会 fail-open（放行），但**语义完全不同**：
 * <ul>
 *   <li>{@code CacheUnavailableException} = Redis 外部故障（可自愈；日志 WARN）；</li>
 *   <li>本异常 = **限流器自身缺陷**（脚本写错 / 返回值格式漂移；日志 ERROR）。</li>
 * </ul>
 * 若把本异常也归一成 {@code CacheUnavailableException}，"**限流静默消失**"就会被伪装成
 * "Redis 挂了"、无人察觉。故 {@code RateLimitRedisOps} 把解析放在 {@code eval}
 * （及其 {@code guarded}）**之外**——异常保持原类型上抛，调用方（T25 过滤器）按类型分档。
 */
public class RateLimitScriptException extends RuntimeException {

    public RateLimitScriptException(String message) {
        super(message);
    }

    public RateLimitScriptException(String message, Throwable cause) {
        super(message, cause);
    }
}
