package io.github.yjhhhaaa06.videoweb.like.cache;

/**
 * 缓存不可用（Redis 连接/命令失败）。
 *
 * <h2>存在理由：把"两种失败"变成类型可区分</h2>
 * 缓存层的设计要求区分两类失败（《事务边界决策表》L-5 的待定项之二）：
 * <ul>
 *   <li><b>Redis 失败</b> → 降级直查 DB，**不影响结果**（缓存只是加速器，挂掉不能让接口 500）</li>
 *   <li><b>DB 失败</b> → **上抛**（"无答案可答"；降级成空值会把"读不到"伪装成"没有"，是更坏的语义）</li>
 * </ul>
 * 这两类失败在实现里必须能被区分——否则 catch 一宽就会把 DB 异常也吞掉。
 * 故 {@link LikeRedisOps} 把所有 Redis 层异常统一转成本异常，
 * {@code LikeCache} 只 catch 它；DB 异常（{@code DataAccessException}）则原样冒泡。
 *
 * <p>对照 TV：旧代码用 {@code com.itheima.exception.CacheException} 表达同一件事，
 * 但它位于通用的 exception 包、且与 {@code DatabaseException} 无类型区分上的强制力。
 * 本类收窄到 like 缓存域内，语义单一。
 */
public class CacheUnavailableException extends RuntimeException {

    public CacheUnavailableException(String message) {
        super(message);
    }

    public CacheUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
