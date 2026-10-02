package io.github.yjhhhaaa06.videoweb.common.cache;

/**
 * 缓存不可用（Redis 连接 / 命令失败）。
 *
 * <h2>存在理由：把"两种失败"变成类型可区分</h2>
 * 缓存层的设计要求区分两类失败（《事务边界决策表》L-5 的待定项之二，F-5 沿用）：
 * <ul>
 *   <li><b>Redis 失败</b> → 降级直查 DB，**不影响结果**（缓存只是加速器，挂掉不能让接口 500）</li>
 *   <li><b>DB 失败</b> → **上抛**（"无答案可答"；降级成空值会把"读不到"伪装成"没有"，是更坏的语义）</li>
 * </ul>
 * 这两类失败在实现里必须能被区分——否则 catch 一宽就会把 DB 异常也吞掉。
 * 故各域的 {@code *RedisOps} 把所有 Redis 层异常统一转成本异常，{@code *Cache} 只 catch 它；
 * DB 异常（{@code DataAccessException}）则原样冒泡。
 *
 * <h2>为什么在 {@code common.cache} 而不是 like 域内（S3 时它在 {@code like.cache}）</h2>
 * S3 把它收在 {@code like.cache}（当时只有一个使用方）。S4（follow）是**第 2 个使用方**，
 * 于是上移到公共包——这是「rule of three 不适用于**类型**」的一个刻意例外：
 * 被抽的是**一个无框架、无泛型的异常类型**，复制一份只会制造两个语义相同的类型，
 * 而抽象公共**框架层**（{@code CacheAside}/{@code ZSetCache}）仍按 L-7 / F-7 等 S5 的第 3 个使用方。
 *
 * <p>对照 TV：旧代码用 {@code com.itheima.exception.CacheException} 表达同一件事
 * （它本就在通用 exception 包，位置反而更对）。
 */
public class CacheUnavailableException extends RuntimeException {

    public CacheUnavailableException(String message) {
        super(message);
    }

    public CacheUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
